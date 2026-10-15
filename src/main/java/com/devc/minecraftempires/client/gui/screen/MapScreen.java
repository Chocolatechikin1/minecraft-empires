package com.devc.minecraftempires.client.gui.screen;

import com.devc.minecraftempires.client.ClientNetworking;
import com.devc.minecraftempires.client.gui.widget.InteractiveMapWidget;
import com.devc.minecraftempires.client.map.ClientMapData;
import com.devc.minecraftempires.client.map.ClientArmyData;
import com.devc.minecraftempires.network.packet.ArmyMapPayload;
import com.devc.minecraftempires.network.packet.DisbandArmyPayload;
import com.devc.minecraftempires.network.packet.ArmyActionPayload;
import net.minecraft.core.BlockPos;
import com.devc.minecraftempires.network.packet.MapDataPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.UUID;

//primary file to house the map screen, including the map widget and the details panel, which shows information about the selected chunk, settlement, and state
public final class MapScreen extends Screen {
    private static final int PANEL_WIDTH = 205;
    private static final int MARGIN = 8;

    private InteractiveMapWidget mapWidget;
    private int actionButtonY = -1;
    private final SidebarScroll sidebarScroll = new SidebarScroll();
    private String sidebarContext = "";

    public MapScreen() {
        super(Component.translatable("gui.minecraftempires.map.title"));
    }

    //sets default map size
    @Override
    protected void init() {
        super.init();
        sidebarScroll.dragging = false;

        int mapWidth = Math.max(80, this.width - PANEL_WIDTH - MARGIN * 2);
        int mapHeight = Math.max(80, this.height - MARGIN * 2);
        this.mapWidget = new InteractiveMapWidget(MARGIN, MARGIN, mapWidth, mapHeight);

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
        this.addRenderableWidget(Button.builder(ClientMapData.terrainViewButton(false), button -> {
            ClientMapData.cycleTerrainView(false);
            button.setMessage(ClientMapData.terrainViewButton(false));
        }).bounds(panelX, buttonY - 48, 180, 20).build());

        ClientNetworking.requestMapData();
        this.addRenderableWidget(Button.builder(Component.literal("Manage state and legions"),
                button -> this.minecraft.gui.setScreen(new EmpireManagementScreen()))
                .bounds(panelX, buttonY - 24, 180, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
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
            this.mapWidget.render(graphics, this.font, mouseX, mouseY);
        }

        drawSidebarFrame(graphics);
        syncSidebarContext();
        actionButtonY = -1;
        // Measure with the same wrapping used for painting, then clip above the fixed footer buttons.
        int contentHeight = drawSidebarContents(null, mouseX, mouseY);
        sidebarScroll.update(contentHeight + 4, 34, Math.max(35, this.height - 84));
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

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
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
        int footerTop = Math.max(35, this.height - 84);
        graphics.fill(panelX + 12, footerTop + 2, this.width - 12, footerTop + 3, 0xFF3E4A55);
    }

    private int drawSidebarContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        UUID selectedId = mapWidget == null ? null : mapWidget.getSelectedArmyId();
        ClientArmyData.Snapshot units = ClientArmyData.get();
        boolean selectedUnit = selectedId != null
                && (units.armiesById().containsKey(selectedId) || units.legionsById().containsKey(selectedId));
        int y = selectedUnit ? drawArmyTopBar(graphics, mouseX, mouseY) : drawDetailsPanel(graphics);
        int textX = this.width - PANEL_WIDTH + 12;
        y = drawHeading(graphics, textX, y + 8, Component.translatable("gui.minecraftempires.map.controls").getString());
        y = drawWrappedHint(graphics, textX, y, Component.translatable("gui.minecraftempires.map.controls.general").getString());
        if (selectedUnit) {
            UUID owner = units.armiesById().containsKey(selectedId)
                    ? units.armiesById().get(selectedId).ownerStateId() : units.legionsById().get(selectedId).ownerStateId();
            // Right-click hint
            String key = owner.equals(ClientMapData.get().viewerStateId())
                    ? "gui.minecraftempires.map.controls.military" : "gui.minecraftempires.map.controls.foreign";
            y = drawWrappedHint(graphics, textX, y + 4, Component.translatable(key).getString());
        } else {
            y = drawWrappedHint(graphics, textX, y + 4,
                    Component.translatable("gui.minecraftempires.map.controls.selection").getString());
        }
        return y;
    }

    private int drawDetailsPanel(GuiGraphicsExtractor graphics) {
        ClientMapData.Snapshot snapshot = ClientMapData.get();
        int textX = this.width - PANEL_WIDTH + 12;
        int y = 4;

        if (snapshot.viewerStateId() == null) {
            y = drawLine(graphics, textX, y, Component.translatable("gui.minecraftempires.map.no_state"), 0xFFFF7777);
            return drawWrappedHint(graphics, textX, y + 4, "[Minecraft Empires] Create or join a state before opening the empire map!");
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
            y = drawLine(graphics, textX, y, "Province tier", Integer.toString(selectedChunk.settlementTier()));
        y = drawLine(graphics, textX, y, "Chunk garrison", selectedChunk.garrisoned() ? "Active" : "None");
        String organizationStatus = selectedChunk.contested()
                ? "Contested"
                : (selectedChunk.settlementId().isBlank() ? Component.translatable("gui.minecraftempires.map.unassigned").getString() : "Organized");
        y = drawLine(graphics, textX, y, "Status", organizationStatus);

        if (settlement != null) {
            y += 5;
            y = drawHeading(graphics, textX, y, settlement.capital() ? "Capital Details" : "Settlement Details");
            y = drawLine(graphics, textX, y, "Population", Integer.toString(settlement.population()));
            y = drawLine(graphics, textX, y, "Garrison cap", Integer.toString(settlement.garrisonCapacity()));
            y = drawLine(graphics, textX, y, "Map marker", settlement.capital() ? "Capital star" : "Settlement dot");
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
        syncSidebarContext();
        if (event.button() == 0 && event.x() >= this.width - 10 && event.x() < this.width - 3
                && sidebarScroll.beginDrag(event.y())) {
            actionButtonY = -1;
            return true;
        }
        if (super.mouseClicked(event, doubleClick)) return true;
        if (this.mapWidget != null && this.mapWidget.getSelectedArmyId() != null) {
            ClientArmyData.Snapshot snap = ClientArmyData.get();
            UUID selectedId = this.mapWidget.getSelectedArmyId();
            boolean isArmy   = snap.armiesById().containsKey(selectedId);
            boolean isLegion = snap.legionsById().containsKey(selectedId);

            if (event.button() == 0) {
                if ((isArmy || isLegion) && actionButtonContains(event.x(), event.y())) {
                    if (isLegion) this.minecraft.gui.setScreen(new ComposeArmyScreen(selectedId));
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
        return this.mapWidget != null && this.mapWidget.mouseClicked(event.x(), event.y(), event.button());
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
        if (event.button() == 0 && sidebarScroll.dragging) {
            sidebarScroll.dragTo(event.y());
            actionButtonY = -1;
            return true;
        }
        if (this.mapWidget != null && this.mapWidget.mouseDragged(dragX, dragY, event.button())) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= this.width - PANEL_WIDTH && mouseX < this.width && mouseY >= 0 && mouseY < this.height) {
            syncSidebarContext();
            sidebarScroll.scrollBy(-scrollY * 24);
            actionButtonY = -1;
            return true;
        }
        if (this.mapWidget != null && this.mapWidget.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_R && this.mapWidget != null) {
            this.mapWidget.resetView();
            return true;
        }
        // Escape clears army selection before closing the screen
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && this.mapWidget != null && this.mapWidget.getSelectedArmyId() != null) {
            this.mapWidget.clearSelectedArmy();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
            y = drawHeading(graphics, textX, y, armyData.isOnCampaign() ? "[Campaign] Army" : "Selected Army");
            if (graphics != null) graphics.fill(textX, y, textX + PANEL_WIDTH - 32, y + 1, 0xFF3E4A55);
            y += 6;

            y = drawLine(graphics, textX, y, "Troops", Integer.toString(armyData.troops())); //shows the number of troops in the selected army, colored in light gray
            //shows the number of troops in the selected army, colored in white
            y = drawLine(graphics, textX, y, "Morale", Integer.toString(armyData.morale())); //shows the morale of the selected army, colored in light gray
            //white color
            y = drawLine(graphics, textX, y, "Cost/day", Integer.toString(armyData.maintenance())); //shows the maintenance cost of the selected army, colored in light gray
            //white
            if (armyData.isEngaged()) { //options for if the army is in battle
                y = drawLine(graphics, textX, y, "Status", "\u00a7cIn Battle"); //shows the status of the selected army, colored in light gray
                //shows the status of the selected army, colored in red
            }
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
        return y;
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
        if (unit != null && !units.armiesById().containsKey(unit) && !units.legionsById().containsKey(unit)) {
            mapWidget.clearSelectedArmy();
            unit = null;
        }
        ChunkPos chunk = mapWidget == null ? null : mapWidget.getSelectedPosition();
        String context = viewer + ":" + (unit != null ? "unit:" + unit : chunk != null ? "chunk:" + chunk.pack() : "state");
        if (!context.equals(sidebarContext)) {
            sidebarContext = context;
            sidebarScroll.reset();
            actionButtonY = -1;
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
