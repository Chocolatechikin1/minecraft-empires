package com.devc.minecraftempires.client.gui.screen;

import com.devc.minecraftempires.client.ClientNetworking;
import com.devc.minecraftempires.client.gui.widget.InteractiveMapWidget;
import com.devc.minecraftempires.client.map.ClientMapData;
import com.devc.minecraftempires.client.map.ClientArmyData;
import com.devc.minecraftempires.client.map.ClientManagementData;
import com.devc.minecraftempires.network.packet.ManagementDataPayload;
import com.devc.minecraftempires.network.packet.StateActionPayload;
import com.devc.minecraftempires.state.StateBalance;
import com.devc.minecraftempires.network.packet.ArmyMapPayload;
import com.devc.minecraftempires.network.packet.DisbandArmyPayload;
import com.devc.minecraftempires.network.packet.ArmyActionPayload;
import net.minecraft.core.BlockPos;
import com.devc.minecraftempires.network.packet.MapDataPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

//primary file to house the map screen, including the map widget and the details panel, which shows information about the selected chunk, settlement, and state
public final class MapScreen extends Screen {
    private static final int PANEL_WIDTH = 205;
    private static final int MARGIN = 8;

    private InteractiveMapWidget mapWidget;
    private int actionButtonY = -1;
    private final SidebarScroll sidebarScroll = new SidebarScroll();
    private String sidebarContext = "";
    private final List<SidebarAction> logisticsActions = new ArrayList<>();
    private UUID requestedSupplyTarget;
    private Object mapWorld;
    private UUID mapViewer;
    private Button terrainViewButton, roadLayerButton, battleListButton, helpButton;
    private final HelpOverlay help = new HelpOverlay();
    private boolean battlesOpen;
    private int battleScroll, battleSelected, battleLeft, battleTop, battleWidth, battleHeight;
    private List<ArmyMapPayload.BattleSummary> displayedBattles = List.of();
    private List<UUID> pendingCohorts;
    private boolean bridgeManagement;

    public MapScreen() {
        this(List.of());
    }
    public MapScreen(List<UUID> cohorts) {
        super(Component.translatable("gui.minecraftempires.map.title"));
        pendingCohorts = List.copyOf(cohorts);
    }

    //sets default map size
    @Override
    protected void init() {
        super.init();
        sidebarScroll.dragging = false;

        int mapWidth = Math.max(80, this.width - PANEL_WIDTH - MARGIN * 2);
        int mapHeight = Math.max(80, this.height - MARGIN * 2);
        // Returning from State management keeps the camera, selection and unfinished road plan.
        if (this.mapWidget == null) this.mapWidget = new InteractiveMapWidget(MARGIN, MARGIN, mapWidth, mapHeight);
        else this.mapWidget.setBounds(MARGIN, MARGIN, mapWidth, mapHeight);
        this.mapWidget.refreshViewport();
        syncSupplySelection();

        var draft = ClientMapData.bridgeDraft();
        if (!mapWidget.isRoadPlanning() && draft != null && Objects.equals(draft.owner(), ClientMapData.get().viewerStateId()))
            mapWidget.restoreBridgePlan(draft.points());

        int panelX = this.width - PANEL_WIDTH + 10;
        int buttonY = this.height - 30;
        int buttonWidth = 56;

        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.minecraftempires.map.reset"),
                button -> this.mapWidget.resetView()
        ).bounds(panelX, buttonY, buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.minecraftempires.map.refresh"),
                button -> ClientNetworking.requestMapData()
        ).bounds(panelX + 62, buttonY, buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.minecraftempires.map.close"),
                button -> this.onClose()
        ).bounds(panelX + 124, buttonY, buttonWidth, 20).build());

        // View selection is local: all three modes reuse the same surveyed terrain.
        // Small vanilla square buttons keep the map visible; tooltips/narration carry the full state.
        int controlsX = MARGIN + mapWidth - 6 - 68;
        terrainViewButton = this.addRenderableWidget(new MapControlButton(controlsX, MARGIN + 6, false, button -> {
            ClientMapData.cycleTerrainView(false);
            updateMapControlLabels();
        }));
        roadLayerButton = this.addRenderableWidget(new MapControlButton(controlsX + 24, MARGIN + 6, true, button -> {
            ClientMapData.toggleLayer(ClientMapData.MapLayer.ROADS);
            updateMapControlLabels();
        }));
        battleListButton = this.addRenderableWidget(new MapControlButton(controlsX + 48, MARGIN + 6, 2, button -> {
            battlesOpen = !battlesOpen; help.close(); this.mapWidget.mouseReleased(0);
            if (battlesOpen) ClientNetworking.requestMapData();
        }));
        battleListButton.setMessage(Component.literal("Active battles"));
        battleListButton.setTooltip(Tooltip.create(Component.literal("Active battles involving your state. Click to open the list.")));
        helpButton = this.addRenderableWidget(Button.builder(Component.literal("?"), button -> { help.toggle(); battlesOpen = false; this.mapWidget.mouseReleased(0); })
                .bounds(this.width - 29, 5, 18, 18).tooltip(Tooltip.create(Component.literal("Map controls and shortcuts"))).build());
        updateMapControlLabels();

        ClientNetworking.requestMapData();
        this.addRenderableWidget(Button.builder(Component.literal("Manage"),
                button -> this.minecraft.gui.setScreen(new EmpireManagementScreen(this)))
                .bounds(panelX, buttonY - 24, 180, 20).build());
        applyPendingCohorts();
    }
    private void applyPendingCohorts() {
        if (!pendingCohorts.isEmpty() && mapWidget != null && ClientMapData.get().hasData()) {
            mapWidget.refreshViewport(); // A delayed first snapshot must initialize before applying the roster selection.
            mapWidget.selectCohorts(pendingCohorts); pendingCohorts = List.of();
        }
    }

    private void updateMapControlLabels() {
        Component view = ClientMapData.terrainViewButton(false);
        terrainViewButton.setMessage(view);
        terrainViewButton.setTooltip(Tooltip.create(view.copy().append(". Click to cycle.")));
        boolean roads = ClientMapData.isLayerVisible(ClientMapData.MapLayer.ROADS);
        String label = "Roads: " + (roads ? "On" : "Off");
        roadLayerButton.setMessage(Component.literal(label));
        roadLayerButton.setTooltip(Tooltip.create(Component.literal(label + ". Click to show or hide roads.")));
    }

    /** Native button frame/focus handling with an item icon or a tiny road glyph, without new assets. */
    private static final class MapControlButton extends Button {
        private final int mode;
        MapControlButton(int x, int y, boolean roads, OnPress action) {
            this(x, y, roads ? 1 : 0, action);
        }
        MapControlButton(int x, int y, int mode, OnPress action) {
            super(x, y, 20, 20, Component.empty(), action, DEFAULT_NARRATION);
            this.mode = mode;
        }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float tick) {
            extractDefaultSprite(g);
            if (mode != 1) {
                g.item((mode == 2 ? Items.IRON_SWORD : Items.MAP).getDefaultInstance(), getX() + 2, getY() + 2);
                return;
            }
            int x = getX(), y = getY();
            boolean shown = ClientMapData.isLayerVisible(ClientMapData.MapLayer.ROADS);
            int edge = shown ? 0xFFF2D8A4 : 0xFF9B9B9B;
            g.fill(x + 6, y + 3, x + 14, y + 17, 0xFF30343B);
            g.fill(x + 5, y + 3, x + 6, y + 17, edge);
            g.fill(x + 14, y + 3, x + 15, y + 17, edge);
            for (int dy = 4; dy < 17; dy += 5) g.fill(x + 9, y + dy, x + 11, y + Math.min(17, dy + 3), edge);
            // A muted road plus slash makes the hidden state visible without a text label.
            if (!shown) for (int d = 0; d < 12; d++) g.fill(x + 4 + d, y + 15 - d, x + 6 + d, y + 17 - d, 0xFFE08779);
        }
    }

    // State-tab actions return to this initialized map instead of creating a fresh view.
    public void startRoadPlanning() { if (mapWidget != null && (!mapWidget.isRoadPlanning() || mapWidget.isBridgePlanning())) mapWidget.setRoadPlanning(true); }
    public void startBridgePlanning() {
        bridgeManagement = false;
        if (mapWidget != null && !mapWidget.isBridgePlanning()) {
            var draft = ClientMapData.bridgeDraft();
            if (draft != null) mapWidget.restoreBridgePlan(draft.points()); else mapWidget.setBridgePlanning();
        }
    }
    public boolean isSupplyRouteVisible() { return mapWidget != null && mapWidget.isSupplyOverlay(); }
    public void toggleSupplyRoute() {
        if (mapWidget != null) { mapWidget.toggleSupplyOverlay(); syncSupplySelection(); }
    }
    private boolean overMapControls(double x, double y) {
        return terrainViewButton != null && terrainViewButton.isMouseOver(x, y)
                || roadLayerButton != null && roadLayerButton.isMouseOver(x, y)
                || battleListButton != null && battleListButton.isMouseOver(x, y) || battlesOpen && battleListContains(x, y);
    }

    @Override
    public void tick() {
        super.tick();
        syncSupplySelection();
        applyPendingCohorts();
        // Request map data every 1 second (20 ticks) to animate army movement
        if (this.minecraft != null && this.minecraft.level != null && this.minecraft.level.getGameTime() % 20 == 0) {
            ClientNetworking.requestMapData();
        }
    }

    //renders the map widget and the details panel, which shows information about the selected chunk, settlement, and state
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        //draw the map widget if it exists
        if (this.mapWidget != null) {
            boolean overControls = help.isOpen() || overMapControls(mouseX, mouseY);
            this.mapWidget.render(graphics, this.font, overControls ? -1 : mouseX, overControls ? -1 : mouseY);
        }

        drawSidebarFrame(graphics);
        syncSidebarContext();
        actionButtonY = -1;
        logisticsActions.clear();
        // Measure with the same wrapping used for painting, then clip above the fixed footer buttons.
        int contentHeight = drawSidebarContents(null, mouseX, mouseY);
        sidebarScroll.update(contentHeight + 4, 34, Math.max(35, this.height - 60));
        graphics.enableScissor(this.width - PANEL_WIDTH + 3, sidebarScroll.top, this.width - 14, sidebarScroll.bottom);
        graphics.pose().pushMatrix();
        graphics.pose().translate(0, (float) (sidebarScroll.top - sidebarScroll.amount));
        try {
            drawSidebarContents(graphics, mouseX, (int) Math.floor(mouseY - sidebarScroll.top + sidebarScroll.amount));
        } finally {
            graphics.pose().popMatrix();
            graphics.disableScissor();
        }
        drawSidebarScrollbar(graphics);
        if (battlesOpen) drawBattleList(graphics, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        help.render(graphics, font, width, height, helpButton, mapHelpText());
    }
    private boolean battleListContains(double x, double y) { return x >= battleLeft && x < battleLeft + battleWidth && y >= battleTop && y < battleTop + battleHeight; }
    private void drawBattleList(GuiGraphicsExtractor g, int mx, int my) {
        displayedBattles = ClientArmyData.get().battles();
        battleWidth = Math.min(300, Math.max(68, width - PANEL_WIDTH - MARGIN * 2 - 12));
        battleLeft = Math.max(MARGIN + 4, battleListButton.getRight() - battleWidth); battleTop = battleListButton.getBottom() + 4;
        battleHeight = Math.max(38, Math.min(height - battleTop - MARGIN - 4, 26 + Math.max(1, displayedBattles.size()) * 40));
        int visible = Math.max(1, (battleHeight - 26) / 40);
        battleSelected = Math.clamp(battleSelected, 0, Math.max(0, displayedBattles.size() - 1));
        battleScroll = Math.clamp(battleScroll, 0, Math.max(0, displayedBattles.size() - visible));
        g.fill(battleLeft - 1, battleTop - 1, battleLeft + battleWidth + 1, battleTop + battleHeight + 1, 0xCC8BA1B5);
        g.fill(battleLeft, battleTop, battleLeft + battleWidth, battleTop + battleHeight, 0xB8121E2A);
        g.text(font, Component.literal("Active battles (" + displayedBattles.size() + ")"), battleLeft + 6, battleTop + 7, 0xFFFFD45A);
        g.enableScissor(battleLeft, battleTop + 23, battleLeft + battleWidth, battleTop + battleHeight - 2);
        if (displayedBattles.isEmpty()) g.text(font, Component.literal("No active battles"), battleLeft + 6, battleTop + 25, 0xFFE2EAF2);
        for (int i = battleScroll; i < Math.min(displayedBattles.size(), battleScroll + visible); i++) {
            var battle = displayedBattles.get(i); int y = battleTop + 25 + (i - battleScroll) * 40, bx = battleLeft + battleWidth - 48;
            g.text(font, Component.literal(font.plainSubstrByWidth(battle.title(), Math.max(16, battleWidth - 62))), battleLeft + 6, y, 0xFFF0F4F8);
            String detail = battle.position().getX() + ", " + battle.position().getZ() + " | " + battle.attackers() + " / " + battle.defenders();
            g.text(font, Component.literal(font.plainSubstrByWidth(detail, Math.max(16, battleWidth - 62))), battleLeft + 6, y + 12, 0xFFB9C9D8);
            boolean hovered = mx >= bx && mx < bx + 42 && my >= y && my < y + 20;
            g.fill(bx, y, bx + 42, y + 20, hovered || i == battleSelected ? 0xFF6986A0 : 0xFF344C63);
            g.centeredText(font, Component.literal("Open"), bx + 21, y + 6, 0xFFFFFFFF);
            if (mx >= battleLeft && mx < bx && my >= y && my < y + 26) g.setTooltipForNextFrame(font, Component.literal(battle.title() + "\n" + battle.phase().toLowerCase(Locale.ROOT) + " | " + detail), mx, my);
        }
        g.disableScissor();
        if (displayedBattles.size() > visible) g.text(font, Component.literal("Scroll for more"), battleLeft + 6, battleTop + battleHeight - 11, 0xFFCEDAE5);
    }
    /** Shared question-mark panel keeps map shortcuts out of the permanent UI, without another screen file. */
    public static final class HelpOverlay {
        private boolean open;
        private int scroll, left, top, panelWidth, panelHeight, contentHeight;
        public void toggle() { open = !open; scroll = 0; }
        public void close() { open = false; }
        public boolean isOpen() { return open; }
        public boolean click(MouseButtonEvent event, Button button) {
            if (!open || button.isMouseOver(event.x(), event.y())) return false;
            if (event.x() < left || event.x() >= left + panelWidth || event.y() < top || event.y() >= top + panelHeight) close();
            return true;
        }
        public boolean scroll(double amount) { if (!open) return false; scroll = Math.clamp(scroll - (int) (amount * 22), 0, Math.max(0, contentHeight - panelHeight + 12)); return true; }
        public void render(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, int width, int height, Button button, String text) {
            if (!open) return;
            panelWidth = Math.min(280, width - 20); left = Math.clamp(button.getRight() - panelWidth, 10, Math.max(10, width - panelWidth - 10)); top = button.getBottom() + 4;
            var lines = font.split(Component.literal(text), panelWidth - 12); contentHeight = lines.size() * 11;
            panelHeight = Math.min(contentHeight + 12, Math.max(24, height - top - 8)); scroll = Math.clamp(scroll, 0, Math.max(0, contentHeight - panelHeight + 12));
            g.fill(left - 1, top - 1, left + panelWidth + 1, top + panelHeight + 1, 0xFFD3BC77);
            g.fill(left, top, left + panelWidth, top + panelHeight, 0xFF17212C);
            g.enableScissor(left, top + 3, left + panelWidth, top + panelHeight - 3);
            int y = top + 6 - scroll; for (var line : lines) { g.text(font, line, left + 6, y, 0xFFE1E7ED); y += 11; }
            g.disableScissor();
        }
    }
    private String mapHelpText() {
        // Right-click hints and the former permanent controls section now live in this panel.
        return "Map controls\n\nLeft-click: select a unit, settlement or chunk\nShift + left-click: select multiple independent cohorts\nRight-click: replace movement orders\nShift + right-click: queue a waypoint\nDrag or middle-drag: pan\nMouse wheel: zoom\nR: reset view\nEscape: cancel planning, clear selection, then close\n\nSelect your units to see their dotted movement paths.\nUse the eye below an engaged army or the Battles list to open its battle map.\n\nRoad/bridge planning: left-click adds points, right-click undoes a point, middle-drag pans. Escape cancels planning.\nScroll this help panel if needed.";
    }

    private void drawSidebarFrame(GuiGraphicsExtractor graphics) {
        int panelX = this.width - PANEL_WIDTH;
        graphics.fill(panelX, 0, this.width, this.height, 0xEE0D1218);
        graphics.fill(panelX, 0, panelX + 2, this.height, 0xFF657381);

        graphics.centeredText(
                this.font,
                Component.translatable("gui.minecraftempires.map.title"),
                panelX + PANEL_WIDTH / 2,
                12,
                0xFFFFD45A
        );
        graphics.fill(panelX + 12, 27, this.width - 12, 28, 0xFF3E4A55);
        int footerTop = Math.max(35, this.height - 60);
        graphics.fill(panelX + 12, footerTop + 2, this.width - 12, footerTop + 3, 0xFF3E4A55);
    }

    private int drawSidebarContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY) { // Controls are available from the question-mark button.
        UUID selectedId = mapWidget == null ? null : mapWidget.getSelectedArmyId();
        ClientArmyData.Snapshot units = ClientArmyData.get();
        boolean selectedUnit = selectedId != null
                && (units.armiesById().containsKey(selectedId) || units.legionsById().containsKey(selectedId) || units.cohortsById().containsKey(selectedId));
        int y = mapWidget != null && mapWidget.isRoadPlanning() ? drawRoadPlan(graphics, mouseX, mouseY)
                : mapWidget != null && mapWidget.getSelectedCampId() != null ? drawCampDetails(graphics, mouseX, mouseY)
                : selectedUnit ? drawArmyTopBar(graphics, mouseX, mouseY) : drawDetailsPanel(graphics);
        int textX = this.width - PANEL_WIDTH + 12;
        if (mapWidget != null && !mapWidget.isRoadPlanning() && ClientMapData.get().viewerStateId() != null) {
            var selectedChunk = mapWidget.getSelectedPosition();
            if (selectedChunk != null) for (var bridge : ClientMapData.get().roads()) {
                if (bridge.bridge() && bridge.ownerId().equals(ClientMapData.get().viewerStateId())
                        && bridgeTouchesChunk(bridge, selectedChunk))
                    y = drawLogisticsAction(graphics, mouseX, mouseY, y, "Remove bridge", () -> stateAction("REMOVE_BRIDGE", bridge.id(), List.of()), true);
            }
            if (selectedUnit && units.armiesById().containsKey(selectedId)) {
                var army = units.armiesById().get(selectedId);
                if (army.isEngaged() && army.battleId() != null && army.ownerStateId().equals(ClientMapData.get().viewerStateId()))
                    y = drawLogisticsAction(graphics, mouseX, mouseY, y, "Return to battle", () -> ClientNetworking.requestSpectate(army.battleId()), true);
                if (army.ownerStateId().equals(ClientMapData.get().viewerStateId()))
                    y = drawLogisticsAction(graphics, mouseX, mouseY, y, "Create camp (" + StateBalance.CAMP_COST + ")",
                            () -> stateAction("CREATE_CAMP", selectedId, List.of()), !army.isEngaged());
            }
        }
        return y;
    }

    private int drawDetailsPanel(GuiGraphicsExtractor graphics) {
        ClientMapData.Snapshot snapshot = ClientMapData.get();
        int textX = this.width - PANEL_WIDTH + 12;
        int y = 4;

        if (snapshot.viewerStateId() == null) {
            y = drawLine(graphics, textX, y, Component.translatable("gui.minecraftempires.map.no_state"), 0xFFFF7777);
            return drawWrappedHint(graphics, textX, y + 4, "[Minecraft Empires] Create or join a state before opening the map!");
        }

        MapDataPayload.MapChunkData selectedChunk = mapWidget == null ? null : mapWidget.getSelectedChunk();
        ChunkPos selectedPosition = mapWidget == null ? null : mapWidget.getSelectedPosition();

        //displays the details of the selected chunk, settlement, and state, or a legend if no chunk is selected
        if (selectedChunk == null) {
            MapDataPayload.StateSummary viewerSummary = snapshot.getState(snapshot.viewerStateId());
            y = drawHeading(graphics, textX, y, snapshot.viewerStateName().isBlank() ? "Your State" : snapshot.viewerStateName());
            if (viewerSummary != null) {
                y = drawLine(graphics, textX, y, "Tier", formatTier(viewerSummary.tierName()));
                y = drawLine(graphics, textX, y, "Visible chunks", Integer.toString(viewerSummary.visibleChunkCount()));
                y = drawLine(graphics, textX, y, "Population", Integer.toString(viewerSummary.population()));
                y = drawLine(graphics, textX, y, "Treasury", String.format(Locale.ROOT, "%.1f", viewerSummary.treasury()));
            }
            y += 5;
            y = drawHeading(graphics, textX, y, "Map Legend");
            y = drawLegendLine(graphics, textX, y, 0xFFFFD45A, "Capital");
            y = drawLegendLine(graphics, textX, y, 0xFFF7F7F7, "Settlement");
            y = drawLegendLine(graphics, textX, y, 0xFF73E0FF, "Garrisoned");
            y = drawLegendLine(graphics, textX, y, 0xFFFF4242, "Recent breach");
            y = drawLegendLine(graphics, textX, y, 0xFFBA9B6D, "Abstract road");
            y = drawLegendLine(graphics, textX, y, 0xFFDAA968, "Marching camp");
            y = drawLegendLine(graphics, textX, y, 0xFF65E8D4, "Selected supply route");
            return y;
        }

        MapDataPayload.StateSummary state = snapshot.getState(selectedChunk.ownerStateId());
        MapDataPayload.SettlementSummary settlement = snapshot.getSettlement(selectedChunk.settlementId());

        // If the claimed chunk has no assigned settlement, explain it as "Unassigned land" in the details panel.
        String heading = settlement == null
                ? (selectedChunk.settlementId().isBlank() ? Component.translatable("gui.minecraftempires.map.unassigned").getString() : "Province")
                : settlement.settlementName();
        y = drawHeading(graphics, textX, y, heading);
        if (selectedChunk.settlementId().isBlank()) {
            y = drawWrappedHint(graphics, textX, y,
                    Component.translatable("gui.minecraftempires.map.unassigned.description").getString());
            y += 4;
        }

        if (state != null) {
            y = drawLine(graphics, textX, y, "State", state.stateName());
            y = drawLine(graphics, textX, y, "State tier", formatTier(state.tierName()));
        }
        if (selectedPosition != null) {
            y = drawLine(graphics, textX, y, "Chunk", selectedPosition.x() + ", " + selectedPosition.z());
        }
        if (!selectedChunk.settlementId().isBlank())
            y = drawLine(graphics, textX, y, "Settlement tier", Integer.toString(selectedChunk.settlementTier()));
        y = drawLine(graphics, textX, y, "Garrisoned?", selectedChunk.garrisoned() ? "Yes" : "No");
        String organizationStatus = selectedChunk.contested()
                ? "Contested"
                : (selectedChunk.settlementId().isBlank() ? Component.translatable("gui.minecraftempires.map.unassigned").getString() : "Organized");
        y = drawLine(graphics, textX, y, "Status", organizationStatus);

        if (settlement != null) {
            y += 5;
            y = drawHeading(graphics, textX, y, settlement.capital() ? "Capital Details" : "Settlement Details");
            y = drawLine(graphics, textX, y, "Population", Integer.toString(settlement.population()));
            y = drawLine(graphics, textX, y, "Garrison cap", Integer.toString(settlement.garrisonCapacity()));
            //TODO future: allow selection of different icons
            //y = drawLine(graphics, textX, y, "Demarcation", settlement.capital() ? "Capital star" : "Settlement dot");
            var own = ClientManagementData.get().settlements().stream().filter(s -> s.id().toString().equals(settlement.settlementId())).findFirst().orElse(null);
            if (own != null) y = drawLine(graphics, textX, y, "Supply source", own.supplyPreparationTicks() > 0
                    ? String.format(Locale.ROOT, "Preparing: %.2f active days", own.supplyPreparationTicks() / (double) StateBalance.TICKS_PER_DAY) : "Ready");
        }
        return y;
    }

    //helper methods for drawing text and lines in the details panel, including headings, labels, values, and legends
    private int drawHeading(GuiGraphicsExtractor graphics, int x, int y, String text) {
        return drawTextBlock(graphics, x, y, Component.literal(text), PANEL_WIDTH - 32, 0xFFFFD45A) + 2;
    }

    //helper method for drawing a line of text with a label and value in the details panel, returning the new y position
    private int drawLine(GuiGraphicsExtractor graphics, int x, int y, String label, String value) {
        Component caption = Component.literal(label + ":");
        int labelWidth = font.width(caption);
        int width = PANEL_WIDTH - 32;
        int valueOffset = Math.max(78, labelWidth + 8);
        if (width - valueOffset < 40) {
            int next = drawTextBlock(graphics, x, y, caption, width, 0xFFAAB4BE);
            return drawTextBlock(graphics, x + 8, next, Component.literal(value), width - 8, 0xFFF3F5F7);
        }
        int labelEnd = drawTextBlock(graphics, x, y, caption, valueOffset - 8, 0xFFAAB4BE);
        int valueEnd = drawTextBlock(graphics, x + valueOffset, y, Component.literal(value), width - valueOffset, 0xFFF3F5F7);
        return Math.max(labelEnd, valueEnd);
    }

    //similar to the previous drawLine method but takes a Component instead of a String for the value
    private int drawLine(GuiGraphicsExtractor graphics, int x, int y, Component text, int color) {
        return drawTextBlock(graphics, x, y, text, PANEL_WIDTH - 32, color);
    }

    //helper method for drawing a legend line with a colored box and text in the details panel, returning the new y position
    private int drawLegendLine(GuiGraphicsExtractor graphics, int x, int y, int color, String text) {
        if (graphics != null) graphics.fill(x, y + 2, x + 7, y + 9, color);
        return drawTextBlock(graphics, x + 12, y, Component.literal(text), PANEL_WIDTH - 44, 0xFFD7DEE5);
    }

    //helper method for drawing wrapped text in the details panel, splitting the text into lines that fit within the panel width
    private int drawWrappedHint(GuiGraphicsExtractor graphics, int x, int y, String text) {
        return drawTextBlock(graphics, x, y, Component.literal(text), PANEL_WIDTH - 32, 0xFF87939E);
    }

    // Font splitting uses actual glyph widths, including long names/words and translated text.
    // A null graphics argument measures the content without submitting a second set of render elements.
    private int drawTextBlock(GuiGraphicsExtractor graphics, int x, int y, Component text, int width, int color) {
        for (var line : font.split(text, Math.max(1, width))) {
            if (graphics != null) graphics.text(font, line, x, y, color);
            y += 12;
        }
        return y;
    }

    //sidebar input handles scrolling and actions; map input handles panning, zooming, and selecting chunks, settlements, and states
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (help.click(event, helpButton)) return true;
        if (battlesOpen && !battleListButton.isMouseOver(event.x(), event.y())) {
            if (battleListContains(event.x(), event.y())) {
                int row = (int) (event.y() - battleTop - 25) / 40 + battleScroll;
                if (event.button() == 0 && event.y() >= battleTop + 25 && row >= 0 && row < displayedBattles.size()
                        && event.x() >= battleLeft + battleWidth - 48 && event.x() < battleLeft + battleWidth - 6
                        && (event.y() - battleTop - 25) % 40 < 20) ClientNetworking.requestSpectate(displayedBattles.get(row).battleId());
            } else battlesOpen = false;
            return true;
        }
        syncSidebarContext();
        if (event.button() == 0 && sidebarScroll.containsY(event.y())) {
            for (SidebarAction action : List.copyOf(logisticsActions)) {
                if (action.enabled() && event.x() >= this.width - PANEL_WIDTH + 12 && event.x() < this.width - 20
                        && event.y() >= action.y() && event.y() < action.y() + 20) {
                    action.run().run();
                    logisticsActions.clear(); actionButtonY = -1;
                    syncSidebarContext(); syncSupplySelection();
                    return true;
                }
            }
        }
        if (event.button() == 0 && event.x() >= this.width - 10 && event.x() < this.width - 3
                && sidebarScroll.beginDrag(event.y())) {
            actionButtonY = -1;
            return true;
        }
        if (super.mouseClicked(event, doubleClick)) return true;
        // Right/middle clicks on an overlay control must not issue marching orders or start a map drag.
        if (overMapControls(event.x(), event.y())) return true;
        if (this.mapWidget != null && this.mapWidget.getSelectedArmyId() != null) {
            ClientArmyData.Snapshot snap = ClientArmyData.get();
            UUID selectedId = this.mapWidget.getSelectedArmyId();
            boolean isArmy   = snap.armiesById().containsKey(selectedId);
            boolean isLegion = snap.legionsById().containsKey(selectedId);
            boolean isCohort = snap.cohortsById().containsKey(selectedId);

            if (event.button() == 0) {
                if ((isArmy || isLegion || isCohort) && actionButtonContains(event.x(), event.y())) {
                    if (isLegion) this.minecraft.gui.setScreen(new ComposeArmyScreen(selectedId));
                    else if (isCohort) this.minecraft.gui.setScreen(new ComposeArmyScreen(snap.cohortsById().get(selectedId).legionId()));
                    else if (snap.armiesById().get(selectedId).isOnCampaign()) {
                        ClientPacketDistributor.sendToServer(new ArmyActionPayload("END_CAMPAIGN", selectedId, BlockPos.ZERO));
                    } else {
                        ClientPacketDistributor.sendToServer(new DisbandArmyPayload(selectedId));
                        this.mapWidget.clearSelectedArmy();
                    }
                    ClientNetworking.requestMapData();
                    return true;
                }
                // Sidebar clicks retain selection so reading and scrolling do not dismiss the unit details.
            }
        }

        if (event.x() >= this.width - PANEL_WIDTH && event.x() < this.width) return true;
        boolean handled = this.mapWidget != null && this.mapWidget.mouseClicked(event.x(), event.y(), event.button(), event.hasShiftDown());
        rememberBridgePlan();
        syncSupplySelection();
        return handled;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = super.mouseReleased(event);
        if (event.button() == 0 && sidebarScroll.dragging) {
            sidebarScroll.dragging = false;
            handled = true;
        }
        if (this.mapWidget != null && this.mapWidget.mouseReleased(event.button())) {
            return true;
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (help.isOpen() || battlesOpen) return true;
        if (event.button() == 0 && sidebarScroll.dragging) {
            sidebarScroll.dragTo(event.y());
            actionButtonY = -1;
            logisticsActions.clear();
            return true;
        }
        if (this.mapWidget != null && this.mapWidget.mouseDragged(dragX, dragY, event.button())) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (help.scroll(scrollY)) return true;
        if (battlesOpen) { battleScroll = Math.clamp(battleScroll - (int) Math.signum(scrollY), 0, Math.max(0, displayedBattles.size() - 1)); return true; }
        if (overMapControls(mouseX, mouseY)) return true;
        if (mouseX >= this.width - PANEL_WIDTH && mouseX < this.width && mouseY >= 0 && mouseY < this.height) {
            syncSidebarContext();
            sidebarScroll.scrollBy(-scrollY * 24);
            actionButtonY = -1;
            logisticsActions.clear();
            return true;
        }
        if (this.mapWidget != null && this.mapWidget.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (help.isOpen()) { if (event.key() == GLFW.GLFW_KEY_ESCAPE) help.close(); else helpButton.keyPressed(event); return true; }
        if (battlesOpen) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) battlesOpen = false;
            else if (event.key() == GLFW.GLFW_KEY_DOWN) battleSelected = Math.min(Math.max(0, displayedBattles.size() - 1), battleSelected + 1);
            else if (event.key() == GLFW.GLFW_KEY_UP) battleSelected = Math.max(0, battleSelected - 1);
            else if (event.key() == GLFW.GLFW_KEY_ENTER && battleSelected < displayedBattles.size()) ClientNetworking.requestSpectate(displayedBattles.get(battleSelected).battleId());
            int visible = Math.max(1, (battleHeight - 26) / 40);
            if (battleSelected < battleScroll) battleScroll = battleSelected;
            if (battleSelected >= battleScroll + visible) battleScroll = battleSelected - visible + 1;
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && mapWidget != null && mapWidget.isRoadPlanning()) {
            if (mapWidget.isBridgePlanning() && bridgeManagement) { bridgeManagement = false; mapWidget.clearBridgeFocus(); return true; }
            if (mapWidget.isBridgePlanning()) cancelBridgePlan();
            mapWidget.setRoadPlanning(false);
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_R && this.mapWidget != null) {
            this.mapWidget.resetView();
            return true;
        }
        // Escape clears army selection before closing the screen
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && this.mapWidget != null && this.mapWidget.getSelectedArmyId() != null) {
            this.mapWidget.clearSelectedArmy();
            syncSupplySelection();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        rememberBridgePlan();
        if (requestedSupplyTarget != null && minecraft != null && minecraft.getConnection() != null)
            stateAction("SELECT_SUPPLY", null, List.of());
        requestedSupplyTarget = null;
        if (mapWidget != null) { mapWidget.mouseReleased(0); mapWidget.mouseReleased(2); }
        super.removed();
    }

    //helper method to format the tier name for display, capitalizing the first letter and replacing underscores with spaces, or returning "Unknown" if the value is null or blank
    private static String formatTier(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown";
        }
        String lower = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    //draws the selected-unit details inside the scrollable panel
    //for selected armies, shows troops, morale, maintenance, campaign status, and a disband button
    //for selected legions, shows available soldiers, average morale, and a hint to compose an army
    private int drawArmyTopBar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        UUID selectedId = this.mapWidget.getSelectedArmyId();
        if (selectedId == null) return 4;

        ClientArmyData.Snapshot snapshot = ClientArmyData.get();
        ArmyMapPayload.ArmySummary armyData   = snapshot.armiesById().get(selectedId);
        ArmyMapPayload.LegionSummary legionData = snapshot.legionsById().get(selectedId);
        if (snapshot.cohortsById().containsKey(selectedId)) return drawCohortDetails(graphics, mouseX, mouseY, snapshot.cohortsById().get(selectedId));
        if (armyData == null && legionData == null) return 4;

        int panelX = this.width - PANEL_WIDTH;
        int textX = panelX + 12;
        int y = 4;
        UUID owner = armyData != null ? armyData.ownerStateId() : legionData.ownerStateId();
        if (!owner.equals(ClientMapData.get().viewerStateId())) {
            y = drawHeading(graphics, textX, y, "Foreign force");
            y = drawLine(graphics, textX, y, "Soldiers", Integer.toString(armyData != null ? armyData.troops() : legionData.availableSoldiers()));
            return drawWrappedHint(graphics, textX, y + 4, "Visible through nearby troops or your territory. Manage relations in Diplomacy.");
        }

        if (armyData != null) { //army menu text and options
            //draws the selected-army details, showing troops, morale, maintenance, campaign status, and a disband button
            //shows the selected army heading, colored in gold
            y = drawHeading(graphics, textX, y, armyData.isOnCampaign() ? "[In Campaign] Army" : "Selected Army");
            if (graphics != null) graphics.fill(textX, y, textX + PANEL_WIDTH - 32, y + 1, 0xFF3E4A55);
            y += 6;

            y = drawLine(graphics, textX, y, "Troops", Integer.toString(armyData.troops())); //shows the number of troops in the selected army, colored in light gray
            //shows the number of troops in the selected army, colored in white
            y = drawLine(graphics, textX, y, "Average Morale", Integer.toString(armyData.morale())); //shows the morale of the selected army, colored in light gray
            //white color
            y = drawLine(graphics, textX, y, "Cost", Integer.toString(armyData.maintenance())); //shows the maintenance cost of the selected army, colored in light gray
            //white
            if (armyData.isEngaged()) { //options for if the army is in battle
                y = drawLine(graphics, textX, y, "Status", "\u00a7cIn Battle"); //shows the status of the selected army, colored in light gray
                //shows the status of the selected army, colored in red
            }
            if (!armyData.terrainStatus().isEmpty()) y = drawLine(graphics, textX, y, "Movement", armyData.terrainStatus());
            y += 6;

            //disband button
            drawActionButton(graphics, mouseX, mouseY, textX, y, armyData.isOnCampaign() ? "End Campaign" : "Disband Army");
            y += 26;
        } else { //legion menu text and options
            y = drawHeading(graphics, textX, y, "Selected Legion"); //shows the selected legion, colored in gold
            if (graphics != null) graphics.fill(textX, y, textX + PANEL_WIDTH - 32, y + 1, 0xFF3E4A55);
            y += 6;

            y = drawLine(graphics, textX, y, "Available", Integer.toString(legionData.availableSoldiers())); //shows the number of available soldiers in the selected legion, colored in light gray
            //white
            y = drawLine(graphics, textX, y, "Avg Morale", Integer.toString(legionData.averageMorale())); //shows the average morale of the selected legion, colored in light gray
            //white
            y += 6;

            //manage legion cohorts button
            drawActionButton(graphics, mouseX, mouseY, textX, y, "Manage cohorts");
            y += 26; //advance y position for the hint text below the button
        }
        return drawSupplyDetails(graphics, textX, y + 4, selectedId, armyData != null);
    }

    private int drawCohortDetails(GuiGraphicsExtractor graphics, int mx, int my, ArmyMapPayload.CohortSummary c) {
        int x = width - PANEL_WIDTH + 12, y = drawHeading(graphics, x, 4, "Detached cohort");
        y = drawLine(graphics, x, y, "Soldiers", Integer.toString(c.soldiers()));
        y = drawLine(graphics, x, y, "Position", c.position().getX() + ", " + c.position().getZ());
        if (!c.ownerStateId().equals(ClientMapData.get().viewerStateId())) return y;
        y = drawLine(graphics, x, y, "Status", !c.terrainStatus().isEmpty() ? c.terrainStatus() : c.retreating() ? "Retreating" : c.waypoints().isEmpty() ? "Stationary" : "Marching");
        y = drawLine(graphics, x, y, "Selected cohorts", Integer.toString(Math.max(1, mapWidget.getCohortOrderSelection().size())));
        y = drawWrappedHint(graphics, x, y, "Detached cohorts stay here until ordered. Garrison and regroup from the cohort roster.");
        drawActionButton(graphics, mx, my, x, y + 4, "Manage cohorts");
        return drawSupplyDetails(graphics, x, y + 30, c.cohortId(), false);
    }

    // Camp and planning controls share the scrollable panel; no additional screen or widget files are needed.
    private record SidebarAction(int y, Runnable run, boolean enabled) {}
    private int drawLogisticsAction(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int y, String label, Runnable run, boolean enabled) {
        if (graphics != null) {
            int x = width - PANEL_WIDTH + 12, w = PANEL_WIDTH - 32;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 20;
            graphics.fill(x, y, x + w, y + 20, !enabled ? 0xFF27313A : hover ? 0xFF586B76 : 0xFF394C58);
            graphics.centeredText(font, Component.literal(font.plainSubstrByWidth(label, w - 8)), x + w / 2, y + 6, enabled ? 0xFFECE5D6 : 0xFF85909A);
            logisticsActions.add(new SidebarAction(sidebarScroll.screenY(y), run, enabled));
        }
        return y + 24;
    }
    private void stateAction(String action, UUID target, List<BlockPos> points) {
        ClientPacketDistributor.sendToServer(new StateActionPayload(action, target == null ? new UUID(0, 0) : target, BlockPos.ZERO, "", points));
        ClientNetworking.requestMapData();
    }
    private void rememberBridgePlan() {
        if (mapWidget != null && mapWidget.isBridgePlanning()) {
            var old = ClientMapData.bridgeDraft();
            if (old != null && !old.points().equals(mapWidget.getRoadPoints())) stateAction("CANCEL_BRIDGE", old.id(), List.of());
            ClientMapData.saveBridgeDraft(mapWidget.getRoadPoints());
        }
    }
    private void cancelBridgePlan() {
        var draft = ClientMapData.bridgeDraft();
        if (draft != null) stateAction("CANCEL_BRIDGE", draft.id(), List.of());
        ClientMapData.clearBridgeDraft();
    }
    private void requestBridge(String action) {
        rememberBridgePlan(); var draft = ClientMapData.bridgeDraft();
        if (draft == null || draft.points().size() != 2) return;
        stateAction(action, draft.id(), draft.points()); ClientMapData.bridgeCheckRequested();
    }

    //when roads are physically loaded, change the wording of these
    private int drawRoadPlan(GuiGraphicsExtractor graphics, int mx, int my) {
        if (mapWidget.isBridgePlanning()) {
            rememberBridgePlan();
            if (bridgeManagement) return drawBridgeManagement(graphics, mx, my);
            int x = width - PANEL_WIDTH + 12, y = drawHeading(graphics, x, 4, "Plan a bridge");
            y = drawLogisticsAction(graphics, mx, my, y, "Manage placed bridges (" + ownedBridges().size() + ")", () -> { bridgeManagement = true; ClientNetworking.requestMapData(); }, true);
            List<BlockPos> points = mapWidget.getRoadPoints();
            int length = points.size() == 2 ? points.getFirst().distManhattan(points.getLast()) : 0;
            y = drawLine(graphics, x, y, "Length", length + "/64 blocks");
            y = drawLine(graphics, x, y, "Width", "3 blocks");
            y = drawLine(graphics, x, y, "Cost", length * 10L + " emeralds");
            y = drawWrappedHint(graphics, x, y, "Choose two banks on the same north–south or east–west line. Start on your land. The full deck and approaches must avoid foreign territory.");
            var draft = ClientMapData.bridgeDraft();
            String phase = draft == null ? "DRAFT" : draft.phase();
            if (draft != null) y = drawWrappedHint(graphics, x, y + 4, draft.message());
            boolean ready = phase.equals("READY"), checking = phase.equals("CHECKING"), built = phase.equals("BUILT");
            String label = ready ? "Purchase bridge" : checking ? "Checking crossing..." : built ? "Bridge purchased" : phase.equals("FAILED") ? "Retry check" : "Check bridge";
            y = drawLogisticsAction(graphics, mx, my, y + 4, label, () -> requestBridge(ready ? "BUILD_BRIDGE" : "CHECK_BRIDGE"),
                    mapWidget.roadPreviewValid() && !checking && !built && (!ready || ClientManagementData.get().treasury() >= length * 10L));
            if (checking) y = drawLogisticsAction(graphics, mx, my, y, "Retry / check status", () -> requestBridge("CHECK_BRIDGE"), true);
            y = drawLogisticsAction(graphics, mx, my, y, "Clear points", () -> { cancelBridgePlan(); mapWidget.clearRoadPlan(); }, !points.isEmpty());
            return drawLogisticsAction(graphics, mx, my, y, "Cancel bridge planning", () -> { cancelBridgePlan(); mapWidget.setRoadPlanning(false); }, true);
        }
        int x = width - PANEL_WIDTH + 12, y = drawHeading(graphics, x, 4, "Plan abstract roads");
        int cells = mapWidget.expandedRoadPreview().size();
        long cost = (long) mapWidget.roadNewCells() * StateBalance.ROAD_COST_PER_CHUNK;
        y = drawLine(graphics, x, y, "Control points", mapWidget.getRoadPoints().size() + "/" + StateBalance.MAX_ROAD_POINTS);
        y = drawLine(graphics, x, y, "Route cells", cells + "/" + StateBalance.MAX_ROAD_CHUNKS);
        y = drawLine(graphics, x, y, "Estimated cost", cost + " emeralds");
        y = drawLine(graphics, x, y, "Treasury", ClientManagementData.get().treasury() + " emeralds");
        y = drawWrappedHint(graphics, x, y, mapWidget.roadPreviewValid() ? "Ownership preview."
                : cells > StateBalance.MAX_ROAD_CHUNKS ? "Route exceeds the length limit." : "Choose a route entirely within your territory.");
        // When roads are physically loaded, this hint will be updated to reflect the actual state.
        //y = drawWrappedHint(graphics, x, y + 3, "Abstract geometry; unsurveyed terrain is unverified for physical construction. Existing cells are free. Removal gives no refund.");
        if (ClientMapData.get().logisticsTruncated()) y = drawWrappedHint(graphics, x, y, "Warning: Some road data is omitted. final price may be lower than this estimate.");
        y = drawLogisticsAction(graphics, mx, my, y + 5, "Purchase", () -> {
            stateAction("BUILD_ROAD", null, mapWidget.getRoadPoints());
        }, mapWidget.roadPreviewValid() && (ClientMapData.get().logisticsTruncated() || ClientManagementData.get().treasury() >= cost));
        y = drawLogisticsAction(graphics, mx, my, y, "Remove roads on route", () -> {
            stateAction("REMOVE_ROAD", null, mapWidget.getRoadPoints());
        }, mapWidget.roadPreviewValid());
        y = drawLogisticsAction(graphics, mx, my, y, "Clear points", mapWidget::clearRoadPlan, cells > 0);
        return drawLogisticsAction(graphics, mx, my, y, "Cancel road planning", () -> mapWidget.setRoadPlanning(false), true);
    }
    private List<MapDataPayload.RoadSummary> ownedBridges() {
        UUID owner = ClientMapData.get().viewerStateId();
        return ClientMapData.get().roads().stream().filter(bridge -> bridge.bridge() && bridge.ownerId().equals(owner) && !bridge.points().isEmpty())
                .collect(Collectors.toMap(MapDataPayload.RoadSummary::id, bridge -> bridge, (first, next) -> first)).values().stream()
                .sorted(java.util.Comparator.comparingInt((MapDataPayload.RoadSummary bridge) -> bridge.points().getFirst().getX())
                        .thenComparingInt(bridge -> bridge.points().getFirst().getZ()).thenComparing(MapDataPayload.RoadSummary::id)).toList();
    }
    private static boolean bridgeTouchesChunk(MapDataPayload.RoadSummary bridge, ChunkPos chunk) {
        if (bridge.points().isEmpty()) return false;
        BlockPos first = bridge.points().getFirst(), last = bridge.points().getLast();
        return chunk.x() >= Math.min(first.getX() >> 4, last.getX() >> 4) && chunk.x() <= Math.max(first.getX() >> 4, last.getX() >> 4)
                && chunk.z() >= Math.min(first.getZ() >> 4, last.getZ() >> 4) && chunk.z() <= Math.max(first.getZ() >> 4, last.getZ() >> 4);
    }
    private int drawBridgeManagement(GuiGraphicsExtractor graphics, int mx, int my) {
        int x = width - PANEL_WIDTH + 12, y = drawHeading(graphics, x, 4, "Placed bridges");
        y = drawLogisticsAction(graphics, mx, my, y, "Back to bridge plan", () -> { bridgeManagement = false; mapWidget.clearBridgeFocus(); }, true);
        var bridges = ownedBridges();
        y = drawWrappedHint(graphics, x, y, "Bridges controlled by your state: " + bridges.size() + ". Show a crossing on the map or dismantle it from here.");
        y = drawWrappedHint(graphics, x, y, "Removal needs an available army, legion or cohort near either bank, with no nearby enemies. No refund.");
        if (ClientMapData.get().logisticsTruncated()) y = drawWrappedHint(graphics, x, y, "Some infrastructure is omitted from this snapshot.");
        if (bridges.isEmpty()) return drawWrappedHint(graphics, x, y + 4, "Your state controls no bridges in this snapshot.");
        for (var bridge : bridges) {
            BlockPos first = bridge.points().getFirst(), last = bridge.points().getLast();
            y = drawWrappedHint(graphics, x, y + 8, "Bridge: " + first.getX() + ", " + first.getZ() + " to " + last.getX() + ", " + last.getZ());
            y = drawLine(graphics, x, y, "Span / width", (Math.abs(first.getX() - last.getX()) + Math.abs(first.getZ() - last.getZ())) + " / 3 blocks");
            y = drawLogisticsAction(graphics, mx, my, y, "Show on map", () -> mapWidget.focusBridge(bridge.id()), true);
            y = drawLogisticsAction(graphics, mx, my, y, "Remove bridge", () -> minecraft.gui.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(confirmed -> {
                minecraft.gui.setScreen(this);
                if (confirmed) stateAction("REMOVE_BRIDGE", bridge.id(), List.of());
            }, Component.literal("Remove this bridge?"), Component.literal("Crossing at " + first.getX() + ", " + first.getZ()
                    + ". Construction costs are not refunded. Troops on the crossing may suffer losses."))), true);
        }
        return y;
    }
    private int drawCampDetails(GuiGraphicsExtractor graphics, int mx, int my) {
        UUID id = mapWidget.getSelectedCampId();
        var camp = ClientMapData.get().camps().stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
        if (camp == null) { mapWidget.clearSelectedCamp(); return drawDetailsPanel(graphics); }
        int x = width - PANEL_WIDTH + 12, y = drawHeading(graphics, x, 4, "Army Camp");
        var state = ClientMapData.get().getState(camp.ownerId());
        y = drawLine(graphics, x, y, "Owner", state == null ? "Foreign State" : state.stateName());
        y = drawLine(graphics, x, y, "Position", camp.position().getX() + ", " + camp.position().getZ());
        if (!camp.ownerId().equals(ClientMapData.get().viewerStateId())) return y;
        var own = ClientManagementData.get().camps().stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
        if (own != null) {
            y = drawLine(graphics, x, y, "Stock", own.stock() + "/" + own.capacity() + " soldier-days");
            y = drawLine(graphics, x, y, "Network", own.connected() ? "Connected" : "Isolated");
            y = drawLine(graphics, x, y, "Supply Range", StateBalance.CAMP_SUPPLY_RANGE + " blocks");
        }
        y = drawWrappedHint(graphics, x, y + 4, "Dismantling requires your unengaged army within " + StateBalance.CAMP_OPERATION_RANGE + " blocks.");
        return drawLogisticsAction(graphics, mx, my, y + 4, "Remove Camp", () -> {
            stateAction("DISMANTLE_CAMP", id, List.of()); mapWidget.clearSelectedCamp();
        }, true);
    }

    private int drawSupplyDetails(GuiGraphicsExtractor graphics, int x, int y, UUID selected, boolean army) {
        var data = ClientManagementData.get();
        if (!Objects.equals(data.stateId(), ClientMapData.get().viewerStateId())) return y;
        boolean individual = data.cohorts().stream().anyMatch(c -> c.id().equals(selected));
        var selectedCohorts = mapWidget.getCohortOrderSelection();
        var cohorts = data.cohorts().stream().filter(c -> c.soldiers() > 0 && (army ? selected.equals(c.armyId())
                : individual ? c.id().equals(selected) || selectedCohorts.contains(c.id())
                : selected.equals(c.legionId()) && c.armyId() == null && c.settlementId() == null
                        && !ClientArmyData.get().cohortsById().containsKey(c.id()))).toList();
        Set<UUID> ids = cohorts.stream().map(ManagementDataPayload.CohortEntry::id).collect(Collectors.toSet());
        var entries = data.supplies().stream().filter(c -> ids.contains(c.cohortId())).toList();
        y = drawHeading(graphics, x, y, "Supply Status");
        if (entries.isEmpty()) return drawWrappedHint(graphics, x, y, "Waiting for supply assessment.");
        boolean mixed = entries.stream().map(ManagementDataPayload.CohortSupplyEntry::status).distinct().count() > 1;
        y = drawLine(graphics, x, y, "Status", mixed ? "Mixed" : entries.getFirst().status());
        var sources = entries.stream().map(e -> e.sourceName().isBlank() ? "None" : e.sourceName()).distinct().toList();
        y = drawLine(graphics, x, y, "Source", sources.size() == 1 ? sources.getFirst() : "Mixed sources");
        double minReserve = entries.stream().mapToDouble(ManagementDataPayload.CohortSupplyEntry::reserveDays).min().orElse(0);
        double maxReserve = entries.stream().mapToDouble(ManagementDataPayload.CohortSupplyEntry::reserveDays).max().orElse(0);
        y = drawLine(graphics, x, y, "Reserve", Math.abs(maxReserve - minReserve) < .05
                ? String.format(Locale.ROOT, "%.1f / %d days", minReserve, StateBalance.CARRIED_SUPPLY_DAYS)
                : String.format(Locale.ROOT, "%.1f–%.1f days", minReserve, maxReserve));
        double maximumCost = entries.stream().mapToDouble(ManagementDataPayload.CohortSupplyEntry::cost).filter(Double::isFinite).max().orElse(0);
        y = drawLine(graphics, x, y, "Route cost", maximumCost < 0 ? "Unavailable" : String.format(Locale.ROOT, "%.0f / %d per leg", maximumCost, StateBalance.SUPPLY_RANGE));
        int fatigue = entries.stream().mapToInt(ManagementDataPayload.CohortSupplyEntry::fatigue).max().orElse(0);
        y = drawLine(graphics, x, y, "Fatigue", "Up to -" + fatigue + " endurance");
        int base = (int) Math.round(cohorts.stream().mapToInt(ManagementDataPayload.CohortEntry::endurance).average().orElse(0));
        int effective = (int) Math.round(entries.stream().mapToInt(ManagementDataPayload.CohortSupplyEntry::effectiveEndurance).average().orElse(0));
        y = drawLine(graphics, x, y, "Endurance", base + " base / " + effective + " effective avg");
        for (String reason : entries.stream().map(ManagementDataPayload.CohortSupplyEntry::reason).filter(r -> !r.isBlank()).distinct().limit(3).toList())
            y = drawWrappedHint(graphics, x, y, reason);
        if (data.selectedRoute().truncated() && Objects.equals(data.selectedRoute().targetId(), mapWidget.supplyTargetId()))
            y = drawWrappedHint(graphics, x, y, "Route display truncated. Supply calculation is complete.");
        if (cohorts.size() > 1) y = drawWrappedHint(graphics, x, y, "Overlay shows one representative route.");
        return y;
    }
    // Send a route query only when the private selection changes; normal snapshots refresh the selected result.
    private void syncSupplySelection() {
        if (mapWidget == null || minecraft == null) return;
        UUID viewer = ClientMapData.get().viewerStateId();
        if (mapWorld != minecraft.level || !Objects.equals(mapViewer, viewer)) {
            // Clear the previous server selection before forgetting it, including same-server dimension changes.
            if (requestedSupplyTarget != null && minecraft.level != null && minecraft.getConnection() != null)
                stateAction("SELECT_SUPPLY", null, List.of());
            mapWorld = minecraft.level; mapViewer = viewer; requestedSupplyTarget = null;
            bridgeManagement = false;
            mapWidget.setRoadPlanning(false);
            mapWidget.clearSelectedArmy(); mapWidget.clearSelectedCamp();
            if (mapWidget.isSupplyOverlay()) mapWidget.toggleSupplyOverlay();
            return;
        }
        UUID target = mapWidget.isSupplyOverlay() ? mapWidget.supplyTargetId() : null;
        if (Objects.equals(target, requestedSupplyTarget)) return;
        requestedSupplyTarget = target;
        if (viewer != null && minecraft.level != null) stateAction("SELECT_SUPPLY", target, List.of());
    }

    // This button is submitted inside the content scissor; its hit box uses the same scroll offset.
    private void drawActionButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int x, int y, String label) {
        if (graphics == null) return;
        int buttonWidth = PANEL_WIDTH - 32;
        actionButtonY = sidebarScroll.screenY(y);
        boolean hover = mouseX >= x && mouseX < x + buttonWidth && mouseY >= y && mouseY < y + 20
                && sidebarScroll.containsY(sidebarScroll.top - sidebarScroll.amount + mouseY);
        graphics.fill(x, y, x + buttonWidth, y + 20, hover ? 0xFFFF6666 : 0xFFCC0000);
        graphics.centeredText(font, Component.literal(label), x + buttonWidth / 2, y + 6, 0xFFFFFFFF);
    }

    private boolean actionButtonContains(double mouseX, double mouseY) {
        return actionButtonY != -1 && sidebarScroll.containsY(mouseY)
                && mouseX >= this.width - PANEL_WIDTH + 12 && mouseX < this.width - 20
                && mouseY >= actionButtonY && mouseY < actionButtonY + 20;
    }

    // Snapshot refreshes preserve reading position; selecting another object starts its details at the top.
    private void syncSidebarContext() {
        UUID viewer = ClientMapData.get().viewerStateId();
        UUID unit = mapWidget == null ? null : mapWidget.getSelectedArmyId();
        ClientArmyData.Snapshot units = ClientArmyData.get();
        if (unit != null && !units.armiesById().containsKey(unit) && !units.legionsById().containsKey(unit) && !units.cohortsById().containsKey(unit)) {
            UUID missing = unit;
            var cohort = ClientManagementData.get().cohorts().stream().filter(c -> c.id().equals(missing)).findFirst().orElse(null);
            if (cohort != null && cohort.armyId() != null && units.armiesById().containsKey(cohort.armyId())) {
                mapWidget.selectUnit(cohort.armyId()); unit = cohort.armyId();
            } else if (cohort == null || cohort.soldiers() == 0 || cohort.settlementId() != null) {
                mapWidget.clearSelectedArmy(); unit = null;
            }
        }
        ChunkPos chunk = mapWidget == null ? null : mapWidget.getSelectedPosition();
        String context = viewer + ":" + (mapWidget != null && mapWidget.isRoadPlanning() ? mapWidget.isBridgePlanning() && bridgeManagement ? "bridges" : "road"
                : mapWidget != null && mapWidget.getSelectedCampId() != null ? "camp:" + mapWidget.getSelectedCampId()
                : unit != null ? "unit:" + unit : chunk != null ? "chunk:" + chunk.pack() : "state");
        if (!context.equals(sidebarContext)) {
            sidebarContext = context;
            sidebarScroll.reset();
            actionButtonY = -1;
            logisticsActions.clear();
        }
    }

    private void drawSidebarScrollbar(GuiGraphicsExtractor graphics) {
        if (sidebarScroll.maximum() <= 0) return;
        int x = this.width - 9;
        graphics.fill(x, sidebarScroll.top, x + 5, sidebarScroll.bottom, 0xFF293540);
        int thumbTop = sidebarScroll.thumbTop();
        graphics.fill(x, thumbTop, x + 5, thumbTop + sidebarScroll.thumbHeight(), sidebarScroll.dragging ? 0xFFE3CF8B : 0xFF8997A3);
    }

    // SIDEBAR SCROLL GEOMETRY: kept here to avoid a separate widget/helper file.
    // Logical content coordinates stay stable while screen coordinates move beneath the pinned header/footer.
    private static final class SidebarScroll {
        private double amount;
        private int contentHeight, top, bottom;
        private boolean dragging;
        private double grabOffset;

        private void update(int height, int viewportTop, int viewportBottom) {
            contentHeight = Math.max(0, height);
            top = viewportTop; bottom = Math.max(top + 1, viewportBottom);
            amount = Math.clamp(amount, 0, maximum());
            if (maximum() == 0) dragging = false;
        }
        private int viewportHeight() { return bottom - top; }
        private int maximum() { return Math.max(0, contentHeight - viewportHeight()); }
        private void reset() { amount = 0; dragging = false; }
        private boolean containsY(double y) { return y >= top && y < bottom; }
        private void scrollBy(double pixels) { amount = Math.clamp(amount + pixels, 0, maximum()); }
        private int screenY(int logicalY) { return (int) Math.floor(top + logicalY - amount); }
        private int thumbHeight() {
            return Math.min(viewportHeight(), Math.max(16, (int) ((long) viewportHeight() * viewportHeight() / Math.max(1, contentHeight))));
        }
        private int thumbTop() {
            int travel = viewportHeight() - thumbHeight();
            return top + (maximum() == 0 ? 0 : (int) Math.round(amount / maximum() * travel));
        }
        private boolean beginDrag(double mouseY) {
            if (maximum() == 0 || !containsY(mouseY)) return false;
            int thumbTop = thumbTop();
            dragging = true;
            if (mouseY >= thumbTop && mouseY < thumbTop + thumbHeight()) {
                // Preserve the exact scroll position when grabbing a rounded thumb rectangle.
                grabOffset = mouseY - (top + amount / maximum() * (viewportHeight() - thumbHeight()));
                return true;
            }
            grabOffset = thumbHeight() / 2.0;
            dragTo(mouseY);
            return true;
        }
        private void dragTo(double mouseY) {
            int travel = viewportHeight() - thumbHeight();
            if (dragging && travel > 0)
                amount = Math.clamp((mouseY - top - grabOffset) / travel * maximum(), 0, maximum());
        }
    }
}
