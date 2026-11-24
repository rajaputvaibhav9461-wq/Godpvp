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

/** Elytra Mace assist: aap glide karo, mod instant aim + ek tick me chestplate swap + mace hit. */
public class ElytraMacePvp {
    // ---- settings (yahan se badal sakte ho) ----
    static final double REACH = 3.0;
    static final double TRIGGER_BASE = 0.8;     // reach se itna pehle hi sab shuru
    static final double TRIGGER_SPEED = 0.3;    // speed jitni zyada, utna pehle
    static final double AIM_RANGE = 120.0;
    static final int HIT_TIMEOUT = 40;

    enum State { IDLE, HIT }

    private State state = State.IDLE;
    private int t = 0;
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
        axeDone = false;
    }

    void onDeactivate(Minecraft mc) {
        state = State.IDLE;
    }

    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;

        if (target == null || me.isInWater()) {
            state = State.IDLE;
            return;
        }

        // ---- glide chalu hote hi har tick instant aim ----
        if (state == State.IDLE) {
            if (me.isFallFlying() && Math.sqrt(me.distanceToSqr(target)) <= AIM_RANGE) {
                KitManager.aimAt(me, target.getBoundingBox().getCenter());
                double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());
                double trigger = REACH + TRIGGER_BASE + me.getDeltaMovement().length() * TRIGGER_SPEED;
                if (dist <= trigger) {
                    state = State.HIT;
                    t = 0;
                    axeDone = false;
                } else {
                    return;
                }
            } else {
                return;
            }
        }

        // ---- HIT: sab ek hi tick me ----
        t++;
        if (t > HIT_TIMEOUT) {
            state = State.IDLE;
            return;
        }
        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());

        // shield: pehle axe se turant hit, agli tick me mace
        int axeIdx = KitManager.findBest(me, KitManager::isAxe, KitManager::zeroScore);
        if (target.isBlocking() && axeIdx >= 0 && !axeDone) {
            wearChestplate(mc, me);
            KitManager.equip(mc, axeIdx);
            mc.gameMode.attack(me, target);
            me.swing(InteractionHand.MAIN_HAND);
            axeDone = true;
            return;
        }

        // chestplate + mace + attack, ek tick me
        wearChestplate(mc, me);
        int maceIdx = KitManager.findBest(me, ElytraMacePvp::isMace, KitManager::zeroScore);
        if (maceIdx >= 0) KitManager.equip(mc, maceIdx);

        if (dist <= REACH + TRIGGER_BASE + 1.0 || me.onGround()) {
            mc.gameMode.attack(me, target);
            me.swing(InteractionHand.MAIN_HAND);
            state = State.IDLE; // control wapas aapko
        }
    }

    /** chestplate pehno (elytra utar jayega). Pehle se pehna ho to kuch nahi. */
    private void wearChestplate(Minecraft mc, LocalPlayer me) {
        if (wearingChestplate(me)) return;
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
    }
                }
