package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.Random;

public class MacePvp {
    // ---- settings (yahan se badal sakte ho) ----
    static final float HEAL_START_HP = 8.0f;   // 4 heart pe heal shuru
    static final float HEAL_DONE_HP = 16.0f;   // itna HP hone pe wapas fight
    static final float PEARL_MIN_HP = 6.5f;    // isse kam HP pe pearl nahi (5 damage lagta hai)
    static final double REACH = 3.0;
    static final double FLEE_SAFE_DIST = 8.0;
    static final double SMASH_FALL = 1.6;      // itna girne ke baad hi smash lagta hai
    static final double SHIELD_MIN_H = 4.0;    // enemy itna uper ho to shield up
    static final double SHIELD_MAX_H = 12.0;
    static final int WC_COOLDOWN = 10;
    static final int PEARL_COOLDOWN = 20;
    static final int TOTEM_COOLDOWN = 10;

    private final Random rng = new Random();
    private int strafeDir = 1, strafeTimer = 0, wcCd = 0, pearlCd = 0, totemCd = 0;
    private boolean healMode = false, thrown = false, pearlUsed = false;
    private boolean escapeUsed = false, hadTotem = false;

    // ------------------------------------------------------------ ITEM HELPERS
    static boolean isMace(ItemStack s) { return s.is(Items.MACE); }
    static boolean isWindCharge(ItemStack s) { return s.is(Items.WIND_CHARGE); }
    static boolean isPearl(ItemStack s) { return s.is(Items.ENDER_PEARL); }
    static boolean isShield(ItemStack s) { return s.is(Items.SHIELD); }
    static boolean isTotem(ItemStack s) { return s.is(Items.TOTEM_OF_UNDYING); }

    // ------------------------------------------------------------ LIFECYCLE
    boolean matches(Minecraft mc) {
        return KitManager.findBest(mc.player, MacePvp::isMace, KitManager::zeroScore) >= 0
                && KitManager.findBest(mc.player, MacePvp::isWindCharge, KitManager::zeroScore) >= 0;
    }

    void onActivate(Minecraft mc) {
        healMode = false;
        thrown = false;
        pearlUsed = false;
        escapeUsed = false;
        wcCd = 0;
        pearlCd = 0;
        totemCd = 0;
        strafeTimer = 0;
        hadTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
    }

    void onDeactivate(Minecraft mc) {
        healMode = false;
        KitManager.releaseAll(mc);
    }

    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (wcCd > 0) wcCd--;
        if (pearlCd > 0) pearlCd--;
        if (totemCd > 0) totemCd--;

        // totem pop hua? -> turant heal mode
        boolean hasTotem = me.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        boolean popped = hadTotem && me.getOffhandItem().isEmpty();
        hadTotem = hasTotem;
        if (popped) {
            healMode = true;
            pearlUsed = false;
            escapeUsed = false;
        }
        if (!me.isUsingItem()) ensureTotem(mc, me);

        if (target == null) {
            KitManager.releaseAll(mc);
            return;
        }

        int foodIdx = KitManager.findBest(me, s -> KitManager.isFood(me, s), KitManager::foodScore);
        float hp = me.getHealth();
        if (!healMode && hp <= HEAL_START_HP && foodIdx >= 0) {
            healMode = true;
            pearlUsed = false;
            escapeUsed = false;
        }
        if (healMode && (hp >= HEAL_DONE_HP || foodIdx < 0)) {
            healMode = false;
            mc.options.keyUse.setDown(false);
        }

        if (healMode) heal(mc, me, target, foodIdx);
        else fight(mc, me, target);
    }

    /** totem offhand me nahi hai to inventory se offhand me swap karo. */
    private void ensureTotem(Minecraft mc, LocalPlayer me) {
        if (totemCd > 0 || me.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        int idx = KitManager.findBest(me, MacePvp::isTotem, KitManager::zeroScore);
        if (idx < 0) return;
        int slot = idx < 9 ? 36 + idx : idx; // inventoryMenu slot number
        mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId, slot, 40, ClickType.SWAP, me);
        totemCd = TOTEM_COOLDOWN;
    }

    // ------------------------------------------------------------ FIGHT
    private void fight(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);

        boolean ground = me.onGround();
        if (ground) thrown = false;

        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());
        double dy = target.getY() - me.getY();
        boolean inWeb = KitManager.inWeb(mc.level, me);

        // 1) enemy 4+ block uper hai (par bahut zyada nahi) -> shield up
        if (dy >= SHIELD_MIN_H && dy <= SHIELD_MAX_H && shieldUp(mc, me)) return;

        // 2) enemy shield laga ke khada hai -> axe se shield todo, phir mace
        int axeIdx = KitManager.findBest(me, KitManager::isAxe, KitManager::zeroScore);
        if (target.isBlocking() && axeIdx >= 0 && dist <= REACH + 1.0) {
            if (!KitManager.isAxe(me.getMainHandItem())) KitManager.equip(mc, axeIdx);
            KitManager.aimAt(me, target.getBoundingBox().getCenter());
            move(mc, me, dist > 2.0 && ground, false, 0, false, false);
            if (dist <= REACH && me.getAttackStrengthScale(0.5f) >= 0.9f) {
                mc.gameMode.attack(me, target);
                me.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }

        int wcIdx = KitManager.findBest(me, MacePvp::isWindCharge, KitManager::zeroScore);
        int maceIdx = KitManager.findBest(me, MacePvp::isMace, KitManager::zeroScore);

        // ---------------- JAMIN PAR ----------------
        if (ground) {
            if (maceIdx >= 0 && !isMace(me.getMainHandItem())) KitManager.equip(mc, maceIdx);

            // yaw enemy ki taraf, pitch seedha (aim enemy pe nahi)
            KitManager.setRotation(me, yawTo(me, target), 0f);

            if (wcIdx >= 0 && wcCd == 0 && !inWeb) {
                move(mc, me, false, false, 0, false, true); // jump karo
                return;
            }
            // wind charge nahi / cooldown: pas jao aur normal hit
            move(mc, me, dist > 2.0, false, strafeDir(), false, false);
            if (dist <= REACH && me.getAttackStrengthScale(0.5f) >= 0.95f) {
                mc.gameMode.attack(me, target);
                me.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }

        // ---------------- HAWA ME ----------------
        double vy = me.getDeltaMovement().y;

        // jump ke top pe -> neeche wind charge, uper jaane ke liye
        if (!thrown && wcIdx >= 0 && wcCd == 0 && me.fallDistance < SMASH_FALL && !inWeb) {
            if (vy <= 0.1) {
                throwWindCharge(mc, me, wcIdx);
                thrown = true;
            } else {
                move(mc, me, false, false, 0, false, false);
            }
            return;
        }

        if (vy < 0) {
            // girte waqt: aim enemy pe, mace smash
            if (maceIdx >= 0 && !isMace(me.getMainHandItem())) KitManager.equip(mc, maceIdx);
            KitManager.aimAt(me, target.getBoundingBox().getCenter());
            move(mc, me, dist > 1.0, false, 0, false, false);
            if (dist <= REACH && me.fallDistance > SMASH_FALL) {
                mc.gameMode.attack(me, target);
                me.swing(InteractionHand.MAIN_HAND);
            }
        } else {
            // uper ja rahe hain: aim nahi lagana
            move(mc, me, true, false, 0, false, false);
        }
    }

    private int strafeDir() {
        if (--strafeTimer <= 0) {
            strafeDir = rng.nextBoolean() ? 1 : -1;
            strafeTimer = 8 + rng.nextInt(15);
        }
        return strafeDir;
    }

    private static float yawTo(Player me, Player target) {
        double dx = target.getX() - me.getX();
        double dz = target.getZ() - me.getZ();
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    private static float yawAway(Player me, Player target) {
        double ax = me.getX() - target.getX();
        double az = me.getZ() - target.getZ();
        if (ax * ax + az * az < 1e-4) ax = 1;
        return (float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0);
    }

    private void throwWindCharge(Minecraft mc, LocalPlayer me, int wcIdx) {
        KitManager.equip(mc, wcIdx);
        KitManager.setRotation(me, me.getYRot(), 90f); // seedha neeche
        mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
        me.swing(InteractionHand.MAIN_HAND);
        wcCd = WC_COOLDOWN;
    }

    /** true = shield utha liya (fight skip). */
    private boolean shieldUp(Minecraft mc, LocalPlayer me) {
        boolean off = isShield(me.getOffhandItem());
        if (!off) {
            int sIdx = KitManager.findBest(me, MacePvp::isShield, KitManager::zeroScore);
            if (sIdx < 0) return false;
            if (!isShield(me.getMainHandItem())) KitManager.equip(mc, sIdx);
        } else if (!isMace(me.getMainHandItem())) {
            int maceIdx = KitManager.findBest(me, MacePvp::isMace, KitManager::zeroScore);
            if (maceIdx >= 0) KitManager.equip(mc, maceIdx);
        }
        KitManager.stopMovement(mc);
        mc.options.keyUse.setDown(true);
        return true;
    }

    // ------------------------------------------------------------ HEAL (wind charge escape + pearl + khana)
    private void heal(Minecraft mc, LocalPlayer me, Player target, int foodIdx) {
        double dist = Math.sqrt(me.distanceToSqr(target));
        boolean eating = me.isUsingItem() && KitManager.hasFoodComponent(me.getMainHandItem());
        float hp = me.getHealth();

        // 1) enemy paas hai, jamin pe hu -> neeche wind charge se uchhal ke door
        int wcIdx = KitManager.findBest(me, MacePvp::isWindCharge, KitManager::zeroScore);
        if (!escapeUsed && wcIdx >= 0 && wcCd == 0 && !eating && dist < 5.0 && me.onGround()) {
            mc.options.keyUse.setDown(false);
            throwWindCharge(mc, me, wcIdx);
            escapeUsed = true;
            return;
        }

        // 2) ender pearl (HP kaafi ho tabhi)
        int pearlIdx = KitManager.findBest(me, MacePvp::isPearl, KitManager::zeroScore);
        if (pearlIdx >= 0 && !pearlUsed && pearlCd == 0 && !eating
                && dist < FLEE_SAFE_DIST && hp > PEARL_MIN_HP) {
            throwPearl(mc, me, target, pearlIdx);
            return;
        }

        // 3) enemy paas hai to bhaago
        if (dist < FLEE_SAFE_DIST) {
            mc.options.keyUse.setDown(false);
            flee(mc, me, target);
            return;
        }

        // 4) safe doori: khana khao
        KitManager.stopMovement(mc);
        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        if (!eating) KitManager.equip(mc, foodIdx);
        mc.options.keyUse.setDown(true);
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
                                }
