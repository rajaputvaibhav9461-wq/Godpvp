package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Random;

/** UHC PvP: bow + rod + sword combo + cobweb + gapple + water bucket. */
public class UhcPvp {
    static final float HEAL_START_HP = 12.0f;    // 6 heart
    static final float EMERGENCY_HP = 6.0f;      // 3 heart: bhaag ke khana
    static final float HEAL_DONE_HP = 18.0f;
    static final double REACH = 2.95;
    static final double CHASE_DIST = 3.5;
    static final double BOW_MIN_DIST = 8.0;
    static final double BOW_MAX_DIST = 35.0;
    static final double ROD_MIN = 4.0;
    static final double ROD_MAX = 7.0;
    static final double FLEE_SAFE_DIST = 8.0;
    static final double EAT_SAFE_DIST = 6.0;
    static final int BOW_FULL_DRAW = 20;
    static final int BOW_COOLDOWN = 30;
    static final int ROD_COOLDOWN = 60;
    static final int ROD_REEL_DELAY = 6;
    static final int WATER_COOLDOWN = 40;
    static final int STAP_CHANCE = 3;
    static final int CRIT_CHANCE = 4;            // 1/4 baar crit try
    static final int WEB_INTERVAL = 200;         // 10 second
    static final double WEB_PLACE_RANGE = 4.5;

    private final Random rng = new Random();
    private final ArrayDeque<BlockPos> webQueue = new ArrayDeque<>();
    private int strafeDir = 1, strafeTimer = 0, backTimer = 0, webTimer = 0;
    private int wtap = 0, stap = 0, bowCd = 0, rodCd = 0, rodReel = 0, waterCd = 0, waterPick = 0;
    private boolean healMode = false, bowing = false, critTry = false;

    // ------------------------------------------------------------ ITEM HELPERS
    static boolean isRod(ItemStack s) { return s.is(Items.FISHING_ROD); }
    static boolean isBow(ItemStack s) { return s.is(Items.BOW); }
    static boolean isArrow(ItemStack s) { return s.is(ItemTags.ARROWS); }
    static boolean isWater(ItemStack s) { return s.is(Items.WATER_BUCKET); }
    static boolean isEmptyBucket(ItemStack s) { return s.is(Items.BUCKET); }

    private static boolean has(Player p, java.util.function.Predicate<ItemStack> f) {
        return KitManager.findBest(p, f, KitManager::zeroScore) >= 0;
    }

    // ------------------------------------------------------------ LIFECYCLE
    boolean matches(Minecraft mc) {
        return KitManager.findBest(mc.player, KitManager::isSword, KitManager::swordScore) >= 0;
    }

    void onActivate(Minecraft mc) {
        webQueue.clear();
        healMode = false;
        bowing = false;
        critTry = false;
        wtap = 0;
        stap = 0;
        bowCd = 0;
        rodCd = 0;
        rodReel = 0;
        waterCd = 0;
        waterPick = 0;
        backTimer = 0;
        strafeTimer = 0;
        webTimer = WEB_INTERVAL;
    }

    void onDeactivate(Minecraft mc) {
        webQueue.clear();
        healMode = false;
        bowing = false;
        KitManager.releaseAll(mc);
    }

    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (bowCd > 0) bowCd--;
        if (rodCd > 0) rodCd--;
        if (waterCd > 0) waterCd--;

        if (target == null) {
            bowing = false;
            KitManager.releaseAll(mc);
            webQueue.clear();
            return;
        }
        webTimer++;

        if (handleWater(mc, me)) return;

        int foodIdx = KitManager.findBest(me, s -> KitManager.isFood(me, s), KitManager::foodScore);
        float hp = me.getHealth();
        double dist = Math.sqrt(me.distanceToSqr(target));

        if (!healMode && foodIdx >= 0
                && (hp <= EMERGENCY_HP || (hp <= HEAL_START_HP && dist > EAT_SAFE_DIST))) {
            healMode = true;
            bowing = false;
            rodReel = 0;
        }
        if (healMode && (hp >= HEAL_DONE_HP || foodIdx < 0)) {
            healMode = false;
            mc.options.keyUse.setDown(false);
        }

        if (healMode) heal(mc, me, target, foodIdx);
        else fight(mc, me, target);
    }

    // ------------------------------------------------------------ WATER BUCKET (aag bujhana)
    /** true = is tick me paani wala kaam chal raha hai. */
    private boolean handleWater(Minecraft mc, LocalPlayer me) {
        if (waterPick > 0) {
            waterPick--;
            if (waterPick == 0) {
                int b = KitManager.findBest(me, UhcPvp::isEmptyBucket, KitManager::zeroScore);
                if (b >= 0) {
                    KitManager.equip(mc, b);
                    KitManager.setRotation(me, me.getYRot(), 90f);
                    mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
                }
            }
            return true;
        }
        if (me.isOnFire() && waterCd == 0) {
            int w = KitManager.findBest(me, UhcPvp::isWater, KitManager::zeroScore);
            if (w >= 0) {
                mc.options.keyUse.setDown(false);
                KitManager.equip(mc, w);
                KitManager.setRotation(me, me.getYRot(), 90f);
                mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
                me.swing(InteractionHand.MAIN_HAND);
                waterCd = WATER_COOLDOWN;
                waterPick = 6;
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ FIGHT
    private void fight(Minecraft mc, LocalPlayer me, Player target) {
        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());

        // rod: cast ke baad thodi der me kheench lo
        if (rodReel > 0) {
            rodReel--;
            KitManager.aimAt(me, target.getBoundingBox().getCenter());
            if (rodReel == 0 && isRod(me.getMainHandItem())) {
                mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
                me.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }

        // bow: door se full draw arrow
        if (bowing || (dist >= BOW_MIN_DIST && dist <= BOW_MAX_DIST && bowCd == 0
                && has(me, UhcPvp::isBow) && has(me, UhcPvp::isArrow))) {
            if (bowShot(mc, me, target, dist)) return;
        }

        mc.options.keyUse.setDown(false);
        if (processWebs(mc, me, target)) return;

        // rod: 4-7 block par ek baar fenko
        if (dist >= ROD_MIN && dist <= ROD_MAX && rodCd == 0 && has(me, UhcPvp::isRod)
                && !target.isBlocking()) {
            int r = KitManager.findBest(me, UhcPvp::isRod, KitManager::zeroScore);
            if (!isRod(me.getMainHandItem())) {
                KitManager.equip(mc, r);
                return;
            }
            KitManager.aimAt(me, target.getBoundingBox().getCenter());
            mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
            me.swing(InteractionHand.MAIN_HAND);
            rodCd = ROD_COOLDOWN;
            rodReel = ROD_REEL_DELAY;
            return;
        }

        // weapon: shield pe axe, warna sword
        int axeIdx = KitManager.findBest(me, KitManager::isAxe, KitManager::zeroScore);
        if (target.isBlocking() && axeIdx >= 0) {
            if (!KitManager.isAxe(me.getMainHandItem())) KitManager.equip(mc, axeIdx);
        } else if (!KitManager.isSword(me.getMainHandItem())) {
            int swordIdx = KitManager.findBest(me, KitManager::isSword, KitManager::swordScore);
            if (swordIdx >= 0) KitManager.equip(mc, swordIdx);
        }

        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        boolean inWeb = KitManager.inWeb(mc.level, me);

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

        // kabhi kabhi crit: jump karke girte waqt hit
        if (me.onGround() && dist <= REACH + 0.5 && rng.nextInt(CRIT_CHANCE * 10) == 0) critTry = true;
        if (me.onGround() && !critTry) { /* normal sprint hit */ }
        boolean jump = critTry && me.onGround() && !inWeb;
        boolean sprint = !critTry && fwd;
        move(mc, me, fwd, back, strafeDir, sprint, jump);

        boolean charged = me.getAttackStrengthScale(0.5f) >= 0.95f;
        if (dist <= REACH && charged) {
            boolean attack = true;
            if (critTry && !inWeb && !KitManager.isAxe(me.getMainHandItem())) {
                attack = me.fallDistance > 0 && !me.onGround() && !me.onClimbable()
                        && !me.isInWater() && !me.isSprinting();
            }
            if (attack) {
                mc.gameMode.attack(me, target);
                me.swing(InteractionHand.MAIN_HAND);
                if (!critTry) {
                    if (rng.nextInt(STAP_CHANCE) == 0) stap = 2;
                    else wtap = 2;
                }
                critTry = false;
            }
        }
        if (me.onGround() && critTry && dist > REACH + 2.0) critTry = false;
    }

    // ------------------------------------------------------------ BOW
    /** true = is tick me bow chala raha hai. */
    private boolean bowShot(Minecraft mc, LocalPlayer me, Player target, double dist) {
        if (!has(me, UhcPvp::isBow) || !has(me, UhcPvp::isArrow) || dist < BOW_MIN_DIST - 3.0) {
            bowing = false;
            mc.options.keyUse.setDown(false);
            return false;
        }
        bowing = true;
        if (!isBow(me.getMainHandItem())) {
            mc.options.keyUse.setDown(false);
            KitManager.equip(mc, KitManager.findBest(me, UhcPvp::isBow, KitManager::zeroScore));
            return true;
        }
        KitManager.stopMovement(mc);
        aimShot(me, target.getBoundingBox().getCenter(), 3.0);
        if (!me.isUsingItem()) {
            mc.options.keyUse.setDown(true);
        } else if (me.getTicksUsingItem() >= BOW_FULL_DRAW) {
            mc.options.keyUse.setDown(false);
            bowing = false;
            bowCd = BOW_COOLDOWN;
        }
        return true;
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
      // ------------------------------------------------------------ HEAL (gapple)
    private void heal(Minecraft mc, LocalPlayer me, Player target, int foodIdx) {
        double dist = Math.sqrt(me.distanceToSqr(target));
        boolean eating = me.isUsingItem() && KitManager.hasFoodComponent(me.getMainHandItem());

        if (dist < EAT_SAFE_DIST && me.getHealth() <= EMERGENCY_HP) {
            // bahut kam HP aur enemy paas: pehle door bhaago
            if (dist < FLEE_SAFE_DIST) {
                mc.options.keyUse.setDown(false);
                flee(mc, me, target);
                return;
            }
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

    private void flee(Minecraft mc, LocalPlayer me, Player target) {
        double ax = me.getX() - target.getX();
        double az = me.getZ() - target.getZ();
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
