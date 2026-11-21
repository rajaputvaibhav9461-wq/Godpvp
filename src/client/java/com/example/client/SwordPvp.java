package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Random;

public class SwordPvp {
    // ---- settings (yahan se badal sakte ho) ----
    static final float LOW_HP = 3.0f;
    static final float FLEE_HP = 6.0f;          // 3 heart pe bhaago (sirf jab khana ho)
    static final double FLEE_FAR_DIST = 20.0;
    static final float HEAL_DONE_HP = 14.0f;
    static final double REACH = 2.95;
    static final double KEEP_DIST = 2.2;        // isse door ho to aage badho
    static final double OUT_DIST = 2.75;        // isse door ho to strafe band, seedha jao
    static final double SPRINT_DIST = 3.2;      // isse door ho to sprint
    static final double CLOSE_DIST = 0.9;       // isse paas ho to thoda peeche
    static final double FLEE_SAFE_DIST = 8.0;
    static final float CRIT_ENEMY_HP = 8.0f;
    static final double CRIT_CLOSE_DIST = 1.8;
    static final int BLOCKHIT_CHANCE = 2;
    static final int WEB_INTERVAL = 100;
    static final double WEB_PLACE_RANGE = 4.5;

    private final Random rng = new Random();
    private final ArrayDeque<BlockPos> webQueue = new ArrayDeque<>();
    private int strafeDir = 1, strafeTimer = 0, webTimer = 0;
    private int wtap = 0, blockTimer = 0;
    private boolean healMode = false;

    boolean matches(Minecraft mc) {
        return KitManager.findBest(mc.player, KitManager::isSword, KitManager::swordScore) >= 0;
    }

    void onActivate(Minecraft mc) {
        webQueue.clear();
        healMode = false;
        wtap = 0;
        blockTimer = 0;
        strafeTimer = 0;
        webTimer = WEB_INTERVAL;
    }

    void onDeactivate(Minecraft mc) {
        webQueue.clear();
        healMode = false;
        KitManager.releaseAll(mc);
    }

    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (target == null) {
            KitManager.releaseAll(mc);
            webQueue.clear();
            return;
        }
        webTimer++;

        int foodIdx = KitManager.findBest(me, s -> KitManager.isFood(me, s), KitManager::foodScore);
        float hp = me.getHealth();
        if (!healMode && hp <= LOW_HP && foodIdx >= 0) healMode = true;
        if (healMode && (hp >= HEAL_DONE_HP || foodIdx < 0)) {
            healMode = false;
            mc.options.keyUse.setDown(false);
        }

        if (healMode) {
            blockTimer = 0;
            heal(mc, me, target, foodIdx);
        } else if (hp <= FLEE_HP && foodIdx >= 0) {
            blockTimer = 0;
            runAway(mc, me, target);
        } else {
            fight(mc, me, target);
        }
    }

    // ------------------------------------------------------------ FIGHT (hit range me rehna)
    private void fight(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);

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

        boolean critMode = !axeMode && (target.getHealth() <= CRIT_ENEMY_HP || dist < CRIT_CLOSE_DIST);

        if (--strafeTimer <= 0) {
            strafeDir = rng.nextBoolean() ? 1 : -1;
            strafeTimer = 8 + rng.nextInt(15);
        }

        // range me rehne ka logic
        boolean outOfRange = dist > OUT_DIST;
        boolean back = dist < CLOSE_DIST;
        boolean fwd = !back && dist > KEEP_DIST;
        if (outOfRange) fwd = true;

        // W-tap: sirf jab enemy paas ho (door ho to W nahi chhodte)
        if (wtap > 0) {
            wtap--;
            if (dist < 2.3) fwd = false;
        }

        boolean sprint = fwd && (dist > SPRINT_DIST || (!critMode && outOfRange));
        int strafe = outOfRange ? 0 : strafeDir;

        boolean jump = critMode && me.onGround() && !inWeb && dist <= REACH + 0.2
                && me.getAttackStrengthScale(8f) >= 0.95f;
        move(mc, me, fwd, back, strafe, sprint, jump);

        boolean charged = axeMode || me.getAttackStrengthScale(0.5f) >= 0.95f;
        if (dist <= REACH && charged) {
            boolean attack = true;
            if (critMode && !inWeb) {
                attack = me.fallDistance > 0 && !me.onGround() && !me.onClimbable()
                        && !me.isInWater() && !me.isSprinting();
            }
            if (attack) {
                mc.gameMode.attack(me, target);
                me.swing(InteractionHand.MAIN_HAND);
                if (!critMode && !axeMode) wtap = 2;
                if (!axeMode && me.getOffhandItem().is(Items.SHIELD) && rng.nextInt(BLOCKHIT_CHANCE) == 0) {
                    blockTimer = 2;
                }
            }
        }

        if (blockTimer > 0) {
            blockTimer--;
            if (KitManager.isSword(me.getMainHandItem()) && me.getOffhandItem().is(Items.SHIELD)) {
                mc.options.keyUse.setDown(true);
            }
        }
            }
        // ------------------------------------------------------------ RUN AWAY (3 heart pe, khana ho tab)
    private void runAway(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);
        double dist = Math.sqrt(me.distanceToSqr(target));
        if (dist >= FLEE_FAR_DIST) {
            KitManager.stopMovement(mc);
            return;
        }
        flee(mc, me, target);
    }

    // ------------------------------------------------------------ HEAL
    private void heal(Minecraft mc, LocalPlayer me, Player target, int foodIdx) {
        double dist = Math.sqrt(me.distanceToSqr(target));
        if (dist < FLEE_SAFE_DIST) {
            mc.options.keyUse.setDown(false);
            flee(mc, me, target);
            return;
        }
        KitManager.stopMovement(mc);
        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        boolean eating = me.isUsingItem() && KitManager.hasFoodComponent(me.getMainHandItem());
        if (!eating) KitManager.equip(mc, foodIdx);
        mc.options.keyUse.setDown(true);
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
