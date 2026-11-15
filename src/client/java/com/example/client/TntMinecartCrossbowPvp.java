package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/** TNT minecart PvP: SIRF CROSSBOW wala tarika. */
public class TntMinecartCrossbowPvp {
    // ---- settings (yahan se badal sakte ho) ----
    static final int CARTS = 2;
    static final float LOW_HP = 6.0f;
    static final float MIN_HP_TO_BLAST = 12.0f;
    static final float HEAL_DONE_HP = 14.0f;
    static final float PEARL_MIN_HP = 6.5f;
    static final double FLEE_SAFE_DIST = 8.0;
    static final double PLACE_REACH = 4.2;
    static final double SHOOT_SAFE_DIST = 5.5;
    static final double CROSSBOW_SPEED = 3.15;
    static final int BACKOFF_MAX = 15;
    static final int RUN_TICKS = 30;
    static final int AFTER_TICKS = 70;
    static final int PEARL_COOLDOWN = 20;
    static final int TOTEM_COOLDOWN = 10;
    static final double THREAT_DIST = 12.0;
    static final int SHIELD_HOLD = 40;
    static final int WEB_INTERVAL = 200;
    static final double WEB_PLACE_RANGE = 4.5;

    enum Phase { PREP, APPROACH, PLACE, BACKOFF, SHOOT, AFTER }

    private final Random rng = new Random();
    private final ArrayDeque<BlockPos> webQueue = new ArrayDeque<>();
    private Phase phase = Phase.PREP;
    private int phaseTicks = 0, placeStep = 0, cartsPlaced = 0, fireTries = 0;
    private int strafeDir = 1, strafeTimer = 0, webTimer = 0, pearlCd = 0, totemCd = 0, shieldHold = 0;
    private boolean healMode = false, pearlUsed = false, shielding = false, warned = false, hadTotem = false;
    private BlockPos railPos = null, firePos = null, lastRail = null;
    private Vec3 threatPos = Vec3.ZERO;

    // ------------------------------------------------------------ ITEM HELPERS
    static boolean isTntCart(ItemStack s) { return s.is(Items.TNT_MINECART); }
    static boolean isRailItem(ItemStack s) { return s.is(Items.RAIL); }
    static boolean isFlint(ItemStack s) { return s.is(Items.FLINT_AND_STEEL); }
    static boolean isCrossbow(ItemStack s) { return s.is(Items.CROSSBOW); }
    static boolean isArrow(ItemStack s) { return s.is(ItemTags.ARROWS); }
    static boolean isPearl(ItemStack s) { return s.is(Items.ENDER_PEARL); }
    static boolean isShield(ItemStack s) { return s.is(Items.SHIELD); }
    static boolean isTotem(ItemStack s) { return s.is(Items.TOTEM_OF_UNDYING); }
    static boolean isIdle(ItemStack s) { return KitManager.isSword(s) || s.is(Items.MACE) || isTntCart(s); }

    private static boolean has(Player p, Predicate<ItemStack> f) {
        return KitManager.findBest(p, f, KitManager::zeroScore) >= 0;
    }

    private static boolean crossbowCharged(Player p) {
        int i = KitManager.findBest(p, TntMinecartCrossbowPvp::isCrossbow, KitManager::zeroScore);
        return i >= 0 && CrossbowItem.isCharged(p.getInventory().getItem(i));
    }

    private static boolean hasSupplies(Player p) {
        return has(p, TntMinecartCrossbowPvp::isTntCart)
                && has(p, TntMinecartCrossbowPvp::isRailItem)
                && has(p, TntMinecartCrossbowPvp::isFlint)
                && has(p, TntMinecartCrossbowPvp::isCrossbow)
                && (has(p, TntMinecartCrossbowPvp::isArrow) || crossbowCharged(p));
    }

    // ------------------------------------------------------------ LIFECYCLE
    boolean matches(Minecraft mc) {
        return hasSupplies(mc.player);
    }

    void onActivate(Minecraft mc) {
        resetRound();
        healMode = false;
        pearlUsed = false;
        shielding = false;
        shieldHold = 0;
        warned = false;
        lastRail = null;
        pearlCd = 0;
        totemCd = 0;
        hadTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        webTimer = WEB_INTERVAL;
    }

    void onDeactivate(Minecraft mc) {
        resetRound();
        healMode = false;
        shielding = false;
        KitManager.releaseAll(mc);
    }

    private void resetRound() {
        phase = Phase.PREP;
        phaseTicks = 0;
        placeStep = 0;
        cartsPlaced = 0;
        fireTries = 0;
        railPos = null;
        firePos = null;
        webQueue.clear();
    }

    private void setPhase(Phase p) {
        phase = p;
        phaseTicks = 0;
        if (p == Phase.PLACE) { placeStep = 0; cartsPlaced = 0; fireTries = 0; }
    }

    // ------------------------------------------------------------ TICK
    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (pearlCd > 0) pearlCd--;
        if (totemCd > 0) totemCd--;

        boolean hasTotem = me.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        boolean popped = hadTotem && me.getOffhandItem().isEmpty();
        hadTotem = hasTotem;
        if (popped) {
            healMode = true;
            pearlUsed = false;
            resetRound();
        }
        if (!me.isUsingItem()) ensureTotem(mc, me);

        if (target == null) {
            resetRound();
            KitManager.releaseAll(mc);
            return;
        }
        webTimer++;

        // 1) enemy TNT minecart use kar raha hai -> shield up
        Vec3 threat = findThreat(mc, me, target);
        if (threat != null) {
            threatPos = threat;
            shieldHold = SHIELD_HOLD;
        }
        if (shieldHold > 0) {
            shieldHold--;
            if (raiseShield(mc, me, threatPos)) {
                shielding = true;
                resetRound();
                return;
            }
        }
        if (shielding) {
            shielding = false;
            mc.options.keyUse.setDown(false);
        }

        // 2) heal
        int foodIdx = KitManager.findBest(me, s -> KitManager.isFood(me, s), KitManager::foodScore);
        float hp = me.getHealth();
        boolean busy = phase == Phase.PLACE || phase == Phase.BACKOFF || phase == Phase.SHOOT;
        if (!healMode && foodIdx >= 0 && (hp <= LOW_HP || (!busy && hp < MIN_HP_TO_BLAST))) {
            healMode = true;
            pearlUsed = false;
            resetRound();
            mc.options.keyUse.setDown(false);
        }
        if (healMode && (hp >= HEAL_DONE_HP || foodIdx < 0)) {
            healMode = false;
            mc.options.keyUse.setDown(false);
        }
        if (healMode) {
            heal(mc, me, target);
            return;
        }

        // 3) saman khatam?
        if ((phase == Phase.PREP || phase == Phase.APPROACH) && !hasSupplies(me)) {
            if (!warned) {
                KitManager.msg(mc, "TNT cart / rail / flint / arrow / crossbow khatam");
                warned = true;
            }
            KitManager.releaseAll(mc);
            return;
        }

        switch (phase) {
            case PREP -> prep(mc, me);
            case APPROACH -> approach(mc, me, target);
            case PLACE -> place(mc, me);
            case BACKOFF -> backoff(mc, me);
            case SHOOT -> shoot(mc, me);
            case AFTER -> after(mc, me, target);
        }
    }

    private void ensureTotem(Minecraft mc, LocalPlayer me) {
        if (totemCd > 0 || me.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        int idx = KitManager.findBest(me, TntMinecartCrossbowPvp::isTotem, KitManager::zeroScore);
        if (idx < 0) return;
        int slot = idx < 9 ? 36 + idx : idx;
        mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId, slot, 40, ClickType.SWAP, me);
        totemCd = TOTEM_COOLDOWN;
    }

    // ------------------------------------------------------------ PREP (crossbow load)
    private void prep(Minecraft mc, LocalPlayer me) {
        int xIdx = KitManager.findBest(me, TntMinecartCrossbowPvp::isCrossbow, KitManager::zeroScore);
        ItemStack main = me.getMainHandItem();
        if (!isCrossbow(main)) {
            mc.options.keyUse.setDown(false);
            KitManager.equip(mc, xIdx);
            return;
        }
        if (CrossbowItem.isCharged(main)) {
            mc.options.keyUse.setDown(false);
            setPhase(Phase.APPROACH);
            return;
        }
        KitManager.stopMovement(mc);
        if (!me.isUsingItem()) mc.options.keyUse.setDown(true);
        else if (me.getTicksUsingItem() >= 30) mc.options.keyUse.setDown(false);
    }

    // ------------------------------------------------------------ APPROACH
    private void approach(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);
        if (!crossbowCharged(me)) {
            setPhase(Phase.PREP);
            return;
        }
        if (processWebs(mc, me, target)) return;

        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());
        KitManager.setRotation(me, yawTo(me, target), 0f);

        if (findSpot(mc, me, target)) {
            KitManager.stopMovement(mc);
            setPhase(Phase.PLACE);
            return;
        }
        move(mc, me, dist > 2.5, dist < 1.2, strafeDir(), dist > 6.0, false);
    }

    private boolean findSpot(Minecraft mc, LocalPlayer me, Player target) {
        Level lvl = mc.level;
        BlockPos base = target.blockPosition();
        Vec3 eye = me.getEyePosition();
        BlockPos bestC = null, bestF = null;
        double bestD = Double.MAX_VALUE;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos c = base.offset(dx, dy, dz);
                    if (!canPlaceOn(lvl, c)) continue;
                    Vec3 cc = Vec3.atCenterOf(c);
                    if (eye.distanceTo(cc) > PLACE_REACH) continue;

                    Direction toMe = Direction.getNearest(me.getX() - cc.x, 0, me.getZ() - cc.z);
                    BlockPos f = c.relative(toMe);
                    if (!canPlaceOn(lvl, f)) continue;
                    Vec3 fc = Vec3.atCenterOf(f);
                    if (eye.distanceTo(fc) > PLACE_REACH) continue;
                    if (me.position().distanceTo(fc) < 1.8) continue;

                    double d = c.distSqr(base);
                    if (d < bestD) {
                        bestD = d;
                        bestC = c;
                        bestF = f;
                    }
                }
            }
        }
        if (bestC == null) return false;
        railPos = bestC;
        firePos = bestF;
        lastRail = bestC;
        return true;
    }

    private static boolean canPlaceOn(Level lvl, BlockPos p) {
        BlockState s = lvl.getBlockState(p);
        if (!(s.isAir() || s.canBeReplaced()) || !s.getFluidState().isEmpty()) return false;
        BlockPos below = p.below();
        return lvl.getBlockState(below).isFaceSturdy(lvl, below, Direction.UP);
    }

    // ------------------------------------------------------------ PLACE (rail -> cart(s) -> fire)
    private void place(Minecraft mc, LocalPlayer me) {
        phaseTicks++;
        KitManager.stopMovement(mc);
        mc.options.keyUse.setDown(false);
        if (railPos == null || firePos == null || phaseTicks > 40) {
            resetRound();
            return;
        }
        Level lvl = mc.level;

        if (placeStep == 0) {
            if (!lvl.getBlockState(railPos).is(BlockTags.RAILS)) {
                int i = KitManager.findBest(me, TntMinecartCrossbowPvp::isRailItem, KitManager::zeroScore);
                if (i < 0) { resetRound(); return; }
                KitManager.equip(mc, i);
                clickBlock(mc, me, railPos.below(), Direction.UP);
                return;
            }
            placeStep = 1;
        }
        if (placeStep == 1) {
            int i = KitManager.findBest(me, TntMinecartCrossbowPvp::isTntCart, KitManager::zeroScore);
            if (i < 0) {
                if (cartsPlaced == 0) { resetRound(); return; }
                placeStep = 2;
            } else {
                KitManager.equip(mc, i);
                clickBlock(mc, me, railPos, Direction.UP);
                cartsPlaced++;
                if (cartsPlaced >= CARTS) placeStep = 2;
                return;
            }
        }
        if (placeStep == 2) {
            int i = KitManager.findBest(me, TntMinecartCrossbowPvp::isFlint, KitManager::zeroScore);
            if (i < 0) { resetRound(); return; }
            KitManager.equip(mc, i);
            clickBlock(mc, me, firePos.below(), Direction.UP);
            placeStep = 3;
            return;
        }
        setPhase(Phase.BACKOFF);
    }

    private void clickBlock(Minecraft mc, LocalPlayer me, BlockPos support, Direction face) {
        Vec3 hit = Vec3.atCenterOf(support).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
        me.swing(InteractionHand.MAIN_HAND);
    }

    // ------------------------------------------------------------ BACKOFF
    private void backoff(Minecraft mc, LocalPlayer me) {
        phaseTicks++;
        mc.options.keyUse.setDown(false);
        if (railPos == null || firePos == null) {
            resetRound();
            return;
        }
        if (!isCrossbow(me.getMainHandItem())) {
            int xIdx = KitManager.findBest(me, TntMinecartCrossbowPvp::isCrossbow, KitManager::zeroScore);
            if (xIdx < 0) { resetRound(); return; }
            KitManager.equip(mc, xIdx);
        }
        Vec3 c = Vec3.atCenterOf(railPos);
        double dx = c.x - me.getX(), dz = c.z - me.getZ();
        KitManager.setRotation(me, (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0), 0f);

        double d = Math.sqrt(dx * dx + dz * dz);
        if (d >= SHOOT_SAFE_DIST || phaseTicks > BACKOFF_MAX) {
            KitManager.stopMovement(mc);
            setPhase(Phase.SHOOT);
            return;
        }
        move(mc, me, false, true, 0, false, false);
    }

    // ------------------------------------------------------------ SHOOT (crossbow)
    private void shoot(Minecraft mc, LocalPlayer me) {
        phaseTicks++;
        KitManager.stopMovement(mc);
        mc.options.keyUse.setDown(false);
        if (railPos == null || firePos == null || phaseTicks > 30) {
            resetRound();
            return;
        }
        int xIdx = KitManager.findBest(me, TntMinecartCrossbowPvp::isCrossbow, KitManager::zeroScore);
        if (xIdx < 0) { resetRound(); return; }

        if (!mc.level.getBlockState(firePos).is(BlockTags.FIRE) && fireTries < 3) {
            int f = KitManager.findBest(me, TntMinecartCrossbowPvp::isFlint, KitManager::zeroScore);
            if (f >= 0 && me.getEyePosition().distanceTo(Vec3.atCenterOf(firePos)) <= PLACE_REACH + 1.0) {
                fireTries++;
                KitManager.equip(mc, f);
                clickBlock(mc, me, firePos.below(), Direction.UP);
                return;
            }
        }
        if (!isCrossbow(me.getMainHandItem())) {
            KitManager.equip(mc, xIdx);
            return;
        }
        if (!CrossbowItem.isCharged(me.getMainHandItem())) {
            setPhase(Phase.PREP);
            return;
        }
        Entity cart = findCart(mc, railPos);
        if (cart == null && phaseTicks < 10) return;

        Vec3 aim = cart != null ? cart.getBoundingBox().getCenter() : railAim();
        aimShot(me, aim, CROSSBOW_SPEED);
        mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
        me.swing(InteractionHand.MAIN_HAND);
        setPhase(Phase.AFTER);
    }

    // ------------------------------------------------------------ AFTER
    private void after(Minecraft mc, LocalPlayer me, Player target) {
        phaseTicks++;
        mc.options.keyUse.setDown(false);
        Vec3 from = railPos != null ? Vec3.atCenterOf(railPos) : target.position();
        if (phaseTicks < RUN_TICKS) fleeFrom(mc, me, from);
        else KitManager.stopMovement(mc);
        if (phaseTicks >= AFTER_TICKS) resetRound();
    }

    // ------------------------------------------------------------ AIM / PROJECTILE
    private Vec3 railAim() {
        return new Vec3(railPos.getX() + 0.5, railPos.getY() + 0.4, railPos.getZ() + 0.5);
    }

    private Entity findCart(Minecraft mc, BlockPos rail) {
        AABB box = new AABB(rail).inflate(0.8);
        List<Entity> l = mc.level.getEntities((Entity) null, box, e -> e.getType() == EntityType.TNT_MINECART);
        return l.isEmpty() ? null : l.get(0);
    }

    private void aimShot(LocalPlayer me, Vec3 aim, double speed) {
        Vec3 from = me.getEyePosition().add(0, -0.1, 0);
        double dx = aim.x - from.x, dz = aim.z - from.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        double hd = Math.sqrt(dx * dx + dz * dz);
        double elev = solveElevation(hd, aim.y - from.y, speed);
        KitManager.setRotation(me, yaw, (float) -elev);
    }

    static double solveElevation(double hd, double dy, double speed) {
        double bestE = Math.toDegrees(Math.atan2(dy, hd)), bestErr = 1e9;
        for (double e = -60; e <= 75; e += 0.25) {
            double r = Math.toRadians(e);
            double vx = speed * Math.cos(r), vy = speed * Math.sin(r);
            double x = 0, y = 0;
            for (int t = 0; t < 160; t++) {
                double px = x, py = y;
                x += vx;
                y += vy;
                vx *= 0.99;
                vy *= 0.99;
                vy -= 0.05;
                if (x >= hd) {
                    double fr = (hd - px) / (x - px);
                    double err = Math.abs(py + (y - py) * fr - dy);
                    if (err < 0.25) return e;
                    if (err < bestErr) { bestErr = err; bestE = e; }
                    break;
                }
                if (vx < 0.02 || y < dy - 60) break;
            }
        }
        return bestE;
  }
      // ------------------------------------------------------------ ENEMY TNT THREAT + SHIELD
    private Vec3 findThreat(Minecraft mc, LocalPlayer me, Player target) {
        AABB box = me.getBoundingBox().inflate(THREAT_DIST);
        List<Entity> carts = mc.level.getEntities((Entity) null, box, e -> e.getType() == EntityType.TNT_MINECART);
        for (Entity c : carts) {
            if (lastRail != null && c.position().distanceToSqr(Vec3.atCenterOf(lastRail)) < 9.0) continue;
            return c.position();
        }
        if (me.distanceTo(target) <= THREAT_DIST
                && (isTntCart(target.getMainHandItem()) || isTntCart(target.getOffhandItem()))) {
            return target.position();
        }
        return null;
    }

    private boolean raiseShield(Minecraft mc, LocalPlayer me, Vec3 face) {
        ItemStack main = me.getMainHandItem();
        if (!isShield(main)) {
            int sIdx = KitManager.findBest(me, TntMinecartCrossbowPvp::isShield, KitManager::zeroScore);
            if (sIdx >= 0) {
                KitManager.equip(mc, sIdx);
            } else if (isShield(me.getOffhandItem())) {
                if (!isIdle(main)) {
                    int idle = KitManager.findBest(me, TntMinecartCrossbowPvp::isIdle, KitManager::zeroScore);
                    if (idle < 0) return false;
                    KitManager.equip(mc, idle);
                }
            } else {
                return false;
            }
        }
        KitManager.stopMovement(mc);
        double dx = face.x - me.getX(), dz = face.z - me.getZ();
        KitManager.setRotation(me, (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0), 0f);
        mc.options.keyUse.setDown(true);
        return true;
    }

    // ------------------------------------------------------------ HEAL
    private void heal(Minecraft mc, LocalPlayer me, Player target) {
        double dist = Math.sqrt(me.distanceToSqr(target));
        boolean eating = me.isUsingItem() && KitManager.hasFoodComponent(me.getMainHandItem());
        int foodIdx = KitManager.findBest(me, s -> KitManager.isFood(me, s), KitManager::foodScore);

        int pearlIdx = KitManager.findBest(me, TntMinecartCrossbowPvp::isPearl, KitManager::zeroScore);
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

    // ------------------------------------------------------------ COBWEB (har 10 sec, 3 web)
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
