package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;

import java.util.Random;

/** Breach Swap: sword se hit, phir Breach mace pe swap karke hit. Sirf zameen par. */
public class BreackSwapPvp {
    static final float HEAL_START_HP = 8.0f;
    static final float HEAL_DONE_HP = 16.0f;
    static final float PEARL_MIN_HP = 6.5f;
    static final double REACH = 2.95;
    static final double CHASE_DIST = 3.5;
    static final double FLEE_SAFE_DIST = 8.0;
    static final float HIT_CHARGE = 0.9f;
    static final int PEARL_COOLDOWN = 20;
    static final int TOTEM_COOLDOWN = 10;

    private final Random rng = new Random();
    private int strafeDir = 1, strafeTimer = 0, backTimer = 0, wtap = 0;
    private int pearlCd = 0, totemCd = 0;
    private boolean healMode = false, pearlUsed = false, hadTotem = false;
    private boolean useBreach = false; // false = sword ki baari, true = Breach mace ki baari

    // ------------------------------------------------------------ ITEM HELPERS
    static boolean hasEnch(ItemStack s, String path) {
        ItemEnchantments e = s.get(DataComponents.ENCHANTMENTS);
        if (e == null) return false;
        for (Holder<Enchantment> h : e.keySet()) {
            String p = h.unwrapKey().map(k -> k.location().getPath()).orElse("");
            if (p.equals(path)) return true;
        }
        return false;
    }

    static boolean isBreachMace(ItemStack s) { return s.is(Items.MACE) && hasEnch(s, "breach"); }
    static boolean isPearl(ItemStack s) { return s.is(Items.ENDER_PEARL); }
    static boolean isTotem(ItemStack s) { return s.is(Items.TOTEM_OF_UNDYING); }

    // ------------------------------------------------------------ LIFECYCLE
    boolean matches(Minecraft mc) {
        Player p = mc.player;
        return KitManager.findBest(p, KitManager::isSword, KitManager::swordScore) >= 0
                && KitManager.findBest(p, BreackSwapPvp::isBreachMace, KitManager::zeroScore) >= 0;
    }

    void onActivate(Minecraft mc) {
        healMode = false;
        pearlUsed = false;
        useBreach = false;
        pearlCd = 0;
        totemCd = 0;
        wtap = 0;
        backTimer = 0;
        strafeTimer = 0;
        hadTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
    }

    void onDeactivate(Minecraft mc) {
        healMode = false;
        KitManager.releaseAll(mc);
    }

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
        int idx = KitManager.findBest(me, BreackSwapPvp::isTotem, KitManager::zeroScore);
        if (idx < 0) return;
        int slot = idx < 9 ? 36 + idx : idx;
        mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId, slot, 40, ClickType.SWAP, me);
        totemCd = TOTEM_COOLDOWN;
    }

    // ------------------------------------------------------------ FIGHT (sword <-> Breach mace)
    private void fight(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);

        int swordIdx = KitManager.findBest(me, KitManager::isSword, KitManager::swordScore);
        int breachIdx = KitManager.findBest(me, BreackSwapPvp::isBreachMace, KitManager::zeroScore);
        int axeIdx = KitManager.findBest(me, KitManager::isAxe, KitManager::zeroScore);

        // weapon chuno: shield ho to axe, warna baari ke hisaab se sword / Breach mace
        boolean axeMode = target.isBlocking() && axeIdx >= 0;
        ItemStack held = me.getMainHandItem();
        boolean holdingRight;
        if (axeMode) {
            holdingRight = KitManager.isAxe(held);
            if (!holdingRight) KitManager.equip(mc, axeIdx);
        } else if (useBreach && breachIdx >= 0) {
            holdingRight = isBreachMace(held);
            if (!holdingRight) KitManager.equip(mc, breachIdx);
        } else {
            holdingRight = KitManager.isSword(held);
            if (!holdingRight && swordIdx >= 0) KitManager.equip(mc, swordIdx);
        }

        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());

        // movement: strafe + kabhi kabhi S + W-tap
        if (--strafeTimer <= 0) {
            strafeDir = rng.nextBoolean() ? 1 : -1;
            strafeTimer = 8 + rng.nextInt(15);
        }
        if (backTimer > 0) backTimer--;
        else if (dist < 3.0 && rng.nextInt(30) == 0) backTimer = 4 + rng.nextInt(4);

        boolean back = backTimer > 0 || dist < 1.2;
        boolean fwd = dist > 2.0 && !back;
        if (dist > CHASE_DIST) fwd = true;
        if (wtap > 0) {
            wtap--;
            fwd = false;
        }
        move(mc, me, fwd, back, strafeDir, fwd, false);

        // attack: poori charge, sahi item hath mein ho tab
        if (holdingRight && dist <= REACH && me.getAttackStrengthScale(0.5f) >= HIT_CHARGE) {
            mc.gameMode.attack(me, target);
            me.swing(InteractionHand.MAIN_HAND);
            wtap = 2;
            if (!axeMode) useBreach = !useBreach; // agli baari doosre item ki
        }
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
        float hp = me.getHealth();

        int pearlIdx = KitManager.findBest(me, BreackSwapPvp::isPearl, KitManager::zeroScore);
        if (pearlIdx >= 0 && !pearlUsed && pearlCd == 0 && !eating
                && dist < FLEE_SAFE_DIST && hp > PEARL_MIN_HP) {
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
