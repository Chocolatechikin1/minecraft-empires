package com.devc.minecraftempires.client.gui.widget;

import com.devc.minecraftempires.client.ClientNetworking;
import com.devc.minecraftempires.client.map.ClientArmyData;
import com.devc.minecraftempires.client.map.ClientMapData;
import com.devc.minecraftempires.client.map.ClientManagementData;
import com.devc.minecraftempires.state.StateBalance;
import com.devc.minecraftempires.network.packet.ArmyMapPayload;
import com.devc.minecraftempires.network.packet.DispatchArmyPayload;
import com.devc.minecraftempires.network.packet.DispatchLegionPayload;
import com.devc.minecraftempires.network.packet.ComposeArmyPayload;
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
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

//primary class for rendering the interactive map, including panning, zooming, and displaying chunk ownership, settlements, and alerts
public final class InteractiveMapWidget {
    private static final double MIN_ZOOM = 0.5;
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
    private boolean initializedViewport;
    private UUID viewportStateId;

    //currently selected unit (Legion, Army or independent Cohort UUID), null = nothing selected
    private UUID selectedArmyId = null;
    private boolean selectedIsArmy = false;
    private List<UUID> cohortOrderSelection = List.of();

    // Road plans are client previews; the server validates ownership, geometry and price atomically.
    private boolean roadPlanning, bridgePlanning, supplyOverlay;
    private final List<BlockPos> roadPoints = new ArrayList<>();
    private List<List<BlockPos>> routePreview = List.of();
    public void setRoutePreview(List<List<BlockPos>> runs) {
        refreshViewport(); routePreview = runs;
        List<BlockPos> points = runs.stream().flatMap(List::stream).toList(); if (points.isEmpty()) return;
        int minX = points.stream().mapToInt(BlockPos::getX).min().orElse(0), maxX = points.stream().mapToInt(BlockPos::getX).max().orElse(0);
        int minZ = points.stream().mapToInt(BlockPos::getZ).min().orElse(0), maxZ = points.stream().mapToInt(BlockPos::getZ).max().orElse(0);
        centerChunkX = (minX + maxX) / 32.0; centerChunkZ = (minZ + maxZ) / 32.0;
        zoom = Math.clamp(Math.min(width * 16.0 / (maxX - minX + 64), height * 16.0 / (maxZ - minZ + 64)), MIN_ZOOM, MAX_ZOOM);
    }
    private UUID selectedCampId;
    private UUID focusedBridgeId;
    public void clearBridgeFocus() { focusedBridgeId = null; }
    public void focusBridge(UUID id) {
        refreshViewport();
        var bridge = ClientMapData.get().roads().stream().filter(road -> road.bridge() && road.id().equals(id) && !road.points().isEmpty()).findFirst().orElse(null);
        if (bridge == null) return;
        focusedBridgeId = id;
        var first = bridge.points().getFirst(); var last = bridge.points().getLast();
        centerChunkX = (first.getX() + last.getX() + 1) / 32.0; centerChunkZ = (first.getZ() + last.getZ() + 1) / 32.0;
        double fit = Math.min(width, height) * 16.0 / (Math.max(Math.abs(first.getX() - last.getX()), Math.abs(first.getZ() - last.getZ())) + 32);
        zoom = Math.clamp(Math.min(fit, Math.max(zoom, 12)), MIN_ZOOM, MAX_ZOOM); dragging = false;
    }
    private Object viewportWorld;
    private int hoverX, hoverY;
    private final List<int[]> occupiedLabels = new ArrayList<>();
    private final List<MapLabel> pendingLabels = new ArrayList<>();
    private static final float LABEL_SCALE = .75f;

    public InteractiveMapWidget(int x, int y, int width, int height) {
        setBounds(x, y, width, height);
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    // Initialize the context before a management action; its first render must not clear the new mode.
    public void refreshViewport() { refreshViewportForNewData(ClientMapData.get()); }

    //sets the center of the map to the specified chunk coordinates, clamping the zoom level to the allowed range
    public void render(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        ClientMapData.Snapshot snapshot = ClientMapData.get();
        refreshViewportForNewData(snapshot);
        hoverX = mouseX; hoverY = mouseY;
        occupiedLabels.clear();
        pendingLabels.clear();

        graphics.fill(x, y, x + width, y + height, 0xFF111820);
        graphics.enableScissor(x, y, x + width, y + height);

        // Terrain uses world blocks; the existing political camera uses chunks (16 blocks each).
        ClientMapData.renderTerrain(graphics, font, x, y, width, height,
                centerChunkX * 16, centerChunkZ * 16, zoom / 16, null);

        drawGrid(graphics);
        if (snapshot.hasData()) {
            ClientMapData.renderPolitics(graphics, font, x, y, width, height, centerChunkX * 16, centerChunkZ * 16, zoom / 16);
            drawTerritory(graphics, snapshot);
            drawLogistics(graphics, font, snapshot);
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
        drawDetachedCohorts(graphics, font, snapshot);
        drawPendingLabels(graphics, font);


        graphics.disableScissor();
        graphics.fill(x, y, x + width, y + 1, 0xFF485563);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF485563);
        graphics.fill(x, y, x + 1, y + height, 0xFF485563);
        graphics.fill(x + width - 1, y, x + width, y + height, 0xFF485563);

        drawCoordinates(graphics, font, mouseX, mouseY);
        drawMarkerTooltip(graphics, font, snapshot, mouseX, mouseY);

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
        for (ArmyMapPayload.LegionSummary legion : armies.legionsById().values().stream()
                .sorted(Comparator.comparingInt(legion -> legion.legionId().equals(selectedArmyId) ? 0 : 1)).toList()) {
            ChunkPos chunkPos = ChunkPos.unpack(legion.packedChunkPos());
            int screenX = chunkToScreenX(chunkPos.x());
            int screenY = chunkToScreenY(chunkPos.z());
            if (legion.legionId().equals(selectedArmyId) && !selectedIsArmy)
                drawMovementPath(graphics, screenX + iconSize / 2, screenY + iconSize / 2, legion.waypoints());
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
                //shows the number of available soldiers in the legion next to the icon, colored in light gray
                queueMapLabel(label, labelX, labelY, 0xFFCCCCCC, false,
                        legion.legionId().equals(selectedArmyId) ? 0 : nearMarker(screenX, screenY) ? 1 : 5);
            }

            // Yellow glow if selected
            if (legion.legionId().equals(selectedArmyId) && !selectedIsArmy) {
                drawMask(graphics, screenX - 2, screenY - 2, screenX + iconSize + 2, screenY + iconSize + 2, ClientMapData.BORDER_NORTH | ClientMapData.BORDER_EAST | ClientMapData.BORDER_SOUTH | ClientMapData.BORDER_WEST, 0xFFFFE84A, 2);
            }
        }

        //draws armies
        for (ArmyMapPayload.ArmySummary army : armies.armiesById().values().stream()
                .sorted(Comparator.comparingInt(army -> army.armyId().equals(selectedArmyId) ? 0 : 1)).toList()) {
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
                boolean hovered = hoverX >= eyeX - 1 && hoverX <= eyeX + 10 && hoverY >= eyeY - 1 && hoverY <= eyeY + 9;
                graphics.fill(eyeX - 1, eyeY - 1, eyeX + 10, eyeY + 9, hovered ? 0xFF6C8EB1 : 0xFF305A80);
                graphics.fill(eyeX - 1, eyeY - 1, eyeX + 10, eyeY, 0xFFB9D5EB);
                graphics.fill(eyeX - 1, eyeY, eyeX, eyeY + 9, 0xFFB9D5EB);
                graphics.fill(eyeX, eyeY + 8, eyeX + 10, eyeY + 9, 0xFF11283E);
                // A tiny eye fits the existing hit box without a font-dependent symbol or new texture.
                graphics.fill(eyeX + 2, eyeY + 2, eyeX + 7, eyeY + 3, 0xFFF3F9FF);
                graphics.fill(eyeX + 1, eyeY + 3, eyeX + 8, eyeY + 5, 0xFFF3F9FF);
                graphics.fill(eyeX + 2, eyeY + 5, eyeX + 7, eyeY + 6, 0xFFF3F9FF);
                graphics.fill(eyeX + 4, eyeY + 3, eyeX + 6, eyeY + 5, 0xFF132B42);
                if (hovered && font != null) graphics.setTooltipForNextFrame(font, Component.literal("Open battle map"), hoverX, hoverY);
            }

            // Troop count label
            if (zoom >= 12.0 && font != null) {
                String label = army.displayName() + " \u00b7 " + army.troops(); //army.troops might be unncessary, mark for now TODO: 
                int labelX = screenX + iconSize + 2;
                int labelY = screenY + (iconSize / 2) - 4;
                queueMapLabel(label, labelX, labelY, 0xFFFFFFFF, false,
                        army.armyId().equals(selectedArmyId) ? 0 : nearMarker(screenX, screenY) ? 1 : 5);
            }
        }
    }

    private void drawDetachedCohorts(GuiGraphicsExtractor graphics, Font font, ClientMapData.Snapshot snapshot) {
        for (var c : ClientArmyData.get().cohortsById().values()) {
            int px = worldScreenX(c.position()), py = worldScreenY(c.position());
            boolean selected = c.cohortId().equals(selectedArmyId) || cohortOrderSelection.contains(c.cohortId());
            if (intersects(px - 4, py - 4, 9, 9)) {
                int color = armyIconColor(c.ownerStateId(), c.ownerStateId().equals(snapshot.viewerStateId()));
                graphics.fill(px - 3, py - 3, px + 4, py + 4, color);
                graphics.fill(px - 1, py - 1, px + 2, py + 2, 0xFF17232E);
                if (selected) drawMask(graphics, px - 6, py - 6, px + 7, py + 7, 15, 0xFFFFE84A, 1);
                if (zoom >= 12 && font != null) queueMapLabel("C " + c.soldiers(), px + 7, py - 3, color, false, selected ? 0 : 5);
            }
            if (selected) drawMovementPath(graphics, px, py, c.waypoints());
        }
    }
    private void drawMovementPath(GuiGraphicsExtractor graphics, int lastX, int lastY, List<BlockPos> points) {
        for (BlockPos point : points) {
            int px = worldScreenX(point), py = worldScreenY(point);
            drawDottedLine(graphics, lastX, lastY, px, py, 0xAAFFE84A);
            graphics.fill(px - 2, py - 2, px + 2, py + 2, 0xFFFFE84A);
            lastX = px; lastY = py;
        }
    }
    public List<UUID> getCohortOrderSelection() { return cohortOrderSelection; }
    /** Explicit management focus does not issue orders or disturb the ordinary return camera. */
    public void focusLocations(List<BlockPos> positions, UUID selected) {
        if (positions.isEmpty()) return;
        double minX = positions.stream().mapToDouble(BlockPos::getX).min().orElse(0), maxX = positions.stream().mapToDouble(BlockPos::getX).max().orElse(0);
        double minZ = positions.stream().mapToDouble(BlockPos::getZ).min().orElse(0), maxZ = positions.stream().mapToDouble(BlockPos::getZ).max().orElse(0);
        centerChunkX = (minX + maxX + 1) / 32.0; centerChunkZ = (minZ + maxZ + 1) / 32.0;
        if (positions.size() > 1) zoom = Math.clamp(Math.min(Math.max(1, width - 48) / ((maxX - minX) / 16 + 3),
                Math.max(1, height - 48) / ((maxZ - minZ) / 16 + 3)), MIN_ZOOM, MAX_ZOOM);
        initializedViewport = true;
        selectUnit(selected);
    }
    public BlockPos positionAt(double screenX, double screenY) {
        return new BlockPos((int) Math.floor(screenToChunkX(screenX) * 16), 64, (int) Math.floor(screenToChunkZ(screenY) * 16));
    }
    public void selectUnit(UUID id) {
        if (id == null) { clearSelectedArmy(); return; }
        selectedArmyId = id; selectedIsArmy = ClientArmyData.get().armiesById().containsKey(id);
        cohortOrderSelection = ClientArmyData.get().cohortsById().containsKey(id) ? List.of(id) : List.of();
        selectedChunk = Long.MIN_VALUE; selectedCampId = null;
    }
    public void selectCohorts(List<UUID> ids) {
        cohortOrderSelection = List.copyOf(ids); selectedArmyId = ids.isEmpty() ? null : ids.getFirst(); selectedIsArmy = false;
        selectedChunk = Long.MIN_VALUE; selectedCampId = null;
        var first = ClientManagementData.get().cohorts().stream().filter(c -> c.id().equals(selectedArmyId)).findFirst().orElse(null);
        if (first != null) {
            centerChunkX = first.position().getX() / 16.0; centerChunkZ = first.position().getZ() / 16.0;
            initializedViewport = true;
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

        // Large overview bounds can contain mostly empty space; choose the smaller set to traverse.
        long cells = (long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        if (cells > snapshot.chunksByPosition().size()) {
            for (var chunk : snapshot.chunksByPosition().values()) {
                ChunkPos pos = ChunkPos.unpack(chunk.packedChunkPos());
                if (pos.x() < minChunkX || pos.x() > maxChunkX || pos.z() < minChunkZ || pos.z() > maxChunkZ) continue;
                drawTerritoryCell(graphics, snapshot, pos.x(), pos.z(), chunk);
            }
        } else {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++)
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
                    drawTerritoryCell(graphics, snapshot, chunkX, chunkZ, snapshot.getChunk(chunkX, chunkZ));
        }
    }

    private void drawTerritoryCell(GuiGraphicsExtractor graphics, ClientMapData.Snapshot snapshot,
            int chunkX, int chunkZ, MapDataPayload.MapChunkData chunk) {
        if (chunk == null) return;
        int left = chunkToScreenX(chunkX), top = chunkToScreenY(chunkZ);
        int right = chunkToScreenX(chunkX + 1.0), bottom = chunkToScreenY(chunkZ + 1.0);
        if (right <= left || bottom <= top) return;
        graphics.fill(left, top, right, bottom, stateFillColor(chunk.ownerStateId(), chunk.ownerStateId().equals(snapshot.viewerStateId())));
        if (chunk.settlementId().isBlank() || chunk.contested()) drawHatching(graphics, left, top, right, bottom);
        drawMask(graphics, left, top, right, bottom, snapshot.getProvinceBorderMask(chunkX, chunkZ), 0xAAB8C0C8, 1);
        drawMask(graphics, left, top, right, bottom, snapshot.getBorderMask(chunkX, chunkZ), 0xFFF4E6A2, zoom >= 10.0 ? 2 : 1);
        if (chunk.packedChunkPos() == selectedChunk) {
            drawMask(graphics, left, top, right, bottom,
                    ClientMapData.BORDER_NORTH | ClientMapData.BORDER_EAST | ClientMapData.BORDER_SOUTH | ClientMapData.BORDER_WEST,
                    0xFFFFFFFF, 2);
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
        for (ClientMapData.ProvinceAnchor anchor : snapshot.provinceAnchors()) {
            int markerX = chunkToScreenX(anchor.chunkX());
            int markerY = chunkToScreenY(anchor.chunkZ());
            if (!intersects(markerX - 30, markerY - 16, 60, 32)) {
                continue;
            }

            if (anchor.settlementMarker()) {
                drawSettlementMarker(graphics, markerX, markerY, anchor.capital(), anchor.garrisoned());
            }

            // Keep capitals labelled longer in the overview; every category hides at its widest zoom.
            if (zoom < (anchor.capital() ? .9 : anchor.settlementMarker() ? 1.7 : 3.0)) {
                continue;
            }
            int labelY = markerY - (anchor.settlementMarker() ? (anchor.capital() ? 15 : 12) : 5);
            var chosen = getSelectedChunk();
            int priority = chosen != null && anchor.settlementId().equals(chosen.settlementId()) ? 0
                    : nearMarker(markerX, markerY) ? 1 : anchor.capital() ? 2 : 3;
            queueMapLabel(anchor.displayName(), markerX, labelY, 0xFFF4F4F4, true, priority);
        }
    }

    // Screen-space glyph bounds keep labels legible without letting the map zoom enlarge them.
    private record MapLabel(String text, int x, int y, int color, boolean centered, int priority) {}
    private void queueMapLabel(String text, int labelX, int labelY, int color, boolean centered, int priority) {
        pendingLabels.add(new MapLabel(text, labelX, labelY, color, centered, priority));
    }
    private void drawPendingLabels(GuiGraphicsExtractor graphics, Font font) {
        pendingLabels.sort(Comparator.comparingInt(MapLabel::priority));
        for (MapLabel candidate : pendingLabels) {
            String label = trimToWidth(font, candidate.text(), Math.max(1, (int) (Math.min(110, width - 10) / LABEL_SCALE)));
            int labelWidth = (int) Math.ceil(font.width(label) * LABEL_SCALE);
            int left = candidate.centered() ? candidate.x() - labelWidth / 2 : candidate.x();
            int top = candidate.y(), right = left + labelWidth, bottom = top + (int) Math.ceil(font.lineHeight * LABEL_SCALE);
            if (left < x + 3 || right > x + width - 3 || top < y + 3 || bottom > y + height - 23) continue;
            if (occupiedLabels.stream().anyMatch(rect -> left - 3 < rect[2] && right + 3 > rect[0]
                    && top - 2 < rect[3] && bottom + 2 > rect[1])) continue;
            occupiedLabels.add(new int[]{left, top, right, bottom});
            graphics.fill(left - 2, top - 1, right + 2, bottom + 1, 0xAA111820);
            graphics.pose().pushMatrix();
            graphics.pose().translate(left, top);
            graphics.pose().scale(LABEL_SCALE, LABEL_SCALE);
            graphics.text(font, Component.literal(label), 0, 0, candidate.color());
            graphics.pose().popMatrix();
        }
    }
    private boolean nearMarker(int markerX, int markerY) {
        return contains(hoverX, hoverY) && Math.abs(hoverX - markerX) <= 10 && Math.abs(hoverY - markerY) <= 10;
    }

    // The overlay never joins separated visible runs across hidden territory.
    private void drawLogistics(GuiGraphicsExtractor graphics, Font font, ClientMapData.Snapshot snapshot) {
        if (ClientMapData.isLayerVisible(ClientMapData.MapLayer.ROADS)) {
            // Thin overview roads stay subordinate to terrain; planning and supply remain independently visible.
            int thickness = zoom >= 6 ? 2 : 1;
            for (var road : snapshot.roads()) drawConnectedPath(graphics, road.points(), road.bridge() ? 0xFFE6CE91 : 0xFFBA9B6D, road.bridge() ? Math.max(2, thickness) : thickness, road.bridge());
        }
        for (var bridge : snapshot.roads()) if (bridge.bridge() && !bridge.points().isEmpty() && bridge.id().equals(focusedBridgeId)) {
            drawConnectedPath(graphics, bridge.points(), 0xFFFFFF55, 3, true);
            for (BlockPos bank : List.of(bridge.points().getFirst(), bridge.points().getLast())) {
                int px = worldScreenX(bank), py = worldScreenY(bank);
                if (intersects(px - 5, py - 5, 11, 11)) drawMask(graphics, px - 5, py - 5, px + 6, py + 6, 15, 0xFFFFFF55, 1);
            }
        }
        if (supplyOverlay) {
            var route = ClientManagementData.get().selectedRoute();
            UUID expected = supplyTargetId();
            if (expected != null && expected.equals(route.targetId())) {
                drawConnectedPath(graphics, route.points(), 0xFF65E8D4, 2);
                // Selected routes are private authoritative geometry; a longer terminal leg is local delivery.
                for (int i = 1; i < route.points().size(); i++) {
                    BlockPos a = route.points().get(i - 1), b = route.points().get(i);
                    if (Math.abs((a.getX() >> 4) - (b.getX() >> 4)) + Math.abs((a.getZ() >> 4) - (b.getZ() >> 4)) > 1)
                        drawDottedLine(graphics, worldScreenX(a), worldScreenY(a), worldScreenX(b), worldScreenY(b), 0xFF65E8D4);
                }
            }
        }
        for (List<BlockPos> run : routePreview) drawConnectedPath(graphics, run, 0xFF65E8D4, 2);
        if (roadPlanning) {
            drawConnectedPath(graphics, expandedRoadPreview(), roadPreviewValid() ? 0xFF88E577 : 0xFFFF7777, 2);
            for (BlockPos point : roadPoints) {
                int px = worldScreenX(point), py = worldScreenY(point);
                graphics.fill(px - 3, py - 3, px + 4, py + 4, 0xFFFFE84A);
            }
        }
        for (var camp : snapshot.camps()) {
            int px = worldScreenX(camp.position()), py = worldScreenY(camp.position());
            if (!intersects(px - 6, py - 6, 12, 12)) continue;
            boolean own = camp.ownerId().equals(snapshot.viewerStateId());
            int color = own ? 0xFFDAA968 : armyIconColor(camp.ownerId(), false);
            graphics.fill(px - 5, py + 1, px + 6, py + 5, color);
            graphics.fill(px - 3, py - 2, px + 4, py + 1, color);
            graphics.fill(px - 1, py - 5, px + 2, py - 2, color);
            if (camp.id().equals(selectedCampId)) drawMask(graphics, px - 8, py - 8, px + 9, py + 9, 15, 0xFFFFE84A, 1);
            if (zoom >= 2.5) queueMapLabel("Camp", px, py - 13, color, true,
                    camp.id().equals(selectedCampId) ? 0 : nearMarker(px, py) ? 1 : 4);
        }
        if (roadPlanning || snapshot.logisticsTruncated() || !cohortOrderSelection.isEmpty()) {
            String message = roadPlanning ? (bridgePlanning ? "BRIDGE PLAN: click two banks | right-click undo | middle-drag" : "ROAD PLAN: click points | right-click undo | drag with middle button")
                    : !cohortOrderSelection.isEmpty() ? cohortOrderSelection.size() + " cohorts: right-click destination | Shift queues | Escape clears"
                    : "Some infrastructure omitted at this scale; refresh closer to inspect.";
            // Leave the upper-right row clear for the map's view and layer buttons.
            graphics.fill(x + 4, y + 32, x + width - 4, y + 47, 0xD0111820);
            graphics.text(font, Component.literal(font.plainSubstrByWidth(message, width - 14)), x + 7, y + 35, 0xFFFFDE94);
        }
    }
    private int worldScreenX(BlockPos point) { return chunkToScreenX(point.getX() / 16.0); }
    private int worldScreenY(BlockPos point) { return chunkToScreenY(point.getZ() / 16.0); }
    private void drawConnectedPath(GuiGraphicsExtractor graphics, List<BlockPos> points, int color, int thickness) {
        drawConnectedPath(graphics, points, color, thickness, false);
    }
    private void drawConnectedPath(GuiGraphicsExtractor graphics, List<BlockPos> points, int color, int thickness, boolean bridge) {
        BlockPos previous = null;
        for (BlockPos point : points) {
            int px = worldScreenX(point), py = worldScreenY(point);
            if (intersects(px - thickness, py - thickness, thickness * 2, thickness * 2))
                graphics.fill(px - thickness / 2, py - thickness / 2, px + thickness, py + thickness, color);
            if (previous != null) {
                int dx = Math.abs((previous.getX() >> 4) - (point.getX() >> 4));
                int dz = Math.abs((previous.getZ() >> 4) - (point.getZ() >> 4));
                if (dx + dz <= 1 || bridge) {
                    int beforeX = worldScreenX(previous), beforeY = worldScreenY(previous);
                    if (previous.getX() == point.getX() || previous.getZ() == point.getZ())
                        graphics.fill(Math.min(px, beforeX), Math.min(py, beforeY), Math.max(px, beforeX) + thickness, Math.max(py, beforeY) + thickness, color);
                    else drawDottedLine(graphics, beforeX, beforeY, px, py, color);
                }
            }
            previous = point;
        }
    }

    private void drawMarkerTooltip(GuiGraphicsExtractor graphics, Font font, ClientMapData.Snapshot snapshot, int mx, int my) {
        if (!contains(mx, my) || roadPlanning) return;
        String text = null;
        for (var anchor : snapshot.provinceAnchors())
            if (nearMarker(chunkToScreenX(anchor.chunkX()), chunkToScreenY(anchor.chunkZ()))) text = anchor.displayName();
        for (var camp : snapshot.camps()) if (nearMarker(worldScreenX(camp.position()), worldScreenY(camp.position()))) {
            text = "Marching camp";
            if (camp.ownerId().equals(snapshot.viewerStateId())) {
                var own = ClientManagementData.get().camps().stream().filter(c -> c.id().equals(camp.id())).findFirst().orElse(null);
                if (own != null) text += " | " + own.stock() + "/" + own.capacity() + " | " + (own.connected() ? "Connected" : "Isolated");
            }
        }
        for (var army : ClientArmyData.get().armiesById().values()) {
            ChunkPos pos = ChunkPos.unpack(army.packedChunkPos());
            if (nearMarker(chunkToScreenX(pos.x()), chunkToScreenY(pos.z()))) text = army.displayName() + " | " + army.troops() + " soldiers";
        }
        for (var legion : ClientArmyData.get().legionsById().values()) {
            ChunkPos pos = ChunkPos.unpack(legion.packedChunkPos());
            if (nearMarker(chunkToScreenX(pos.x()), chunkToScreenY(pos.z()))) text = "Legion | " + legion.availableSoldiers() + " soldiers";
        }
        for (var c : ClientArmyData.get().cohortsById().values()) if (nearMarker(worldScreenX(c.position()), worldScreenY(c.position())))
            text = "Detached cohort | " + c.soldiers() + " soldiers | " + c.position().getX() + ", " + c.position().getZ()
                    + " | Click repeatedly to cycle; Shift-click when sharing a legion/army marker";
        if (text == null) return;
        // Wrap the full label/instructions instead of silently cutting off the useful end of the tooltip.
        var lines = font.split(Component.literal(text), Math.max(1, Math.min(240, graphics.guiWidth() - 24)));
        // Vanilla places this after the sidebar/widgets and flips it at screen edges, even on a narrow map.
        graphics.setTooltipForNextFrame(font, lines, mx, my);
    }

    public boolean isRoadPlanning() { return roadPlanning; }
    public void setRoadPlanning(boolean enabled) { roadPlanning = enabled; bridgePlanning = false; roadPoints.clear(); dragging = false; focusedBridgeId = null; }
    public void setBridgePlanning() { setRoadPlanning(true); bridgePlanning = true; }
    public void restoreBridgePlan(List<BlockPos> points) { setBridgePlanning(); roadPoints.addAll(points.stream().limit(2).toList()); }
    public boolean isBridgePlanning() { return bridgePlanning; }
    public List<BlockPos> getRoadPoints() { return List.copyOf(roadPoints); }
    public void clearRoadPlan() { roadPoints.clear(); }
    public boolean isSupplyOverlay() { return supplyOverlay; }
    public void toggleSupplyOverlay() { supplyOverlay = !supplyOverlay; }
    public UUID getSelectedCampId() { return selectedCampId; }
    public void clearSelectedCamp() { selectedCampId = null; }
    public UUID supplyTargetId() {
        if (selectedArmyId == null) return null;
        var management = ClientManagementData.get();
        if (!Objects.equals(management.stateId(), ClientMapData.get().viewerStateId())) return null;
        if (management.cohorts().stream().anyMatch(c -> c.id().equals(selectedArmyId))) return selectedArmyId;
        if (selectedIsArmy) return ClientArmyData.get().armiesById().containsKey(selectedArmyId)
                && ClientArmyData.get().armiesById().get(selectedArmyId).ownerStateId().equals(management.stateId()) ? selectedArmyId : null;
        return management.cohorts().stream().filter(c -> c.legionId().equals(selectedArmyId)
                && c.armyId() == null && c.settlementId() == null && c.soldiers() > 0
                && !ClientArmyData.get().cohortsById().containsKey(c.id())).map(c -> c.id()).findFirst().orElse(null);
    }
    // Expand the same cardinal staircase used by the server, with an early preview bound.
    public List<BlockPos> expandedRoadPreview() {
        if (bridgePlanning) return List.copyOf(roadPoints);
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos target : roadPoints) {
            if (result.isEmpty()) result.add(target);
            else {
                BlockPos from = result.getLast();
                int cx = from.getX() >> 4, cz = from.getZ() >> 4, tx = target.getX() >> 4, tz = target.getZ() >> 4;
                while ((cx != tx || cz != tz) && result.size() <= StateBalance.MAX_ROAD_CHUNKS) {
                    if (cx != tx && (cz == tz || Math.abs(tx - cx) >= Math.abs(tz - cz))) cx += Integer.signum(tx - cx);
                    else cz += Integer.signum(tz - cz);
                    result.add(new BlockPos(cx * 16 + 8, 100, cz * 16 + 8));
                }
            }
            if (result.size() > StateBalance.MAX_ROAD_CHUNKS) break;
        }
        return result;
    }
    public boolean roadPreviewValid() {
        if (bridgePlanning) return roadPoints.size() == 2
                && (roadPoints.getFirst().getX() == roadPoints.getLast().getX() || roadPoints.getFirst().getZ() == roadPoints.getLast().getZ())
                && roadPoints.getFirst().distManhattan(roadPoints.getLast()) >= 2
                && roadPoints.getFirst().distManhattan(roadPoints.getLast()) <= 64;
        List<BlockPos> points = expandedRoadPreview();
        if (roadPoints.size() < 2 || points.size() > StateBalance.MAX_ROAD_CHUNKS) return false;
        UUID viewer = ClientMapData.get().viewerStateId();
        for (BlockPos p : points) {
            var claim = ClientMapData.get().getChunk(p.getX() >> 4, p.getZ() >> 4);
            if (claim == null || !claim.ownerStateId().equals(viewer)) return false;
        }
        return true;
    }
    public int roadNewCells() {
        Set<Long> existing = new HashSet<>(), planned = new HashSet<>();
        for (var road : ClientMapData.get().roads()) if (!road.bridge()) for (BlockPos p : road.points()) existing.add(ChunkPos.containing(p).pack());
        for (BlockPos p : expandedRoadPreview()) planned.add(ChunkPos.containing(p).pack());
        planned.removeAll(existing);
        return planned.size();
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
        Integer elevation = ClientMapData.terrainHeight(screenToChunkX(mouseX) * 16, screenToChunkZ(mouseY) * 16);
        if (elevation != null) text += " | Y " + elevation;
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
        return mouseClicked(mouseX, mouseY, button, false);
    }
    public boolean mouseClicked(double mouseX, double mouseY, int button, boolean shiftHeld) {
        if (!contains(mouseX, mouseY)) return false;

        int chunkX = floorChunk(screenToChunkX(mouseX));
        int chunkZ = floorChunk(screenToChunkZ(mouseY));

        if (roadPlanning) {
            if (button == 0) {
                BlockPos point = bridgePlanning ? new BlockPos((int) Math.floor(screenToChunkX(mouseX) * 16), 100,
                        (int) Math.floor(screenToChunkZ(mouseY) * 16)) : new BlockPos(chunkX * 16 + 8, 100, chunkZ * 16 + 8);
                if (roadPoints.size() < (bridgePlanning ? 2 : StateBalance.MAX_ROAD_POINTS)
                        && ((!bridgePlanning && roadPoints.size() <= 1) || roadPoints.isEmpty() || !roadPoints.getLast().equals(point))) roadPoints.add(point);
                return true;
            }
            if (button == 1) { if (!roadPoints.isEmpty()) roadPoints.removeLast(); return true; }
            if (button == 2) { dragging = true; return true; }
            return true;
        }

        if (button == 0) {
            var nearbyCohorts = ClientArmyData.get().cohortsById().values().stream()
                    .filter(c -> Math.abs(mouseX - worldScreenX(c.position())) <= 6 && Math.abs(mouseY - worldScreenY(c.position())) <= 6)
                    .map(ArmyMapPayload.CohortSummary::cohortId).sorted().toList();
            boolean shared = ClientArmyData.get().getArmyIdAtChunk(chunkX, chunkZ) != null
                    || ClientArmyData.get().getLegionIdAtChunk(chunkX, chunkZ) != null;
            if (!nearbyCohorts.isEmpty() && (!shared || shiftHeld)) {
                int next = (nearbyCohorts.indexOf(selectedArmyId) + 1) % nearbyCohorts.size();
                UUID id = nearbyCohorts.get(next);
                if (shiftHeld) {
                    List<UUID> selected = new ArrayList<>(cohortOrderSelection);
                    if (selected.contains(id)) selected.remove(id); else selected.add(id);
                    cohortOrderSelection = List.copyOf(selected); selectedArmyId = selected.isEmpty() ? null : selected.getLast(); selectedIsArmy = false;
                } else selectUnit(id);
                return true;
            }
            cohortOrderSelection = List.of();
            // Priority 0: spectate eye on an engaged Army?
            ArmyMapPayload.ArmySummary engagedArmy = getEngagedArmyEyeAt(mouseX, mouseY);
            if (engagedArmy != null && engagedArmy.battleId() != null) {
                ClientNetworking.requestSpectate(engagedArmy.battleId());
                return true;
            }

            // A second click on a camp under the selected unit opens the camp rather than trapping selection on the unit.
            if (selectedArmyId != null || shiftHeld) {
                for (var camp : ClientMapData.get().camps()) {
                    if (Math.abs(mouseX - worldScreenX(camp.position())) <= 8 && Math.abs(mouseY - worldScreenY(camp.position())) <= 8) {
                        selectedCampId = camp.id(); selectedChunk = ChunkPos.containing(camp.position()).pack();
                        return true;
                    }
                }
            }

            // Priority 1: did the player click an Army icon?
            UUID clickedArmyId = ClientArmyData.get().getArmyIdAtChunk(chunkX, chunkZ);
            if (clickedArmyId != null) {
                this.selectedArmyId = clickedArmyId;
                this.selectedIsArmy = true;
                this.selectedChunk = Long.MIN_VALUE;
                this.selectedCampId = null;
                dragging = true;
                return true;
            }

            // Priority 2: did the player click a standalone Legion icon?
            UUID clickedLegionId = ClientArmyData.get().getLegionIdAtChunk(chunkX, chunkZ);
            if (clickedLegionId != null) {
                this.selectedArmyId = clickedLegionId;
                this.selectedIsArmy = false;
                this.selectedChunk = Long.MIN_VALUE;
                this.selectedCampId = null;
                dragging = true;
                return true;
            }

            // Camp markers are independent saved staging points; retain an army selection for nearby dismantling.
            for (var camp : ClientMapData.get().camps()) {
                if (Math.abs(mouseX - worldScreenX(camp.position())) <= 8 && Math.abs(mouseY - worldScreenY(camp.position())) <= 8) {
                    this.selectedCampId = camp.id();
                    this.selectedChunk = ChunkPos.containing(camp.position()).pack();
                    return true;
                }
            }
            selectedCampId = null;
            // Priority 3: clear selection or fall through to chunk
            if (this.selectedArmyId != null) {
                this.selectedArmyId = null;
                dragging = true;
                return true;
            }

            // Remembered settlement markers can be inspected without inventing a claimed map chunk.
            for (var anchor : ClientMapData.get().provinceAnchors()) if (anchor.settlementMarker()
                    && Math.abs(mouseX - chunkToScreenX(anchor.chunkX())) <= 7 && Math.abs(mouseY - chunkToScreenY(anchor.chunkZ())) <= 7) {
                selectedChunk = new ChunkPos((int) Math.floor(anchor.chunkX()), (int) Math.floor(anchor.chunkZ())).pack();
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
                UUID viewer = ClientMapData.get().viewerStateId();
                var snapshot = ClientArmyData.get();
                UUID owner = selectedIsArmy
                        ? snapshot.armiesById().containsKey(selectedArmyId) ? snapshot.armiesById().get(selectedArmyId).ownerStateId() : null
                        : snapshot.cohortsById().containsKey(selectedArmyId) ? snapshot.cohortsById().get(selectedArmyId).ownerStateId()
                        : !cohortOrderSelection.isEmpty() ? ClientManagementData.get().stateId()
                        : snapshot.legionsById().containsKey(selectedArmyId) ? snapshot.legionsById().get(selectedArmyId).ownerStateId() : null;
                if (viewer == null || !viewer.equals(owner)) return true;
                boolean isQueueing = shiftHeld;
                BlockPos targetPos = new BlockPos(chunkX * 16 + 8, 100, chunkZ * 16 + 8);

                if (!cohortOrderSelection.isEmpty()) {
                    ClientPacketDistributor.sendToServer(new ComposeArmyPayload(cohortOrderSelection, targetPos, "MOVE", isQueueing));
                } else if (selectedIsArmy) {
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
        if ((button == 0 || button == 2) && dragging) {
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
            if (!army.ownerStateId().equals(ClientMapData.get().viewerStateId())) continue;
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
        if ((button != 0 && button != 2) || !dragging) {
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
        cohortOrderSelection = List.of();
        selectedCampId = null;
    }

    //gets the currently selected chunk, returning null if no chunk is selected
    public MapDataPayload.MapChunkData getSelectedChunk() {
        if (selectedChunk == Long.MIN_VALUE) {
            return null;
        }
        var snapshot = ClientMapData.get();
        var actual = snapshot.chunksByPosition().get(selectedChunk);
        if (actual != null) return actual;
        // Presentation only: this result never enters the claim store or grants server-side ownership.
        return snapshot.settlementsById().values().stream().filter(site -> site.packedCenterChunk() == selectedChunk)
                .map(site -> new MapDataPayload.MapChunkData(selectedChunk, 1, site.stateId(), site.settlementId(), site.garrisoned(), site.tier(), false))
                .findFirst().orElse(null);
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
        cohortOrderSelection = List.of();
    }

    //gets the currently selected chunk position, returning null if no chunk is selected
    private void refreshViewportForNewData(ClientMapData.Snapshot snapshot) {
        Object world = net.minecraft.client.Minecraft.getInstance().level;
        if (world != viewportWorld || !Objects.equals(viewportStateId, snapshot.viewerStateId())) {
            viewportWorld = world;
            viewportStateId = snapshot.viewerStateId();
            initializedViewport = false;
            selectedArmyId = null; selectedCampId = null; selectedChunk = Long.MIN_VALUE;
            cohortOrderSelection = List.of();
            roadPlanning = false; roadPoints.clear(); supplyOverlay = false;
            focusedBridgeId = null;
        }
        if (snapshot.version() == loadedSnapshotVersion) {
            return;
        }
        //check if this is teh intial load of the map
        loadedSnapshotVersion = snapshot.version();
        if (!java.util.Objects.equals(viewportStateId, snapshot.viewerStateId())) {
            viewportStateId = snapshot.viewerStateId();
            initializedViewport = false;
        }
        // The first frame can be an empty snapshot while the map request is still travelling.
        if (!initializedViewport && snapshot.hasData()) {
            resetView();
            initializedViewport = true;
        }
    }

    //calculates the zoom level
    private double calculateFitZoom(ClientMapData.Snapshot snapshot) {
        if (!snapshot.hasData()) {
            return DEFAULT_ZOOM;
        }

        int chunkWidth = Math.max(1, snapshot.maxChunkX() - snapshot.minChunkX() + 1);
        int chunkHeight = Math.max(1, snapshot.maxChunkZ() - snapshot.minChunkZ() + 1);
        double horizontalFit = Math.max(MIN_ZOOM, (width - 36.0) / chunkWidth);
        double verticalFit = Math.max(MIN_ZOOM, (height - 36.0) / chunkHeight);
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
        int alpha = viewerState ? 0x38 : 0x24;
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

        // Clip the iteration interval as well as the graphics, so a distant waypoint never creates millions of dots.
        double first = 0, last = 1;
        double[] origin = {x1, y1}, delta = {(double) x2 - x1, (double) y2 - y1};
        int[] minimum = {x - 2, y - 2}, maximum = {x + width + 2, y + height + 2};
        for (int axis = 0; axis < 2; axis++) {
            if (delta[axis] == 0) { if (origin[axis] < minimum[axis] || origin[axis] > maximum[axis]) return; }
            else {
                double a = (minimum[axis] - origin[axis]) / delta[axis], b = (maximum[axis] - origin[axis]) / delta[axis];
                first = Math.max(first, Math.min(a, b)); last = Math.min(last, Math.max(a, b));
            }
        }
        if (first > last || dots == 0) return;

        //draw each dot along the line using linear interpolation
        for (int i = Math.max(1, (int) Math.floor(first * dots)); i <= Math.min(dots, (int) Math.ceil(last * dots)); i++) {
            double t = (double) i / dots;
            int dotX = (int) (x1 + t * (x2 - x1));
            int dotY = (int) (y1 + t * (y2 - y1));
            graphics.fill(dotX, dotY, dotX + 2, dotY + 2, color);
        }
    }

}
