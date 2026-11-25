package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/** Perfect Aim: sirf aim enemy par lock. Baaki sab (movement, hit, hotbar) aap khud karo. */
public class PerfectAimPvp {
    // ---- settings (yahan se badal sakte ho) ----
    static final double AIM_RANGE = 30.0;   // enemy itni door tak ho to aim lock

    boolean matches(Minecraft mc) { return true; }

    void onActivate(Minecraft mc) { }

    void onDeactivate(Minecraft mc) { }

    void tick(Minecraft mc, Player target) {
        if (target == null || mc.screen != null) return;
        if (mc.player.distanceTo(target) > AIM_RANGE) return;
        KitManager.aimAt(mc.player, target.getBoundingBox().getCenter());
    }
                    }
