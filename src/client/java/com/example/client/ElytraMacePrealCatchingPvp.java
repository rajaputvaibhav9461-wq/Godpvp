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

/** Elytra Mace PvP: PEARL CATCHING wala tarika (pearl + wind charge + elytra). */
public class ElytraMacePrealCatchingPvp {
    static final float LOW_HP = 8.0f;
    static final float HEAL_DONE_HP = 16.0f;
    static final float PEARL_MIN_HP = 6.5f;     // heal me pearl isse kam HP pe nahi
    static final float COMBO_MIN_HP = 10.0f;    // combo ke liye minimum HP (pearl 5 damage)
    static final double REACH = 3.0;
    static final double FLEE_SAFE_DIST = 8.0;
    static final double PEARL_MIN_DIST = 6.0;   // isse paas ho to pearl nahi
    static final double PEARL_MAX_DIST = 30.0;  // isse door ho to pehle paas jao
    static final double PEARL_PITCH = -50.0;    // pearl ka upar wala arc
    static final double ENGAGE_BASE = 4.5;
    static final double ENGAGE_SPEED_MUL = 2.0;
    static final double SMASH_FALL = 1.6;
    static final int WC_COOLDOWN = 10;
    static final int PEARL_COOLDOWN = 20;
    static final int SWAP_COOLDOWN = 3;
    static final int TOTEM_COOLDOWN = 10;

    enum Phase { SETUP, PEARL, JUMP, WC, ELYTRA, GLIDE, DIVE, ATTACK }

    private final Random rng = new Random();
    private Phase phase = Phase.SETUP;
    private int phaseTicks = 0, wcCd = 0, pearlCd = 0, swapCd = 0, totemCd = 0;
    private int afterHit = 0, groundTicks = 0, strafeDir = 1;
    private boolean healMode = false, pearlUsed = false, warned = false, hadTotem = false;
    private boolean jumpToggle = false, attacked = false, axeHit = false;

    // ------------------------------------------------------------ ITEM HELPERS
    static boolean isMace(ItemStack s) { return s.is(Items.MACE); }
    static boolean isPearl(ItemStack s) { return s.is(Items.ENDER_PEARL); }
    static boolean isWindCharge(ItemStack s) { return s.is(Items.WIND_CHARGE); }
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
        return wearing(p, true) || has(p, ElytraMacePrealCatchingPvp::isElytraOk);
    }

    private static boolean hasChestplate(Player p) {
        return wearing(p, false) || has(p, ElytraMacePrealCatchingPvp::isChestplate);
    }

    private static boolean hasCombo(Player p) {
        return has(p, ElytraMacePrealCatchingPvp::isPearl) && has(p, ElytraMacePrealCatchingPvp::isWindCharge);
    }

    // ------------------------------------------------------------ LIFECYCLE
    boolean matches(Minecraft mc) {
        Player p = mc.player;
        return hasElytra(p) && hasChestplate(p) && has(p, ElytraMacePrealCatchingPvp::isMace) && hasCombo(p);
    }

    void onActivate(Minecraft mc) {
        healMode = false;
        pearlUsed = false;
        warned = false;
        wcCd = 0;
        pearlCd = 0;
        swapCd = 0;
        totemCd = 0;
        hadTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        setPhase(Phase.SETUP);
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
        if (wcCd > 0) wcCd--;
        if (pearlCd > 0) pearlCd--;
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
            setPhase(Phase.SETUP);
        }
        if (healMode) {
            heal(mc, me, target, foodIdx);
            return;
        }

        phaseTicks++;

        if (phase == Phase.SETUP && !hasCombo(me)) {
            if (!warned) {
                KitManager.msg(mc, "Pearl ya wind charge khatam");
                warned = true;
            }
            KitManager.releaseAll(mc);
            return;
        }
        warned = false;

        switch (phase) {
            case SETUP -> setup(mc, me, target);
            case PEARL -> pearl(mc, me, target);
            case JUMP -> jump(mc, me);
            case WC -> wc(mc, me);
            case ELYTRA -> elytra(mc, me, target);
            case GLIDE -> glide(mc, me, target);
            case DIVE -> dive(mc, me, target);
            case ATTACK -> attack(mc, me, target);
        }
    }

    private void ensureTotem(Minecraft mc, LocalPlayer me) {
        if (totemCd > 0 || me.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        int idx = KitManager.findBest(me, ElytraMacePrealCatchingPvp::isTotem, KitManager::zeroScore);
        if (idx < 0) return;
        int slot = idx < 9 ? 36 + idx : idx;
        mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId, slot, 40, ClickType.SWAP, me);
        totemCd = TOTEM_COOLDOWN;
    }

    // ------------------------------------------------------------ PHASES
    /** 1) jamin pe chestplate pehen ke taiyar. Enemy door ho to paas jao. */
    private void setup(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);
        mc.options.keyJump.setDown(false);
        if (!me.onGround()) {
            KitManager.stopMovement(mc);
            ensureChest(mc, me, false);
            return;
        }
        if (!ensureChest(mc, me, false) && hasChestplate(me)) return;

        double dist = Math.sqrt(me.distanceToSqr(target));
        if (dist > PEARL_MAX_DIST) {
            KitManager.setRotation(me, yawTo(me, target), 0f);
            move(mc, me, true, false, 0, true, false);
            return;
        }
        KitManager.stopMovement(mc);
        if (me.getHealth() < COMBO_MIN_HP) return;
        if (pearlCd > 0 || wcCd > 0 || phaseTicks < 5) return;
        if (dist < PEARL_MIN_DIST) {
            KitManager.setRotation(me, yawAway(me, target), 0f);
            move(mc, me, true, false, 0, true, false); // thoda door hato
            return;
        }
        setPhase(Phase.PEARL);
    }

    /** 2) pearl enemy ki taraf upar arc mein. */
    private void pearl(Minecraft mc, LocalPlayer me, Player target) {
        int pr = KitManager.findBest(me, ElytraMacePrealCatchingPvp::isPearl, KitManager::zeroScore);
        if (pr < 0) { setPhase(Phase.SETUP); return; }
        mc.options.keyUse.setDown(false);
        KitManager.equip(mc, pr);
        KitManager.setRotation(me, yawTo(me, target), (float) PEARL_PITCH);
        mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
        me.swing(InteractionHand.MAIN_HAND);
        pearlCd = PEARL_COOLDOWN;
        setPhase(Phase.JUMP);
    }

    /** 3) jump. */
    private void jump(Minecraft mc, LocalPlayer me) {
        if (me.onGround()) {
            mc.options.keyJump.setDown(true);
            if (phaseTicks > 20) {
                mc.options.keyJump.setDown(false);
                setPhase(Phase.SETUP);
            }
            return;
        }
        mc.options.keyJump.setDown(false);
        setPhase(Phase.WC);
    }

    /** 4) jump ke top pe neeche wind charge (8-10 block uper). */
    private void wc(Minecraft mc, LocalPlayer me) {
        if (me.onGround()) {
            if (phaseTicks > 5) setPhase(Phase.SETUP);
            return;
        }
        if (me.getDeltaMovement().y <= 0.1 && wcCd == 0) {
            int wc = KitManager.findBest(me, ElytraMacePrealCatchingPvp::isWindCharge, KitManager::zeroScore);
            if (wc < 0) { setPhase(Phase.SETUP); return; }
            mc.options.keyUse.setDown(false);
            KitManager.equip(mc, wc);
            KitManager.setRotation(me, me.getYRot(), 90f);
            mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
            me.swing(InteractionHand.MAIN_HAND);
            wcCd = WC_COOLDOWN;
            setPhase(Phase.ELYTRA);
            return;
        }
        if (phaseTicks > 40) setPhase(Phase.SETUP);
    }

    /** 5) hawa me elytra pehno (aim enemy pe). */
    private void elytra(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyJump.setDown(false);
        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        if (ensureChest(mc, me, true)) {
            jumpToggle = false;
            setPhase(Phase.GLIDE);
        } else if (phaseTicks > 30 || me.onGround()) {
            setPhase(Phase.SETUP);
        }
    }

    /** 6) glide shuru karo. */
    private void glide(Minecraft mc, LocalPlayer me, Player target) {
        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        if (me.isFallFlying()) {
            mc.options.keyJump.setDown(false);
            setPhase(Phase.DIVE);
            return;
        }
        if (me.onGround() || phaseTicks > 40) {
            mc.options.keyJump.setDown(false);
            setPhase(Phase.SETUP);
            return;
        }
        tryGlide(mc, me);
    }

    /** 7) enemy pe dive. Hit range me aate hi ATTACK. */
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
                setPhase(Phase.SETUP);
                return;
            }
            tryGlide(mc, me);
            if (phaseTicks > 100) setPhase(Phase.SETUP);
            return;
        }
        mc.options.keyJump.setDown(false);
        if (phaseTicks > 160) setPhase(Phase.SETUP);
  }
      /** 8) elytra utaar ke chestplate, shield ho to axe, phir mace smash. */
    private void attack(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);
        mc.options.keyJump.setDown(false);

        if (attacked) {
            KitManager.stopMovement(mc);
            if (++afterHit >= 6) setPhase(Phase.SETUP);
            return;
        }
        if (!ensureChest(mc, me, false) && hasChestplate(me)) {
            if (phaseTicks > 30) setPhase(Phase.SETUP);
            return;
        }

        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());
        boolean ground = me.onGround();
        move(mc, me, dist > 1.5, false, 0, ground && dist > 4.0, false);

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
            if (phaseTicks > 40) setPhase(Phase.SETUP);
            return;
        }

        int maceIdx = KitManager.findBest(me, ElytraMacePrealCatchingPvp::isMace, KitManager::zeroScore);
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
            setPhase(Phase.SETUP);
            return;
        }
        if (ground) {
            if (++groundTicks > 25) setPhase(Phase.SETUP);
        } else {
            groundTicks = 0;
        }
        if (phaseTicks > 80) setPhase(Phase.SETUP);
    }

    // ------------------------------------------------------------ ELYTRA / CHESTPLATE SWAP
    private boolean ensureChest(Minecraft mc, LocalPlayer me, boolean elytra) {
        if (wearing(me, elytra)) return true;
        if (swapCd > 0) return false;

        Predicate<ItemStack> f = elytra ? ElytraMacePrealCatchingPvp::isElytraOk
                : ElytraMacePrealCatchingPvp::isChestplate;
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

    // ------------------------------------------------------------ HEAL
    private void heal(Minecraft mc, LocalPlayer me, Player target, int foodIdx) {
        double dist = Math.sqrt(me.distanceToSqr(target));
        boolean eating = me.isUsingItem() && KitManager.hasFoodComponent(me.getMainHandItem());

        if (!me.onGround()) {
            mc.options.keyUse.setDown(false);
            if (wearing(me, true)) {
                KitManager.setRotation(me, yawAway(me, target), 10f);
                tryGlide(mc, me);
            } else {
                fleeFrom(mc, me, target);
            }
            return;
        }

        if (!ensureChest(mc, me, false) && hasChestplate(me)) return;

        int pearlIdx = KitManager.findBest(me, ElytraMacePrealCatchingPvp::isPearl, KitManager::zeroScore);
        if (pearlIdx >= 0 && !pearlUsed && pearlCd == 0 && !eating
                && dist < FLEE_SAFE_DIST && me.getHealth() > PEARL_MIN_HP) {
            mc.options.keyUse.setDown(false);
            KitManager.equip(mc, pearlIdx);
            KitManager.setRotation(me, yawAway(me, target), -25f);
            mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
            me.swing(InteractionHand.MAIN_HAND);
            pearlUsed = true;
            pearlCd = PEARL_COOLDOWN;
            return;
        }
        if (dist < FLEE_SAFE_DIST) {
            mc.options.keyUse.setDown(false);
            fleeFrom(mc, me, target);
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

    private void fleeFrom(Minecraft mc, LocalPlayer me, Player target) {
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
