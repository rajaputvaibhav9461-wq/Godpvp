package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Mace assist: aap khud khelo. Sirf bade uchhal ke baad girte waqt mod aim + smash (shield ho to axe stun slam). */
public class MacePvp {
    // ---- settings (yahan se badal sakte ho) ----
    static final double REACH = 3.0;           // hit range
    static final double AIM_RANGE = 15.0;      // enemy itni door tak ho to aim
    static final double AIM_FALL = 2.0;        // peak se itna girne ke baad hi aim (jump par aim nahi)
    static final double SMASH_FALL = 1.6;      // itna girne ke baad smash hit

    private boolean axeDone = false;
    private boolean smashed = false;
    private double peakY = 0;

    static boolean isMace(ItemStack s) { return s.is(Items.MACE); }

    boolean matches(Minecraft mc) {
        return KitManager.findBest(mc.player, MacePvp::isMace, KitManager::zeroScore) >= 0;
    }

    void onActivate(Minecraft mc) {
        axeDone = false;
        smashed = false;
        peakY = mc.player.getY();
    }

    void onDeactivate(Minecraft mc) {
        axeDone = false;
        smashed = false;
    }

    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        double y = me.getY();
        double vy = me.getDeltaMovement().y;

        // zameen / paani / elytra: reset, aap khud khelo
        if (me.onGround() || me.isInWater() || me.isFallFlying()) {
            axeDone = false;
            smashed = false;
            peakY = y;
            return;
        }

        // upar jaa rahe ho: peak track karo, har naye uchhal par sab reset, aim nahi
        if (vy > 0.05) {
            axeDone = false;
            smashed = false;
            peakY = y;
            return;
        }
        if (y > peakY) peakY = y;

        if (target == null || smashed) return;

        double fallen = Math.max(peakY - y, me.fallDistance);

        // chhota jump: aim nahi
        if (fallen < AIM_FALL) return;

        if (Math.sqrt(me.distanceToSqr(target)) > AIM_RANGE) return;

        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());

        // enemy pe aim
        KitManager.aimAt(me, target.getBoundingBox().getCenter());

        // shield: pehle axe se turant hit (stun slam)
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

        // mace haath me
        int maceIdx = KitManager.findBest(me, MacePvp::isMace, KitManager::zeroScore);
        if (maceIdx >= 0 && !isMace(me.getMainHandItem())) {
            KitManager.equip(mc, maceIdx);
            return;
        }

        // mace smash
        if (dist <= REACH && fallen > SMASH_FALL) {
            mc.gameMode.attack(me, target);
            me.swing(InteractionHand.MAIN_HAND);
            smashed = true;
        }
    }
    }
