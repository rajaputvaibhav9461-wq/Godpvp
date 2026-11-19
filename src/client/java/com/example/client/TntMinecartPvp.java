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
 * TNT minecart assist.
 * BOW: aap arrow chhodo -> mod landing spot pe aim karke rail + TNT minecart lagata hai.
 * CROSSBOW: aap rail lagao -> mod cart, fire (cart ke aage) lagata hai aur crossbow chalata hai.
 */
public class TntMinecartPvp {
    // ---- settings (yahan se badal sakte ho) ----
    static final int CARTS = 2;
    static final double PLACE_REACH = 4.4;
    static final double MIN_SPOT_DIST = 2.0;
    static final double CROSSBOW_SPEED = 3.15;
    static final int COOLDOWN = 15;
    static final int AFTER_COOLDOWN = 40;
    static final int MAX_SIM_TICKS = 40;

    enum State { IDLE, B_PLACE, C_CARTS, C_LOAD, C_FIRE, C_SHOOT, END }

    private State state = State.IDLE;
    private int pt = 0, cd = 0, drawTicks = 0, step = 0, cartsPlaced = 0, fireTries = 0, savedSlot = 0;
    private BlockPos rail = null, spot = null, firePos = null;
    private Vec3 lastEye = null, lastLook = null;
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
    /** bow ya crossbow me se koi ek bhi ho + TNT minecart ho to Z chalega. */
    boolean matches(Minecraft mc) {
        Player p = mc.player;
        return has(p, TntMinecartPvp::isTntCart)
                && (has(p, TntMinecartPvp::isBow) || has(p, TntMinecartPvp::isCrossbow));
    }

    void onActivate(Minecraft mc) {
        state = State.IDLE;
        pt = 0;
        cd = 0;
        drawTicks = 0;
        rail = null;
        spot = null;
        firePos = null;
        known = new HashSet<>();
        ignore.clear();
        primed = false;
    }

    void onDeactivate(Minecraft mc) {
        if (state != State.IDLE) mc.player.getInventory().selected = savedSlot;
        state = State.IDLE;
        drawTicks = 0;
        mc.options.keyUse.setDown(false);
    }

    // ------------------------------------------------------------ TICK
    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (cd > 0) cd--;

        BlockPos newRail = scanRails(mc, me);

        if (state != State.IDLE) {
            run(mc, me);
            return;
        }

        // aapne rail lagayi -> crossbow wala tarika
        if (newRail != null && cd == 0 && crossbowKit(me)) {
            rail = newRail;
            savedSlot = me.getInventory().selected;
            startState(State.C_CARTS);
            return;
        }

        // bow wala tarika: aap arrow chhodo
        if (!bowKit(me)) {
            drawTicks = 0;
            return;
        }
        ItemStack main = me.getMainHandItem();
        if (me.isUsingItem() && isBow(main)) {
            drawTicks = me.getTicksUsingItem();
            lastEye = me.getEyePosition();
            lastLook = me.getLookAngle();
            return;
        }
        if (drawTicks > 0 && isBow(main) && cd == 0) onRelease(mc, me, drawTicks + 1);
        drawTicks = 0;
    }

    private void startState(State s) {
        state = s;
        pt = 0;
        if (s == State.B_PLACE || s == State.C_CARTS) {
            step = 0;
            cartsPlaced = 0;
        }
        if (s == State.C_FIRE) fireTries = 0;
    }

    /** slot wapas, control wapas. */
    private void finish(Minecraft mc, LocalPlayer me, int cooldown) {
        me.getInventory().selected = savedSlot;
        mc.options.keyUse.setDown(false);
        state = State.IDLE;
        rail = null;
        spot = null;
        firePos = null;
        cd = cooldown;
    }

    private void run(Minecraft mc, LocalPlayer me) {
        pt++;
        switch (state) {
            case B_PLACE -> bowPlace(mc, me);
            case C_CARTS -> cCarts(mc, me);
            case C_LOAD -> cLoad(mc, me);
            case C_FIRE -> cFire(mc, me);
            case C_SHOOT -> cShoot(mc, me);
            case END -> {
                if (pt >= 2) finish(mc, me, AFTER_COOLDOWN);
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------ RAIL DETECT (aapki lagayi rail)
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

    // ------------------------------------------------------------ BOW: arrow raasta
    private void onRelease(Minecraft mc, LocalPlayer me, int ticks) {
        if (lastEye == null || lastLook == null) return;
        double t = ticks / 20.0;
        double f = (t * t + t * 2.0) / 3.0;
        if (f < 0.1) return;
        if (f > 1.0) f = 1.0;
        double speed = f * 3.0;

        Vec3 pos = lastEye.add(0, -0.1, 0);
        Vec3 vel = lastLook.scale(speed);
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
        BlockPos s = hit.getBlockPos().relative(face);
        if (!canPlaceOn(lvl, s)) return;
        double d = me.getEyePosition().distanceTo(Vec3.atCenterOf(s));
        if (d > PLACE_REACH || d < MIN_SPOT_DIST) return;

        spot = s;
        savedSlot = me.getInventory().selected;
        ignore.put(s.asLong(), 100); // mod ki rail: crossbow-tarika trigger na ho
        startState(State.B_PLACE);
    }

    static boolean canPlaceOn(Level lvl, BlockPos p) {
        BlockState s = lvl.getBlockState(p);
        if (!(s.isAir() || s.canBeReplaced()) || !s.getFluidState().isEmpty()) return false;
        BlockPos below = p.below();
        return lvl.getBlockState(below).isFaceSturdy(lvl, below, Direction.UP);
    }

    /** landing spot ki taraf dekho, rail, phir cart(s), phir slot wapas. */
    private void bowPlace(Minecraft mc, LocalPlayer me) {
        if (spot == null || pt > 20) { finish(mc, me, COOLDOWN); return; }
        lookAt(me, Vec3.atCenterOf(spot));
        Level lvl = mc.level;

        if (step == 0) {
            if (!lvl.getBlockState(spot).is(BlockTags.RAILS)) {
                int r = KitManager.findBest(me, TntMinecartPvp::isRailItem, KitManager::zeroScore);
                if (r < 0) { finish(mc, me, COOLDOWN); return; }
                KitManager.equip(mc, r);
                click(mc, me, spot.below(), Direction.UP);
            }
            step = 1;
            return;
        }

        if (!lvl.getBlockState(spot).is(BlockTags.RAILS)) {
            if (pt > 8) finish(mc, me, COOLDOWN);
            return;
        }
        int c = KitManager.findBest(me, TntMinecartPvp::isTntCart, KitManager::zeroScore);
        if (c < 0 || cartsPlaced >= CARTS) {
            finish(mc, me, COOLDOWN);
            return;
        }
        KitManager.equip(mc, c);
        click(mc, me, spot, Direction.UP);
        cartsPlaced++;
        if (cartsPlaced >= CARTS) finish(mc, me, COOLDOWN);
            }
        // ------------------------------------------------------------ CROSSBOW FLOW
    /** 1) aapki rail par cart(s). */
    private void cCarts(Minecraft mc, LocalPlayer me) {
        if (rail == null || pt > 40 || (pt > 3 && !mc.level.getBlockState(rail).is(BlockTags.RAILS))) {
            finish(mc, me, COOLDOWN);
            return;
        }
        lookAt(me, Vec3.atCenterOf(rail));
        int c = KitManager.findBest(me, TntMinecartPvp::isTntCart, KitManager::zeroScore);
        if (c < 0 || cartsPlaced >= CARTS) {
            if (cartsPlaced == 0) { finish(mc, me, COOLDOWN); return; }
            startState(State.C_LOAD);
            return;
        }
        KitManager.equip(mc, c);
        click(mc, me, rail, Direction.UP);
        cartsPlaced++;
        if (cartsPlaced >= CARTS) startState(State.C_LOAD);
    }

    /** 2) crossbow load. */
    private void cLoad(Minecraft mc, LocalPlayer me) {
        if (pt > 100) { finish(mc, me, COOLDOWN); return; }
        int x = KitManager.findBest(me, TntMinecartPvp::isCrossbow, KitManager::zeroScore);
        if (x < 0) { finish(mc, me, COOLDOWN); return; }
        ItemStack main = me.getMainHandItem();
        if (!isCrossbow(main)) {
            mc.options.keyUse.setDown(false);
            KitManager.equip(mc, x);
            return;
        }
        if (CrossbowItem.isCharged(main)) {
            mc.options.keyUse.setDown(false);
            startState(State.C_FIRE);
            return;
        }
        if (!me.isUsingItem()) mc.options.keyUse.setDown(true);
        else if (me.getTicksUsingItem() >= 30) mc.options.keyUse.setDown(false);
    }

    /** 3) fire: cart aur aapke beech ki seedhi line par, cart ke bilkul aage. */
    private void cFire(Minecraft mc, LocalPlayer me) {
        mc.options.keyUse.setDown(false);
        if (rail == null || pt > 30) { finish(mc, me, COOLDOWN); return; }
        if (firePos == null) {
            firePos = pickFire(mc.level, me, rail);
            if (firePos == null) { finish(mc, me, COOLDOWN); return; }
        }
        int f = KitManager.findBest(me, TntMinecartPvp::isFlint, KitManager::zeroScore);
        if (f < 0) { finish(mc, me, COOLDOWN); return; }
        lookAt(me, Vec3.atCenterOf(firePos));
        KitManager.equip(mc, f);
        click(mc, me, firePos.below(), Direction.UP);
        startState(State.C_SHOOT);
    }

    /** aapki aankh se cart ki seedhi line me, cart se 1 block pehle wala block. */
    private BlockPos pickFire(Level lvl, LocalPlayer me, BlockPos r) {
        Vec3 c = Vec3.atCenterOf(r);
        Vec3 eye = me.getEyePosition();
        double dx = eye.x - c.x, dz = eye.z - c.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-3) return null;
        double ux = dx / len, uz = dz / len;
        for (double s : new double[] { 1.0, 1.3, 1.6 }) {
            BlockPos fp = BlockPos.containing(c.x + ux * s, r.getY(), c.z + uz * s);
            if (fp.equals(r)) continue;
            if (!canPlaceOn(lvl, fp)) continue;
            if (eye.distanceTo(Vec3.atCenterOf(fp)) > PLACE_REACH) continue;
            return fp;
        }
        return null;
    }

    /** 4) fire jali ho to cart ki taraf crossbow. Arrow fire se guzar ke cart ko lagta hai. */
    private void cShoot(Minecraft mc, LocalPlayer me) {
        mc.options.keyUse.setDown(false);
        if (pt > 30 || firePos == null || rail == null) { finish(mc, me, COOLDOWN); return; }

        int x = KitManager.findBest(me, TntMinecartPvp::isCrossbow, KitManager::zeroScore);
        if (x < 0) { finish(mc, me, COOLDOWN); return; }

        // fire bujh gayi ho to dobara
        if (!mc.level.getBlockState(firePos).is(BlockTags.FIRE) && fireTries < 3) {
            int f = KitManager.findBest(me, TntMinecartPvp::isFlint, KitManager::zeroScore);
            if (f >= 0) {
                fireTries++;
                lookAt(me, Vec3.atCenterOf(firePos));
                KitManager.equip(mc, f);
                click(mc, me, firePos.below(), Direction.UP);
                return;
            }
        }
        if (!isCrossbow(me.getMainHandItem())) {
            KitManager.equip(mc, x);
            return;
        }
        if (!CrossbowItem.isCharged(me.getMainHandItem())) {
            startState(State.C_LOAD);
            return;
        }
        Entity cart = findCart(mc, rail);
        if (cart == null && pt < 10) return; // server se cart aane ka wait

        Vec3 aim = cart != null ? cart.getBoundingBox().getCenter()
                : new Vec3(rail.getX() + 0.5, rail.getY() + 0.4, rail.getZ() + 0.5);
        aimShot(me, aim, CROSSBOW_SPEED);
        mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
        me.swing(InteractionHand.MAIN_HAND);
        startState(State.END); // 2 tick baad slot wapas, control aapka
    }

    // ------------------------------------------------------------ HELPERS
    private void lookAt(LocalPlayer me, Vec3 p) {
        KitManager.aimAt(me, p);
    }

    private void click(Minecraft mc, LocalPlayer me, BlockPos support, Direction face) {
        Vec3 hit = Vec3.atCenterOf(support).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
        me.swing(InteractionHand.MAIN_HAND);
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
            }
