package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Random;
import java.util.function.Predicate;

/** Elytra Mace PvP: SIRF FIREWORK ROCKET wala tarika. */
public class ElytraMaceRocketPvp {
    static final float LOW_HP = 8.0f;
    static final float HEAL_DONE_HP = 16.0f;
    static final float PEARL_MIN_HP = 6.5f;
    static final double REACH = 3.0;
    static final double FLEE_SAFE_DIST = 8.0;
    static final double CLIMB_HEIGHT = 20.0;
    static final double ENGAGE_BASE = 4.5;
    static final double ENGAGE_SPEED_MUL = 2.0;
    static final double SMASH_FALL = 1.6;
    static final int ROCKET_INTERVAL = 10;
    static final int PEARL_COOLDOWN = 20;
    static final int SWAP_COOLDOWN = 3;
    static final int TOTEM_COOLDOWN = 10;

    enum Phase { LAUNCH, GLIDE_UP, SWAP_CHEST, SWAP_ELYTRA, REGLIDE, DIVE, ATTACK }

    private final Random rng = new Random();
    private Phase phase = Phase.LAUNCH;
    private int phaseTicks = 0, pearlCd = 0, rocketCd = 0, swapCd = 0, totemCd = 0;
    private int afterHit = 0, groundTicks = 0, strafeDir = 1;
    private boolean healMode = false, pearlUsed = false, warned = false, hadTotem = false;
    private boolean jumpToggle = false, attacked = false, axeHit = false;
    private double climbStartY = 0;

    // ------------------------------------------------------------ ITEM HELPERS
    static boolean isMace(ItemStack s) { return s.is(Items.MACE); }
    static boolean isRocket(ItemStack s) { return s.is(Items.FIREWORK_ROCKET); }
    static boolean isPearl(ItemStack s) { return s.is(Items.ENDER_PEARL); }
    static boolean isTotem(ItemStack s) { return s.is(Items.TOTEM_OF_UNDYING); }

    static boolean isElytraOk(ItemStack s) {
        return s.is(Items.ELYTRA) && s.getDamageValue() < s.getMaxDamage() - 1;
    }

    static boolean isChestplate(ItemStack s) {
        return !s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_chestplate");
    }

    private static boolean has(Player p, Predicate<ItemStack> f) {
        return KitManager.findBest(p, f, KitManager::zeroScore) >= 0;
    }

    private static boolean wearing(Player p, boolean elytra) {
        ItemStack c = p.getItemBySlot(EquipmentSlot.CHEST);
        return elytra ? isElytraOk(c) : isChestplate(c);
    }

    private static boolean hasElytra(Player p) {
        return wearing(p, true) || has(p, ElytraMaceRocketPvp::isElytraOk);
    }

    private static boolean hasChestplate(Player p) {
        return wearing(p, false) || has(p, ElytraMaceRocketPvp::isChestplate);
    }

    private static boolean hasRocket(Player p) {
        return has(p, ElytraMaceRocketPvp::isRocket);
    }

    // ------------------------------------------------------------ LIFECYCLE
    boolean matches(Minecraft mc) {
        Player p = mc.player;
        return hasElytra(p) && hasChestplate(p) && has(p, ElytraMaceRocketPvp::isMace) && hasRocket(p);
    }

    void onActivate(Minecraft mc) {
        healMode = false;
        pearlUsed = false;
        warned = false;
        pearlCd = 0;
        rocketCd = 0;
        swapCd = 0;
        totemCd = 0;
        hadTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        setPhase(Phase.LAUNCH);
    }

    void onDeactivate(Minecraft mc) {
        healMode = false;
        KitManager.releaseAll(mc);
    }

    private void setPhase(Phase p) {
        phase = p;
        phaseTicks = 0;
        if (p == Phase.ATTACK) {
            attacked = false;
            axeHit = false;
            afterHit = 0;
            groundTicks = 0;
        }
    }

    // ------------------------------------------------------------ TICK
    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (pearlCd > 0) pearlCd--;
        if (rocketCd > 0) rocketCd--;
        if (swapCd > 0) swapCd--;
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
            return;
        }

        int foodIdx = KitManager.findBest(me, s -> KitManager.isFood(me, s), KitManager::foodScore);
        float hp = me.getHealth();
        if (!healMode && hp <= LOW_HP && foodIdx >= 0) {
            healMode = true;
            pearlUsed = false;
        }
        if (healMode && (hp >= HEAL_DONE_HP || foodIdx < 0)) {
            healMode = false;
            mc.options.keyUse.setDown(false);
            setPhase(Phase.LAUNCH);
        }
        if (healMode) {
            heal(mc, me, target, foodIdx);
            return;
        }

        phaseTicks++;

        if (phase == Phase.LAUNCH && !hasRocket(me)) {
            if (!warned) {
                KitManager.msg(mc, "Firework rocket khatam");
                warned = true;
            }
            KitManager.releaseAll(mc);
            return;
        }
        warned = false;

        switch (phase) {
            case LAUNCH -> launch(mc, me);
            case GLIDE_UP -> glideUp(mc, me, target);
            case SWAP_CHEST -> swapChest(mc, me, target);
            case SWAP_ELYTRA -> swapElytra(mc, me, target);
            case REGLIDE -> reglide(mc, me, target);
            case DIVE -> dive(mc, me, target);
            case ATTACK -> attack(mc, me, target);
        }
    }

    private void ensureTotem(Minecraft mc, LocalPlayer me) {
        if (totemCd > 0 || me.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        int idx = KitManager.findBest(me, ElytraMaceRocketPvp::isTotem, KitManager::zeroScore);
        if (idx < 0) return;
        int slot = idx < 9 ? 36 + idx : idx;
        mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId, slot, 40, ClickType.SWAP, me);
        totemCd = TOTEM_COOLDOWN;
    }

    // ------------------------------------------------------------ PHASES
    /** 1) elytra pehno. */
    private void launch(Minecraft mc, LocalPlayer me) {
        mc.options.keyUse.setDown(false);
        if (!ensureChest(mc, me, true)) return;
        climbStartY = me.getY();
        jumpToggle = false;
        setPhase(Phase.GLIDE_UP);
    }

    /** 2) uper dekho, double jump, rocket se 20 block uper. */
    private void glideUp(Minecraft mc, LocalPlayer me, Player target) {
        KitManager.setRotation(me, yawTo(me, target), -85f);
        if (!me.isFallFlying()) {
            tryGlide(mc, me);
            if (phaseTicks > 40) setPhase(Phase.LAUNCH);
            return;
        }
        mc.options.keyJump.setDown(false);
        if (me.getY() - climbStartY >= CLIMB_HEIGHT || phaseTicks > 120) {
            setPhase(Phase.SWAP_CHEST);
            return;
        }
        if (!hasRocket(me)) {
            setPhase(Phase.LAUNCH);
            return;
        }
        if (rocketCd == 0) useRocket(mc, me);
    }

    /** 3) chestplate pehno (aim enemy pe). */
    private void swapChest(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyJump.setDown(false);
        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        if (ensureChest(mc, me, false)) setPhase(Phase.SWAP_ELYTRA);
        else if (phaseTicks > 30) setPhase(Phase.LAUNCH);
    }

    /** 4) wapas elytra pehno (aim enemy pe). */
    private void swapElytra(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyJump.setDown(false);
        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        if (ensureChest(mc, me, true)) {
            jumpToggle = false;
            setPhase(Phase.REGLIDE);
        } else if (phaseTicks > 30) {
            setPhase(Phase.LAUNCH);
        }
    }

    /** 5) dobara fly shuru karo (enemy pe aim). */
    private void reglide(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);
        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        if (me.isFallFlying()) {
            mc.options.keyJump.setDown(false);
            setPhase(Phase.DIVE);
            return;
        }
        tryGlide(mc, me);
        if (phaseTicks > 40) setPhase(Phase.LAUNCH);
    }

    /** 6) rocket se enemy ki taraf teji se. Hit range me aate hi ATTACK. */
    private void dive(Minecraft mc, LocalPlayer me, Player target) {
        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());
        double speed = me.getDeltaMovement().length();
        double engage = ENGAGE_BASE + speed * ENGAGE_SPEED_MUL;

        if (dist <= engage) {
            mc.options.keyJump.setDown(false);
            setPhase(Phase.ATTACK);
            return;
        }
        if (!me.isFallFlying()) {
            if (me.onGround()) {
                setPhase(Phase.LAUNCH);
                return;
            }
            tryGlide(mc, me);
            if (phaseTicks > 150) setPhase(Phase.LAUNCH);
            return;
        }
        mc.options.keyJump.setDown(false);
        if (rocketCd == 0 && hasRocket(me)) useRocket(mc, me);
        if (phaseTicks > 200) setPhase(Phase.LAUNCH);
  }
      /** 7) elytra utaar ke chestplate, shield ho to axe, phir mace smash. */
    private void attack(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);
        mc.options.keyJump.setDown(false);

        if (attacked) {
            KitManager.stopMovement(mc);
            if (++afterHit >= 6) setPhase(Phase.LAUNCH);
            return;
        }
        if (!ensureChest(mc, me, false) && hasChestplate(me)) {
            if (phaseTicks > 30) setPhase(Phase.LAUNCH);
            return;
        }

        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());
        boolean ground = me.onGround();
        move(mc, me, dist > 1.5, false, 0, ground && dist > 4.0, false);

        // stun slam: enemy shield pe axe
        int axeIdx = KitManager.findBest(me, KitManager::isAxe, KitManager::zeroScore);
        if (!axeHit && axeIdx >= 0 && target.isBlocking()) {
            if (!KitManager.isAxe(me.getMainHandItem())) {
                KitManager.equip(mc, axeIdx);
                return;
            }
            if (dist <= REACH) {
                mc.gameMode.attack(me, target);
                me.swing(InteractionHand.MAIN_HAND);
                axeHit = true;
            }
            if (phaseTicks > 40) setPhase(Phase.LAUNCH);
            return;
        }

        // mace smash
        int maceIdx = KitManager.findBest(me, ElytraMaceRocketPvp::isMace, KitManager::zeroScore);
        if (maceIdx >= 0 && !isMace(me.getMainHandItem())) {
            KitManager.equip(mc, maceIdx);
            return;
        }

        boolean smash = !me.isFallFlying() && me.fallDistance > SMASH_FALL;
        boolean groundHit = ground && me.getAttackStrengthScale(0.5f) >= 0.9f;
        if (dist <= REACH && (smash || groundHit || phaseTicks > 40)) {
            mc.gameMode.attack(me, target);
            me.swing(InteractionHand.MAIN_HAND);
            attacked = true;
            afterHit = 0;
            return;
        }
        if (!ground && dist > REACH + 8.0 && phaseTicks > 5) {
            setPhase(Phase.LAUNCH);
            return;
        }
        if (ground) {
            if (++groundTicks > 25) setPhase(Phase.LAUNCH);
        } else {
            groundTicks = 0;
        }
        if (phaseTicks > 80) setPhase(Phase.LAUNCH);
    }

    // ------------------------------------------------------------ ELYTRA / CHESTPLATE SWAP
    private boolean ensureChest(Minecraft mc, LocalPlayer me, boolean elytra) {
        if (wearing(me, elytra)) return true;
        if (swapCd > 0) return false;

        Predicate<ItemStack> f = elytra ? ElytraMaceRocketPvp::isElytraOk : ElytraMaceRocketPvp::isChestplate;
        int idx = KitManager.findBest(me, f, KitManager::zeroScore);
        if (idx < 0) return false;

        Inventory inv = me.getInventory();
        int cid = me.inventoryMenu.containerId;
        int btn = idx;
        if (idx >= 9) {
            btn = KitManager.freeHotbarSlot(inv);
            mc.gameMode.handleInventoryMouseClick(cid, idx, btn, ClickType.SWAP, me);
        }
        mc.gameMode.handleInventoryMouseClick(cid, 6, btn, ClickType.SWAP, me);
        swapCd = SWAP_COOLDOWN;
        return false;
    }

    // ------------------------------------------------------------ FLY HELPERS
    private void tryGlide(Minecraft mc, LocalPlayer me) {
        if (me.isFallFlying()) {
            mc.options.keyJump.setDown(false);
            jumpToggle = false;
            return;
        }
        if (me.onGround()) {
            mc.options.keyJump.setDown(true);
            jumpToggle = true;
            return;
        }
        jumpToggle = !jumpToggle;
        mc.options.keyJump.setDown(jumpToggle);
    }

    private void useRocket(Minecraft mc, LocalPlayer me) {
        int r = KitManager.findBest(me, ElytraMaceRocketPvp::isRocket, KitManager::zeroScore);
        if (r < 0) return;
        if (!isRocket(me.getMainHandItem())) KitManager.equip(mc, r);
        mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
        me.swing(InteractionHand.MAIN_HAND);
        rocketCd = ROCKET_INTERVAL;
    }

    private static float yawTo(Player me, Player target) {
        double dx = target.getX() - me.getX();
        double dz = target.getZ() - me.getZ();
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    // ------------------------------------------------------------ HEAL
    private void heal(Minecraft mc, LocalPlayer me, Player target, int foodIdx) {
        double dist = Math.sqrt(me.distanceToSqr(target));
        boolean eating = me.isUsingItem() && KitManager.hasFoodComponent(me.getMainHandItem());

        // hawa me: elytra ho to glide karke door utro
        if (!me.onGround()) {
            mc.options.keyUse.setDown(false);
            if (wearing(me, true)) {
                double ax = me.getX() - target.getX();
                double az = me.getZ() - target.getZ();
                float yaw = (float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0);
                KitManager.setRotation(me, yaw, 10f);
                tryGlide(mc, me);
            } else {
                fleeFrom(mc, me, target.position());
            }
            return;
        }

        // jamin pe: chestplate pehno
        if (!ensureChest(mc, me, false) && hasChestplate(me)) return;

        int pearlIdx = KitManager.findBest(me, ElytraMaceRocketPvp::isPearl, KitManager::zeroScore);
        if (pearlIdx >= 0 && !pearlUsed && pearlCd == 0 && !eating
                && dist < FLEE_SAFE_DIST && me.getHealth() > PEARL_MIN_HP) {
            throwPearl(mc, me, target, pearlIdx);
            return;
        }
        if (dist < FLEE_SAFE_DIST) {
            mc.options.keyUse.setDown(false);
            fleeFrom(mc, me, target.position());
            return;
        }
        KitManager.stopMovement(mc);
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
        double ax = me.getX() - target.getX();
        double az = me.getZ() - target.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0);
        KitManager.setRotation(me, yaw, -25f);
        mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
        me.swing(InteractionHand.MAIN_HAND);
        pearlUsed = true;
        pearlCd = PEARL_COOLDOWN;
    }

    private void fleeFrom(Minecraft mc, LocalPlayer me, Vec3 p) {
        double ax = me.getX() - p.x;
        double az = me.getZ() - p.z;
        if (ax * ax + az * az < 1e-4) ax = 1;
        float yaw = (float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0);
        KitManager.setRotation(me, yaw, 0f);
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
