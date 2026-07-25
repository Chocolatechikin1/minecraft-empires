package com.devc.minecraftempires.client.gui.screen;

import com.devc.minecraftempires.client.ClientNetworking;
import com.devc.minecraftempires.client.gui.widget.InteractiveMapWidget;
import com.devc.minecraftempires.client.map.ClientMapData;
import com.devc.minecraftempires.client.map.ClientArmyData;
import com.devc.minecraftempires.network.packet.ArmyMapPayload;
import com.devc.minecraftempires.network.packet.DisbandArmyPayload;
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

    public MapScreen() {
        super(Component.translatable("gui.minecraftempires.map.title"));
    }

    //sets default map size
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



    //renders the map widget and the details panel, which shows information about the selected chunk, settlement, and state
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        //draw the map widget if it exists
        if (this.mapWidget != null) {
            this.mapWidget.render(graphics, this.font, mouseX, mouseY);
        }

        drawDetailsPanel(graphics);

        if (this.mapWidget != null && this.mapWidget.getSelectedArmyId() != null) {
            drawArmyTopBar(graphics, mouseX, mouseY);
        }

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
            drawWrappedHint(graphics, textX, y + 4, "[Minecraft Empires] Create or join a state before opening the empire map!");
            return;
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
            y += 5;
            drawWrappedHint(graphics, textX, y, "Left-click territory for details. Drag to pan. Scroll to zoom. Right-click to clear selection.");
            return;
        }

        MapDataPayload.StateSummary state = snapshot.getState(selectedChunk.ownerStateId());
        MapDataPayload.SettlementSummary settlement = snapshot.getSettlement(selectedChunk.settlementId());

        //if chunk is part of an unorganized province, display "Unorganized Territory" instead of the settlement name
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

    //helper methods for drawing text and lines in the details panel, including headings, labels, values, and legends
    private int drawHeading(GuiGraphicsExtractor graphics, int x, int y, String text) {
        graphics.text(this.font, Component.literal(trim(text, 28)), x, y, 0xFFFFD45A);
        return y + 14;
    }

    //helper method for drawing a line of text with a label and value in the details panel, returning the new y position
    private int drawLine(GuiGraphicsExtractor graphics, int x, int y, String label, String value) {
        graphics.text(this.font, Component.literal(label + ":"), x, y, 0xFFAAB4BE);
        graphics.text(this.font, Component.literal(trim(value, 22)), x + 78, y, 0xFFF3F5F7);
        return y + 12;
    }

    //similar to the previous drawLine method but takes a Component instead of a String for the value
    private int drawLine(GuiGraphicsExtractor graphics, int x, int y, Component text, int color) {
        graphics.text(this.font, text, x, y, color);
        return y + 12;
    }

    //helper method for drawing a legend line with a colored box and text in the details panel, returning the new y position
    private int drawLegendLine(GuiGraphicsExtractor graphics, int x, int y, int color, String text) {
        graphics.fill(x, y + 2, x + 7, y + 9, color);
        graphics.text(this.font, Component.literal(text), x + 12, y, 0xFFD7DEE5);
        return y + 12;
    }

    //helper method for drawing wrapped text in the details panel, splitting the text into lines that fit within the panel width
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

    //all mouse and keyboard input is passed to the map widget, which handles panning, zooming, and selecting chunks, settlements, and states
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (this.mapWidget != null && this.mapWidget.getSelectedArmyId() != null) {
            // Button coords must match those computed in drawArmyTopBar
            int panelX = this.width - PANEL_WIDTH;
            int overlayTop = 29;
            // heading (14) + divider gap (6) + 4 stat rows (48) + gap (18) = top of button
            int buttonY = overlayTop + 8 + 14 + 1 + 6 + (12 * 4) + 18;
            int buttonWidth = PANEL_WIDTH - 28;
            int buttonX = panelX + 14;
            if (event.button() == 0) {
                if (event.y() >= buttonY && event.y() <= buttonY + 20 && event.x() >= buttonX && event.x() <= buttonX + buttonWidth) {
                    // Disband button clicked
                    ClientPacketDistributor.sendToServer(new DisbandArmyPayload(this.mapWidget.getSelectedArmyId()));
                    this.mapWidget.clearSelectedArmy();
                    return true;
                }
                // Left-clicking anywhere in the panel clears the army selection
                if (event.x() >= panelX) {
                    this.mapWidget.clearSelectedArmy();
                    return true;
                }
            }
        }

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

    //helper method to trim a string to a maximum number of characters, adding "..." if the string is too long, or returning an empty string if the value is null
    private static String trim(String value, int maximumCharacters) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maximumCharacters) {
            return value;
        }
        return value.substring(0, maximumCharacters - 3) + "...";
    }

    private void drawArmyTopBar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        UUID selectedArmyId = this.mapWidget.getSelectedArmyId();
        if (selectedArmyId == null) return;
        ArmyMapPayload.LegionSummary activeData = ClientArmyData.get().byId().get(selectedArmyId);
        if (activeData == null) return;

        // Overlay covers only the details panel content area (below the header + divider)
        int panelX = this.width - PANEL_WIDTH;
        int overlayTop = 29;  // just below the separator line drawn at y=27-28
        int overlayBottom = this.height - 35; // leave room for the buttons at the bottom
        int textX = panelX + 12;

        // Dark overlay fills the panel content area
        graphics.fill(panelX + 2, overlayTop, this.width, overlayBottom, 0xF0101820);
        graphics.fill(panelX + 2, overlayBottom, this.width, overlayBottom + 1, 0xFF485563);

        // Army heading PUT ARMY NAME OR NUMBER HERE INSTEAD OF ID
        int y = overlayTop + 8;
        graphics.text(this.font, Component.literal("Selected Army"), textX, y, 0xFFFFD45A);
        y += 14;
        graphics.fill(textX, y, this.width - 12, y + 1, 0xFF3E4A55);
        y += 6;

        // Stats
        //FUTURE: display army name or number, not the ID (PUT IT ABOVE AT "Selected Army" HEADING)
        /*graphics.text(this.font, Component.literal("ID:"), textX, y, 0xFFAAB4BE);
        graphics.text(this.font, Component.literal(selectedArmyId.toString().substring(0, 8)), textX + 78, y, 0xFFF3F5F7);*/
        //y += 12;
        graphics.text(this.font, Component.literal("Troops:"), textX, y, 0xFFAAB4BE);
        graphics.text(this.font, Component.literal(Integer.toString(activeData.troops())), textX + 78, y, 0xFFF3F5F7);
        y += 12;
        graphics.text(this.font, Component.literal("Morale:"), textX, y, 0xFFAAB4BE);
        graphics.text(this.font, Component.literal(Integer.toString(activeData.morale())), textX + 78, y, 0xFFF3F5F7);
        y += 12;
        graphics.text(this.font, Component.literal("Cost/day:"), textX, y, 0xFFAAB4BE);
        graphics.text(this.font, Component.literal(Integer.toString(activeData.maintenance())), textX + 78, y, 0xFFF3F5F7);
        y += 18;

        // Disband button — centered inside the panel
        int buttonWidth = PANEL_WIDTH - 28;
        int buttonX = panelX + 14;
        int buttonY = y;
        boolean hover = mouseX >= buttonX && mouseX <= buttonX + buttonWidth && mouseY >= buttonY && mouseY <= buttonY + 20;
        graphics.fill(buttonX, buttonY, buttonX + buttonWidth, buttonY + 20, hover ? 0xFFFF6666 : 0xFFCC0000);
        graphics.centeredText(this.font, Component.literal("Disband Army"), buttonX + buttonWidth / 2, buttonY + 6, 0xFFFFFFFF);
    }
}
