package com.snakepvp;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

public class KitManager implements ClientModInitializer {
    static final double RANGE = 100.0;
    static final boolean SHOW_MESSAGES = false;   // true = upar "PvP ON/OFF" text dikhega

    /** Ek PvP mode. Naya PvP add karna ho to onInitializeClient me ek MODES.add line badhao. */
    record PvpMode(String name,
                   Predicate<Minecraft> matches,
                   Consumer<Minecraft> onActivate,
                   Consumer<Minecraft> onDeactivate,
                   BiConsumer<Minecraft, Player> tick,
                   String missingMsg) { }

    static final List<PvpMode> MODES = new ArrayList<>();

    KeyMapping toggleKey;
    KeyMapping menuKey;
    PvpMode active = null;
    static Player locked = null;

    @Override
    public void onInitializeClient() {
        FriendList.load();

        SwordPvp sword = new SwordPvp();
        NethpotPvp nethpot = new NethpotPvp();
        MacePvp mace = new MacePvp();
        TntMinecartPvp tnt = new TntMinecartPvp();
        ElytraMacePvp elytra = new ElytraMacePvp();
        CAPvp cap = new CAPvp();
        PerfectAimPvp aim = new PerfectAimPvp();
        BreachSwapPvp breach = new BreachSwapPvp();

        MODES.clear();
        MODES.add(new PvpMode("SWORD", sword::matches, sword::onActivate, sword::onDeactivate,
                sword::tick, "Inventory me sword nahi hai"));
        MODES.add(new PvpMode("NETHPOT", nethpot::matches, nethpot::onActivate, nethpot::onDeactivate,
                nethpot::tick, "Inventory me sword nahi hai"));
        MODES.add(new PvpMode("MACE", mace::matches, mace::onActivate, mace::onDeactivate,
                mace::tick, "Inventory me mace nahi hai"));
        MODES.add(new PvpMode("TNT_MINECART", tnt::matches, tnt::onActivate, tnt::onDeactivate,
                tnt::tick, "TNT minecart aur bow ya crossbow chahiye"));
        MODES.add(new PvpMode("ELYTRA_MACE", elytra::matches, elytra::onActivate, elytra::onDeactivate,
                elytra::tick, "Elytra, chestplate ya mace nahi hai"));
        MODES.add(new PvpMode("CAPVP", cap::matches, cap::onActivate, cap::onDeactivate,
                cap::tick, "CAPVP start nahi hua"));
        MODES.add(new PvpMode("PERFECT_AIM", aim::matches, aim::onActivate, aim::onDeactivate,
                aim::tick, "Perfect aim start nahi hua"));
        MODES.add(new PvpMode("BREACH_SWAP", breach::matches, breach::onActivate, breach::onDeactivate,
                breach::tick, "Sword ya hotbar me Breach mace nahi hai"));

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.snakepvp.toggle", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Z, "category.snakepvp"));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.snakepvp.menu", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_C, "category.snakepvp"));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            active = null;
            locked = null;
            return;
        }

        while (menuKey.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new FriendList.Menu());
        }
        if (mc.screen != null) {
            if (active != null) stop(mc, false);
            return;
        }

        while (toggleKey.consumeClick()) {
            if (active != null) stop(mc, true);
            else start(mc);
        }
        if (active == null) return;

        if (!mc.player.isAlive()) {
            stop(mc, false);
            return;
        }

        Player target = findTarget(mc);
        active.tick().accept(mc, target);
    }

    void start(Minecraft mc) {
        String sel = FriendList.selectedPvp;
        if (sel == null) {
            msg(mc, "Pehle C menu me PvP select karo");
            return;
        }
        PvpMode found = null;
        for (PvpMode m : MODES) {
            if (m.name().equals(sel)) { found = m; break; }
        }
        if (found == null) {
            msg(mc, "Ye PvP mod me nahi hai: " + sel);
            return;
        }
        if (!found.matches().test(mc)) {
            msg(mc, found.missingMsg());
            return;
        }
        locked = null;
        active = found;
        found.onActivate().accept(mc);
        msg(mc, "PvP ON: " + sel);
    }

    void stop(Minecraft mc, boolean show) {
        if (active != null) active.onDeactivate().accept(mc);
        active = null;
        locked = null;
        if (show) msg(mc, "PvP: OFF");
    }

    /** Upar ka text. SHOW_MESSAGES false ho to kuch nahi dikhta. */
    static void msg(Minecraft mc, String text) {
        if (!SHOW_MESSAGES) return;
        mc.player.displayClientMessage(Component.literal(text), true);
    }

    // ---------------------------------------------------------------- TARGET (lock)
    Player findTarget(Minecraft mc) {
        if (locked != null && validTarget(mc, locked)) return locked;
        locked = null;

        Player me = mc.player;
        Player best = null;
        double bestD = RANGE * RANGE;
        for (Player p : mc.level.players()) {
            if (!validTarget(mc, p)) continue;
            double d = p.distanceToSqr(me);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        locked = best;
        return best;
    }

    static boolean validTarget(Minecraft mc, Player p) {
        Player me = mc.player;
        if (p == me || p.isSpectator() || !p.isAlive() || p.isRemoved()) return false;
        if (FriendList.isSafe(p.getName().getString())) return false;
        return p.distanceToSqr(me) <= RANGE * RANGE;
                                       }
        // ---------------------------------------------------------------- INVENTORY HELPERS
    static boolean isSword(ItemStack s) { return s.is(ItemTags.SWORDS); }
    static boolean isAxe(ItemStack s) { return s.is(ItemTags.AXES); }
    static boolean isWeb(ItemStack s) { return s.is(Items.COBWEB); }

    static int swordScore(ItemStack s) {
        if (s.is(Items.NETHERITE_SWORD)) return 6;
        if (s.is(Items.DIAMOND_SWORD)) return 5;
        if (s.is(Items.IRON_SWORD)) return 4;
        if (s.is(Items.STONE_SWORD)) return 3;
        if (s.is(Items.GOLDEN_SWORD)) return 2;
        return 1;
    }

    static int zeroScore(ItemStack s) { return 0; }

    static boolean hasFoodComponent(ItemStack s) {
        return s.get(DataComponents.FOOD) != null;
    }

    static boolean isFood(Player p, ItemStack s) {
        FoodProperties f = s.get(DataComponents.FOOD);
        if (f == null) return false;
        if (s.is(Items.ROTTEN_FLESH) || s.is(Items.SPIDER_EYE) || s.is(Items.POISONOUS_POTATO)
                || s.is(Items.PUFFERFISH) || s.is(Items.CHORUS_FRUIT)) return false;
        return p.canEat(f.canAlwaysEat());
    }

    static int foodScore(ItemStack s) {
        if (s.is(Items.ENCHANTED_GOLDEN_APPLE)) return 110;
        if (s.is(Items.GOLDEN_APPLE)) return 100;
        FoodProperties f = s.get(DataComponents.FOOD);
        return f == null ? 0 : f.nutrition();
    }

    /** Poori inventory me best item ka index (0-35) ya -1. Tie me hotbar ko preference. */
    static int findBest(Player p, Predicate<ItemStack> filter, ToIntFunction<ItemStack> score) {
        Inventory inv = p.getInventory();
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !filter.test(s)) continue;
            int sc = score.applyAsInt(s) * 2 + (i < 9 ? 1 : 0);
            if (sc > bestScore) {
                bestScore = sc;
                best = i;
            }
        }
        return best;
    }

    /** Item ko hotbar me laake hath me pakadta hai. */
    static boolean equip(Minecraft mc, int idx) {
        if (idx < 0 || mc.player == null || mc.gameMode == null) return false;
        Inventory inv = mc.player.getInventory();
        if (idx < 9) {
            inv.selected = idx;
            return true;
        }
        int slot = freeHotbarSlot(inv);
        mc.gameMode.handleInventoryMouseClick(
                mc.player.inventoryMenu.containerId, idx, slot, ClickType.SWAP, mc.player);
        inv.selected = slot;
        return true;
    }

    static boolean isKeeper(ItemStack s) {
        return isSword(s) || isAxe(s) || hasFoodComponent(s) || s.is(Items.MACE)
                || s.is(Items.WIND_CHARGE) || s.is(Items.ELYTRA) || s.is(Items.ENDER_PEARL)
                || s.is(Items.FIREWORK_ROCKET) || s.is(Items.SHIELD) || s.is(Items.TOTEM_OF_UNDYING)
                || s.is(Items.BOW) || s.is(Items.CROSSBOW) || s.is(Items.FISHING_ROD);
    }

    static int freeHotbarSlot(Inventory inv) {
        for (int i = 0; i < 9; i++) if (inv.getItem(i).isEmpty()) return i;
        for (int i = 8; i >= 0; i--) {
            if (!isKeeper(inv.getItem(i))) return i;
        }
        return 8;
    }

    // ---------------------------------------------------------------- MOVE / AIM HELPERS
    static void setRotation(Player me, float yaw, float pitch) {
        pitch = Mth.clamp(pitch, -90f, 90f);
        me.setYRot(yaw);
        me.setXRot(pitch);
        me.setYHeadRot(yaw);
        me.yRotO = yaw;
        me.xRotO = pitch;
    }

    static void aimAt(Player me, Vec3 p) {
        Vec3 eye = me.getEyePosition();
        double dx = p.x - eye.x, dy = p.y - eye.y, dz = p.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        setRotation(me, yaw, pitch);
    }

    static void stopMovement(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    static void releaseAll(Minecraft mc) {
        stopMovement(mc);
        mc.options.keyUse.setDown(false);
        mc.options.keyAttack.setDown(false);
    }

    static boolean isWebBlock(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.COBWEB);
    }

    static boolean inWeb(Level level, Player me) {
        BlockPos feet = BlockPos.containing(me.getX(), me.getY(), me.getZ());
        return isWebBlock(level, feet) || isWebBlock(level, feet.above());
    }

    /** (wx, wz) direction me aage cobweb hai? */
    static boolean webAhead(Level level, Player me, double wx, double wz) {
        double len = Math.sqrt(wx * wx + wz * wz);
        if (len < 1e-6) return false;
        wx /= len;
        wz /= len;
        int y = Mth.floor(me.getY());
        for (double step : new double[] { 0.7, 1.4 }) {
            int bx = Mth.floor(me.getX() + wx * step);
            int bz = Mth.floor(me.getZ() + wz * step);
            if (isWebBlock(level, new BlockPos(bx, y, bz)) || isWebBlock(level, new BlockPos(bx, y + 1, bz)))
                return true;
        }
        return false;
    }

    /** Point se hitbox tak ki doori (reach check). */
    static double boxDist(Vec3 p, AABB b) {
        double dx = Math.max(Math.max(b.minX - p.x, 0), p.x - b.maxX);
        double dy = Math.max(Math.max(b.minY - p.y, 0), p.y - b.maxY);
        double dz = Math.max(Math.max(b.minZ - p.z, 0), p.z - b.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
            }
