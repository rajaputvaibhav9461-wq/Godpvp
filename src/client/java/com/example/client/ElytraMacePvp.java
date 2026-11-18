package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/** Elytra Mace assist: aap glide karo, mod aim + chestplate swap + mace smash. */
public class ElytraMacePvp {
    static final double REACH = 3.0;
    static final double ENGAGE_BASE = 4.5;
    static final double ENGAGE_SPEED_MUL = 2.0;
    static final double SMASH_FALL = 1.6;
    static final double AIM_RANGE = 120.0;
    static final int SWAP_CD = 3;
    static final int HIT_TIMEOUT = 60;

    enum State { IDLE, AIM, HIT }

    private State state = State.IDLE;
    private int t = 0, swapCd = 0;
    private boolean axeDone = false;

    static boolean isMace(ItemStack s) { return s.is(Items.MACE); }

    static boolean isElytraOk(ItemStack s) {
        return s.is(Items.ELYTRA) && s.getDamageValue() < s.getMaxDamage() - 1;
    }

    static boolean isChestplate(ItemStack s) {
        return !s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_chestplate");
    }

    private static boolean has(Player p, Predicate<ItemStack> f) {
        return KitManager.findBest(p, f, KitManager::zeroScore) >= 0;
    }

    private static boolean wearingChestplate(Player p) {
        return isChestplate(p.getItemBySlot(EquipmentSlot.CHEST));
    }

    boolean matches(Minecraft mc) {
        Player p = mc.player;
        boolean elytra = isElytraOk(p.getItemBySlot(EquipmentSlot.CHEST)) || has(p, ElytraMacePvp::isElytraOk);
        boolean chest = wearingChestplate(p) || has(p, ElytraMacePvp::isChestplate);
        return elytra && chest && has(p, ElytraMacePvp::isMace);
    }

    void onActivate(Minecraft mc) {
        state = State.IDLE;
        t = 0;
        swapCd = 0;
        axeDone = false;
    }

    void onDeactivate(Minecraft mc) {
        state = State.IDLE;
    }

    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (swapCd > 0) swapCd--;

        if (state == State.IDLE) {
            if (me.isFallFlying() && target != null
                    && Math.sqrt(me.distanceToSqr(target)) <= AIM_RANGE) {
                state = State.AIM;
                t = 0;
                axeDone = false;
            }
            return;
        }
        if (target == null || me.isInWater()) {
            state = State.IDLE;
            return;
        }
        t++;
        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());

        if (state == State.AIM) {
            if (!me.isFallFlying()) { // aapne khud glide band kiya
                state = State.IDLE;
                return;
            }
            KitManager.aimAt(me, target.getBoundingBox().getCenter());
            double engage = ENGAGE_BASE + me.getDeltaMovement().length() * ENGAGE_SPEED_MUL;
            if (dist <= engage) {
                state = State.HIT;
                t = 0;
            }
            return;
        }

        // ---- HIT ----
        if (t > HIT_TIMEOUT || (me.onGround() && dist > REACH + 2.0 && t > 5)) {
            state = State.IDLE;
            return;
        }
        KitManager.aimAt(me, target.getBoundingBox().getCenter());

        // chestplate pehno (elytra utar jayega)
        if (!wearingChestplate(me) && has(me, ElytraMacePvp::isChestplate)) {
            wearChestplate(mc, me);
            return;
        }

        // shield: axe turant
        int axeIdx = KitManager.findBest(me, KitManager::isAxe, KitManager::zeroScore);
        if (target.isBlocking() && axeIdx >= 0 && !axeDone) {
            if (!KitManager.isAxe(me.getMainHandItem())) KitManager.equip(mc, axeIdx);
            if (dist <= REACH) {
                mc.gameMode.attack(me, target);
                me.swing(InteractionHand.MAIN_HAND);
                axeDone = true;
            }
            return;
        }

        int maceIdx = KitManager.findBest(me, ElytraMacePvp::isMace, KitManager::zeroScore);
        if (maceIdx >= 0 && !isMace(me.getMainHandItem())) {
            KitManager.equip(mc, maceIdx);
            return;
        }

        if (dist <= REACH && (me.fallDistance > SMASH_FALL || me.onGround())) {
            mc.gameMode.attack(me, target);
            me.swing(InteractionHand.MAIN_HAND);
            state = State.IDLE; // control wapas aapko
        }
    }

    private void wearChestplate(Minecraft mc, LocalPlayer me) {
        if (swapCd > 0) return;
        int idx = KitManager.findBest(me, ElytraMacePvp::isChestplate, KitManager::zeroScore);
        if (idx < 0) return;
        Inventory inv = me.getInventory();
        int cid = me.inventoryMenu.containerId;
        int btn = idx;
        if (idx >= 9) {
            btn = KitManager.freeHotbarSlot(inv);
            mc.gameMode.handleInventoryMouseClick(cid, idx, btn, ClickType.SWAP, me);
        }
        mc.gameMode.handleInventoryMouseClick(cid, 6, btn, ClickType.SWAP, me);
        swapCd = SWAP_CD;
    }
          }
