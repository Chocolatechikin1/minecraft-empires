package com.devc.minecraftempires.client.gui.screen;

import com.devc.minecraftempires.client.ClientNetworking;
import com.devc.minecraftempires.client.map.ClientManagementData;
import com.devc.minecraftempires.network.packet.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.*;

/** Cohort roster, army composition, auxiliary levy and physical regrouping. */
public final class ComposeArmyScreen extends Screen {
    private final UUID sourceLegionId;
    private final Set<UUID> selected = new LinkedHashSet<>();
    private UUID detail;
    private int page, ticks, dataVersion = -1, settlementIndex;
    private boolean allLegions, confirmDisband;
    public ComposeArmyScreen(UUID sourceLegionId) { super(Component.literal("Legion and Cohorts")); this.sourceLegionId = sourceLegionId; }
    private ManagementDataPayload data() { return ClientManagementData.get(); }
    private List<ManagementDataPayload.CohortEntry> roster() {
        return data().cohorts().stream().filter(c -> allLegions || c.legionId().equals(sourceLegionId)).toList();
    }
    private int rows() { return Math.max(1, (height - 202) / 23); }
    private void rebuild() { clearWidgets(); init(); }
    private Button button(String label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, 20).build());
    }
    private void action(String name) {
        ClientPacketDistributor.sendToServer(new ArmyActionPayload(name, sourceLegionId, BlockPos.ZERO));
        ClientNetworking.requestMapData();
    }
    @Override protected void init() {
        dataVersion = ClientManagementData.version();
        button(allLegions ? "All legions" : "This legion", 12, 31, 95, () -> { allLegions = !allLegions; page = 0; rebuild(); });
        button("Select available", 111, 31, 105, () -> { roster().stream().filter(ManagementDataPayload.CohortEntry::available).forEach(c -> selected.add(c.id())); rebuild(); });
        button("Clear", 220, 31, 58, () -> { selected.clear(); rebuild(); });
        var roster = roster();
        page = Math.min(page, Math.max(0, (roster.size() - 1) / rows()));
        for (int i = page * rows(); i < Math.min(roster.size(), (page + 1) * rows()); i++) {
            var c = roster.get(i);
            String status = c.settlementId() != null ? "Garrison" : c.armyId() != null ? "Deployed" : c.soldiers() == 0 ? "Lost" : "Available";
            button((selected.contains(c.id()) ? "[x] " : "[ ] ") + c.label() + " " + c.soldiers() + "/" + c.maximum() + " | " + status,
                    12, 58 + (i % rows()) * 23, width - 24, () -> {
                        detail = c.id();
                        if (c.soldiers() > 0 && !selected.remove(c.id())) selected.add(c.id());
                        rebuild();
                    });
        }
        int w = (width - 32) / 3;
        button("Compose army", 12, height - 98, w, this::compose);
        button("Light infantry: 100", 16 + w, height - 98, w, () -> action("RECRUIT_AUXILIARY"));
        button("Regroup legion", 20 + 2 * w, height - 98, w, () -> action("REGROUP"));
        var settlements = data().settlements();
        if (!settlements.isEmpty()) {
            settlementIndex %= settlements.size();
            var s = settlements.get(settlementIndex);
            button("At: " + EmpireManagementScreen.shorten(s.name(), 14), 12, height - 74, w, () -> { settlementIndex++; rebuild(); });
            button("Garrison selected", 16 + w, height - 74, w, () -> garrison(s.id(), false));
        }
        button("Release garrison", 20 + 2 * w, height - 74, w, () -> garrison(null, true));
        if (page > 0) button("<", 12, height - 49, 30, () -> { page--; rebuild(); });
        if ((page + 1) * rows() < roster.size()) button(">", 46, height - 49, 30, () -> { page++; rebuild(); });
        button(confirmDisband ? "Confirm dissolution" : "Dissolve legion", width - 148, height - 49, 136, () -> {
            if (!confirmDisband) { confirmDisband = true; rebuild(); return; }
            ClientPacketDistributor.sendToServer(new DisbandArmyPayload(sourceLegionId));
            minecraft.gui.setScreen(new EmpireManagementScreen());
        });
        button("Back", width / 2 - 45, height - 25, 90, () -> minecraft.gui.setScreen(new EmpireManagementScreen()));
    }
    private void compose() {
        var cohorts = data().cohorts().stream().filter(c -> selected.contains(c.id()) && c.available()).toList();
        if (cohorts.isEmpty()) return;
        ClientPacketDistributor.sendToServer(new ComposeArmyPayload(cohorts.stream().map(ManagementDataPayload.CohortEntry::id).toList(), cohorts.getFirst().position()));
        selected.clear();
        ClientNetworking.requestMapData();
    }
    private void garrison(UUID settlement, boolean release) {
        Map<UUID, List<UUID>> groups = new LinkedHashMap<>();
        for (var c : data().cohorts()) {
            if (!selected.contains(c.id())) continue;
            UUID target = release ? c.settlementId() : settlement;
            if (target != null) groups.computeIfAbsent(target, id -> new ArrayList<>()).add(c.id());
        }
        groups.forEach((target, ids) -> ClientPacketDistributor.sendToServer(new GarrisonCohortPayload(ids, target, release)));
        selected.clear();
        ClientNetworking.requestMapData();
    }
    @Override public void tick() {
        if (++ticks % 20 == 0) ClientNetworking.requestMapData();
        if (dataVersion != ClientManagementData.version()) rebuild();
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        g.fill(0, 0, width, height, 0xF0101820);
        g.centeredText(font, title, width / 2, 10, 0xFFFFD45A);
        var c = data().cohorts().stream().filter(entry -> entry.id().equals(detail)).findFirst().orElse(null);
        String first = c == null ? "Select nearby cohorts to form an army or garrison." : "Health " + c.health() + "% | Morale " + c.morale() + " | XP " + c.xp() + " | " + c.position().getX() + ", " + c.position().getZ();
        String second = c == null ? "Use one cohort for a detachment. Regroup requires travel." : "Endurance " + c.endurance() + " | Strength " + c.strength() + " | Speed " + c.speed() + " | " + c.gear();
        g.centeredText(font, Component.literal(first), width / 2, height - 137, 0xFFCCD5DE);
        g.centeredText(font, Component.literal(second), width / 2, height - 123, 0xFFCCD5DE);
        super.extractRenderState(g, x, y, tick);
    }
    @Override public boolean isPauseScreen() { return false; }
}
