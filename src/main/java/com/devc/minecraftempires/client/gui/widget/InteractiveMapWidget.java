package com.devc.minecraftempires.client.gui.widget;

import com.devc.minecraftempires.client.map.ClientMapData;
import com.devc.minecraftempires.network.packet.MapDataPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 3 map canvas. Handles viewport culling, panning, cursor-centered zooming,
 * territory borders, province labels, settlement markers, and breach overlays.
 */
public final class InteractiveMapWidget {
    private static final double MIN_ZOOM = 3.0;
    private static final double MAX_ZOOM = 28.0;
    private static final double DEFAULT_ZOOM = 8.0;

    private int x;
    private int y;
    private int width;
    private int height;

    private double centerChunkX;
    private double centerChunkZ;
    private double zoom = DEFAULT_ZOOM;
    private boolean dragging;
    private long selectedChunk = Long.MIN_VALUE;
    private int loadedSnapshotVersion = -1;

    public InteractiveMapWidget(int x, int y, int width, int height) {
        setBounds(x, y, width, height);
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    public void render(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        ClientMapData.Snapshot snapshot = ClientMapData.get();
        refreshViewportForNewData(snapshot);

        graphics.fill(x, y, x + width, y + height, 0xFF111820);
        graphics.enableScissor(x, y, x + width, y + height);

        drawGrid(graphics);
        if (snapshot.hasData()) {
            drawTerritory(graphics, snapshot);
            drawBreachAlerts(graphics, snapshot);
            drawProvinceLabelsAndMarkers(graphics, font, snapshot);
        } else {
            graphics.centeredText(
                    font,
                    Component.translatable("gui.minecraftempires.map.awaiting_data"),
                    x + width / 2,
                    y + height / 2 - 5,
                    0xFFB8C0C8
            );
        }

        graphics.disableScissor();
        graphics.fill(x, y, x + width, y + 1, 0xFF485563);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF485563);
        graphics.fill(x, y, x + 1, y + height, 0xFF485563);
        graphics.fill(x + width - 1, y, x + width, y + height, 0xFF485563);

        drawCoordinates(graphics, font, mouseX, mouseY);
    }

    private void drawGrid(GuiGraphicsExtractor graphics) {
        if (zoom < 7.0) {
            return;
        }

        int minChunkX = floorChunk(screenToChunkX(x));
        int maxChunkX = floorChunk(screenToChunkX(x + width)) + 1;
        int minChunkZ = floorChunk(screenToChunkZ(y));
        int maxChunkZ = floorChunk(screenToChunkZ(y + height)) + 1;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            int screenX = chunkToScreenX(chunkX);
            graphics.fill(screenX, y, screenX + 1, y + height, 0x182E3A46);
        }
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            int screenY = chunkToScreenY(chunkZ);
            graphics.fill(x, screenY, x + width, screenY + 1, 0x182E3A46);
        }
    }

    private void drawTerritory(GuiGraphicsExtractor graphics, ClientMapData.Snapshot snapshot) {
        int minChunkX = Math.max(snapshot.minChunkX(), floorChunk(screenToChunkX(x)) - 1);
        int maxChunkX = Math.min(snapshot.maxChunkX(), floorChunk(screenToChunkX(x + width)) + 1);
        int minChunkZ = Math.max(snapshot.minChunkZ(), floorChunk(screenToChunkZ(y)) - 1);
        int maxChunkZ = Math.min(snapshot.maxChunkZ(), floorChunk(screenToChunkZ(y + height)) + 1);

        int cellSize = Math.max(1, (int) Math.ceil(zoom));
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                MapDataPayload.MapChunkData chunk = snapshot.getChunk(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }

                int left = chunkToScreenX(chunkX);
                int top = chunkToScreenY(chunkZ);
                int right = left + cellSize;
                int bottom = top + cellSize;

                graphics.fill(left, top, right, bottom, stateFillColor(chunk.ownerStateId(), chunk.ownerStateId().equals(snapshot.viewerStateId())));

                if (chunk.settlementId().isBlank() || chunk.contested()) {
                    drawHatching(graphics, left, top, right, bottom);
                }

                int provinceMask = snapshot.getProvinceBorderMask(chunkX, chunkZ);
                int stateMask = snapshot.getBorderMask(chunkX, chunkZ);
                drawMask(graphics, left, top, right, bottom, provinceMask, 0xAAB8C0C8, 1);
                drawMask(graphics, left, top, right, bottom, stateMask, 0xFFF4E6A2, zoom >= 10.0 ? 2 : 1);

                if (new ChunkPos(chunkX, chunkZ).pack() == selectedChunk) {
                    drawMask(
                            graphics,
                            left,
                            top,
                            right,
                            bottom,
                            ClientMapData.BORDER_NORTH
                                    | ClientMapData.BORDER_EAST
                                    | ClientMapData.BORDER_SOUTH
                                    | ClientMapData.BORDER_WEST,
                            0xFFFFFFFF,
                            2
                    );
                }
            }
        }
    }

    private void drawHatching(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
        if (zoom < 6.0) {
            return;
        }
        for (int row = top + 2; row < bottom; row += 4) {
            graphics.fill(left + 1, row, right - 1, Math.min(row + 1, bottom), 0x5A111111);
        }
    }

    private void drawMask(
            GuiGraphicsExtractor graphics,
            int left,
            int top,
            int right,
            int bottom,
            int mask,
            int color,
            int thickness
    ) {
        if ((mask & ClientMapData.BORDER_NORTH) != 0) {
            graphics.fill(left, top, right, top + thickness, color);
        }
        if ((mask & ClientMapData.BORDER_EAST) != 0) {
            graphics.fill(right - thickness, top, right, bottom, color);
        }
        if ((mask & ClientMapData.BORDER_SOUTH) != 0) {
            graphics.fill(left, bottom - thickness, right, bottom, color);
        }
        if ((mask & ClientMapData.BORDER_WEST) != 0) {
            graphics.fill(left, top, left + thickness, bottom, color);
        }
    }

    private void drawBreachAlerts(GuiGraphicsExtractor graphics, ClientMapData.Snapshot snapshot) {
        for (MapDataPayload.BreachAlert alert : snapshot.breachAlerts()) {
            ChunkPos position = ChunkPos.unpack(alert.packedChunkPos());
            int left = chunkToScreenX(position.x());
            int top = chunkToScreenY(position.z());
            int size = Math.max(4, (int) Math.ceil(zoom));

            if (!intersects(left, top, size, size)) {
                continue;
            }

            drawMask(
                    graphics,
                    left - 2,
                    top - 2,
                    left + size + 2,
                    top + size + 2,
                    ClientMapData.BORDER_NORTH
                            | ClientMapData.BORDER_EAST
                            | ClientMapData.BORDER_SOUTH
                            | ClientMapData.BORDER_WEST,
                    0xFFFF4242,
                    2
            );
        }
    }

    private void drawProvinceLabelsAndMarkers(
            GuiGraphicsExtractor graphics,
            Font font,
            ClientMapData.Snapshot snapshot
    ) {
        Set<String> occupiedLabelSlots = new HashSet<>();

        for (ClientMapData.ProvinceAnchor anchor : snapshot.provinceAnchors()) {
            int markerX = chunkToScreenX(anchor.chunkX());
            int markerY = chunkToScreenY(anchor.chunkZ());
            if (!intersects(markerX - 30, markerY - 16, 60, 32)) {
                continue;
            }

            if (anchor.settlementMarker()) {
                drawSettlementMarker(graphics, markerX, markerY, anchor.capital(), anchor.garrisoned());
            }

            if (zoom < 5.5) {
                continue;
            }

            String slotKey = (markerX / 50) + ":" + (markerY / 16);
            if (!occupiedLabelSlots.add(slotKey)) {
                continue;
            }

            String label = trimToWidth(font, anchor.displayName(), 96);
            int labelY = markerY - (anchor.settlementMarker() ? (anchor.capital() ? 15 : 12) : 5);
            graphics.centeredText(font, Component.literal(label), markerX + 1, labelY + 1, 0xE0000000);
            graphics.centeredText(font, Component.literal(label), markerX, labelY, 0xFFF4F4F4);
        }
    }

    private void drawSettlementMarker(
            GuiGraphicsExtractor graphics,
            int centerX,
            int centerY,
            boolean capital,
            boolean garrisoned
    ) {
        if (capital) {
            graphics.fill(centerX - 1, centerY - 6, centerX + 2, centerY + 7, 0xFFFFD45A);
            graphics.fill(centerX - 6, centerY - 1, centerX + 7, centerY + 2, 0xFFFFD45A);
            graphics.fill(centerX - 4, centerY - 4, centerX + 5, centerY + 5, 0xFFFFD45A);
            graphics.fill(centerX - 2, centerY - 2, centerX + 3, centerY + 3, 0xFFFFFFFF);
        } else {
            graphics.fill(centerX - 3, centerY - 3, centerX + 4, centerY + 4, 0xFFF7F7F7);
            graphics.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, 0xFF202830);
        }

        if (garrisoned) {
            graphics.fill(centerX - 7, centerY - 7, centerX + 8, centerY - 6, 0xFF73E0FF);
            graphics.fill(centerX - 7, centerY + 7, centerX + 8, centerY + 8, 0xFF73E0FF);
            graphics.fill(centerX - 7, centerY - 7, centerX - 6, centerY + 8, 0xFF73E0FF);
            graphics.fill(centerX + 7, centerY - 7, centerX + 8, centerY + 8, 0xFF73E0FF);
        }
    }

    private void drawCoordinates(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        if (!contains(mouseX, mouseY)) {
            return;
        }

        int chunkX = floorChunk(screenToChunkX(mouseX));
        int chunkZ = floorChunk(screenToChunkZ(mouseY));
        String text = "Chunk " + chunkX + ", " + chunkZ + "   Zoom " + String.format(java.util.Locale.ROOT, "%.1fx", zoom / DEFAULT_ZOOM);
        int textWidth = font.width(text);
        int left = x + 7;
        int top = y + height - 18;
        graphics.fill(left - 4, top - 3, left + textWidth + 5, top + 11, 0xC011171D);
        graphics.text(font, Component.literal(text), left, top, 0xFFD7DEE5);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }

        if (button == 0) {
            int chunkX = floorChunk(screenToChunkX(mouseX));
            int chunkZ = floorChunk(screenToChunkZ(mouseY));
            if (ClientMapData.get().getChunk(chunkX, chunkZ) != null) {
                selectedChunk = new ChunkPos(chunkX, chunkZ).pack();
            } else {
                selectedChunk = Long.MIN_VALUE;
            }
            dragging = true;
            return true;
        }

        if (button == 1) {
            selectedChunk = Long.MIN_VALUE;
            return true;
        }

        return false;
    }

    public boolean mouseReleased(int button) {
        if (button == 0 && dragging) {
            dragging = false;
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double deltaX, double deltaY, int button) {
        if (button != 0 || !dragging) {
            return false;
        }

        centerChunkX -= deltaX / zoom;
        centerChunkZ -= deltaY / zoom;
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!contains(mouseX, mouseY) || scrollY == 0.0) {
            return false;
        }

        double beforeX = screenToChunkX(mouseX);
        double beforeZ = screenToChunkZ(mouseY);
        double scale = scrollY > 0.0 ? 1.18 : 1.0 / 1.18;
        zoom = clamp(zoom * scale, MIN_ZOOM, MAX_ZOOM);
        double afterX = screenToChunkX(mouseX);
        double afterZ = screenToChunkZ(mouseY);
        centerChunkX += beforeX - afterX;
        centerChunkZ += beforeZ - afterZ;
        return true;
    }

    public void resetView() {
        ClientMapData.Snapshot snapshot = ClientMapData.get();
        centerChunkX = snapshot.centerChunkX();
        centerChunkZ = snapshot.centerChunkZ();
        zoom = calculateFitZoom(snapshot);
        selectedChunk = Long.MIN_VALUE;
    }

    public MapDataPayload.MapChunkData getSelectedChunk() {
        if (selectedChunk == Long.MIN_VALUE) {
            return null;
        }
        return ClientMapData.get().chunksByPosition().get(selectedChunk);
    }

    public ChunkPos getSelectedPosition() {
        return selectedChunk == Long.MIN_VALUE ? null : ChunkPos.unpack(selectedChunk);
    }

    private void refreshViewportForNewData(ClientMapData.Snapshot snapshot) {
        if (snapshot.version() == loadedSnapshotVersion) {
            return;
        }
        loadedSnapshotVersion = snapshot.version();
        resetView();
    }

    private double calculateFitZoom(ClientMapData.Snapshot snapshot) {
        if (!snapshot.hasData()) {
            return DEFAULT_ZOOM;
        }

        int chunkWidth = Math.max(1, snapshot.maxChunkX() - snapshot.minChunkX() + 1);
        int chunkHeight = Math.max(1, snapshot.maxChunkZ() - snapshot.minChunkZ() + 1);
        double horizontalFit = Math.max(1.0, (width - 36.0) / chunkWidth);
        double verticalFit = Math.max(1.0, (height - 36.0) / chunkHeight);
        return clamp(Math.min(horizontalFit, verticalFit), MIN_ZOOM, 12.0);
    }

    private int chunkToScreenX(double chunkX) {
        return (int) Math.round(x + width / 2.0 + (chunkX - centerChunkX) * zoom);
    }

    private int chunkToScreenY(double chunkZ) {
        return (int) Math.round(y + height / 2.0 + (chunkZ - centerChunkZ) * zoom);
    }

    private double screenToChunkX(double screenX) {
        return centerChunkX + (screenX - (x + width / 2.0)) / zoom;
    }

    private double screenToChunkZ(double screenY) {
        return centerChunkZ + (screenY - (y + height / 2.0)) / zoom;
    }

    private boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private boolean intersects(int itemX, int itemY, int itemWidth, int itemHeight) {
        return itemX + itemWidth >= x
                && itemX <= x + width
                && itemY + itemHeight >= y
                && itemY <= y + height;
    }

    private static int floorChunk(double value) {
        return (int) Math.floor(value);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int stateFillColor(UUID stateId, boolean viewerState) {
        int hash = stateId.hashCode();
        int red = 72 + Math.floorMod(hash, 112);
        int green = 72 + Math.floorMod(hash >> 8, 112);
        int blue = 72 + Math.floorMod(hash >> 16, 112);
        int alpha = viewerState ? 0xB8 : 0x82;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static String trimToWidth(Font font, String text, int maximumWidth) {
        if (font.width(text) <= maximumWidth) {
            return text;
        }

        String suffix = "...";
        String current = text;
        while (!current.isEmpty() && font.width(current + suffix) > maximumWidth) {
            current = current.substring(0, current.length() - 1);
        }
        return current + suffix;
    }
}
