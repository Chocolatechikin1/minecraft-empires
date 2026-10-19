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
    private int panelWidth;
    private boolean registered;
    private Button formation, withdraw, autoResolve;
    private Button helpButton;
    private final MapScreen.HelpOverlay help = new MapScreen.HelpOverlay();
    private static final String HELP = "Battle controls\n\nLeft-click to select a formation\nShift + left-click to add/remove selection\nRight-click enemy to pursue\nRight-click ground for movement order\nShift + right-click ground to queue a waypoint\nDrag empty ground or middle-drag to pan\nMouse wheel to zoom\nR to reset view\nEscape to clear selection then close\n\nYellow dashed line: accepted route\nBlue dashed line: route being checked\nRed dashed line and brackets: selected enemy target\nNumbered squares: queued destinations\n\nDeployment orders reposition units; combat orders move them.\nTarget orders follow a moving enemy. Ground orders cancel targeting; destroyed or escaped targets clear it. Friendly formations and terrain still block movement.\nClose leaves AI in command; use the normal map's Battles list to return.\nScroll this help panel if needed.";
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
            String current = data.controlsAttacker() ? data.attackerFormation() : data.defenderFormation();
            String name = FORMATIONS[(java.util.Arrays.asList(FORMATIONS).indexOf(current) + 1) % FORMATIONS.length];
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
        autoResolve = addRenderableWidget(Button.builder(Component.literal("Auto-resolve"), b -> {
            var data = ClientBattleData.get();
            data.cohorts().values().stream().filter(data::canControl).findFirst().ifPresent(cohort -> send(cohort.cohortId, "AUTO_RESOLVE"));
            onClose();
        }).tooltip(Tooltip.create(Component.literal("Choose automatic resolution once all viewers leave for 10 seconds. Reopening cancels this request.")))
                .bounds(6, height - 68, panelWidth - 12, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .tooltip(Tooltip.create(Component.literal("The battle continues under AI control. Return through the map's Battles list or eye button.")))
                .bounds(6, height - 46, panelWidth - 12, 20).build());
        helpButton = addRenderableWidget(Button.builder(Component.literal("?"), b -> { help.toggle(); grid.mouseReleased(0); }).bounds(panelWidth - 25, 5, 18, 18)
                .tooltip(Tooltip.create(Component.literal("Battle controls and shortcuts"))).build());
    }
    private void send(UUID cohort, String action) {
        ClientPacketDistributor.sendToServer(new BattleCommandPayload(battleId, cohort, 0, 0, true, action));
    }
    @Override public void tick() {
        var data = ClientBattleData.get();
        formation.setMessage(Component.literal("Formation: " + (data.controlsAttacker() ? data.attackerFormation() : data.defenderFormation()).toLowerCase(java.util.Locale.ROOT)));
        autoResolve.active = battleId.equals(data.battleId()) && !data.hasEnded() && (data.controlsAttacker() || data.controlsDefender());
        formation.active = battleId.equals(data.battleId()) && data.isDeploymentPhase() && (data.controlsAttacker() || data.controlsDefender());
        withdraw.active = battleId.equals(data.battleId()) && !data.hasEnded() && !data.isPreparing() && !data.isDeploymentPhase()
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
        var cohort = grid == null ? null : data.getCohort(grid.getFirstSelectedCohortId());
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
            String status = cohort.currentHealth == 0 ? "Destroyed" : cohort.escaped ? "Retreated" : cohort.isRouting ? "Routing" : cohort.isWithdrawing ? "Withdrawing" : cohort.isEngaged ? "Engaged" : cohort.orderStatus.isEmpty() ? "Ready" : cohort.orderStatus;
            for (var line : font.split(Component.literal(status), panelWidth - 14)) { if (y < height - 139) graphics.text(font, line, 7, y, cohort.isRouting ? 0xFFFF6666 : 0xFFFFD45A); y += 10; }
        }
        if (!data.deploymentMessage().isEmpty()) graphics.text(font, Component.literal(data.deploymentMessage()), panelWidth + 6, height - 14, 0xFFFFD45A);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        help.render(graphics, font, width, height, helpButton, HELP);
    }
    private void text(GuiGraphicsExtractor graphics, String value, int y, int color) {
        if (y < height - 139) graphics.text(font, Component.literal(value), 7, y, color);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (help.click(event, helpButton)) return true;
        if (super.mouseClicked(event, doubleClick)) return true;
        return grid != null && grid.mouseClicked(event.x(), event.y(), event.button(), event.hasShiftDown());
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        return grid != null && grid.mouseReleased(event.button()) || super.mouseReleased(event);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (help.isOpen()) return true;
        return grid != null && grid.mouseDragged(dx, dy, event.button()) || super.mouseDragged(event, dx, dy);
    }
    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (help.scroll(sy)) return true;
        return grid != null && grid.mouseScrolled(x, y, sy) || super.mouseScrolled(x, y, sx, sy);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (help.isOpen()) { if (event.key() == GLFW.GLFW_KEY_ESCAPE) help.close(); else helpButton.keyPressed(event); return true; }
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
