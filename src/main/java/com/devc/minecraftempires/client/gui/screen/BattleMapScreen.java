package com.devc.minecraftempires.client.gui.screen;

import com.devc.minecraftempires.client.ClientNetworking;
import com.devc.minecraftempires.client.gui.widget.BattleGridWidget;
import com.devc.minecraftempires.client.map.ClientBattleData;
import com.devc.minecraftempires.client.map.ClientMapData;
import com.devc.minecraftempires.network.packet.BattleCommandPayload;
import com.devc.minecraftempires.network.packet.RequestSpectatePayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;
import java.util.UUID;

/** Initial tactical controls; the server always validates side ownership and combat state. */
public final class BattleMapScreen extends Screen {
    private final UUID battleId, attackerArmyId, defenderArmyId;
    private BattleGridWidget grid;
    private int panelWidth, formationIndex;
    private boolean registered;
    private Button formation, withdraw;
    private static final String[] FORMATIONS = {"LINE", "TRIPLEX", "WEDGE"};
    public BattleMapScreen(UUID battleId, UUID attackerArmyId, UUID defenderArmyId) {
        super(Component.literal("Tactical Battle"));
        this.battleId = battleId; this.attackerArmyId = attackerArmyId; this.defenderArmyId = defenderArmyId;
    }
    public UUID getBattleId() { return battleId; }
    @Override protected void init() {
        panelWidth = Math.clamp(width / 3, 110, 150);
        grid = new BattleGridWidget(panelWidth, 24, width - panelWidth, height - 45, battleId, attackerArmyId, defenderArmyId);
        if (!registered) {
            registered = true;
            ClientPacketDistributor.sendToServer(new RequestSpectatePayload(battleId));
        }
        formation = addRenderableWidget(Button.builder(Component.literal("Formation: Line"), b -> {
            var data = ClientBattleData.get();
            var owned = data.cohorts().values().stream().filter(data::canControl).findFirst().orElse(null);
            if (owned == null) return;
            String name = FORMATIONS[formationIndex++ % FORMATIONS.length];
            b.setMessage(Component.literal(name));
            send(owned.cohortId, name);
        }).bounds(6, height - 112, panelWidth - 12, 20).build());
        withdraw = addRenderableWidget(Button.builder(Component.literal("Withdraw selected"), b -> {
            var data = ClientBattleData.get();
            for (UUID id : grid.getSelectedCohortIds()) if (data.canControl(data.cohorts().get(id))) send(id, "WITHDRAW");
        }).bounds(6, height - 90, panelWidth - 12, 20).build());
        addRenderableWidget(Button.builder(ClientMapData.terrainViewButton(true), b -> {
            ClientMapData.cycleTerrainView(true);
            b.setMessage(ClientMapData.terrainViewButton(true));
        }).bounds(6, height - 136, panelWidth - 12, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Auto-resolve"), b -> onClose())
                .tooltip(Tooltip.create(Component.literal("Leave this battle. It resolves from the current troops once all viewers leave for 10 seconds.")))
                .bounds(6, height - 68, panelWidth - 12, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(6, height - 46, panelWidth - 12, 20).build());
    }
    private void send(UUID cohort, String action) {
        ClientPacketDistributor.sendToServer(new BattleCommandPayload(battleId, cohort, 0, 0, true, action));
    }
    @Override public void tick() {
        var data = ClientBattleData.get();
        formation.active = battleId.equals(data.battleId()) && data.isDeploymentPhase() && (data.controlsAttacker() || data.controlsDefender());
        withdraw.active = battleId.equals(data.battleId()) && !data.hasEnded() && !data.isDeploymentPhase()
                && grid.getSelectedCohortIds().stream().map(data.cohorts()::get).anyMatch(c -> data.canControl(c) && !c.isRouting && !c.isWithdrawing && c.currentHealth > 0 && !c.escaped);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF0D1218);
        graphics.fill(panelWidth - 1, 0, panelWidth, height - 20, 0xFF657381);
        var data = ClientBattleData.get();
        int attackers = data.cohorts().values().stream().filter(c -> c.isAttacker).mapToInt(c -> c.currentHealth).sum();
        int defenders = data.cohorts().values().stream().filter(c -> !c.isAttacker).mapToInt(c -> c.currentHealth).sum();
        graphics.text(font, Component.literal("Unit details"), 7, 9, 0xFFFFD45A);
        graphics.centeredText(font, Component.literal("Attacker " + attackers + "  |  Defender " + defenders),
                panelWidth + (width - panelWidth) / 2, 8, 0xFFF3F5F7);
        if (grid != null) grid.render(graphics, font, mouseX, mouseY, partialTick);
        var cohort = grid == null ? null : data.cohorts().get(grid.getFirstSelectedCohortId());
        if (cohort == null) {
            text(graphics, "Select a formation", 30, 0xFFAAB4BE);
            text(graphics, "on the battlefield.", 42, 0xFFAAB4BE);
        } else {
            int y = 28;
            text(graphics, cohort.type, y, cohort.isAttacker ? 0xFF7799EE : 0xFFEE6666); y += 12;
            text(graphics, "Soldiers: " + cohort.currentHealth + "/" + cohort.maxHealth, y, 0xFFF3F5F7); y += 12;
            text(graphics, "Health: " + Math.round(100.0 * cohort.currentHealth / Math.max(1, cohort.maxHealth)) + "%", y, 0xFFF3F5F7); y += 12;
            text(graphics, "Morale: " + cohort.morale, y, 0xFFF3F5F7); y += 12;
            if (height >= 265) {
                text(graphics, "Strength: " + cohort.strength, y, 0xFFAAB4BE); y += 12;
                text(graphics, "Endurance: " + cohort.endurance, y, 0xFFAAB4BE); y += 12;
                text(graphics, "Speed: " + Math.round(cohort.speed * 50), y, 0xFFAAB4BE); y += 12;
            }
            String status = cohort.currentHealth == 0 ? "Destroyed" : cohort.escaped ? "Retreated" : cohort.isRouting ? "Routing" : cohort.isWithdrawing ? "Withdrawing" : cohort.isEngaged ? "Engaged" : "Ready";
            text(graphics, status, y, cohort.isRouting ? 0xFFFF6666 : 0xFFFFD45A);
        }
        String hint = width >= 590 ? "Left: select | Shift: multi-select/queue | Right: order | Drag: pan | Wheel: zoom | R: reset"
                : "Right-click: order | Shift: queue | R: reset";
        graphics.text(font, Component.literal(hint), 6, height - 14, 0xFFAAB4BE);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
    private void text(GuiGraphicsExtractor graphics, String value, int y, int color) {
        if (y < height - 139) graphics.text(font, Component.literal(value), 7, y, color);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        return grid != null && grid.mouseClicked(event.x(), event.y(), event.button(), minecraft.options.keyShift.isDown());
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        return grid != null && grid.mouseReleased(event.button()) || super.mouseReleased(event);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return grid != null && grid.mouseDragged(dx, dy, event.button()) || super.mouseDragged(event, dx, dy);
    }
    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        return grid != null && grid.mouseScrolled(x, y, sy) || super.mouseScrolled(x, y, sx, sy);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && grid != null && !grid.getSelectedCohortIds().isEmpty()) {
            grid.clearSelection(); return true;
        }
        if (event.key() == GLFW.GLFW_KEY_R && grid != null) { grid.resetView(); return true; }
        return super.keyPressed(event);
    }
    @Override public void removed() {
        if (registered) ClientNetworking.leaveSpectate(battleId);
        registered = false;
        ClientBattleData.clear();
        super.removed();
    }
    @Override public boolean isPauseScreen() { return false; }
}
