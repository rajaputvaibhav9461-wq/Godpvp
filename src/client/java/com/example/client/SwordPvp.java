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
    static final float LOW_HP = 3.0f;           // itne HP pe bhaag ke heal
    static final float HEAL_DONE_HP = 14.0f;    // itna HP hone pe wapas fight
    static final double REACH = 2.95;           // hit range
    static final double FLEE_SAFE_DIST = 8.0;   // itni door jaake khana khayega
    static final double CHASE_DIST = 3.5;       // isse door ho to sprint karke pas jao
    static final float CRIT_ENEMY_HP = 8.0f;    // enemy ka HP itna ya kam ho to crit finisher
    static final double CRIT_CLOSE_DIST = 1.8;  // itna paas ho to bhi crit (close trade)
    static final int STAP_CHANCE = 3;           // 1/3 baar S-tap, baaki W-tap
    static final int BLOCKHIT_CHANCE = 2;       // 1/2 baar block-hit (offhand me shield ho tab)
    static final int WEB_INTERVAL = 100;        // 100 tick = 5 second
    static final double WEB_PLACE_RANGE = 4.5;

    private final Random rng = new Random();
    private final ArrayDeque<BlockPos> webQueue = new ArrayDeque<>();
    private int strafeDir = 1, strafeTimer = 0, backTimer = 0, webTimer = 0;
    private int wtap = 0, stap = 0, blockTimer = 0;
    private boolean healMode = false;

    boolean matches(Minecraft mc) {
        return KitManager.findBest(mc.player, KitManager::isSword, KitManager::swordScore) >= 0;
    }

    void onActivate(Minecraft mc) {
        webQueue.clear();
        healMode = false;
        wtap = 0;
        stap = 0;
        blockTimer = 0;
        backTimer = 0;
        strafeTimer = 0;
        webTimer = WEB_INTERVAL; // pehla web turant
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
        } else {
            fight(mc, me, target);
        }
    }

    // ------------------------------------------------------------ FIGHT
    private void fight(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);

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

        // crit finisher: enemy ka HP kam ho ya bahut paas (close trade)
        boolean critMode = target.getHealth() <= CRIT_ENEMY_HP || dist < CRIT_CLOSE_DIST;

        // strafe + kabhi kabhi S
        if (--strafeTimer <= 0) {
            strafeDir = rng.nextBoolean() ? 1 : -1;
            strafeTimer = 8 + rng.nextInt(15);
        }
        if (backTimer > 0) backTimer--;
        else if (dist < 3.0 && rng.nextInt(30) == 0) backTimer = 4 + rng.nextInt(4);

        boolean back = backTimer > 0 || dist < 1.2;
        boolean fwd = dist > 2.0 && !back;
        if (dist > CHASE_DIST) fwd = true; // door hai to pas jao

        // S-tap / W-tap (hit ke baad sprint reset)
        if (stap > 0) {
            stap--;
            back = true;
            fwd = false;
        } else if (wtap > 0) {
            wtap--;
            fwd = false;
        }

        // crit ke liye: attack charge hone se thoda pehle jump (girte waqt hit lagega)
        boolean jump = critMode && me.onGround() && !inWeb && me.getAttackStrengthScale(8f) >= 0.95f;
        boolean sprint = !critMode && fwd; // crit me sprint nahi chalta
        move(mc, me, fwd, back, strafeDir, sprint, jump);

        // attack
        boolean charged = me.getAttackStrengthScale(0.5f) >= 0.95f;
        if (dist <= REACH && charged) {
            boolean attack = true;
            if (critMode && !inWeb && !KitManager.isAxe(me.getMainHandItem())) {
                // crit: girte waqt hi hit
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
                // block-hit: offhand shield ho to hit ke baad 2 tick right-click
                if (me.getOffhandItem().is(Items.SHIELD) && rng.nextInt(BLOCKHIT_CHANCE) == 0) {
                    blockTimer = 2;
                }
            }
        }

        // block-hit (sirf sword hath me ho tab)
        if (blockTimer > 0) {
            blockTimer--;
            if (KitManager.isSword(me.getMainHandItem()) && me.getOffhandItem().is(Items.SHIELD)) {
                mc.options.keyUse.setDown(true);
            }
        }
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
    /** true = is tick me web laga raha hai (fight skip). */
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
