package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;

import java.util.Random;

/** Breach Swap (full auto): sword haath me, hit se pehle Breach mace pe swap, hit mace se, wapas sword. */
public class BreachSwapPvp {
    // ---- settings (yahan se badal sakte ho) ----
    static final float HEAL_START_HP = 9.0f;
    static final float FLEE_HP = 6.0f;          // 3 heart pe bhaago (sirf jab khana ho)
    static final double FLEE_FAR_DIST = 20.0;
    static final float HEAL_DONE_HP = 16.0f;
    static final float PEARL_MIN_HP = 8.0f;
    static final double REACH = 2.95;
    static final double KEEP_DIST = 2.2;
    static final double OUT_DIST = 2.75;
    static final double SPRINT_DIST = 3.2;
    static final double CLOSE_DIST = 0.9;
    static final double FLEE_SAFE_DIST = 8.0;
    static final float HIT_CHARGE = 0.95f;
    static final int CRIT_FALLBACK = 15;
    static final int PEARL_COOLDOWN = 20;
    static final int TOTEM_COOLDOWN = 10;

    private final Random rng = new Random();
    private int strafeDir = 1, strafeTimer = 0;
    private int pearlCd = 0, totemCd = 0, critWait = 0;
    private boolean healMode = false, pearlUsed = false, hadTotem = false;

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
        int sword = KitManager.findBest(p, KitManager::isSword, KitManager::swordScore);
        int breach = KitManager.findBest(p, BreachSwapPvp::isBreachMace, KitManager::zeroScore);
        return sword >= 0 && breach >= 0 && breach < 9; // mace hotbar me ho, tabhi 1 tick me swap
    }

    void onActivate(Minecraft mc) {
        healMode = false;
        pearlUsed = false;
        strafeTimer = 0;
        pearlCd = 0;
        totemCd = 0;
        critWait = 0;
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
        boolean canHeal = foodIdx >= 0;
        float hp = me.getHealth();

        if (!healMode && hp <= HEAL_START_HP && canHeal) {
            healMode = true;
            pearlUsed = false;
        }
        if (healMode && (hp >= HEAL_DONE_HP || !canHeal)) {
            healMode = false;
            mc.options.keyUse.setDown(false);
        }

        if (healMode) heal(mc, me, target, foodIdx);
        else if (hp <= FLEE_HP && canHeal) runAway(mc, me, target);
        else fight(mc, me, target);
    }

    private void ensureTotem(Minecraft mc, LocalPlayer me) {
        if (totemCd > 0 || me.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        int idx = KitManager.findBest(me, BreachSwapPvp::isTotem, KitManager::zeroScore);
        if (idx < 0) return;
        int slot = idx < 9 ? 36 + idx : idx;
        mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId, slot, 40, ClickType.SWAP, me);
        totemCd = TOTEM_COOLDOWN;
    }

    // ------------------------------------------------------------ FIGHT (breach swap)
    private void fight(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);

        int swordIdx = KitManager.findBest(me, KitManager::isSword, KitManager::swordScore);
        int breachIdx = KitManager.findBest(me, BreachSwapPvp::isBreachMace, KitManager::zeroScore);
        int axeIdx = KitManager.findBest(me, KitManager::isAxe, KitManager::zeroScore);

        boolean axeMode = target.isBlocking() && axeIdx >= 0;
        ItemStack held = me.getMainHandItem();
        if (axeMode) {
            if (!KitManager.isAxe(held)) KitManager.equip(mc, axeIdx);
        } else if (!KitManager.isSword(held) && swordIdx >= 0) {
            KitManager.equip(mc, swordIdx);
        }

        KitManager.aimAt(me, target.getBoundingBox().getCenter());

        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());
        boolean inWeb = KitManager.inWeb(mc.level, me);

        // strafe: enemy ka aim player par na tike
        if (--strafeTimer <= 0) {
            strafeDir = rng.nextBoolean() ? 1 : -1;
            strafeTimer = 8 + rng.nextInt(15);
        }

        boolean outOfRange = dist > OUT_DIST;
        boolean back = dist < CLOSE_DIST;
        boolean fwd = !back && dist > KEEP_DIST;
        if (outOfRange) fwd = true;

        boolean sprint = fwd && dist > SPRINT_DIST;
        int strafe = outOfRange ? 0 : strafeDir;

        boolean jump = !axeMode && me.onGround() && !inWeb && dist <= REACH + 0.2
                && me.getAttackStrengthScale(8f) >= HIT_CHARGE;
        move(mc, me, fwd, back, strafe, sprint, jump);

        if (dist > REACH) {
            critWait = 0;
            return;
        }

        // shield: axe turant, cooldown ka wait nahi
        if (axeMode) {
            mc.gameMode.attack(me, target);
            me.swing(InteractionHand.MAIN_HAND);
            return;
        }

        if (me.getAttackStrengthScale(0.5f) < HIT_CHARGE) return;

        boolean crit = me.fallDistance > 0 && !me.onGround() && !me.onClimbable()
                && !me.isInWater() && !me.isSprinting();
        critWait++;
        if (crit || inWeb || critWait > CRIT_FALLBACK) {
            swapHit(mc, me, target, breachIdx);
            critWait = 0;
        }
    }

    /** ek hi tick me: mace pe swap, mace se hit, wapas sword. */
    private void swapHit(Minecraft mc, LocalPlayer me, Player target, int breachIdx) {
        Inventory inv = me.getInventory();
        int back = inv.selected;
        if (breachIdx >= 0 && breachIdx < 9) inv.selected = breachIdx;
        mc.gameMode.attack(me, target);
        me.swing(InteractionHand.MAIN_HAND);
        inv.selected = back;
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

    // ------------------------------------------------------------ HEAL (pearl -> bhaagna -> khana)
    private void heal(Minecraft mc, LocalPlayer me, Player target, int foodIdx) {
        double dist = Math.sqrt(me.distanceToSqr(target));
        boolean eating = me.isUsingItem() && KitManager.hasFoodComponent(me.getMainHandItem());
        float hp = me.getHealth();

        int pearlIdx = KitManager.findBest(me, BreachSwapPvp::isPearl, KitManager::zeroScore);
        if (pearlIdx >= 0 && !pearlUsed && pearlCd == 0 && !eating
                && dist < 5.0 && hp > PEARL_MIN_HP) {
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
            flee(mc, me, target);
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

    private void flee(Minecraft mc, LocalPlayer me, Player target) {
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
