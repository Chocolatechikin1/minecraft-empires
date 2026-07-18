package com.devc.minecraftempires.client.gui.screen;

import com.devc.minecraftempires.client.ClientNetworking;
import com.devc.minecraftempires.client.gui.widget.InteractiveMapWidget;
import com.devc.minecraftempires.client.map.ClientMapData;
import com.devc.minecraftempires.network.packet.MapDataPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/** Main Phase 3 empire-map dashboard. */
public final class MapScreen extends Screen {
    private static final int PANEL_WIDTH = 205;
    private static final int MARGIN = 8;

    private InteractiveMapWidget mapWidget;

    public MapScreen() {
        super(Component.translatable("gui.minecraftempires.map.title"));
    }

    @Override
    protected void init() {
        super.init();

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

        ClientNetworking.requestMapData();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.extractRenderStateWithTooltipAndSubtitles already extracts the
        // blurred background. Calling extractBackground here requests a second
        // blur in the same frame and crashes with "Can only blur once per frame".
        if (this.mapWidget != null) {
            this.mapWidget.render(graphics, this.font, mouseX, mouseY);
        }

        drawDetailsPanel(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawDetailsPanel(GuiGraphicsExtractor graphics) {
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

        ClientMapData.Snapshot snapshot = ClientMapData.get();
        int textX = panelX + 12;
        int y = 38;

        if (snapshot.viewerStateId() == null) {
            y = drawLine(graphics, textX, y, Component.translatable("gui.minecraftempires.map.no_state"), 0xFFFF7777);
            drawWrappedHint(graphics, textX, y + 4, "Create or join a state before opening the empire map.");
            return;
        }

        MapDataPayload.MapChunkData selectedChunk = mapWidget == null ? null : mapWidget.getSelectedChunk();
        ChunkPos selectedPosition = mapWidget == null ? null : mapWidget.getSelectedPosition();

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
            y += 5;
            drawWrappedHint(graphics, textX, y, "Left-click territory for details. Drag to pan. Scroll to zoom. Right-click clears selection.");
            return;
        }

        MapDataPayload.StateSummary state = snapshot.getState(selectedChunk.ownerStateId());
        MapDataPayload.SettlementSummary settlement = snapshot.getSettlement(selectedChunk.settlementId());

        String heading = settlement == null
                ? (selectedChunk.settlementId().isBlank() ? "Unorganized Territory" : "Province")
                : settlement.settlementName();
        y = drawHeading(graphics, textX, y, heading);

        if (state != null) {
            y = drawLine(graphics, textX, y, "State", state.stateName());
            y = drawLine(graphics, textX, y, "State tier", formatTier(state.tierName()));
        }
        if (selectedPosition != null) {
            y = drawLine(graphics, textX, y, "Chunk", selectedPosition.x() + ", " + selectedPosition.z());
        }
        y = drawLine(graphics, textX, y, "Province tier", Integer.toString(selectedChunk.settlementTier()));
        y = drawLine(graphics, textX, y, "Chunk garrison", selectedChunk.garrisoned() ? "Active" : "None");
        String organizationStatus = selectedChunk.contested()
                ? "Contested"
                : (selectedChunk.settlementId().isBlank() ? "Unorganized" : "Organized");
        y = drawLine(graphics, textX, y, "Status", organizationStatus);

        if (settlement != null) {
            y += 5;
            y = drawHeading(graphics, textX, y, settlement.capital() ? "Capital Details" : "Settlement Details");
            y = drawLine(graphics, textX, y, "Population", Integer.toString(settlement.population()));
            y = drawLine(graphics, textX, y, "Garrison cap", Integer.toString(settlement.garrisonCapacity()));
            y = drawLine(graphics, textX, y, "Map marker", settlement.capital() ? "Capital star" : "Settlement dot");
        }
    }

    private int drawHeading(GuiGraphicsExtractor graphics, int x, int y, String text) {
        graphics.text(this.font, Component.literal(trim(text, 28)), x, y, 0xFFFFD45A);
        return y + 14;
    }

    private int drawLine(GuiGraphicsExtractor graphics, int x, int y, String label, String value) {
        graphics.text(this.font, Component.literal(label + ":"), x, y, 0xFFAAB4BE);
        graphics.text(this.font, Component.literal(trim(value, 22)), x + 78, y, 0xFFF3F5F7);
        return y + 12;
    }

    private int drawLine(GuiGraphicsExtractor graphics, int x, int y, Component text, int color) {
        graphics.text(this.font, text, x, y, color);
        return y + 12;
    }

    private int drawLegendLine(GuiGraphicsExtractor graphics, int x, int y, int color, String text) {
        graphics.fill(x, y + 2, x + 7, y + 9, color);
        graphics.text(this.font, Component.literal(text), x + 12, y, 0xFFD7DEE5);
        return y + 12;
    }

    private void drawWrappedHint(GuiGraphicsExtractor graphics, int x, int y, String text) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        int currentY = y;
        for (String word : words) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (this.font.width(candidate) > PANEL_WIDTH - 28 && !line.isEmpty()) {
                graphics.text(this.font, Component.literal(line.toString()), x, currentY, 0xFF87939E);
                line = new StringBuilder(word);
                currentY += 11;
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            graphics.text(this.font, Component.literal(line.toString()), x, currentY, 0xFF87939E);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        return this.mapWidget != null && this.mapWidget.mouseClicked(event.x(), event.y(), event.button());
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.mapWidget != null && this.mapWidget.mouseReleased(event.button())) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.mapWidget != null && this.mapWidget.mouseDragged(dragX, dragY, event.button())) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
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
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String formatTier(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown";
        }
        String lower = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String trim(String value, int maximumCharacters) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maximumCharacters) {
            return value;
        }
        return value.substring(0, maximumCharacters - 3) + "...";
    }
}
