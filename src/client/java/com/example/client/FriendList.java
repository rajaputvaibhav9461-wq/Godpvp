package com.snakepvp;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FriendList {
    static final List<String> names = new ArrayList<>();
    static final List<Boolean> safe = new ArrayList<>();

    // ek time par sirf ek PvP ON. null = koi nahi. Naam KitManager.MODES se aate hain.
    static String selectedPvp = null;

    // ---------------------------------------------------------------- friend data
    static boolean isSafe(String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) return safe.get(i);
        }
        return false;
    }

    static void addName(String name) {
        name = name.trim();
        if (name.isEmpty()) return;
        for (String n : names) if (n.equalsIgnoreCase(name)) return;
        names.add(name);
        safe.add(true);
        save();
    }

    static void removeName(int i) {
        names.remove(i);
        safe.remove(i);
        save();
    }

    static void toggleName(int i) {
        safe.set(i, !safe.get(i));
        save();
    }

    // ---------------------------------------------------------------- save / load
    static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("snakepvp_friends.txt");
    }

    static void load() {
        names.clear();
        safe.clear();
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f)) {
                String[] p = line.split(",");
                if (p.length < 2 || p[0].isBlank()) continue;
                names.add(p[0].trim());
                safe.add(Boolean.parseBoolean(p[1].trim()));
            }
        } catch (IOException ignored) { }
    }

    static void save() {
        try {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) lines.add(names.get(i) + "," + safe.get(i));
            Files.write(file(), lines);
        } catch (IOException ignored) { }
    }

    // ---------------------------------------------------------------- menu (C dabane pe)
    public static class Menu extends Screen {
        private String draft = "";
        private EditBox input;

        public Menu() {
            super(Component.literal("Snake PvP Menu"));
        }

        @Override
        public boolean isPauseScreen() { return false; }

        private int pvpX() { return Math.max(this.width / 2, this.width - 240); }

        @Override
        protected void init() {
            int leftX = 10;
            int px = pvpX();

            input = new EditBox(this.font, leftX, 30, 140, 20, Component.literal("name"));
            input.setValue(draft);
            this.addRenderableWidget(input);
            this.setInitialFocus(input);

            this.addRenderableWidget(Button.builder(Component.literal("Add"), b -> {
                addName(input.getValue());
                draft = "";
                this.rebuildWidgets();
            }).bounds(leftX + 145, 30, 40, 20).build());

            for (int i = 0; i < names.size(); i++) {
                final int idx = i;
                int y = 60 + i * 24;
                this.addRenderableWidget(Button.builder(
                        Component.literal(safe.get(idx) ? "ON" : "OFF"), b -> {
                            draft = input.getValue();
                            toggleName(idx);
                            this.rebuildWidgets();
                        }).bounds(leftX + 100, y, 40, 20).build());
                this.addRenderableWidget(Button.builder(
                        Component.literal("Hata"), b -> {
                            draft = input.getValue();
                            removeName(idx);
                            this.rebuildWidgets();
                        }).bounds(leftX + 145, y, 40, 20).build());
            }

            // PvP buttons: 2 column
            int perCol = (KitManager.MODES.size() + 1) / 2;
            for (int i = 0; i < KitManager.MODES.size(); i++) {
                final String pvp = KitManager.MODES.get(i).name();
                int col = i / perCol;
                int row = i % perCol;
                boolean on = pvp.equals(selectedPvp);
                this.addRenderableWidget(Button.builder(
                        Component.literal((on ? "ON " : "OFF ") + pvp), b -> {
                            draft = input.getValue();
                            selectedPvp = pvp.equals(selectedPvp) ? null : pvp;
                            this.rebuildWidgets();
                        }).bounds(px + col * 118, 60 + row * 24, 115, 20).build());
            }
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            int leftX = 10;
            int px = pvpX();

            g.drawString(this.font, "Friend list (ON = safe, attack nahi)", leftX, 18, 0xFFFFFF);
            for (int i = 0; i < names.size(); i++) {
                g.drawString(this.font, names.get(i), leftX, 66 + i * 24, 0xFFFFFF);
            }

            g.drawString(this.font, "PvP select (ek hi ON)", px, 18, 0xFFFFFF);
            int perCol = (KitManager.MODES.size() + 1) / 2;
            String cur = selectedPvp == null ? "koi nahi" : selectedPvp;
            g.drawString(this.font, "Abhi ON: " + cur, px, 60 + perCol * 24 + 6, 0xFFFF55);
        }
    }
          }
