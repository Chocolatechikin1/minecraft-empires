package com.devc.minecraftempires.client.gui.screen;

import com.devc.minecraftempires.client.ClientNetworking;
import com.devc.minecraftempires.client.map.ClientManagementData;
import com.devc.minecraftempires.network.packet.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.UUID;

/** A small, server-authoritative management hub for the first playable UI pass. */
public final class EmpireManagementScreen extends Screen {
    private static final UUID NONE = new UUID(0, 0);
    private int tab, page, settlementIndex, ticks, dataVersion = -1;
    private UUID selected;
    private final MapScreen mapScreen;
    public EmpireManagementScreen() { this(new MapScreen()); }
    public EmpireManagementScreen(MapScreen mapScreen) {
        super(Component.literal("Empire Management"));
        this.mapScreen = mapScreen;
    }
    private ManagementDataPayload data() { return ClientManagementData.get(); }
    private int rows() { return Math.max(1, (height - 184) / 24); }
    private void rebuild() { clearWidgets(); init(); }
    private Button button(String label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, 20).build());
    }
    private void stateAction(String action, UUID target, String text) {
        ClientPacketDistributor.sendToServer(new StateActionPayload(action, target == null ? NONE : target, BlockPos.ZERO, text));
        ClientNetworking.requestMapData();
    }
    @Override protected void init() {
        dataVersion = ClientManagementData.version();
        String[] tabs = {"State", "Settlements", "Legions", "Diplomacy"};
        int tabWidth = (width - 46) / 4;
        for (int i = 0; i < tabs.length; i++) {
            final int next = i;
            button((tab == i ? "[" : "") + tabs[i] + (tab == i ? "]" : ""), 20 + i * (tabWidth + 2), 72, tabWidth,
                    () -> { tab = next; page = 0; selected = null; rebuild(); });
        }
        button("Map", 20, height - 25, 70, () -> minecraft.gui.setScreen(mapScreen));
        button("Close", width - 90, height - 25, 70, this::onClose);
        if (data().stateId() == null) return;
        switch (tab) {
            case 0 -> {
                int w = (width - 46) / 2;
                button("Deposit 64 emeralds", 20, 105, w, () -> stateAction("DEPOSIT", null, "64"));
                button("Deposit all emeralds", 26 + w, 105, w, () -> stateAction("DEPOSIT", null, "all"));
                // Logistics entry points live with state infrastructure; their work still takes place on the map.
                button("View Roads", 20, 133, w, () -> {
                    minecraft.gui.setScreen(mapScreen);
                    mapScreen.startRoadPlanning();
                }).setTooltip(Tooltip.create(Component.literal("Return to the map to draw, purchase or remove roads.")));
                button(mapScreen.isSupplyRouteVisible() ? "Hide Supply Lines" : "Show Supply Lines", 26 + w, 133, w, () -> {
                    minecraft.gui.setScreen(mapScreen);
                    mapScreen.toggleSupplyRoute();
                }).setTooltip(Tooltip.create(Component.literal("Return to the map and select an army to inspect its supply route.")));
                button("View Bridges", 20, 161, w, () -> {
                    minecraft.gui.setScreen(mapScreen);
                    mapScreen.startBridgePlanning();
                }).setTooltip(Tooltip.create(Component.literal("Return to the map and choose two banks for a three-block-wide bridge.")));
                if (data().endgameChoice()) {
                    button("Choose government", 26 + w, 161, w, () -> minecraft.gui.setScreen(new GovernmentChoiceScreen()));
                }
            }
            case 1 -> settlements();
            case 2 -> legions();
            case 3 -> diplomacy();
            default -> { }
        }
    }
    private void settlements() {
        var entries = data().settlements();
        clampPage(entries.size());
        for (int i = page * rows(); i < Math.min(entries.size(), (page + 1) * rows()); i++) {
            var s = entries.get(i);
            button(shorten(s.name(), 25) + " | " + s.tierLabel() + " | Pop. " + s.population(), 20, 101 + (i % rows()) * 24, width - 40,
                    () -> minecraft.gui.setScreen(new SettlementManagementScreen(s.id(), s.name(), s.position())));
        }
        pagination(entries.size());
    }
    private void legions() {
        var entries = data().legions();
        clampPage(entries.size());
        for (int i = page * rows(); i < Math.min(entries.size(), (page + 1) * rows()); i++) {
            var l = entries.get(i);
            var body = com.devc.minecraftempires.client.map.ClientArmyData.get().legionsById().get(l.id());
            String location = body == null ? "Assigned / detached" : "Field body: " + net.minecraft.world.level.ChunkPos.unpack(body.packedChunkPos()).x() * 16
                    + ", " + net.minecraft.world.level.ChunkPos.unpack(body.packedChunkPos()).z() * 16;
            button("Legion " + (i + 1) + " | " + l.soldiers() + " soldiers | " + location,
                    20, 101 + (i % rows()) * 24, width - 40, () -> minecraft.gui.setScreen(new ComposeArmyScreen(l.id())));
        }
        var settlements = data().settlements();
        if (!settlements.isEmpty()) {
            settlementIndex %= settlements.size();
            var s = settlements.get(settlementIndex);
            int w = (width - 46) / 2;
            button("At: " + shorten(s.name(), 17), 20, height - 77, w, () -> { settlementIndex++; rebuild(); });
            button(data().freeLegion() ? "Raise free legion" : "Raise legion: 5,000", 26 + w, height - 77, w, () -> {
                ClientPacketDistributor.sendToServer(new ArmyActionPayload("RECRUIT_LEGION", s.id(), s.position()));
                ClientNetworking.requestMapData();
            });
        }
        pagination(entries.size());
    }
    private void diplomacy() {
        var entries = data().relations();
        clampPage(entries.size());
        for (int i = page * rows(); i < Math.min(entries.size(), (page + 1) * rows()); i++) {
            var r = entries.get(i);
            button((r.id().equals(selected) ? "> " : "") + shorten(r.name(), 25) + " | " + (r.war() ? "War" : r.passageReceived() ? "Passage allowed" : "Peace"),
                    20, 101 + (i % rows()) * 24, width - 40, () -> { selected = r.id(); rebuild(); });
        }
        entries.stream().filter(r -> r.id().equals(selected)).findFirst().ifPresent(r -> {
            int w = (width - 46) / 2;
            button(r.war() ? r.peaceOffered() ? "Accept peace" : "Offer peace" : "Declare war", 20, height - 77, w,
                    () -> stateAction(r.war() ? r.peaceOffered() ? "ACCEPT_PEACE" : "OFFER_PEACE" : "DECLARE_WAR", r.id(), ""));
            if (!r.war()) button(r.passageGranted() ? "Revoke passage" : "Grant passage", 26 + w, height - 77, w,
                    () -> stateAction(r.passageGranted() ? "REVOKE_PASSAGE" : "GRANT_PASSAGE", r.id(), ""));
        });
        pagination(entries.size());
    }
    private void clampPage(int count) { page = Math.min(page, Math.max(0, (count - 1) / rows())); }
    private void pagination(int count) {
        if (page > 0) button("Previous", 20, height - 51, 90, () -> { page--; rebuild(); });
        if ((page + 1) * rows() < count) button("Next", width - 110, height - 51, 90, () -> { page++; rebuild(); });
    }
    @Override public void tick() {
        if (++ticks % 20 == 0) ClientNetworking.requestMapData();
        if (dataVersion != ClientManagementData.version()) rebuild();
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        g.fill(0, 0, width, height, 0xF0101820);
        g.centeredText(font, title, width / 2, 10, 0xFFFFD45A);
        var d = data();
        if (d.stateId() == null) {
            g.centeredText(font, Component.literal("Place a crafted City Altar to found your county."), width / 2, 112, 0xFFFFFFFF);
        } else {
            g.centeredText(font, Component.literal(shorten(d.stateName(), 35) + " | " + d.tier().replace('_', ' ')), width / 2, 27, 0xFFFFFFFF);
            g.centeredText(font, Component.literal("Treasury " + d.treasury() + " | Tax/day " + d.dailyIncome() + " | Upkeep/day " + d.dailyUpkeep()), width / 2, 41, 0xFFCCD5DE);
            g.centeredText(font, Component.literal(d.graceDays() > 0 ? "Upkeep waived: " + d.graceDays() + " days remaining" : "Upkeep is charged daily"), width / 2, 55, 0xFFAACAA0);
            if (tab == 0) g.centeredText(font, Component.literal(d.endgameChoice() ? "Choose your permanent government path." : "Develop settlements to unlock further expansion."), width / 2, 193, 0xFFCCD5DE);
        }
        super.extractRenderState(g, x, y, tick);
    }
    public static String shorten(String s, int limit) { return s.length() > limit ? s.substring(0, limit - 1) + "…" : s; }
    @Override public boolean isPauseScreen() { return false; }
}
