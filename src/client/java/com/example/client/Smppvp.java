package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Random;
import java.util.function.Predicate;

/** SMP PvP: bow + sword/axe combo + cobweb + pearl + gapple + totem. */
public class Smppvp {
    static final float HEAL_START_HP = 12.0f;   // 6 heart
    static final float HEAL_DONE_HP = 18.0f;
    static final float PEARL_MIN_HP = 6.5f;     // pearl 5 damage deta hai
    static final float CRIT_ENEMY_HP = 8.0f;
    static final double REACH = 2.95;
    static final double CHASE_DIST = 3.5;
    static final double BOW_MIN_DIST = 8.0;
    static final double BOW_MAX_DIST = 35.0;
    static final double FLEE_SAFE_DIST = 8.0;
    static final int BOW_FULL_DRAW = 20;
    static final int BOW_COOLDOWN = 30;
    static final int STAP_CHANCE = 3;
    static final int PEARL_COOLDOWN = 20;
    static final int TOTEM_COOLDOWN = 10;
    static final int WEB_INTERVAL = 200;        // 10 second
    static final double WEB_PLACE_RANGE = 4.5;

    private final Random rng = new Random();
    private final ArrayDeque<BlockPos> webQueue = new ArrayDeque<>();
    private int strafeDir = 1, strafeTimer = 0, backTimer = 0, webTimer = 0;
    private int wtap = 0, stap = 0, bowCd = 0, pearlCd = 0, totemCd = 0;
    private boolean healMode = false, bowing = false, pearlUsed = false, hadTotem = false;

    // ------------------------------------------------------------ ITEM HELPERS
    static boolean isBow(ItemStack s) { return s.is(Items.BOW); }
    static boolean isArrow(ItemStack s) { return s.is(ItemTags.ARROWS); }
    static boolean isPearl(ItemStack s) { return s.is(Items.ENDER_PEARL); }
    static boolean isTotem(ItemStack s) { return s.is(Items.TOTEM_OF_UNDYING); }

    private static boolean has(Player p, Predicate<ItemStack> f) {
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
        pearlUsed = false;
        wtap = 0;
        stap = 0;
        bowCd = 0;
        pearlCd = 0;
        totemCd = 0;
        backTimer = 0;
        strafeTimer = 0;
        hadTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
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
        if (pearlCd > 0) pearlCd--;
        if (totemCd > 0) totemCd--;

        // totem pop hua? -> turant heal mode
        boolean hasTotem = me.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        boolean popped = hadTotem && me.getOffhandItem().isEmpty();
        hadTotem = hasTotem;
        if (popped) {
            healMode = true;
            pearlUsed = false;
            bowing = false;
        }
        if (!me.isUsingItem()) ensureTotem(mc, me);

        if (target == null) {
            bowing = false;
            KitManager.releaseAll(mc);
            webQueue.clear();
            return;
        }
        webTimer++;

        int foodIdx = KitManager.findBest(me, s -> KitManager.isFood(me, s), KitManager::foodScore);
        float hp = me.getHealth();
        if (!healMode && hp <= HEAL_START_HP && foodIdx >= 0) {
            healMode = true;
            pearlUsed = false;
            bowing = false;
        }
        if (healMode && (hp >= HEAL_DONE_HP || foodIdx < 0)) {
            healMode = false;
            mc.options.keyUse.setDown(false);
        }

        if (healMode) heal(mc, me, target, foodIdx);
        else fight(mc, me, target);
    }

    private void ensureTotem(Minecraft mc, LocalPlayer me) {
        if (totemCd > 0 || me.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        int idx = KitManager.findBest(me, Smppvp::isTotem, KitManager::zeroScore);
        if (idx < 0) return;
        int slot = idx < 9 ? 36 + idx : idx;
        mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId, slot, 40, ClickType.SWAP, me);
        totemCd = TOTEM_COOLDOWN;
    }

    // ------------------------------------------------------------ FIGHT
    private void fight(Minecraft mc, LocalPlayer me, Player target) {
        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());

        // 1) bow: door se full draw
        if (bowing || (dist >= BOW_MIN_DIST && dist <= BOW_MAX_DIST && bowCd == 0
                && has(me, Smppvp::isBow) && has(me, Smppvp::isArrow))) {
            if (bowShot(mc, me, target, dist)) return;
        }

        mc.options.keyUse.setDown(false);
        if (processWebs(mc, me, target)) return;

        // 2) weapon: shield pe axe, warna sword
        int axeIdx = KitManager.findBest(me, KitManager::isAxe, KitManager::zeroScore);
        boolean axeMode = target.isBlocking() && axeIdx >= 0;
        if (axeMode) {
            if (!KitManager.isAxe(me.getMainHandItem())) KitManager.equip(mc, axeIdx);
        } else if (!KitManager.isSword(me.getMainHandItem())) {
            int swordIdx = KitManager.findBest(me, KitManager::isSword, KitManager::swordScore);
            if (swordIdx >= 0) KitManager.equip(mc, swordIdx);
        }

        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        boolean inWeb = KitManager.inWeb(mc.level, me);

        // crit: enemy khana/potion use kar raha ho ya HP kam ho
        boolean critMode = target.isUsingItem() || target.getHealth() <= CRIT_ENEMY_HP;

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

        boolean jump = critMode && me.onGround() && !inWeb && me.getAttackStrengthScale(8f) >= 0.95f;
        boolean sprint = !critMode && fwd;
        move(mc, me, fwd, back, strafeDir, sprint, jump);

        boolean charged = me.getAttackStrengthScale(0.5f) >= 0.95f;
        if (dist <= REACH && charged) {
            boolean attack = true;
            if (critMode && !inWeb && !axeMode) {
                attack = me.fallDistance > 0 && !me.onGround() && !me.onClimbable()
                        && !me.isInWater() && !me.isSprinting();
            }
            if (attack) {
                mc.gameMode.attack(me, target);
                me.swing(InteractionHand.MAIN_HAND);
                if (!critMode && !axeMode) {
                    if (rng.nextInt(STAP_CHANCE) == 0) stap = 2;
                    else wtap = 2;
                }
            }
        }
    }

    // ------------------------------------------------------------ BOW
    /** true = is tick me bow chala raha hai. */
    private boolean bowShot(Minecraft mc, LocalPlayer me, Player target, double dist) {
        if (!has(me, Smppvp::isBow) || !has(me, Smppvp::isArrow) || dist < BOW_MIN_DIST - 3.0) {
            bowing = false;
            mc.options.keyUse.setDown(false);
            return false;
        }
        bowing = true;
        if (!isBow(me.getMainHandItem())) {
            mc.options.keyUse.setDown(false);
            KitManager.equip(mc, KitManager.findBest(me, Smppvp::isBow, KitManager::zeroScore));
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
      // ------------------------------------------------------------ HEAL (pearl -> bhaagna -> gapple)
    private void heal(Minecraft mc, LocalPlayer me, Player target, int foodIdx) {
        double dist = Math.sqrt(me.distanceToSqr(target));
        boolean eating = me.isUsingItem() && KitManager.hasFoodComponent(me.getMainHandItem());

        // 1) enemy paas hai aur HP kaafi hai -> pearl se door
        int pearlIdx = KitManager.findBest(me, Smppvp::isPearl, KitManager::zeroScore);
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

        // 2) enemy paas hai to bhaago
        if (dist < FLEE_SAFE_DIST) {
            mc.options.keyUse.setDown(false);
            KitManager.setRotation(me, yawAway(me, target), 0f);
            move(mc, me, true, false, 0, true, rng.nextInt(12) == 0);
            return;
        }

        // 3) safe doori: gapple khao
        KitManager.stopMovement(mc);
        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        if (foodIdx >= 0) {
            if (!eating) KitManager.equip(mc, foodIdx);
            mc.options.keyUse.setDown(true);
        } else {
            mc.options.keyUse.setDown(false);
        }
    }

    private static float yawAway(Player me, Player target) {
        double ax = me.getX() - target.getX();
        double az = me.getZ() - target.getZ();
        if (ax * ax + az * az < 1e-4) ax = 1;
        return (float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0);
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
