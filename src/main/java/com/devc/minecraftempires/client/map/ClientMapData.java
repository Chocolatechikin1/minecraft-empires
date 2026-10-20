package com.devc.minecraftempires.client.map;

import com.devc.minecraftempires.network.packet.MapDataPayload;
import com.devc.minecraftempires.network.packet.StateActionPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import com.devc.minecraftempires.client.ClientNetworking;
import com.devc.minecraftempires.network.packet.RequestMapDataPayload;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.MapColor;
import java.util.Comparator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

//cleint side storage and processing of map data received from the server, including chunk ownership, settlement information, and breach alerts
public final class ClientMapData {
    public static final int BORDER_NORTH = 1;
    public static final int BORDER_EAST = 1 << 1;
    public static final int BORDER_SOUTH = 1 << 2;
    public static final int BORDER_WEST = 1 << 3;

    private static final AtomicInteger VERSION_COUNTER = new AtomicInteger();
    private static volatile Snapshot snapshot = Snapshot.empty(0);
    public record BridgeDraft(UUID id, UUID owner, List<BlockPos> points, String phase, String message) {}
    private static BridgeDraft bridgeDraft;
    public static BridgeDraft bridgeDraft() { return bridgeDraft; }
    public static void saveBridgeDraft(List<BlockPos> points) {
        UUID owner = snapshot.viewerStateId();
        if (owner == null || points.isEmpty()) { bridgeDraft = null; return; }
        if (bridgeDraft != null && bridgeDraft.owner.equals(owner) && bridgeDraft.points.equals(points)) return;
        bridgeDraft = new BridgeDraft(UUID.randomUUID(), owner, List.copyOf(points), "DRAFT", "Check the crossing before purchasing. Your bank positions are retained when you close the map.");
    }
    public static void clearBridgeDraft() { bridgeDraft = null; }
    public static void acceptBridgeStatus(StateActionPayload.BridgeStatus status) {
        if (bridgeDraft == null || !bridgeDraft.id.equals(status.planId()) || !bridgeDraft.owner.equals(status.ownerId())
                || !bridgeDraft.points.equals(status.points()) || !Objects.equals(snapshot.viewerStateId(), status.ownerId())) return;
        bridgeDraft = new BridgeDraft(status.planId(), status.ownerId(), status.points(), status.phase(), status.message());
    }
    public static void bridgeCheckRequested() {
        if (bridgeDraft != null) bridgeDraft = new BridgeDraft(bridgeDraft.id, bridgeDraft.owner, bridgeDraft.points,
                "CHECKING", "Checking the full three-block crossing. These points are saved; you can leave the map and return.");
    }

    private ClientMapData() {}

    public static Snapshot get() {
        return snapshot;
    }

    public static void accept(MapDataPayload payload) {
        if (!Objects.equals(snapshot.viewerStateId(), payload.viewerStateId())) setTerrainState(payload.viewerStateId());
        snapshot = Snapshot.from(payload, VERSION_COUNTER.incrementAndGet());
        politicalRegions = payload.politicalRegions();
        claimsTruncated = payload.claimsTruncated();
    }

    //terrain view toggle: these are local styles over the same server-surveyed pixels.
    //3 styles selected: stylized (default), topographic (elevation), atlas (political)
    public enum TerrainView {
        STYLIZED("stylized"), TOPOGRAPHIC("topographic"), ATLAS("atlas");
        private final String key;
        TerrainView(String key) { this.key = key; }
        public Component label() { return Component.translatable("gui.minecraftempires.map.view." + key); }
        public TerrainView next() { return values()[(ordinal() + 1) % values().length]; }
    }
    private static TerrainView empireView = TerrainView.STYLIZED, battleView = TerrainView.STYLIZED;
    public static TerrainView terrainView(boolean battle) { return battle ? battleView : empireView; }
    public static Component terrainViewButton(boolean battle) {
        return Component.translatable("gui.minecraftempires.map.view", terrainView(battle).label());
    }
    public static void cycleTerrainView(boolean battle) {
        if (battle) battleView = battleView.next(); else empireView = empireView.next();
    }

    // Local overlay preferences affect drawing only. Ownership can join this set when its control is added.
    public enum MapLayer { ROADS }
    private static final java.util.EnumSet<MapLayer> visibleLayers = java.util.EnumSet.allOf(MapLayer.class);
    public static boolean isLayerVisible(MapLayer layer) { return visibleLayers.contains(layer); }
    public static void toggleLayer(MapLayer layer) {
        if (!visibleLayers.remove(layer)) visibleLayers.add(layer);
    }

    //terrain cache for backend/server side: CPU samples and GPU textures have separate bounds, including a smaller fine-detail budget.
    //including variables to house distant unexplored terrain
    private static final int MAX_TERRAIN_TILES = 768, MAX_FINE_TILES = 64;
    private static final Map<MapDataPayload.TerrainKey, MapDataPayload.TerrainTile> terrainTiles = new LinkedHashMap<>(128, .75f, true);
    private static final Map<TextureKey, Identifier> terrainTextures = new LinkedHashMap<>(128, .75f, true);
    private static final Map<MapDataPayload.TerrainKey, Long> terrainRequests = new LinkedHashMap<>();
    private static UUID terrainState;
    private static long nextTerrainRequest, textureSerial;
    private static long nextOverviewRequest;
    private static RequestMapDataPayload viewport = new RequestMapDataPayload();
    public static RequestMapDataPayload viewport() { return viewport; }
    private static final Map<MapDataPayload.CoastKey, MapDataPayload.CoastTile> coastTiles = new LinkedHashMap<>(64, .75f, true);
    private static final Map<MapDataPayload.CoastKey, Identifier> coastTextures = new HashMap<>();
    public static final int MAX_COAST_TILES = 192, COAST_PIXELS = 64;
    public static final int OVERVIEW_LAND_COLOR = 0xFFAD916A, OVERVIEW_WATER_COLOR = 0xFFA6C6CF, OVERVIEW_COAST_COLOR = 0xFF574735;
    private static List<MapDataPayload.CoastTile> coastLayers = List.of();
    /** Interpolate the signed field, drawing its zero contour rather than the edges of sample squares. */
    public static int[] coastPixels(MapDataPayload.CoastTile tile) {
        short[] values = tile.heights(); int[] pixels = new int[COAST_PIXELS * COAST_PIXELS];
        double unit = MapDataPayload.CoastKey.CELLS / (double) COAST_PIXELS;
        for (int z = 0; z < COAST_PIXELS; z++) for (int x = 0; x < COAST_PIXELS; x++) {
            double u = (x + .5) * unit, v = (z + .5) * unit, density = coastValue(values, u, v);
            if (Double.isNaN(density)) continue;
            double dx = coastValue(values, Math.min(16, u + unit / 2), v) - coastValue(values, Math.max(0, u - unit / 2), v);
            double dz = coastValue(values, u, Math.min(16, v + unit / 2)) - coastValue(values, u, Math.max(0, v - unit / 2));
            double width = Math.hypot(dx, dz) * .55;
            pixels[z * COAST_PIXELS + x] = width > 0 && Math.abs(density) <= width ? OVERVIEW_COAST_COLOR : density > 0 ? OVERVIEW_LAND_COLOR : OVERVIEW_WATER_COLOR;
        }
        return pixels;
    }
    private static double coastValue(short[] values, double x, double z) {
        int ix = Math.min(15, (int) x), iz = Math.min(15, (int) z), side = MapDataPayload.CoastKey.SIDE;
        double fx = x - ix, fz = z - iz;
        short a = values[iz * side + ix], b = values[iz * side + ix + 1], c = values[(iz + 1) * side + ix], d = values[(iz + 1) * side + ix + 1];
        if (a == MapDataPayload.CoastTile.UNKNOWN || b == MapDataPayload.CoastTile.UNKNOWN || c == MapDataPayload.CoastTile.UNKNOWN || d == MapDataPayload.CoastTile.UNKNOWN) return Double.NaN;
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }
    private static List<MapDataPayload.PoliticalRegion> politicalRegions = List.of();
    private static boolean claimsTruncated;
    private static boolean overviewUnsupported;
    public static void acceptOverview(MapDataPayload.OverviewPayload payload) {
        if (!Objects.equals(ClientManagementData.get().stateId(), payload.viewer())) return;
        overviewUnsupported = payload.unsupported();
        boolean changed = false;
        for (var tile : payload.tiles()) {
            var previous = coastTiles.get(tile.key());
            if (previous != null && (previous.detailed() && !tile.detailed() || previous.version() > tile.version())) continue;
            if (!tile.equals(previous)) { coastTiles.put(tile.key(), tile); releaseCoastTexture(tile.key()); changed = true; }
        }
        while (coastTiles.size() > MAX_COAST_TILES) { var key = coastTiles.keySet().iterator().next(); coastTiles.remove(key); releaseCoastTexture(key); changed = true; }
        if (changed) {
            // Sort only when facts change. Cached textures do the work on all subsequent rendered frames.
            coastLayers = coastTiles.values().stream().sorted(Comparator.comparingInt((MapDataPayload.CoastTile tile) -> tile.key().step()).reversed()).toList();
        }
        politicalRegions = payload.regions();
    }

    public static void setTerrainState(UUID stateId) {
        if (!Objects.equals(terrainState, stateId)) {
            clearTerrain(); terrainState = stateId;
            if (!Objects.equals(snapshot.viewerStateId(), stateId)) snapshot = Snapshot.empty(VERSION_COUNTER.incrementAndGet());
        }
    }
    public static void clearTerrain() {
        bridgeDraft = null;
        var manager = Minecraft.getInstance().getTextureManager();
        for (Identifier texture : terrainTextures.values()) manager.release(texture);
        terrainTextures.clear(); terrainTiles.clear(); terrainRequests.clear();
        for (var texture : coastTextures.values()) manager.release(texture);
        coastTextures.clear(); coastTiles.clear(); coastLayers = List.of(); politicalRegions = List.of(); nextOverviewRequest = 0;
        claimsTruncated = false;
        overviewUnsupported = false;
        viewport = new RequestMapDataPayload();
        terrainState = null; nextTerrainRequest = 0;
    }
    public static void acceptTerrain(MapDataPayload.TerrainPayload payload) {
        // Management snapshots provide membership even if a battle opens before the empire map.
        UUID membership = ClientManagementData.get().stateId();
        if (!Objects.equals(membership, payload.stateId())) return;
        if (terrainState != null && !terrainState.equals(payload.stateId())) return;
        terrainState = payload.stateId();
        for (var tile : payload.tiles()) {
            MapDataPayload.TerrainTile previous = terrainTiles.put(tile.key(), tile);
            if (previous == null || previous.version() != tile.version()) releaseTerrainTextures(tile.key());
        }
        while (terrainTiles.size() > MAX_TERRAIN_TILES) removeTerrainTile(terrainTiles.keySet().iterator().next());
        while (terrainTiles.keySet().stream().filter(key -> key.step() == 1).count() > MAX_FINE_TILES) {
            var oldest = terrainTiles.keySet().stream().filter(key -> key.step() == 1).findFirst().orElseThrow();
            removeTerrainTile(oldest);
        }
    }
    private static void removeTerrainTile(MapDataPayload.TerrainKey key) {
        terrainTiles.remove(key); releaseTerrainTextures(key);
    }
    private static void releaseTerrainTextures(MapDataPayload.TerrainKey key) {
        var iterator = terrainTextures.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().tile.equals(key)) {
                Minecraft.getInstance().getTextureManager().release(entry.getValue()); iterator.remove();
            }
        }
    }

    //terrain renderer: both widgets pass a camera in world blocks, so borders and units stay aligned
    //shared with all 3 views
    public static void renderTerrain(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
            double centerX, double centerZ, double pixelsPerBlock, UUID battleId) {
        TerrainView view = terrainView(battleId != null);
        int step = pixelsPerBlock >= .75 ? 1 : pixelsPerBlock >= .09 ? 4 : 16;
        int background = battleId == null ? OVERVIEW_LAND_COLOR : view == TerrainView.ATLAS ? 0xFFC6B99C : view == TerrainView.TOPOGRAPHIC ? 0xFF273431 : 0xFF202D35;
        graphics.fill(x, y, x + width, y + height, background);
        if (battleId == null) {
            if (System.currentTimeMillis() >= nextOverviewRequest && ClientManagementData.get().stateId() != null && Minecraft.getInstance().getConnection() != null) {
                nextOverviewRequest = System.currentTimeMillis() + 350;
                int span = (int) Math.clamp(Math.max(width, height) / pixelsPerBlock, 256, 65536);
                viewport = new RequestMapDataPayload((int) Math.clamp(centerX, -29_999_984, 29_999_984), (int) Math.clamp(centerZ, -29_999_984, 29_999_984), span);
                net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new RequestMapDataPayload.OverviewRequest(
                        (int) Math.clamp(centerX, -29_999_984, 29_999_984), (int) Math.clamp(centerZ, -29_999_984, 29_999_984), span,
                        coastTiles.values().stream().map(tile -> new MapDataPayload.CoastStamp(tile.key(), tile.version())).toList()));
            }
            int[] coastBudget = {2};
            for (var tile : coastLayers) {
                var key = tile.key();
                int left = (int) Math.round(x + width / 2.0 + (key.x() - centerX) * pixelsPerBlock), top = (int) Math.round(y + height / 2.0 + (key.z() - centerZ) * pixelsPerBlock);
                int right = (int) Math.round(x + width / 2.0 + (key.x() + key.blocks() - centerX) * pixelsPerBlock), bottom = (int) Math.round(y + height / 2.0 + (key.z() + key.blocks() - centerZ) * pixelsPerBlock);
                if (right < x || left > x + width || bottom < y || top > y + height) continue;
                Identifier texture = coastTexture(tile, coastBudget);
                if (texture != null) graphics.blit(texture, left, top, right, bottom, 0, 1, 0, 1);
            }
        }
        trimTerrainTextures();
        double minX = centerX - width / (2.0 * pixelsPerBlock), maxX = centerX + width / (2.0 * pixelsPerBlock);
        double minZ = centerZ - height / (2.0 * pixelsPerBlock), maxZ = centerZ + height / (2.0 * pixelsPerBlock);
        List<MapDataPayload.TerrainKey> wanted = visibleTerrainKeys(minX, maxX, minZ, maxZ, step, centerX, centerZ);
        requestVisibleTerrain(wanted, battleId);
        int[] textureBudget = {4};
        // Existing coarse tiles remain visible while the sharper tiles arrive after a zoom change.
        for (int detail : new int[]{16, 4, 1}) {
            if (detail < step) continue;
            for (var key : visibleTerrainKeys(minX, maxX, minZ, maxZ, detail, centerX, centerZ)) {
                var tile = terrainTiles.get(key);
                if (tile == null) continue;
                Identifier texture = terrainTexture(tile, view, textureBudget, battleId == null);
                if (texture == null) continue;
                int left = (int) Math.round(x + width / 2.0 + (key.tileX() * (double) key.blocks() - centerX) * pixelsPerBlock);
                int top = (int) Math.round(y + height / 2.0 + (key.tileZ() * (double) key.blocks() - centerZ) * pixelsPerBlock);
                int right = (int) Math.round(x + width / 2.0 + ((key.tileX() + 1.0) * key.blocks() - centerX) * pixelsPerBlock);
                int bottom = (int) Math.round(y + height / 2.0 + ((key.tileZ() + 1.0) * key.blocks() - centerZ) * pixelsPerBlock);
                float edge = 1f / key.side();
                graphics.blit(texture, left, top, right, bottom, edge, 1 - edge, edge, 1 - edge);
            }
        }
        if (font != null && ClientManagementData.get().stateId() != null
                && !wanted.isEmpty() && wanted.stream().noneMatch(terrainTiles::containsKey)) {
            String loading = font.plainSubstrByWidth(Component.translatable("gui.minecraftempires.map.terrain_loading").getString(), width - 20);
            graphics.fill(x + 6, y + 38, x + 16 + font.width(loading), y + 51, 0xBB111820);
            graphics.text(font, Component.literal(loading), x + 11, y + 41, 0xFFE5E5D9);
        }
    }

    //terrain rendering: determine which tiles are visible in the current viewport, sorted by distance to the center for progressive loading
    private static List<MapDataPayload.TerrainKey> visibleTerrainKeys(double minX, double maxX, double minZ,
            double maxZ, int step, double centerX, double centerZ) {
        int blocks = step == 16 ? 1024 : 256;
        int minTileX = Math.max(-117188, (int) Math.floor(minX / blocks));
        int maxTileX = Math.min(117188, (int) Math.floor(maxX / blocks));
        int minTileZ = Math.max(-117188, (int) Math.floor(minZ / blocks));
        int maxTileZ = Math.min(117188, (int) Math.floor(maxZ / blocks));
        List<MapDataPayload.TerrainKey> result = new ArrayList<>();
        // Defensive viewport bounds avoid an accidental enormous camera producing unbounded work.
        if ((long) (maxTileX - minTileX + 1) * (maxTileZ - minTileZ + 1) > 4096) return result;
        for (int tz = minTileZ; tz <= maxTileZ; tz++) for (int tx = minTileX; tx <= maxTileX; tx++)
            result.add(new MapDataPayload.TerrainKey(tx, tz, step));
        result.sort(Comparator.comparingDouble(key -> {
            double dx = (key.tileX() + .5) * blocks - centerX, dz = (key.tileZ() + .5) * blocks - centerZ;
            return dx * dx + dz * dz;
        }));
        return result;
    }

    //terrain rendering: request the server to send any tiles that are visible but not yet cached, with a rate limit to avoid flooding the server
    private static void requestVisibleTerrain(List<MapDataPayload.TerrainKey> visible, UUID battleId) {
        if (ClientManagementData.get().stateId() == null) return;
        long now = System.currentTimeMillis();
        if (now < nextTerrainRequest) return;
        nextTerrainRequest = now + 300;
        List<RequestMapDataPayload.TerrainRequest> requests = new ArrayList<>();
        for (var key : visible) {
            var tile = terrainTiles.get(key);
            long previous = terrainRequests.getOrDefault(key, 0L);
            if (now - previous < (tile == null ? 1500 : 4000)) continue;
            requests.add(new RequestMapDataPayload.TerrainRequest(key, tile == null ? Long.MIN_VALUE : tile.version()));
            terrainRequests.put(key, now);
            if (requests.size() == RequestMapDataPayload.RequestTerrainPayload.MAX_TILES) break;
        }
        while (terrainRequests.size() > 4096) terrainRequests.remove(terrainRequests.keySet().iterator().next());
        ClientNetworking.requestTerrain(battleId, requests);
    }

    // Release old textures before submitting this frame, never while a submitted quad still references one.
    private static void trimTerrainTextures() {
        while (terrainTextures.size() > MAX_TERRAIN_TILES
                || terrainTextures.keySet().stream().filter(key -> key.tile.step() == 1).count() > MAX_FINE_TILES) {
            var key = terrainTextures.keySet().iterator().next();
            Minecraft.getInstance().getTextureManager().release(terrainTextures.remove(key));
        }
    }

    private static void releaseCoastTexture(MapDataPayload.CoastKey key) {
        Identifier texture = coastTextures.remove(key); if (texture != null) Minecraft.getInstance().getTextureManager().release(texture);
    }
    private static Identifier coastTexture(MapDataPayload.CoastTile tile, int[] budget) {
        Identifier existing = coastTextures.get(tile.key()); if (existing != null) return existing;
        if (budget[0]-- <= 0) return null;
        int[] pixels = coastPixels(tile); NativeImage image = new NativeImage(COAST_PIXELS, COAST_PIXELS, false);
        for (int z = 0; z < COAST_PIXELS; z++) for (int x = 0; x < COAST_PIXELS; x++) image.setPixel(x, z, pixels[z * COAST_PIXELS + x]);
        Identifier id = Identifier.fromNamespaceAndPath("minecraftempires", "coast/runtime_" + textureSerial++);
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> id.toString(), image));
        coastTextures.put(tile.key(), id); return id;
    }

    //for the stylized, topographic, and atlas views, generate a texture for the given tile and view, caching it for reuse. If the texture budget is exceeded, return null to avoid overloading the GPU.
    private static Identifier terrainTexture(MapDataPayload.TerrainTile tile, TerrainView view, int[] budget, boolean overview) {
        TextureKey key = new TextureKey(tile.key(), view, overview);
        Identifier existing = terrainTextures.get(key);
        if (existing != null) return existing;
        if (budget[0]-- <= 0) return null;
        int side = tile.key().side();
        NativeImage image = new NativeImage(side, side, false);
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++)
            image.setPixel(x, z, overview && !tile.knownAt(z * side + x) ? 0 : terrainPixel(tile, view, x, z));
        Identifier id = Identifier.fromNamespaceAndPath("minecraftempires", "terrain/runtime_" + textureSerial++);
        DynamicTexture texture = new DynamicTexture(() -> id.toString(), image);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        terrainTextures.put(key, id); return id;
    }

    // PALETTES AND RELIEF: use global sample coordinates for paper grain, avoiding visible tile boundaries.
    private static int terrainPixel(MapDataPayload.TerrainTile tile, TerrainView view, int x, int z) {
        int side = tile.key().side(), index = z * side + x;
        int wx = tile.key().tileX() * tile.key().blocks() + (x - 1) * tile.key().step();
        int wz = tile.key().tileZ() * tile.key().blocks() + (z - 1) * tile.key().step();
        int grain = Math.floorMod(wx * 73428767 ^ wz * 912931, 7) - 3;
        if (!tile.knownAt(index)) {
            int unknown = view == TerrainView.ATLAS ? 0xBFB297 : view == TerrainView.TOPOGRAPHIC ? 0x273431 : 0x202D35;
            return shade(unknown, 1 + grain * .006);
        }
        int flags = tile.features()[index] & 255, height = tile.heightAt(index);
        boolean water = (flags & MapDataPayload.TERRAIN_WATER) != 0;
        int source = MapColor.byId(tile.colors()[index] & 63).col;
        int color;
        if (view == TerrainView.TOPOGRAPHIC) {
            color = water ? 0x537F99 : elevationColor(height);
        } else if (view == TerrainView.ATLAS) {
            color = water ? 0x70878A : (flags & MapDataPayload.TERRAIN_FOREST) != 0 ? 0xAFAC86 : 0xD8C9A5;
            if ((flags & MapDataPayload.TERRAIN_STRUCTURE) != 0) color = 0x9B8B70;
        } else {
            color = blend(source, 0x8B9279, .30);
            if (water) color = 0x427D9B;
            else if ((flags & MapDataPayload.TERRAIN_FOREST) != 0) color = blend(source, 0x547655, .45);
            else if ((flags & MapDataPayload.TERRAIN_SNOW) != 0) color = 0xD5DCD1;
            else if ((flags & MapDataPayload.TERRAIN_SAND) != 0) color = 0xC7B58B;
        }
        if ((flags & MapDataPayload.TERRAIN_LAVA) != 0) color = view == TerrainView.ATLAS ? 0x9B7553 : 0xB4653C;
        int west = z * side + Math.max(0, x - 1), north = Math.max(0, z - 1) * side + x;
        double relief = 0;
        if (!water && tile.knownAt(west) && tile.knownAt(north)) {
            relief = Math.clamp((tile.heightAt(west) + tile.heightAt(north) - 2.0 * height)
                    / (tile.key().step() * 2.0), -2, 2);
        }
        color = shade(color, 1 + relief * .065 + grain * .003);
        if (view != TerrainView.STYLIZED && !water) {
            int interval = tile.key().step() == 16 ? 20 : 10;
            int band = Math.floorDiv(height, interval);
            boolean contour = tile.knownAt(west) && (tile.features()[west] & MapDataPayload.TERRAIN_WATER) == 0
                    && band != Math.floorDiv(tile.heightAt(west), interval)
                    || tile.knownAt(north) && (tile.features()[north] & MapDataPayload.TERRAIN_WATER) == 0
                    && band != Math.floorDiv(tile.heightAt(north), interval);
            if (contour) color = blend(color, view == TerrainView.ATLAS ? 0x8D7959 : 0x455346, .55) & 0xFFFFFF;
        }
        return 0xFF000000 | color;
    }

    //coloring for stylized option (check if accurate)
    private static int elevationColor(int height) {
        if (height < 64) return blend(0x739779, 0xA9B48B, Math.clamp((height + 64) / 128.0, 0, 1));
        if (height < 128) return blend(0xA9B48B, 0xC2B38B, (height - 64) / 64.0);
        if (height < 192) return blend(0xC2B38B, 0xB69D87, (height - 128) / 64.0);
        return blend(0xB69D87, 0xDBD9CD, Math.clamp((height - 192) / 128.0, 0, 1));
    }

    //blend two colors together by a given amount, returning the resulting color
    private static int blend(int first, int second, double amount) {
        int red = (int) ((first >> 16 & 255) * (1 - amount) + (second >> 16 & 255) * amount);
        int green = (int) ((first >> 8 & 255) * (1 - amount) + (second >> 8 & 255) * amount);
        int blue = (int) ((first & 255) * (1 - amount) + (second & 255) * amount);
        return red << 16 | green << 8 | blue;
    }
    //darken a color by a given amount, returning the resulting color
    private static int shade(int color, double amount) {
        int red = Math.clamp((int) ((color >> 16 & 255) * amount), 0, 255);
        int green = Math.clamp((int) ((color >> 8 & 255) * amount), 0, 255);
        int blue = Math.clamp((int) ((color & 255) * amount), 0, 255);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    //for topographic view: terrain height lookup - given world coordinates, find the corresponding terrain tile and return the height at that location, or null if unknown
    public static Integer terrainHeight(double worldX, double worldZ) {
        int wx = (int) Math.floor(worldX), wz = (int) Math.floor(worldZ);
        for (int step : new int[]{1, 4, 16}) {
            int blocks = step == 16 ? 1024 : 256;
            var key = new MapDataPayload.TerrainKey(Math.floorDiv(wx, blocks), Math.floorDiv(wz, blocks), step);
            var tile = terrainTiles.get(key);
            if (tile == null) continue;
            int index = (Math.floorMod(wz, blocks) / step + 1) * key.side() + Math.floorMod(wx, blocks) / step + 1;
            if (tile.knownAt(index)) return tile.heightAt(index);
        }
        return null;
    }
    private record TextureKey(MapDataPayload.TerrainKey tile, TerrainView view, boolean overview) {}
    public static void renderPolitics(GuiGraphicsExtractor g, Font font, int x, int y, int width, int height, double cx, double cz, double scale) {
        Set<UUID> labeled = new java.util.HashSet<>();
        Map<Long, MapDataPayload.PoliticalRegion> index = new HashMap<>(); politicalRegions.forEach(r -> index.put(r.key(), r));
        for (var region : politicalRegions) {
            if (region.owner() == null) continue;
            ChunkPos pos = ChunkPos.unpack(region.key()); double wx = pos.x() * 1024.0, wz = pos.z() * 1024.0;
            double left = x + width / 2.0 + (wx - cx) * scale, top = y + height / 2.0 + (wz - cz) * scale;
            if (left + 1024 * scale < x || left > x + width || top + 1024 * scale < y || top > y + height) continue;
            int color = region.owner().hashCode(); color = 0x30000000 | (color & 0x7F7F7F) | 0x606060;
            java.util.BitSet land = java.util.BitSet.valueOf(region.land());
            boolean[] occupiedCells = new boolean[64];
            for (int row = 0; row < 8; row++) for (int col = 0; col < 8; col++) for (int dz = 0; dz < 8; dz++) {
                int start = (row * 8 + dz) * 64 + col * 8, next = land.nextSetBit(start);
                if (next >= start && next < start + 8) { occupiedCells[row * 8 + col] = true; break; }
            }
            for (int row = 0; row < 8; row++) for (int col = 0; col < 8; col++) {
                if (!occupiedCells[row * 8 + col]) continue;
                int l = (int) Math.floor(left + col * 128 * scale), t = (int) Math.floor(top + row * 128 * scale);
                int r = (int) Math.ceil(left + (col + 1) * 128 * scale), b = (int) Math.ceil(top + (row + 1) * 128 * scale);
                g.fill(l, t, r, b, color);
                if (row > 0 ? !occupiedCells[(row - 1) * 8 + col] : !samePolity(index, pos.x(), pos.z() - 1, region.owner())) g.fill(l, t, r, t + 1, color | 0x99000000);
                if (col > 0 ? !occupiedCells[row * 8 + col - 1] : !samePolity(index, pos.x() - 1, pos.z(), region.owner())) g.fill(l, t, l + 1, b, color | 0x99000000);
                if (row < 7 ? !occupiedCells[(row + 1) * 8 + col] : !samePolity(index, pos.x(), pos.z() + 1, region.owner())) g.fill(l, b - 1, r, b, color | 0x99000000);
                if (col < 7 ? !occupiedCells[row * 8 + col + 1] : !samePolity(index, pos.x() + 1, pos.z(), region.owner())) g.fill(r - 1, t, r, b, color | 0x99000000);
            }
            if (labeled.add(region.owner()) && font != null) {
                var nation = snapshot.statesById().get(region.owner());
                if (nation != null) g.text(font, Component.literal(nation.stateName()), (int) left + 3, (int) top + 3, 0xFFE9D9B8);
            }
        }
        if (font != null && overviewUnsupported) g.text(font, Component.translatable("gui.minecraftempires.map.overview_unavailable"), x + 6, y + 6, 0xFFD9C6A3);
        if (font != null && claimsTruncated) g.text(font, Component.translatable("gui.minecraftempires.map.zoom_details"), x + 6, y + 18, 0xFFFFCD77);
    }
    private static boolean samePolity(Map<Long, MapDataPayload.PoliticalRegion> regions, int x, int z, UUID owner) {
        var neighbor = regions.get(new ChunkPos(x, z).pack()); return neighbor != null && owner.equals(neighbor.owner());
    }

    public record ProvinceAnchor(
            String settlementId,
            String displayName,
            UUID stateId,
            double chunkX,
            double chunkZ,
            boolean capital,
            boolean garrisoned,
            boolean settlementMarker
    ) {}

    public record Snapshot(
            int version,
            UUID viewerStateId,
            String viewerStateName,
            Map<Long, MapDataPayload.MapChunkData> chunksByPosition,
            Map<Long, Integer> borderMasks,
            Map<Long, Integer> provinceBorderMasks,
            Map<UUID, MapDataPayload.StateSummary> statesById,
            Map<String, MapDataPayload.SettlementSummary> settlementsById,
            List<ProvinceAnchor> provinceAnchors,
            List<MapDataPayload.BreachAlert> breachAlerts,
            List<MapDataPayload.RoadSummary> roads,
            List<MapDataPayload.CampSummary> camps,
            boolean logisticsTruncated,
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ
    ) {
        private static Snapshot empty(int version) {
            return new Snapshot(
                    version,
                    null,
                    "",
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    List.of(),
                    List.of(),
                    List.of(), List.of(), false,
                    0,
                    0,
                    0,
                    0
            );
        }

        //handles client snapshot creation from a payload which has all the data for the map
        private static Snapshot from(MapDataPayload payload, int version) {
            if (payload == null) {
                return new Snapshot(
                        version,
                        payload == null ? null : payload.viewerStateId(),
                        payload == null ? "" : payload.viewerStateName(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        indexStates(payload == null ? List.of() : payload.states()),
                        indexSettlements(payload == null ? List.of() : payload.settlements()),
                        List.of(),
                        payload == null ? List.of() : List.copyOf(payload.breachAlerts()),
                        payload == null ? List.of() : List.copyOf(payload.roads()),
                        payload == null ? List.of() : List.copyOf(payload.camps()),
                        payload != null && payload.logisticsTruncated(),
                        0,
                        0,
                        0,
                        0
                );
            }

            Map<Long, MapDataPayload.MapChunkData> chunks = new HashMap<>();
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;

            //process the run-length encoded chunk data from the payload, unpacking it into individual chunks and storing them in a map, while also tracking the minimum and maximum chunk coordinates for later use
            for (MapDataPayload.MapChunkData run : payload.chunks()) {
                ChunkPos start = ChunkPos.unpack(run.packedChunkPos());
                for (int offset = 0; offset < run.runLength(); offset++) {
                    ChunkPos position = new ChunkPos(start.x() + offset, start.z());
                    MapDataPayload.MapChunkData chunk = new MapDataPayload.MapChunkData(
                            position.pack(),
                            1,
                            run.ownerStateId(),
                            run.settlementId(),
                            run.garrisoned(),
                            run.settlementTier(),
                            run.contested()
                    );
                    chunks.put(position.pack(), chunk);
                    minX = Math.min(minX, position.x());
                    maxX = Math.max(maxX, position.x());
                    minZ = Math.min(minZ, position.z());
                    maxZ = Math.max(maxZ, position.z());
                }
            }

            Map<Long, Integer> borderMasks = precomputeBorders(chunks, false);
            Map<Long, Integer> provinceBorderMasks = precomputeBorders(chunks, true);
            Map<UUID, MapDataPayload.StateSummary> states = indexStates(payload.states());
            Map<String, MapDataPayload.SettlementSummary> settlements = indexSettlements(payload.settlements());
            List<ProvinceAnchor> anchors = buildProvinceAnchors(chunks, settlements);
            if (chunks.isEmpty()) {
                minX = minZ = maxX = maxZ = 0;
                if (!anchors.isEmpty()) {
                    minX = (int) Math.floor(anchors.stream().mapToDouble(ProvinceAnchor::chunkX).min().orElse(0)); maxX = (int) Math.floor(anchors.stream().mapToDouble(ProvinceAnchor::chunkX).max().orElse(0));
                    minZ = (int) Math.floor(anchors.stream().mapToDouble(ProvinceAnchor::chunkZ).min().orElse(0)); maxZ = (int) Math.floor(anchors.stream().mapToDouble(ProvinceAnchor::chunkZ).max().orElse(0));
                } else if (!payload.politicalRegions().isEmpty()) {
                    ChunkPos region = ChunkPos.unpack(payload.politicalRegions().getFirst().key()); minX = region.x() * 64; maxX = minX + 63; minZ = region.z() * 64; maxZ = minZ + 63;
                }
            }

            return new Snapshot(
                    version,
                    payload.viewerStateId(),
                    payload.viewerStateName(),
                    Collections.unmodifiableMap(chunks),
                    Collections.unmodifiableMap(borderMasks),
                    Collections.unmodifiableMap(provinceBorderMasks),
                    states,
                    settlements,
                    List.copyOf(anchors),
                    List.copyOf(payload.breachAlerts()),
                    List.copyOf(payload.roads()),
                    List.copyOf(payload.camps()),
                    payload.logisticsTruncated(),
                    minX,
                    maxX,
                    minZ,
                    maxZ
            );
        }

        public boolean hasData() {
            return viewerStateId != null || !chunksByPosition.isEmpty() || !settlementsById.isEmpty();
        }

        public MapDataPayload.MapChunkData getChunk(int chunkX, int chunkZ) {
            return chunksByPosition.get(new ChunkPos(chunkX, chunkZ).pack());
        }

        public int getBorderMask(int chunkX, int chunkZ) {
            return borderMasks.getOrDefault(new ChunkPos(chunkX, chunkZ).pack(), 0);
        }

        public int getProvinceBorderMask(int chunkX, int chunkZ) {
            return provinceBorderMasks.getOrDefault(new ChunkPos(chunkX, chunkZ).pack(), 0);
        }

        public MapDataPayload.StateSummary getState(UUID stateId) {
            return statesById.get(stateId);
        }

        public MapDataPayload.SettlementSummary getSettlement(String settlementId) {
            return settlementsById.get(settlementId);
        }

        public double centerChunkX() {
            return (minChunkX + maxChunkX) / 2.0;
        }

        public double centerChunkZ() {
            return (minChunkZ + maxChunkZ) / 2.0;
        }
    }

    //calculates the borders for each chunk based on nearby chunks
    private static Map<Long, Integer> precomputeBorders(
            Map<Long, MapDataPayload.MapChunkData> chunks,
            boolean compareSettlement
    ) {
        Map<Long, Integer> result = new HashMap<>(chunks.size());

        for (MapDataPayload.MapChunkData chunk : chunks.values()) {
            ChunkPos position = ChunkPos.unpack(chunk.packedChunkPos());
            int mask = 0;
            if (isDifferentRegion(chunks, position.x(), position.z() - 1, chunk, compareSettlement)) {
                mask |= BORDER_NORTH;
            }
            if (isDifferentRegion(chunks, position.x() + 1, position.z(), chunk, compareSettlement)) {
                mask |= BORDER_EAST;
            }
            if (isDifferentRegion(chunks, position.x(), position.z() + 1, chunk, compareSettlement)) {
                mask |= BORDER_SOUTH;
            }
            if (isDifferentRegion(chunks, position.x() - 1, position.z(), chunk, compareSettlement)) {
                mask |= BORDER_WEST;
            }
            result.put(chunk.packedChunkPos(), mask);
        }

        return result;
    }

    //determines if nearby chunks are part of the same region
    private static boolean isDifferentRegion(
            Map<Long, MapDataPayload.MapChunkData> chunks,
            int x,
            int z,
            MapDataPayload.MapChunkData current,
            boolean compareSettlement
    ) {
        MapDataPayload.MapChunkData neighbor = chunks.get(new ChunkPos(x, z).pack());
        if (neighbor == null || !Objects.equals(neighbor.ownerStateId(), current.ownerStateId())) {
            return true;
        }
        return compareSettlement && !Objects.equals(neighbor.settlementId(), current.settlementId());
    }

    //load all states
    private static Map<UUID, MapDataPayload.StateSummary> indexStates(
            List<MapDataPayload.StateSummary> states
    ) {
        Map<UUID, MapDataPayload.StateSummary> indexed = new LinkedHashMap<>();
        for (MapDataPayload.StateSummary state : states) {
            indexed.put(state.stateId(), state);
        }
        return Collections.unmodifiableMap(indexed);
    }

    //load all settlements
    private static Map<String, MapDataPayload.SettlementSummary> indexSettlements(
            List<MapDataPayload.SettlementSummary> settlements
    ) {
        Map<String, MapDataPayload.SettlementSummary> indexed = new LinkedHashMap<>();
        for (MapDataPayload.SettlementSummary settlement : settlements) {
            indexed.put(settlement.settlementId(), settlement);
        }
        return Collections.unmodifiableMap(indexed);
    }

    //builds the anchors for each province based on the chunks and settlements
    private static List<ProvinceAnchor> buildProvinceAnchors(
            Map<Long, MapDataPayload.MapChunkData> chunks,
            Map<String, MapDataPayload.SettlementSummary> settlements
    ) {
        record Accumulator(UUID stateId, long sumX, long sumZ, int count, boolean garrisoned) {
            Accumulator add(int x, int z, boolean chunkGarrisoned) {
                return new Accumulator(
                        stateId,
                        sumX + x,
                        sumZ + z,
                        count + 1,
                        garrisoned || chunkGarrisoned
                );
            }
        }

        //uses hashing to group chunks by their settlement ID, accumulating the sum of their coordinates and garrison status for later use in calculating province anchors
        Map<String, Accumulator> groups = new HashMap<>();
        for (MapDataPayload.MapChunkData chunk : chunks.values()) {
            String settlementId = chunk.settlementId();
            if (settlementId == null || settlementId.isBlank()) {
                continue;
            }
            ChunkPos position = ChunkPos.unpack(chunk.packedChunkPos());
            groups.compute(
                    settlementId,
                    (ignored, current) -> current == null
                            ? new Accumulator(
                                    chunk.ownerStateId(),
                                    position.x(),
                                    position.z(),
                                    1,
                                    chunk.garrisoned()
                            )
                            : current.add(position.x(), position.z(), chunk.garrisoned())
            );
        }

        List<ProvinceAnchor> anchors = new ArrayList<>();
        for (var settlement : settlements.values()) if (!groups.containsKey(settlement.settlementId())) {
            ChunkPos center = ChunkPos.unpack(settlement.packedCenterChunk());
            anchors.add(new ProvinceAnchor(settlement.settlementId(), settlement.settlementName(), settlement.stateId(), center.x() + .5, center.z() + .5,
                    settlement.capital(), settlement.garrisoned(), true));
        }
        for (Map.Entry<String, Accumulator> entry : groups.entrySet()) {
            String settlementId = entry.getKey();
            Accumulator group = entry.getValue();
            MapDataPayload.SettlementSummary settlement = settlements.get(settlementId);

            double anchorX;
            double anchorZ;
            String displayName;
            boolean capital = false;
            boolean garrisoned = group.garrisoned();
            boolean settlementMarker = false;

            if (settlement != null) {
                ChunkPos center = ChunkPos.unpack(settlement.packedCenterChunk());
                anchorX = center.x() + 0.5;
                anchorZ = center.z() + 0.5;
                displayName = settlement.settlementName();
                capital = settlement.capital();
                garrisoned = settlement.garrisoned();
                settlementMarker = true;
            } else {
                //commanderies and legacy provinces have no known capital marker (by design), so their label falls back to the mathematical center of their visible claim.
                anchorX = (double) group.sumX() / group.count() + 0.5;
                anchorZ = (double) group.sumZ() / group.count() + 0.5;
                displayName = abbreviatedId(settlementId);
            }

            anchors.add(new ProvinceAnchor(
                    settlementId,
                    displayName,
                    group.stateId(),
                    anchorX,
                    anchorZ,
                    capital,
                    garrisoned,
                    settlementMarker
            ));
        }

        anchors.sort((left, right) -> left.displayName().compareToIgnoreCase(right.displayName()));
        return anchors;
    }

    private static String abbreviatedId(String settlementId) {
        if (settlementId.length() <= 12) {
            return settlementId;
        }
        return settlementId.substring(0, 8) + "...";
    }
}
