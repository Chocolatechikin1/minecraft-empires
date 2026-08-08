package com.devc.minecraftempires.client.gui.widget;

import com.devc.minecraftempires.client.ClientNetworking;
import com.devc.minecraftempires.client.map.ClientArmyData;
import com.devc.minecraftempires.client.map.ClientMapData;
import com.devc.minecraftempires.network.packet.ArmyMapPayload;
import com.devc.minecraftempires.network.packet.DispatchArmyPayload;
import com.devc.minecraftempires.network.packet.DispatchLegionPayload;
import com.devc.minecraftempires.network.packet.MapDataPayload;
import com.devc.minecraftempires.network.packet.DisbandArmyPayload; 
import net.neoforged.neoforge.network.PacketDistributor; 
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

//primary class for rendering the interactive map, including panning, zooming, and displaying chunk ownership, settlements, and alerts
public final class InteractiveMapWidget {
    private static final double MIN_ZOOM = 3.0;
    private static final double MAX_ZOOM = 28.0;
    private static final double DEFAULT_ZOOM = 8.0;

    //army icon size in pixels at 1:1 zoom, scales with zoom but is clamped so it's always legible
    private static final int ARMY_ICON_BASE_PX = 10;

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

    //currently selected unit (Legion or Army UUID), null = nothing selected
    private UUID selectedArmyId = null;
    private boolean selectedIsArmy = false;

    public InteractiveMapWidget(int x, int y, int width, int height) {
        setBounds(x, y, width, height);
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    //sets the center of the map to the specified chunk coordinates, clamping the zoom level to the allowed range
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

        //places cohort and army icons in front of the map
        drawArmies(graphics, font, snapshot);

        graphics.disableScissor();
        graphics.fill(x, y, x + width, y + 1, 0xFF485563);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF485563);
        graphics.fill(x, y, x + 1, y + height, 0xFF485563);
        graphics.fill(x + width - 1, y, x + width, y + height, 0xFF485563);

        drawCoordinates(graphics, font, mouseX, mouseY);

        //shows army info sidebar if an army is selected
        // (Removed in favor of top bar in MapScreen)
    }

    //renders unit icons on the map.
    //Armies (operational groupings) are drawn as brighter square icons with a 'S' type marker.
    //Legions (standing units) are drawn with reduced brightness and an 'L' marker.
    //Yellow border drawn around whichever unit is currently selected.
    private void drawArmies(GuiGraphicsExtractor graphics, Font font, ClientMapData.Snapshot snapshot) {
        ClientArmyData.Snapshot armies = ClientArmyData.get();
        if (!armies.hasAnyUnits()) return;

        int iconSize = Math.max(6, Math.min(ARMY_ICON_BASE_PX, (int) zoom));

        //draws legions
        for (ArmyMapPayload.LegionSummary legion : armies.legionsById().values()) {
            ChunkPos chunkPos = ChunkPos.unpack(legion.packedChunkPos());
            int screenX = chunkToScreenX(chunkPos.x());
            int screenY = chunkToScreenY(chunkPos.z());
            if (!intersects(screenX, screenY, iconSize, iconSize)) continue;

            boolean isViewer = snapshot.viewerStateId() != null && legion.ownerStateId().equals(snapshot.viewerStateId());
            int fillColor = armyIconColor(legion.ownerStateId(), isViewer);
            //shows the legion icon, smaller and less colored, colored in gray if non player unit, colored in blue if player unit
            graphics.fill(screenX + 1, screenY + 1, screenX + iconSize - 1, screenY + iconSize - 1, fillColor & 0xBBFFFFFF | fillColor & 0xFF000000);;

            // 'L' label at zoom ≥ 12 (note might not look good, adjust this later if needed)
            if (zoom >= 12.0 && font != null) {
                String label = "L" + legion.availableSoldiers(); //here
                int labelX = screenX + iconSize + 2;
                int labelY = screenY + (iconSize / 2) - 4;
                graphics.fill(labelX - 1, labelY - 1, labelX + font.width(label) + 2, labelY + 10, 0xAA111820); //shows the number of available soldiers in the legion next to the icon, colored in light gray
                graphics.text(font, Component.literal(label), labelX, labelY, 0xFFCCCCCC);
            }

            // Yellow glow if selected
            if (legion.legionId().equals(selectedArmyId) && !selectedIsArmy) {
                drawMask(graphics, screenX - 2, screenY - 2, screenX + iconSize + 2, screenY + iconSize + 2, ClientMapData.BORDER_NORTH | ClientMapData.BORDER_EAST | ClientMapData.BORDER_SOUTH | ClientMapData.BORDER_WEST, 0xFFFFE84A, 2);
            }
        }

        //draws armies
        for (ArmyMapPayload.ArmySummary army : armies.armiesById().values()) {
            ChunkPos chunkPos = ChunkPos.unpack(army.packedChunkPos());
            int screenX = chunkToScreenX(chunkPos.x());
            int screenY = chunkToScreenY(chunkPos.z());
            if (!intersects(screenX, screenY, iconSize, iconSize)) continue;

            boolean isViewer = snapshot.viewerStateId() != null && army.ownerStateId().equals(snapshot.viewerStateId());
            int fillColor = armyIconColor(army.ownerStateId(), isViewer);
            graphics.fill(screenX, screenY, screenX + iconSize, screenY + iconSize, fillColor);

            //crosshair center
            int cx = screenX + iconSize / 2;
            int cy = screenY + iconSize / 2;
            if (iconSize >= 8) { //colored in gray
                graphics.fill(cx - 1, screenY + 1, cx + 1, screenY + iconSize - 1, 0x88000000);
                graphics.fill(screenX + 1, cy - 1, screenX + iconSize - 1, cy + 1, 0x88000000);
            }

            // Campaign indicator (blue dot in corner)
            if (army.isOnCampaign()) {
                graphics.fill(screenX + iconSize - 3, screenY, screenX + iconSize, screenY + 3, 0xFF44AAFF);
            }

            // Yellow selection glow + waypoint line
            if (army.armyId().equals(selectedArmyId) && selectedIsArmy) {
                drawMask(graphics, screenX - 2, screenY - 2, screenX + iconSize + 2, screenY + iconSize + 2, ClientMapData.BORDER_NORTH | ClientMapData.BORDER_EAST | ClientMapData.BORDER_SOUTH | ClientMapData.BORDER_WEST, 0xFFFFE84A, 2);

                if (!army.waypoints().isEmpty()) {
                    int lastX = cx;
                    int lastY = cy;
                    for (BlockPos wp : army.waypoints()) {
                        int wpChunkX = wp.getX() >> 4;
                        int wpChunkZ = wp.getZ() >> 4;
                        int targetX = chunkToScreenX(wpChunkX) + (int)(zoom / 2.0);
                        int targetY = chunkToScreenY(wpChunkZ) + (int)(zoom / 2.0);
                        drawDottedLine(graphics, lastX, lastY, targetX, targetY, 0xAAFFE84A);
                        graphics.fill(targetX - 2, targetY - 2, targetX + 2, targetY + 2, 0xFFFFE84A);
                        lastX = targetX;
                        lastY = targetY;
                    }
                }
            }

            // Engagement indicator
            if (army.isEngaged() && army.battleId() != null) {
                int indicatorX = screenX + iconSize / 2 - 3;
                int indicatorY = screenY - 12;
                graphics.fill(indicatorX - 1, indicatorY - 1, indicatorX + 8, indicatorY + 9, 0x99FF8800);
                if (font != null) graphics.text(font, Component.literal("\u2694"), indicatorX, indicatorY, 0xFFFF8800);

                int eyeX = screenX + iconSize / 2 - 3;
                int eyeY = screenY + iconSize + 4;
                graphics.fill(eyeX - 1, eyeY - 1, eyeX + 10, eyeY + 9, 0x88003355);
                if (font != null) graphics.text(font, Component.literal("\u25CE"), eyeX, eyeY, 0xFF44AAFF);
            }

            // Troop count label
            if (zoom >= 12.0 && font != null) {
                String label = "A" + army.troops();
                int labelX = screenX + iconSize + 2;
                int labelY = screenY + (iconSize / 2) - 4;
                graphics.fill(labelX - 1, labelY - 1, labelX + font.width(label) + 2, labelY + 10, 0xAA111820);
                graphics.text(font, Component.literal(label), labelX, labelY, 0xFFFFFFFF);
            }
        }
    }

    //allows the map to be panned by dragging with the left mouse button, zoomed with the scroll wheel, and chunks to be selected or deselected with left and right clicks
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

    //renders the territory on the map, including chunk ownership, settlements, and borders, using the provided snapshot of the map data
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

    //draws a hatching pattern on the map for unclaimed chunks
    private void drawHatching(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
        if (zoom < 6.0) {
            return;
        }
        for (int row = top + 2; row < bottom; row += 4) {
            graphics.fill(left + 1, row, right - 1, Math.min(row + 1, bottom), 0x5A111111);
        }
    }

    //draws borders around chunks using a mask, using the specified color and thickness
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

    //highlights chunks that have breach alerts, drawing a red border around them
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

    //primary method for drawing province labels and settlement markers on the map, ensuring that labels do not overlap and are only drawn when zoomed in sufficiently
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

            //only draw province markers when close enough
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

    //draws a marker for a settlement on the map
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

    //adds the coordinates of the chunk the users mouse is hovering over
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

    //left click:  Select an army (priority) or a chunk.
    //right click: If an army is selected, dispatch it to the clicked chunk.
    //Shift + Right click queues an additional waypoint instead of overwriting.
    //If no army is selected, deselects everything.
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!contains(mouseX, mouseY)) return false;

        int chunkX = floorChunk(screenToChunkX(mouseX));
        int chunkZ = floorChunk(screenToChunkZ(mouseY));

        if (button == 0) {
            // Priority 0: spectate eye on an engaged Army?
            ArmyMapPayload.ArmySummary engagedArmy = getEngagedArmyEyeAt(mouseX, mouseY);
            if (engagedArmy != null && engagedArmy.battleId() != null) {
                ClientNetworking.requestSpectate(engagedArmy.battleId());
                return true;
            }

            // Priority 1: did the player click an Army icon?
            UUID clickedArmyId = ClientArmyData.get().getArmyIdAtChunk(chunkX, chunkZ);
            if (clickedArmyId != null) {
                this.selectedArmyId = clickedArmyId;
                this.selectedIsArmy = true;
                this.selectedChunk = Long.MIN_VALUE;
                dragging = true;
                return true;
            }

            // Priority 2: did the player click a standalone Legion icon?
            UUID clickedLegionId = ClientArmyData.get().getLegionIdAtChunk(chunkX, chunkZ);
            if (clickedLegionId != null) {
                this.selectedArmyId = clickedLegionId;
                this.selectedIsArmy = false;
                this.selectedChunk = Long.MIN_VALUE;
                dragging = true;
                return true;
            }

            // Priority 3: clear selection or fall through to chunk
            if (this.selectedArmyId != null) {
                this.selectedArmyId = null;
                dragging = true;
                return true;
            }

            if (ClientMapData.get().getChunk(chunkX, chunkZ) != null) {
                selectedChunk = new ChunkPos(chunkX, chunkZ).pack();
            } else {
                selectedChunk = Long.MIN_VALUE;
            }
            dragging = true;
            return true;
        }

        if (button == 1) {
            if (this.selectedArmyId != null) {
                boolean isQueueing = net.minecraft.client.Minecraft.getInstance().options.keyShift.isDown();
                BlockPos targetPos = new BlockPos(chunkX * 16 + 8, 100, chunkZ * 16 + 8);

                if (selectedIsArmy) {
                    ClientPacketDistributor.sendToServer(
                            new DispatchArmyPayload(this.selectedArmyId, targetPos, isQueueing));
                } else {
                    ClientPacketDistributor.sendToServer(
                            new DispatchLegionPayload(this.selectedArmyId, targetPos, isQueueing));
                }
                return true;
            }
            selectedChunk = Long.MIN_VALUE;
            return true;
        }

        return false;
    }

    //stops dragging the map when the left mouse button is released
    public boolean mouseReleased(int button) {
        if (button == 0 && dragging) {
            dragging = false;
            return true;
        }
        return false;
    }

    //gets army summary when the spectate eye button is clicked, allowing the player to spectate a battle
    private ArmyMapPayload.ArmySummary getEngagedArmyEyeAt(double mx, double mz) {
        ClientArmyData.Snapshot armies = ClientArmyData.get();
        if (!armies.hasAnyUnits()) return null;

        int iconSize = Math.max(6, Math.min(ARMY_ICON_BASE_PX, (int) zoom));

        for (ArmyMapPayload.ArmySummary army : armies.armiesById().values()) {
            if (!army.isEngaged() || army.battleId() == null) continue;
            ChunkPos chunkPos = ChunkPos.unpack(army.packedChunkPos());
            int screenX = chunkToScreenX(chunkPos.x());
            int screenY = chunkToScreenY(chunkPos.z());

            int eyeX = screenX + iconSize / 2 - 3;
            int eyeY = screenY + iconSize + 4;
            if (mx >= eyeX - 1 && mx <= eyeX + 10 && mz >= eyeY - 1 && mz <= eyeY + 9) {
                return army;
            }
        }
        return null;
    }

    //allows the user to drag the map by updating the center chunk coordinates based on mouse movement
    public boolean mouseDragged(double deltaX, double deltaY, int button) {
        if (button != 0 || !dragging) {
            return false;
        }

        centerChunkX -= deltaX / zoom;
        centerChunkZ -= deltaY / zoom;
        return true;
    }

    //allows user to zoom in and out of the map, also adjusts center chunk coordinates to keep the mouse position consistent during zooming
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

    //controls the "reset" button, which resets the map to the default zoom and centers it on the player's current position
    public void resetView() {
        ClientMapData.Snapshot snapshot = ClientMapData.get();
        centerChunkX = snapshot.centerChunkX();
        centerChunkZ = snapshot.centerChunkZ();
        zoom = calculateFitZoom(snapshot);
        selectedChunk = Long.MIN_VALUE;
        selectedArmyId = null;
    }

    //gets the currently selected chunk, returning null if no chunk is selected
    public MapDataPayload.MapChunkData getSelectedChunk() {
        if (selectedChunk == Long.MIN_VALUE) {
            return null;
        }
        return ClientMapData.get().chunksByPosition().get(selectedChunk);
    }

    //gets the currently selected chunk position, returning null if no chunk is selected
    public ChunkPos getSelectedPosition() {
        return selectedChunk == Long.MIN_VALUE ? null : ChunkPos.unpack(selectedChunk);
    }

    //returns the UUID of the currently selected legion, or null if none is selected
    public UUID getSelectedArmyId() {
        return selectedArmyId;
    }

    //clears the currently selected legion
    public void clearSelectedArmy() {
        this.selectedArmyId = null;
    }

    //gets the currently selected chunk position, returning null if no chunk is selected
    private void refreshViewportForNewData(ClientMapData.Snapshot snapshot) {
        if (snapshot.version() == loadedSnapshotVersion) {
            return;
        }
        //check if this is teh intial load of the map
        boolean isFirstLoad = (loadedSnapshotVersion == -1);
        loadedSnapshotVersion = snapshot.version();
        if(isFirstLoad) {
            resetView();
        }
    }

    //calculates the zoom level
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

    //colors the state territories based on their UUID, using a hash function to generate a unique color for each state, with different alpha values for the viewer's state and other states
    private static int stateFillColor(UUID stateId, boolean viewerState) {
        int hash = stateId.hashCode();
        int red = 72 + Math.floorMod(hash, 112);
        int green = 72 + Math.floorMod(hash >> 8, 112);
        int blue = 72 + Math.floorMod(hash >> 16, 112);
        int alpha = viewerState ? 0xB8 : 0x82;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    // Army icon color — same hash-derived hue as the territory but pushed into a brighter range (140–254)
    // so icons are visually distinct from the muted territory fill beneath them.
    // Viewer's own legions are fully opaque; enemy legions use reduced alpha (added in Sprint 6).
    private static int armyIconColor(UUID stateId, boolean isViewerLegion) {
        int hash = stateId.hashCode();
        int red   = 140 + Math.floorMod(hash,        114);
        int green = 140 + Math.floorMod(hash >> 8,   114);
        int blue  = 140 + Math.floorMod(hash >> 16,  114);
        int alpha = isViewerLegion ? 0xFF : 0xCC;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    //helper method to trim a string to a maximum number of characters, adding "..." if the string is too long, or returning an empty string if the value is null
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

    //helper method to draw a dotted line between two points, using linear interpolation to calculate the positions of the dots
    /*private static void drawDottedLine(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color) {
        double distance = Math.hypot(x2 - x1, y2 - y1);
        double step = 4.0; // Distance between dots
        double dx = (x2 - x1) / distance * step;
        double dy = (y2 - y1) / distance * step;

        for (double d = 0; d < distance; d += step) {
            int dotX = (int) Math.round(x1 + dx * (d / step));
            int dotY = (int) Math.round(y1 + dy * (d / step));
            graphics.fill(dotX - 1, dotY - 1, dotX + 2, dotY + 2, color);
        }
    }*/
    private void drawDottedLine(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color) {
        double distance = Math.hypot(x2 - x1, y2 - y1);
        int dotSpacing = 8; //number of pixels between dots
        int dots = (int) (distance / dotSpacing); //number of dots to draw

        //draw each dot along the line using linear interpolation
        for (int i = 1; i <= dots; i++) {
            double t = (double) i / dots;
            int dotX = (int) (x1 + t * (x2 - x1));
            int dotY = (int) (y1 + t * (y2 - y1));
            graphics.fill(dotX, dotY, dotX + 2, dotY + 2, color);
        }
    }

}
