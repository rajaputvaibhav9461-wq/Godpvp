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
    static final float HEAL_START_HP = 9.0f;
    static final float FLEE_HP = 6.0f;
    static final double FLEE_FAR_DIST = 20.0;
    static final float POT_DONE_HP = 16.0f;
    static final float HEAL_DONE_HP = 16.0f;
    static final float PEARL_MIN_HP = 8.0f;
    static final double REACH = 2.95;
    static final double SPRINT_DIST = 3.2;
    static final double CLOSE_DIST = 1.0;
    static final double FLEE_SAFE_DIST = 8.0;
    static final double BUFF_SAFE_DIST = 10.0;
    static final int CRIT_FALLBACK = 15;
    static final int POT_COOLDOWN = 4;
    static final int PEARL_COOLDOWN = 20;
    static final int TOTEM_COOLDOWN = 10;
    static final int WEB_INTERVAL = 100;
    static final double WEB_PLACE_RANGE = 4.5;

    private final Random rng = new Random();
    private final ArrayDeque<BlockPos> webQueue = new ArrayDeque<>();
    private int strafeDir = 1, strafeTimer = 0, webTimer = 0;
    private int potCd = 0, pearlCd = 0, totemCd = 0, critWait = 0;
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
        strafeTimer = 0;
        potCd = 0;
        pearlCd = 0;
        totemCd = 0;
        critWait = 0;
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
        else if (hp <= FLEE_HP) runAway(mc, me, target);
        else fight(mc, me, target);
    }

    private void ensureTotem(Minecraft mc, LocalPlayer me) {
        if (totemCd > 0 || me.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        int idx = KitManager.findBest(me, NethpotPvp::isTotem, KitManager::zeroScore);
        if (idx < 0) return;
        int slot = idx < 9 ? 36 + idx : idx;
        mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId, slot, 40, ClickType.SWAP, me);
        totemCd = TOTEM_COOLDOWN;
    }

    // ------------------------------------------------------------ BUFF (strength + speed)
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

    // ------------------------------------------------------------ FIGHT (sprint chase + crit + axe turant)
    private void fight(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);

        if (buff(mc, me, target)) return;
        if (processWebs(mc, me, target)) return;

        int axeIdx = KitManager.findBest(me, KitManager::isAxe, KitManager::zeroScore);
        boolean axeMode = target.isBlocking() && axeIdx >= 0;

        if (axeMode) {
            if (!KitManager.isAxe(me.getMainHandItem())) KitManager.equip(mc, axeIdx);
        } else if (!KitManager.isSword(me.getMainHandItem())) {
            int swordIdx = KitManager.findBest(me, KitManager::isSword, KitManager::swordScore);
            if (swordIdx >= 0) KitManager.equip(mc, swordIdx);
        }

        KitManager.aimAt(me, target.getBoundingBox().getCenter());

        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());
        boolean inWeb = KitManager.inWeb(mc.level, me);

        if (--strafeTimer <= 0) {
            strafeDir = rng.nextBoolean() ? 1 : -1;
            strafeTimer = 8 + rng.nextInt(15);
        }

        boolean outOfRange = dist > SPRINT_DIST;
        boolean back = dist < CLOSE_DIST;
        boolean fwd = !back && dist > 1.5;
        if (outOfRange) fwd = true;

        boolean sprint = outOfRange;
        int strafe = outOfRange ? 0 : strafeDir;

        boolean jump = !axeMode && !outOfRange && me.onGround() && !inWeb && dist <= REACH + 0.2
                && me.getAttackStrengthScale(8f) >= 0.95f;
        move(mc, me, fwd, back, strafe, sprint, jump);

        if (dist > REACH) {
            critWait = 0;
            return;
        }

        if (axeMode) {
            mc.gameMode.attack(me, target);
            me.swing(InteractionHand.MAIN_HAND);
            return;
        }

        if (me.getAttackStrengthScale(0.5f) < 0.95f) return;

        boolean crit = me.fallDistance > 0 && !me.onGround() && !me.onClimbable()
                && !me.isInWater() && !me.isSprinting();
        critWait++;
        if (crit || inWeb || critWait > CRIT_FALLBACK) {
            mc.gameMode.attack(me, target);
            me.swing(InteractionHand.MAIN_HAND);
            critWait = 0;
        }
                                   }
        // ------------------------------------------------------------ RUN AWAY (3 heart pe)
    private void runAway(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);
        double dist = Math.sqrt(me.distanceToSqr(target));
        if (dist >= FLEE_FAR_DIST) {
            KitManager.stopMovement(mc);
            return;
        }
        flee(mc, me, target);
    }

    // ------------------------------------------------------------ HEAL (pearl -> pot -> gapple)
    private void heal(Minecraft mc, LocalPlayer me, Player target, int foodIdx, int potIdx) {
        double dist = Math.sqrt(me.distanceToSqr(target));
        boolean eating = me.isUsingItem() && KitManager.hasFoodComponent(me.getMainHandItem());
        float hp = me.getHealth();

        int pearlIdx = KitManager.findBest(me, NethpotPvp::isPearl, KitManager::zeroScore);
        if (pearlIdx >= 0 && !pearlUsed && pearlCd == 0 && !eating
                && dist < 5.0 && hp > PEARL_MIN_HP) {
            throwPearl(mc, me, target, pearlIdx);
            return;
        }

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

        if (dist < FLEE_SAFE_DIST) {
            mc.options.keyUse.setDown(false);
            flee(mc, me, target);
            return;
        }

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
