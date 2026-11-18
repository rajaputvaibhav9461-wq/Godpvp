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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * TNT minecart PvP (assist):
 * 1) BOW: aap arrow chhodo -> landing spot par rail + TNT minecart lag jaati hai.
 * 2) RAIL: aap rail lagao -> mod cart + fire lagata hai aur crossbow se phodta hai.
 */
public class TntMinecartPvp {
    // ---- settings (yahan se badal sakte ho) ----
    static final int CARTS = 2;
    static final double PLACE_REACH = 4.4;
    static final double MIN_SPOT_DIST = 2.0;
    static final double SHOOT_SAFE_DIST = 5.5;
    static final double CROSSBOW_SPEED = 3.15;
    static final int COOLDOWN = 15;
    static final int AFTER_COOLDOWN = 40;
    static final int MAX_SIM_TICKS = 40;
    static final int BACKOFF_MAX = 15;
    static final int RUN_TICKS = 30;

    enum Phase { IDLE, LOAD, PLACE, BACKOFF, SHOOT, AFTER }

    private Phase phase = Phase.IDLE;
    private int pt = 0, cd = 0, drawTicks = 0, placeStep = 0, cartsPlaced = 0, fireTries = 0;
    private BlockPos rail = null, fire = null;
    private Set<Long> known = new HashSet<>();
    private final Map<Long, Integer> ignore = new HashMap<>();
    private boolean primed = false;

    static boolean isBow(ItemStack s) { return s.is(Items.BOW); }
    static boolean isCrossbow(ItemStack s) { return s.is(Items.CROSSBOW); }
    static boolean isArrow(ItemStack s) { return s.is(ItemTags.ARROWS); }
    static boolean isRailItem(ItemStack s) { return s.is(Items.RAIL); }
    static boolean isTntCart(ItemStack s) { return s.is(Items.TNT_MINECART); }
    static boolean isFlint(ItemStack s) { return s.is(Items.FLINT_AND_STEEL); }

    private static boolean has(Player p, Predicate<ItemStack> f) {
        return KitManager.findBest(p, f, KitManager::zeroScore) >= 0;
    }

    private static boolean crossbowCharged(Player p) {
        int i = KitManager.findBest(p, TntMinecartPvp::isCrossbow, KitManager::zeroScore);
        return i >= 0 && CrossbowItem.isCharged(p.getInventory().getItem(i));
    }

    private static boolean bowKit(Player p) {
        return has(p, TntMinecartPvp::isBow) && has(p, TntMinecartPvp::isArrow)
                && has(p, TntMinecartPvp::isRailItem) && has(p, TntMinecartPvp::isTntCart);
    }

    private static boolean crossbowKit(Player p) {
        return has(p, TntMinecartPvp::isCrossbow) && has(p, TntMinecartPvp::isFlint)
                && has(p, TntMinecartPvp::isTntCart)
                && (has(p, TntMinecartPvp::isArrow) || crossbowCharged(p));
    }

    // ------------------------------------------------------------ LIFECYCLE
    boolean matches(Minecraft mc) {
        return bowKit(mc.player) || crossbowKit(mc.player);
    }

    void onActivate(Minecraft mc) {
        phase = Phase.IDLE;
        pt = 0;
        cd = 0;
        drawTicks = 0;
        rail = null;
        fire = null;
        known = new HashSet<>();
        ignore.clear();
        primed = false;
    }

    void onDeactivate(Minecraft mc) {
        phase = Phase.IDLE;
        drawTicks = 0;
        KitManager.releaseAll(mc);
    }

    // ------------------------------------------------------------ TICK
    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (cd > 0) cd--;

        BlockPos newRail = scanRails(mc, me);

        if (phase != Phase.IDLE) {
            runRail(mc, me);
            return;
        }

        // aapne rail lagayi -> crossbow wala tarika
        if (newRail != null && cd == 0 && crossbowKit(me)) {
            rail = newRail;
            fire = null;
            drawTicks = 0;
            startPhase(Phase.LOAD);
            return;
        }

        // bow wala tarika (aap khud aim aur shoot karte ho)
        if (!bowKit(me)) return;
        ItemStack main = me.getMainHandItem();
        if (me.isUsingItem() && isBow(main)) {
            drawTicks = me.getTicksUsingItem();
            return;
        }
        if (drawTicks > 0 && isBow(main) && cd == 0) onRelease(mc, me, drawTicks + 1);
        drawTicks = 0;
    }

    private void startPhase(Phase p) {
        phase = p;
        pt = 0;
        if (p == Phase.PLACE) {
            placeStep = 0;
            cartsPlaced = 0;
            fireTries = 0;
        }
    }

    private void abort(Minecraft mc) {
        phase = Phase.IDLE;
        rail = null;
        fire = null;
        cd = COOLDOWN;
        KitManager.releaseAll(mc);
    }

    // ------------------------------------------------------------ RAIL DETECT
    /** naya rail (jo aapne lagaya) dhoondta hai. Mod ki apni rail ignore hoti hai. */
    private BlockPos scanRails(Minecraft mc, LocalPlayer me) {
        Level lvl = mc.level;
        BlockPos c = me.blockPosition();
        Vec3 eye = me.getEyePosition();
        Set<Long> now = new HashSet<>();
        BlockPos found = null;
        double bd = 1e9;

        ignore.replaceAll((k, v) -> v - 1);
        ignore.values().removeIf(v -> v <= 0);

        for (int dx = -5; dx <= 5; dx++)
            for (int dy = -3; dy <= 3; dy++)
                for (int dz = -5; dz <= 5; dz++) {
                    BlockPos p = c.offset(dx, dy, dz);
                    if (!lvl.getBlockState(p).is(BlockTags.RAILS)) continue;
                    long k = p.asLong();
                    now.add(k);
                    if (!primed || known.contains(k) || ignore.containsKey(k)) continue;
                    double d = eye.distanceTo(Vec3.atCenterOf(p));
                    if (d > PLACE_REACH || findCart(mc, p) != null) continue;
                    if (d < bd) {
                        bd = d;
                        found = p;
                    }
                }
        known = now;
        primed = true;
        return found;
    }

    private Entity findCart(Minecraft mc, BlockPos r) {
        AABB box = new AABB(r).inflate(0.8);
        List<Entity> l = mc.level.getEntities((Entity) null, box, e -> e.getType() == EntityType.TNT_MINECART);
        return l.isEmpty() ? null : l.get(0);
    }

    // ------------------------------------------------------------ BOW: arrow raasta + place
    private void onRelease(Minecraft mc, LocalPlayer me, int ticks) {
        double t = ticks / 20.0;
        double f = (t * t + t * 2.0) / 3.0;
        if (f < 0.1) return;
        if (f > 1.0) f = 1.0;
        double speed = f * 3.0;

        Vec3 pos = me.getEyePosition().add(0, -0.1, 0);
        Vec3 vel = me.getLookAngle().scale(speed);
        Level lvl = mc.level;

        BlockHitResult hit = null;
        for (int i = 0; i < MAX_SIM_TICKS; i++) {
            Vec3 next = pos.add(vel);
            BlockHitResult r = lvl.clip(new ClipContext(pos, next,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, me));
            if (r.getType() == HitResult.Type.BLOCK) {
                hit = r;
                break;
            }
            pos = next;
            vel = vel.scale(0.99).add(0, -0.05, 0);
        }
        if (hit == null) return;

        Direction face = hit.getDirection();
        if (face == Direction.DOWN) return;
        BlockPos spot = hit.getBlockPos().relative(face);
        if (!canPlaceOn(lvl, spot)) return;
        double d = me.getEyePosition().distanceTo(Vec3.atCenterOf(spot));
        if (d > PLACE_REACH || d < MIN_SPOT_DIST) return;

        placeBowCombo(mc, me, spot);
        cd = COOLDOWN;
    }

    private void placeBowCombo(Minecraft mc, LocalPlayer me, BlockPos spot) {
        Inventory inv = me.getInventory();
        int saved = inv.selected;

        int r = KitManager.findBest(me, TntMinecartPvp::isRailItem, KitManager::zeroScore);
        if (r < 0) return;
        ignore.put(spot.asLong(), 100); // mod ki rail: rail-tarika trigger na ho
        KitManager.equip(mc, r);
        click(mc, me, spot.below(), Direction.UP);

        for (int i = 0; i < CARTS; i++) {
            int cart = KitManager.findBest(me, TntMinecartPvp::isTntCart, KitManager::zeroScore);
            if (cart < 0) break;
            KitManager.equip(mc, cart);
            click(mc, me, spot, Direction.UP);
        }
        inv.selected = saved;
    }

    static boolean canPlaceOn(Level lvl, BlockPos p) {
        BlockState s = lvl.getBlockState(p);
        if (!(s.isAir() || s.canBeReplaced()) || !s.getFluidState().isEmpty()) return false;
        BlockPos below = p.below();
        return lvl.getBlockState(below).isFaceSturdy(lvl, below, Direction.UP);
    }

    private void click(Minecraft mc, LocalPlayer me, BlockPos support, Direction face) {
        Vec3 hit = Vec3.atCenterOf(support).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
        me.swing(InteractionHand.MAIN_HAND);
}
      // ------------------------------------------------------------ RAIL FLOW
    private void runRail(Minecraft mc, LocalPlayer me) {
        pt++;
        if (rail == null || !mc.level.getBlockState(rail).is(BlockTags.RAILS) && phase != Phase.AFTER) {
            abort(mc);
            return;
        }
        switch (phase) {
            case LOAD -> load(mc, me);
            case PLACE -> place(mc, me);
            case BACKOFF -> backoff(mc, me);
            case SHOOT -> shoot(mc, me);
            case AFTER -> after(mc, me);
            default -> { }
        }
    }

    /** crossbow load (khada rehke). */
    private void load(Minecraft mc, LocalPlayer me) {
        KitManager.stopMovement(mc);
        if (pt > 80) { abort(mc); return; }
        int x = KitManager.findBest(me, TntMinecartPvp::isCrossbow, KitManager::zeroScore);
        if (x < 0) { abort(mc); return; }
        ItemStack main = me.getMainHandItem();
        if (!isCrossbow(main)) {
            mc.options.keyUse.setDown(false);
            KitManager.equip(mc, x);
            return;
        }
        if (CrossbowItem.isCharged(main)) {
            mc.options.keyUse.setDown(false);
            startPhase(Phase.PLACE);
            return;
        }
        if (!me.isUsingItem()) mc.options.keyUse.setDown(true);
        else if (me.getTicksUsingItem() >= 30) mc.options.keyUse.setDown(false);
    }

    /** rail par cart(s), phir rail ke aage (meri taraf) fire. */
    private void place(Minecraft mc, LocalPlayer me) {
        KitManager.stopMovement(mc);
        mc.options.keyUse.setDown(false);
        if (pt > 40) { abort(mc); return; }

        if (placeStep == 0) {
            int c = KitManager.findBest(me, TntMinecartPvp::isTntCart, KitManager::zeroScore);
            if (c < 0) {
                if (cartsPlaced == 0) { abort(mc); return; }
                placeStep = 1;
            } else {
                KitManager.equip(mc, c);
                click(mc, me, rail, Direction.UP);
                cartsPlaced++;
                if (cartsPlaced >= CARTS) placeStep = 1;
                return;
            }
        }
        if (placeStep == 1) {
            fire = pickFire(mc.level, me, rail);
            int f = KitManager.findBest(me, TntMinecartPvp::isFlint, KitManager::zeroScore);
            if (fire == null || f < 0) { abort(mc); return; }
            KitManager.equip(mc, f);
            click(mc, me, fire.below(), Direction.UP);
            placeStep = 2;
            return;
        }
        startPhase(Phase.BACKOFF);
    }

    /** fire: rail ke bagal me, meri taraf (arrow isi se guzrega). */
    private BlockPos pickFire(Level lvl, LocalPlayer me, BlockPos r) {
        Direction toMe = Direction.getNearest(me.getX() - (r.getX() + 0.5), 0, me.getZ() - (r.getZ() + 0.5));
        Direction[] order = { toMe, toMe.getClockWise(), toMe.getCounterClockWise() };
        for (Direction d : order) {
            BlockPos f = r.relative(d);
            if (!canPlaceOn(lvl, f)) continue;
            Vec3 fc = Vec3.atCenterOf(f);
            if (me.position().distanceTo(fc) < 1.5) continue;
            if (me.getEyePosition().distanceTo(fc) > PLACE_REACH) continue;
            return f;
        }
        return null;
    }

    /** cart ki taraf dekhte hue S dabake peeche. */
    private void backoff(Minecraft mc, LocalPlayer me) {
        mc.options.keyUse.setDown(false);
        if (!isCrossbow(me.getMainHandItem())) {
            int x = KitManager.findBest(me, TntMinecartPvp::isCrossbow, KitManager::zeroScore);
            if (x < 0) { abort(mc); return; }
            KitManager.equip(mc, x);
        }
        Vec3 c = Vec3.atCenterOf(rail);
        double dx = c.x - me.getX(), dz = c.z - me.getZ();
        KitManager.setRotation(me, (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0), 0f);
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d >= SHOOT_SAFE_DIST || pt > BACKOFF_MAX) {
            KitManager.stopMovement(mc);
            startPhase(Phase.SHOOT);
            return;
        }
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(true);
        mc.options.keySprint.setDown(false);
    }

    /** fire bujhi ho to dobara, phir crossbow: arrow fire se guzar ke cart ko lagta hai. */
    private void shoot(Minecraft mc, LocalPlayer me) {
        KitManager.stopMovement(mc);
        mc.options.keyUse.setDown(false);
        if (pt > 30 || fire == null) { abort(mc); return; }

        int x = KitManager.findBest(me, TntMinecartPvp::isCrossbow, KitManager::zeroScore);
        if (x < 0) { abort(mc); return; }

        if (!mc.level.getBlockState(fire).is(BlockTags.FIRE) && fireTries < 3) {
            int f = KitManager.findBest(me, TntMinecartPvp::isFlint, KitManager::zeroScore);
            if (f >= 0 && me.getEyePosition().distanceTo(Vec3.atCenterOf(fire)) <= PLACE_REACH + 1.0) {
                fireTries++;
                KitManager.equip(mc, f);
                click(mc, me, fire.below(), Direction.UP);
                return;
            }
        }
        if (!isCrossbow(me.getMainHandItem())) {
            KitManager.equip(mc, x);
            return;
        }
        if (!CrossbowItem.isCharged(me.getMainHandItem())) { abort(mc); return; }

        Entity cart = findCart(mc, rail);
        if (cart == null && pt < 10) return;
        Vec3 aim = cart != null ? cart.getBoundingBox().getCenter()
                : new Vec3(rail.getX() + 0.5, rail.getY() + 0.4, rail.getZ() + 0.5);
        aimShot(me, aim, CROSSBOW_SPEED);
        mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
        me.swing(InteractionHand.MAIN_HAND);
        startPhase(Phase.AFTER);
    }

    /** blast se door bhaago. */
    private void after(Minecraft mc, LocalPlayer me) {
        mc.options.keyUse.setDown(false);
        if (pt < RUN_TICKS && rail != null) {
            Vec3 c = Vec3.atCenterOf(rail);
            double ax = me.getX() - c.x, az = me.getZ() - c.z;
            if (ax * ax + az * az < 1e-4) ax = 1;
            KitManager.setRotation(me, (float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0), 0f);
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(true);
            mc.options.keyDown.setDown(false);
            return;
        }
        phase = Phase.IDLE;
        rail = null;
        fire = null;
        cd = AFTER_COOLDOWN;
        KitManager.releaseAll(mc);
    }

    // ------------------------------------------------------------ AIM
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
                                    }
