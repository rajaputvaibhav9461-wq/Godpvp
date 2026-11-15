package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Random;

public class NethpotPvp {
    // ---- settings (yahan se badal sakte ho) ----
    static final float HEAL_START_HP = 9.0f;    // 4.5 heart pe heal shuru
    static final float POT_DONE_HP = 16.0f;     // itna HP hone tak pot fenkta rahega
    static final float HEAL_DONE_HP = 16.0f;    // itna HP hone pe wapas fight
    static final float PEARL_MIN_HP = 8.0f;     // isse kam HP pe pearl nahi (5 damage lagta hai)
    static final double REACH = 2.95;
    static final double CHASE_DIST = 3.5;
    static final double FLEE_SAFE_DIST = 8.0;   // khana is doori pe khayega
    static final double BUFF_SAFE_DIST = 10.0;  // enemy itna door ho tab buff piyega
    static final float CRIT_ENEMY_HP = 8.0f;
    static final int STAP_CHANCE = 3;           // 1/3 baar S-tap, baaki W-tap
    static final int POT_COOLDOWN = 4;          // 2 pot ke beech ke tick
    static final int PEARL_COOLDOWN = 20;
    static final int TOTEM_COOLDOWN = 10;
    static final int WEB_INTERVAL = 100;
    static final double WEB_PLACE_RANGE = 4.5;

    private final Random rng = new Random();
    private final ArrayDeque<BlockPos> webQueue = new ArrayDeque<>();
    private int strafeDir = 1, strafeTimer = 0, backTimer = 0, webTimer = 0;
    private int potCd = 0, pearlCd = 0, totemCd = 0, wtap = 0, stap = 0;
    private boolean healMode = false, pearlUsed = false, hadTotem = false;

    // ------------------------------------------------------------ POT HELPERS
    static String potPath(ItemStack s, boolean splash) {
        if (!s.is(splash ? Items.SPLASH_POTION : Items.POTION)) return "";
        PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
        if (pc == null || pc.potion().isEmpty()) return "";
        return pc.potion().get().unwrapKey().map(k -> k.location().getPath()).orElse("");
    }

    static boolean isHealPot(ItemStack s) {
        String p = potPath(s, true);
        return p.equals("healing") || p.equals("strong_healing");
    }

    static int potScore(ItemStack s) {
        return potPath(s, true).equals("strong_healing") ? 2 : 1;
    }

    static boolean isStrengthPot(ItemStack s) { return potPath(s, false).contains("strength"); }
    static boolean isSpeedPot(ItemStack s) { return potPath(s, false).contains("swiftness"); }
    static boolean isTotem(ItemStack s) { return s.is(Items.TOTEM_OF_UNDYING); }
    static boolean isPearl(ItemStack s) { return s.is(Items.ENDER_PEARL); }

    // ------------------------------------------------------------ LIFECYCLE
    boolean matches(Minecraft mc) {
        return KitManager.findBest(mc.player, KitManager::isSword, KitManager::swordScore) >= 0;
    }

    void onActivate(Minecraft mc) {
        webQueue.clear();
        healMode = false;
        pearlUsed = false;
        backTimer = 0;
        strafeTimer = 0;
        potCd = 0;
        pearlCd = 0;
        totemCd = 0;
        wtap = 0;
        stap = 0;
        hadTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        webTimer = WEB_INTERVAL;
    }

    void onDeactivate(Minecraft mc) {
        webQueue.clear();
        healMode = false;
        KitManager.releaseAll(mc);
    }

    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (potCd > 0) potCd--;
        if (pearlCd > 0) pearlCd--;
        if (totemCd > 0) totemCd--;

        // totem pop hua? (offhand totem gayab) -> turant heal mode
        boolean hasTotem = me.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        boolean popped = hadTotem && me.getOffhandItem().isEmpty();
        hadTotem = hasTotem;
        if (popped) {
            healMode = true;
            pearlUsed = false;
        }
        if (!me.isUsingItem()) ensureTotem(mc, me);

        if (target == null) {
            KitManager.releaseAll(mc);
            webQueue.clear();
            return;
        }
        webTimer++;

        int foodIdx = KitManager.findBest(me, s -> KitManager.isFood(me, s), KitManager::foodScore);
        int potIdx = KitManager.findBest(me, NethpotPvp::isHealPot, NethpotPvp::potScore);
        boolean canHeal = foodIdx >= 0 || potIdx >= 0;
        float hp = me.getHealth();

        if (!healMode && hp <= HEAL_START_HP && canHeal) {
            healMode = true;
            pearlUsed = false;
        }
        if (healMode && (hp >= HEAL_DONE_HP || !canHeal)) {
            healMode = false;
            mc.options.keyUse.setDown(false);
        }

        if (healMode) heal(mc, me, target, foodIdx, potIdx);
        else fight(mc, me, target);
    }

    /** totem offhand me nahi hai to inventory se offhand me swap karo. */
    private void ensureTotem(Minecraft mc, LocalPlayer me) {
        if (totemCd > 0 || me.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        int idx = KitManager.findBest(me, NethpotPvp::isTotem, KitManager::zeroScore);
        if (idx < 0) return;
        int slot = idx < 9 ? 36 + idx : idx; // inventoryMenu slot number
        mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId, slot, 40, ClickType.SWAP, me);
        totemCd = TOTEM_COOLDOWN;
    }

    // ------------------------------------------------------------ BUFF (strength + speed)
    /** true = is tick me buff pee raha hai. Sirf tab jab enemy door ho. */
    private boolean buff(Minecraft mc, LocalPlayer me, Player target) {
        if (me.distanceTo(target) < BUFF_SAFE_DIST) return false;
        int idx = -1;
        if (!me.hasEffect(MobEffects.DAMAGE_BOOST)) {
            idx = KitManager.findBest(me, NethpotPvp::isStrengthPot, KitManager::zeroScore);
        }
        if (idx < 0 && !me.hasEffect(MobEffects.MOVEMENT_SPEED)) {
            idx = KitManager.findBest(me, NethpotPvp::isSpeedPot, KitManager::zeroScore);
        }
        if (idx < 0) return false;

        KitManager.stopMovement(mc);
        boolean drinking = me.isUsingItem() && me.getMainHandItem().is(Items.POTION);
        if (!drinking) KitManager.equip(mc, idx);
        mc.options.keyUse.setDown(true);
        return true;
    }

    // ------------------------------------------------------------ FIGHT
    private void fight(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);

        if (buff(mc, me, target)) return;
        if (processWebs(mc, me, target)) return;

        // weapon: shield pe axe, warna sword
        int axeIdx = KitManager.findBest(me, KitManager::isAxe, KitManager::zeroScore);
        if (target.isBlocking() && axeIdx >= 0) {
            if (!KitManager.isAxe(me.getMainHandItem())) KitManager.equip(mc, axeIdx);
        } else if (!KitManager.isSword(me.getMainHandItem())) {
            int swordIdx = KitManager.findBest(me, KitManager::isSword, KitManager::swordScore);
            if (swordIdx >= 0) KitManager.equip(mc, swordIdx);
        }

        KitManager.aimAt(me, target.getBoundingBox().getCenter());

        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());
        boolean inWeb = KitManager.inWeb(mc.level, me);

        // crit sirf tab: enemy potion/khana use kar raha ho ya uska HP kam ho
        boolean critMode = target.isUsingItem() || target.getHealth() <= CRIT_ENEMY_HP;

        if (--strafeTimer <= 0) {
            strafeDir = rng.nextBoolean() ? 1 : -1;
            strafeTimer = 8 + rng.nextInt(15);
        }
        if (backTimer > 0) backTimer--;
        else if (dist < 3.0 && rng.nextInt(30) == 0) backTimer = 4 + rng.nextInt(4);

        boolean back = backTimer > 0 || dist < 1.2;
        boolean fwd = dist > 2.0 && !back;
        if (dist > CHASE_DIST) fwd = true;

        if (stap > 0) {
            stap--;
            back = true;
            fwd = false;
        } else if (wtap > 0) {
            wtap--;
            fwd = false;
        }

        boolean jump = critMode && me.onGround() && !inWeb && me.getAttackStrengthScale(8f) >= 0.95f;
        boolean sprint = !critMode && fwd;
        move(mc, me, fwd, back, strafeDir, sprint, jump);

        boolean charged = me.getAttackStrengthScale(0.5f) >= 0.95f;
        if (dist <= REACH && charged) {
            boolean attack = true;
            if (critMode && !inWeb && !KitManager.isAxe(me.getMainHandItem())) {
                attack = me.fallDistance > 0 && !me.onGround() && !me.onClimbable()
                        && !me.isInWater() && !me.isSprinting();
            }
            if (attack) {
                mc.gameMode.attack(me, target);
                me.swing(InteractionHand.MAIN_HAND);
                if (!critMode) {
                    if (rng.nextInt(STAP_CHANCE) == 0) stap = 2;
                    else wtap = 2;
                }
            }
        }
    }

    // ------------------------------------------------------------ HEAL (pearl -> pot -> gapple)
    private void heal(Minecraft mc, LocalPlayer me, Player target, int foodIdx, int potIdx) {
        double dist = Math.sqrt(me.distanceToSqr(target));
        boolean eating = me.isUsingItem() && KitManager.hasFoodComponent(me.getMainHandItem());
        float hp = me.getHealth();

        // 1) enemy paas hai aur HP kaafi hai -> pearl se door
        int pearlIdx = KitManager.findBest(me, NethpotPvp::isPearl, KitManager::zeroScore);
        if (pearlIdx >= 0 && !pearlUsed && pearlCd == 0 && !eating
                && dist < 5.0 && hp > PEARL_MIN_HP) {
            throwPearl(mc, me, target, pearlIdx);
            return;
        }

        // 2) quick pot: enemy se ulta bhaagte hue neeche dekh ke pair pe fenko
        if (potIdx >= 0 && hp < POT_DONE_HP && !eating) {
            mc.options.keyUse.setDown(false);
            KitManager.setRotation(me, yawAway(me, target), 90f);
            move(mc, me, true, false, 0, false, false);
            if (potCd == 0) {
                KitManager.equip(mc, potIdx);
                mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
                me.swing(InteractionHand.MAIN_HAND);
                potCd = POT_COOLDOWN;
            }
            return;
        }

        // 3) enemy paas hai to bhaago
        if (dist < FLEE_SAFE_DIST) {
            mc.options.keyUse.setDown(false);
            flee(mc, me, target);
            return;
        }

        // 4) safe doori: khana (gapple) khao
        KitManager.stopMovement(mc);
        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        if (foodIdx >= 0) {
            if (!eating) KitManager.equip(mc, foodIdx);
            mc.options.keyUse.setDown(true);
        } else {
            mc.options.keyUse.setDown(false);
        }
    }

    private void throwPearl(Minecraft mc, LocalPlayer me, Player target, int pearlIdx) {
        mc.options.keyUse.setDown(false);
        KitManager.equip(mc, pearlIdx);
        KitManager.setRotation(me, yawAway(me, target), -25f);
        mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
        me.swing(InteractionHand.MAIN_HAND);
        pearlUsed = true;
        pearlCd = PEARL_COOLDOWN;
    }

    private static float yawAway(Player me, Player target) {
        double ax = me.getX() - target.getX();
        double az = me.getZ() - target.getZ();
        if (ax * ax + az * az < 1e-4) ax = 1;
        return (float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0);
    }

    private void flee(Minecraft mc, LocalPlayer me, Player target) {
        KitManager.setRotation(me, yawAway(me, target), 0f);
        move(mc, me, true, false, 0, true, rng.nextInt(12) == 0);
    }

    // ------------------------------------------------------------ MOVE (cobweb se bachte huve)
    private void move(Minecraft mc, LocalPlayer me, boolean fwd, boolean back, int strafe,
                      boolean sprint, boolean jump) {
        double yawR = Math.toRadians(me.getYRot());
        double fx = -Math.sin(yawR), fz = Math.cos(yawR);
        double rx = -Math.cos(yawR), rz = -Math.sin(yawR);
        Level lvl = mc.level;

        if (fwd && KitManager.webAhead(lvl, me, fx, fz)) {
            fwd = false;
            if (strafe == 0) strafe = rng.nextBoolean() ? 1 : -1;
        }
        if (back && KitManager.webAhead(lvl, me, -fx, -fz)) back = false;
        if (strafe > 0 && KitManager.webAhead(lvl, me, rx, rz)) {
            strafe = KitManager.webAhead(lvl, me, -rx, -rz) ? 0 : -1;
            strafeDir = -1;
        } else if (strafe < 0 && KitManager.webAhead(lvl, me, -rx, -rz)) {
            strafe = KitManager.webAhead(lvl, me, rx, rz) ? 0 : 1;
            strafeDir = 1;
        }
        if (KitManager.inWeb(lvl, me)) {
            back = false;
            strafe = 0;
            jump = false;
        }

        mc.options.keyUp.setDown(fwd);
        mc.options.keyDown.setDown(back);
        mc.options.keyLeft.setDown(strafe < 0);
        mc.options.keyRight.setDown(strafe > 0);
        mc.options.keySprint.setDown(sprint && fwd);
        mc.options.keyJump.setDown(jump && me.onGround());
        if (!(sprint && fwd)) me.setSprinting(false);
    }

    // ------------------------------------------------------------ COBWEB PLACE
    private boolean processWebs(Minecraft mc, LocalPlayer me, Player target) {
        int webIdx = KitManager.findBest(me, KitManager::isWeb, KitManager::zeroScore);

        if (!webQueue.isEmpty()) {
            if (webIdx < 0) {
                webQueue.clear();
                return false;
            }
            BlockPos pos = webQueue.poll();
            if (!KitManager.isWeb(me.getMainHandItem())) KitManager.equip(mc, webIdx);
            placeWeb(mc, me, pos);
            return true;
        }

        if (webTimer >= WEB_INTERVAL && webIdx >= 0 && me.distanceTo(target) <= WEB_PLACE_RANGE) {
            BlockPos feet = target.blockPosition();
            if (KitManager.isWebBlock(mc.level, feet) || KitManager.isWebBlock(mc.level, feet.above()))
                return false;

            Direction away = Direction.getNearest(
                    target.getX() - me.getX(), 0, target.getZ() - me.getZ());
            webQueue.add(feet);
            webQueue.add(feet.above());
            webQueue.add(feet.relative(away));
            webTimer = 0;
        }
        return false;
    }

    private void placeWeb(Minecraft mc, LocalPlayer me, BlockPos pos) {
        BlockState st = mc.level.getBlockState(pos);
        if (!st.isAir() && !st.canBeReplaced()) return;
        if (me.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > WEB_PLACE_RANGE) return;

        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            BlockState ns = mc.level.getBlockState(n);
            if (ns.isAir() || ns.canBeReplaced()) continue;
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, n, false));
            me.swing(InteractionHand.MAIN_HAND);
            return;
        }
    }
  }
