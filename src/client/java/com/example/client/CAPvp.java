package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/** Crystal + Anchor assist: sirf wahi jo crosshair par ho. Baaki sab aap khud. */
public class CAPvp {
    static final int CRYSTAL_CD = 2;
    static final int CHARGE_CD = 3;
    static final int BLAST_CD = 4;

    private int cd = 0;

    static boolean isGlowstone(ItemStack s) { return s.is(Items.GLOWSTONE); }

    boolean matches(Minecraft mc) {
        return true; // koi item shart nahi
    }

    void onActivate(Minecraft mc) { cd = 0; }

    void onDeactivate(Minecraft mc) { cd = 0; }

    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (cd > 0) {
            cd--;
            return;
        }
        HitResult hr = mc.hitResult;
        if (hr == null) return;

        // crystal crosshair par -> sirf wahi todo
        if (hr instanceof EntityHitResult ehr && ehr.getEntity() instanceof EndCrystal crystal) {
            mc.gameMode.attack(me, crystal);
            me.swing(InteractionHand.MAIN_HAND);
            cd = CRYSTAL_CD;
            return;
        }

        // anchor crosshair par
        if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
            Level lvl = mc.level;
            if (lvl.dimension() == Level.NETHER) return;
            BlockPos pos = bhr.getBlockPos();
            BlockState st = lvl.getBlockState(pos);
            if (!st.is(Blocks.RESPAWN_ANCHOR)) return;

            int charge = st.getValue(RespawnAnchorBlock.CHARGE);
            if (charge == 0) {
                int g = KitManager.findBest(me, CAPvp::isGlowstone, KitManager::zeroScore);
                if (g < 0) return;
                KitManager.equip(mc, g);
                mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, bhr);
                me.swing(InteractionHand.MAIN_HAND);
                cd = CHARGE_CD;
            } else {
                selectNonGlowstone(me);
                mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, bhr);
                me.swing(InteractionHand.MAIN_HAND);
                cd = BLAST_CD;
            }
        }
    }

    /** glowstone ke alawa koi slot: pehle sword, warna pehla non-glowstone slot. */
    private void selectNonGlowstone(LocalPlayer me) {
        Inventory inv = me.getInventory();
        int s = KitManager.findBest(me, KitManager::isSword, KitManager::swordScore);
        if (s >= 0 && s < 9) {
            inv.selected = s;
            return;
        }
        for (int i = 0; i < 9; i++) {
            if (!isGlowstone(inv.getItem(i))) {
                inv.selected = i;
                return;
            }
        }
    }
          }
