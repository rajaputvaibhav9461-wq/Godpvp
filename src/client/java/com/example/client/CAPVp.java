package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/** Crystal + Anchor PvP. Nether me sirf crystal, baaki me dono baari-baari. */
public class CAPVp {
    static final float HEAL_START_HP = 10.0f;   // 5 heart
    static final float HEAL_DONE_HP = 18.0f;
    static final float PEARL_MIN_HP = 6.5f;
    static final double REACH = 2.95;
    static final double PLACE_MAX = 4.4;        // block lagane ki max doori
    static final double CRYSTAL_HIT = 3.8;      // crystal phodne ki max doori
    static final double ANCHOR_SAFE = 4.0;      // anchor phodne se pehle itni doori
    static final double FLEE_SAFE_DIST = 8.0;
    static final int ACTION_CD = 2;
    static final int PEARL_COOLDOWN = 20;
    static final int TOTEM_COOLDOWN = 10;

    private final Random rng = new Random();
    private int actionCd = 0, pearlCd = 0, totemCd = 0, anchorStep = 0, anchorTicks = 0, strafeDir = 1, strafeTimer = 0;
    private boolean healMode = false, pearlUsed = false, hadTotem = false, useAnchor = false;
    private BlockPos anchorPos = null;

    // ------------------------------------------------------------ ITEM HELPERS
    static boolean isObsidian(ItemStack s) { return s.is(Items.OBSIDIAN); }
    static boolean isCrystal(ItemStack s) { return s.is(Items.END_CRYSTAL); }
    static boolean isAnchor(ItemStack s) { return s.is(Items.RESPAWN_ANCHOR); }
    static boolean isGlowstone(ItemStack s) { return s.is(Items.GLOWSTONE); }
    static boolean isPearl(ItemStack s) { return s.is(Items.ENDER_PEARL); }
    static boolean isTotem(ItemStack s) { return s.is(Items.TOTEM_OF_UNDYING); }

    private static boolean has(Player p, Predicate<ItemStack> f) {
        return KitManager.findBest(p, f, KitManager::zeroScore) >= 0;
    }

    private static boolean crystalKit(Player p) {
        return has(p, CAPVp::isCrystal) && (has(p, CAPVp::isObsidian));
    }

    private static boolean anchorKit(Player p) {
        return has(p, CAPVp::isAnchor) && has(p, CAPVp::isGlowstone);
    }

    private static boolean hold(Minecraft mc, Player p, Predicate<ItemStack> f) {
        int i = KitManager.findBest(p, f, KitManager::zeroScore);
        if (i < 0) return false;
        KitManager.equip(mc, i);
        return true;
    }

    // ------------------------------------------------------------ LIFECYCLE
    boolean matches(Minecraft mc) {
        Player p = mc.player;
        return KitManager.findBest(p, KitManager::isSword, KitManager::swordScore) >= 0
                && (crystalKit(p) || anchorKit(p));
    }

    void onActivate(Minecraft mc) {
        healMode = false;
        pearlUsed = false;
        anchorPos = null;
        anchorStep = 0;
        anchorTicks = 0;
        useAnchor = false;
        actionCd = 0;
        pearlCd = 0;
        totemCd = 0;
        hadTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
    }

    void onDeactivate(Minecraft mc) {
        healMode = false;
        anchorPos = null;
        KitManager.releaseAll(mc);
    }

    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (actionCd > 0) actionCd--;
        if (pearlCd > 0) pearlCd--;
        if (totemCd > 0) totemCd--;

        boolean hasTotem = me.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        boolean popped = hadTotem && me.getOffhandItem().isEmpty();
        hadTotem = hasTotem;
        if (popped) {
            healMode = true;
            pearlUsed = false;
            anchorPos = null;
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
            anchorPos = null;
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
        int idx = KitManager.findBest(me, CAPVp::isTotem, KitManager::zeroScore);
        if (idx < 0) return;
        int slot = idx < 9 ? 36 + idx : idx;
        mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId, slot, 40, ClickType.SWAP, me);
        totemCd = TOTEM_COOLDOWN;
    }

    // ------------------------------------------------------------ FIGHT
    private void fight(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);
        Level lvl = mc.level;
        boolean nether = lvl.dimension() == Level.NETHER;
        boolean cKit = crystalKit(me);
        boolean aKit = anchorKit(me) && !nether;
        if (!aKit) useAnchor = false;
        else if (!cKit) useAnchor = true;

        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());

        // 1) paas wala crystal (apna ya enemy ka) phod do
        EndCrystal c = nearestCrystal(mc, me, target);
        if (c != null) {
            mc.gameMode.attack(me, c);
            me.swing(InteractionHand.MAIN_HAND);
            return;
        }

        // 2) enemy door hai -> paas jao
        if (dist > PLACE_MAX + 1.0) {
            move(mc, me, true, false, 0, dist > 8.0, false);
            return;
        }

        // 3) crystal ya anchor ka kaam
        if (actionCd == 0) {
            boolean done = useAnchor ? anchorAction(mc, me, target) : crystalAction(mc, me, target);
            if (done) {
                actionCd = ACTION_CD;
                return;
            }
        }

        // 4) kuch nahi to sword se melee
        if (!KitManager.isSword(me.getMainHandItem())) {
            int s = KitManager.findBest(me, KitManager::isSword, KitManager::swordScore);
            if (s >= 0) KitManager.equip(mc, s);
        }
        if (--strafeTimer <= 0) {
            strafeDir = rng.nextBoolean() ? 1 : -1;
            strafeTimer = 8 + rng.nextInt(15);
        }
        move(mc, me, dist > 3.0, dist < 1.5, strafeDir, false, false);
        if (dist <= REACH && me.getAttackStrengthScale(0.5f) >= 0.95f) {
            mc.gameMode.attack(me, target);
            me.swing(InteractionHand.MAIN_HAND);
        }
    }

    private EndCrystal nearestCrystal(Minecraft mc, LocalPlayer me, Player target) {
        List<EndCrystal> l = mc.level.getEntitiesOfClass(EndCrystal.class, me.getBoundingBox().inflate(5.0));
        EndCrystal best = null;
        double bd = 1e9;
        Vec3 eye = me.getEyePosition();
        for (EndCrystal e : l) {
            double d = eye.distanceTo(e.position());
            if (d > CRYSTAL_HIT || e.distanceTo(target) > 6.5) continue;
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ CRYSTAL
    private boolean crystalAction(Minecraft mc, LocalPlayer me, Player target) {
        Level lvl = mc.level;
        Vec3 eye = me.getEyePosition();
        BlockPos base = target.blockPosition();

        // pehle se obsidian/bedrock pe crystal
        if (has(me, CAPVp::isCrystal)) {
            BlockPos best = null;
            double bd = 1e9;
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++)
                    for (int dy = -1; dy <= 0; dy++) {
                        BlockPos b = base.offset(dx, dy, dz);
                        BlockState s = lvl.getBlockState(b);
                        if (!(s.is(Blocks.OBSIDIAN) || s.is(Blocks.BEDROCK))) continue;
                        if (!lvl.getBlockState(b.above()).isAir() || !lvl.getBlockState(b.above(2)).isAir()) continue;
                        double ed = eye.distanceTo(Vec3.atCenterOf(b));
                        if (ed > PLACE_MAX || ed < 2.2) continue;
                        double d = b.distSqr(base);
                        if (d < bd) {
                            bd = d;
                            best = b;
                        }
                    }
            if (best != null) {
                hold(mc, me, CAPVp::isCrystal);
                click(mc, me, best, Direction.UP);
                return true;
            }
        }

        // obsidian lagao
        if (has(me, CAPVp::isObsidian)) {
            BlockPos spot = findSpot(lvl, me, target, 2.5, PLACE_MAX);
            if (spot != null) {
                hold(mc, me, CAPVp::isObsidian);
                click(mc, me, spot.below(), Direction.UP);
                return true;
            }
        }
        return false;
    }

    /** zameen pe khali jagah (upar 2 block khali), enemy ke paas, meri reach me. */
    private BlockPos findSpot(Level lvl, LocalPlayer me, Player target, double minD, double maxD) {
        Vec3 eye = me.getEyePosition();
        BlockPos base = target.blockPosition();
        BlockPos best = null;
        double bd = 1e9;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = -1; dy <= 0; dy++) {
                    BlockPos p = base.offset(dx, dy, dz);
                    if (p.equals(base) || p.equals(me.blockPosition())) continue;
                    if (!lvl.getBlockState(p).isAir()) continue;
                    if (!lvl.getBlockState(p.above()).isAir() || !lvl.getBlockState(p.above(2)).isAir()) continue;
                    BlockPos below = p.below();
                    if (!lvl.getBlockState(below).isFaceSturdy(lvl, below, Direction.UP)) continue;
                    double ed = eye.distanceTo(Vec3.atCenterOf(p));
                    if (ed < minD || ed > maxD) continue;
                    double d = p.distSqr(base);
                    if (d < bd) {
                        bd = d;
                        best = p;
                    }
                }
        return best;
  }
      // ------------------------------------------------------------ ANCHOR
    private boolean anchorAction(Minecraft mc, LocalPlayer me, Player target) {
        Level lvl = mc.level;

        if (anchorPos == null) {
            if (!has(me, CAPVp::isAnchor)) { useAnchor = false; return false; }
            BlockPos spot = findSpot(lvl, me, target, 3.5, PLACE_MAX);
            if (spot == null) return false;
            hold(mc, me, CAPVp::isAnchor);
            click(mc, me, spot.below(), Direction.UP);
            anchorPos = spot;
            anchorStep = 1;
            anchorTicks = 0;
            return true;
        }

        anchorTicks++;
        BlockState st = lvl.getBlockState(anchorPos);
        if (anchorTicks > 60 || (anchorTicks > 8 && !st.is(Blocks.RESPAWN_ANCHOR))) {
            anchorPos = null;
            anchorStep = 0;
            return false;
        }
        if (!st.is(Blocks.RESPAWN_ANCHOR)) return true; // server se block aane ka wait

        int charge = st.getValue(RespawnAnchorBlock.CHARGE);

        if (anchorStep == 1) {
            if (charge == 0) {
                if (!hold(mc, me, CAPVp::isGlowstone)) { anchorPos = null; return false; }
                click(mc, me, anchorPos, Direction.UP);
                return true;
            }
            anchorStep = 2;
        }

        if (anchorStep == 2) {
            double d = me.getEyePosition().distanceTo(Vec3.atCenterOf(anchorPos));
            if (d < ANCHOR_SAFE) { // thoda peeche hato
                double ax = me.getX() - (anchorPos.getX() + 0.5);
                double az = me.getZ() - (anchorPos.getZ() + 0.5);
                float yaw = (float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0);
                KitManager.setRotation(me, yaw, 0f);
                move(mc, me, true, false, 0, false, false);
                return true;
            }
            KitManager.stopMovement(mc);
            // sword hath me (glowstone nahi) -> right click se anchor phatega
            int s = KitManager.findBest(me, KitManager::isSword, KitManager::swordScore);
            if (s >= 0) KitManager.equip(mc, s);
            click(mc, me, anchorPos, Direction.UP);
            anchorPos = null;
            anchorStep = 0;
            useAnchor = false; // agli baari crystal ki
            return true;
        }
        return true;
    }

    private void click(Minecraft mc, LocalPlayer me, BlockPos support, Direction face) {
        Vec3 hit = Vec3.atCenterOf(support).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
        me.swing(InteractionHand.MAIN_HAND);
    }

    // ------------------------------------------------------------ HEAL
    private void heal(Minecraft mc, LocalPlayer me, Player target, int foodIdx) {
        double dist = Math.sqrt(me.distanceToSqr(target));
        boolean eating = me.isUsingItem() && KitManager.hasFoodComponent(me.getMainHandItem());

        int pearlIdx = KitManager.findBest(me, CAPVp::isPearl, KitManager::zeroScore);
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
            KitManager.setRotation(me, yawAway(me, target), 0f);
            move(mc, me, true, false, 0, true, rng.nextInt(12) == 0);
            return;
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
                  }
