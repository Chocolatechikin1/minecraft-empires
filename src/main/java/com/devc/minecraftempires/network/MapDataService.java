package com.devc.minecraftempires.network;

import com.devc.minecraftempires.army.Army;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Legion;
import com.devc.minecraftempires.army.Cohort;
import com.devc.minecraftempires.army.LogisticsManager;
import com.devc.minecraftempires.network.packet.ArmyMapPayload;
import com.devc.minecraftempires.network.packet.MapDataPayload;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateManager;
import com.devc.minecraftempires.state.StateBalance;
import com.devc.minecraftempires.territory.BreachAlertService;
import com.devc.minecraftempires.territory.ChunkData;
import com.devc.minecraftempires.territory.ClaimManager;
import com.devc.minecraftempires.territory.SettlementData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import com.devc.minecraftempires.network.packet.RequestMapDataPayload;
import com.devc.minecraftempires.combat.BattleManager;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.core.Direction;
import net.minecraft.tags.BiomeTags;
import java.util.PriorityQueue;
import java.util.Objects;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

//this class will build a map *snapshot* (prevents constant server calling and recalculating) and applies the fog-of-war filter to only show what the player is allowed to see
//gathers all data from ArmyManager, ClaimManager, and StateManager to build the map snapshot
//recent changes
// - added a new method to compress horizontal runs of chunks with the same metadata into a single entry with a run length, improving network efficiency
// - added a new method to determine if a chunk is visible to the player based on ownership, war visibility, or scouting
// - added a new method to find all scouted foreign chunks and their bordering state IDs, used for fog-of-war and visibility calculations
// - added a new method to calculate the distance between two positions
// - added a new method to get the war-visible state IDs for a given viewer state
// - added a new method to get the scouting origins for a given viewer state
// - added a new method to normalize settlement IDs for consistent representation
// - added a new method to get the fallback province name for a given settlement ID
//and more, hard to keep up with the ai 
public final class MapDataService {
    private static final int[][] CARDINAL_DIRECTIONS = {
            {0, -1},
            {1, 0},
            {0, 1},
            {-1, 0}
    };

    private MapDataService() {}
    private static final Map<UUID, RequestMapDataPayload> VIEWPORTS = new HashMap<>();
    public static void recordViewport(UUID player, RequestMapDataPayload viewport) { VIEWPORTS.put(player, viewport); }
    public static void clearViewport(UUID player) {
        VIEWPORTS.remove(player);
        for (var runtime : TERRAIN_RUNTIMES.values()) runtime.geography.forgetPreview(player);
    }

    //builds the map data payload for a given player, applying fog-of-war
    public static MapDataPayload buildPayload(ServerPlayer player) {
        //get all player details and managers
        ServerLevel level = player.level().getServer().overworld();
        StateManager stateManager = StateManager.get(level);
        ClaimManager claimManager = ClaimManager.get(level);
        StateData viewerState = stateManager.getStateByPlayer(player.getUUID());

        if (viewerState == null) return MapDataPayload.empty();
        var viewport = VIEWPORTS.getOrDefault(player.getUUID(), new RequestMapDataPayload());
        int cameraX = viewport.span() == 0 ? player.blockPosition().getX() : viewport.centerX();
        int cameraZ = viewport.span() == 0 ? player.blockPosition().getZ() : viewport.centerZ();
        int radius = viewport.span() == 0 ? 4096 : viewport.span() / 2 + 1024;
        java.util.function.Predicate<BlockPos> inViewport = p -> Math.abs((long) p.getX() - cameraX) <= radius && Math.abs((long) p.getZ() - cameraZ) <= radius;

        //get the viewer's state ID and all claims, then determine which states are visible due to war or scouting
        UUID viewerStateId = viewerState.getStateId();
        Map<ChunkPos, ChunkData> allClaims = claimManager.getClaimsView();
        Set<UUID> warVisibleStateIds = getWarVisibleStateIds(viewerState);
        Map<ChunkPos, ChunkData> viewportClaims = new LinkedHashMap<>();
        boolean claimsTruncated = false;
        for (var entry : allClaims.entrySet()) if (inViewport.test(new BlockPos(entry.getKey().x() * 16, 0, entry.getKey().z() * 16))) {
            if (viewportClaims.size() < 32768) viewportClaims.put(entry.getKey(), entry.getValue()); else claimsTruncated = true;
        }
        ScoutingResult scouting = findScoutedForeignChunks(viewportClaims, viewerStateId);
        Set<Long> scouted = new HashSet<>(scouting.scoutedForeignChunks());
        Set<UUID> encountered = new HashSet<>(scouting.borderingStateIds());
        for (BlockPos origin : scoutingOrigins(ArmyManager.get(level), viewerStateId)) {
            int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
            for (int x = cx - 16; x <= cx + 16; x++) for (int z = cz - 16; z <= cz + 16; z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                ChunkData claim = allClaims.get(chunk);
                if (claim != null && claim.getOwnerUUID() != null && !viewerStateId.equals(claim.getOwnerUUID())
                        && ArmyManager.near(origin, new BlockPos(x * 16 + 8, 0, z * 16 + 8), 250)) {
                    scouted.add(chunk.pack()); encountered.add(claim.getOwnerUUID());
                }
            }
        }
        scouting = new ScoutingResult(scouted, encountered);

        Set<UUID> visibleStateIds = new HashSet<>();
        visibleStateIds.add(viewerStateId);
        visibleStateIds.addAll(scouting.borderingStateIds());
        visibleStateIds.addAll(warVisibleStateIds);

        //build the visible chunk list, settlement summaries, and state summaries, utilizing ArrayLists and HashMaps for efficient data storage and retrieval (O(1) average complexity for get/put operations)
        List<MapDataPayload.MapChunkData> visibleChunks = new ArrayList<>();
        Map<UUID, Integer> visibleChunkCounts = new HashMap<>();
        Map<String, Boolean> settlementGarrisonStatus = new HashMap<>();
        Map<String, UUID> settlementOwners = new HashMap<>();
        Map<String, Integer> settlementTiers = new HashMap<>();
        Set<Long> visibleChunkPositions = new HashSet<>();

        for (Map.Entry<ChunkPos, ChunkData> entry : viewportClaims.entrySet()) { //while iterating through all claims, check if the chunk is visible to the player based on ownership, war visibility, or scouting
            ChunkPos position = entry.getKey();
            ChunkData data = entry.getValue();
            UUID ownerStateId = data.getOwnerUUID();
            if (ownerStateId == null || !isChunkVisible(position, ownerStateId, viewerStateId, warVisibleStateIds, scouting.scoutedForeignChunks())) continue;

            //add the visible chunk to the list, along with its metadata (owner state ID, settlement ID, garrison status, settlement tier, and contested status)
            String settlementId = normalizeSettlementId(data.getSettlementID());
            visibleChunks.add(new MapDataPayload.MapChunkData(position.pack(), 1, ownerStateId, settlementId, data.isGarrisoned(), data.getSettlementTier(), false));
            visibleChunkPositions.add(position.pack());
            visibleChunkCounts.merge(ownerStateId, 1, Integer::sum);

            if (!settlementId.isEmpty()) { //for each visible chunk, if it belongs to a settlement, record the settlement's owner state ID, tier, and garrison status (if applicable)
                settlementOwners.putIfAbsent(settlementId, ownerStateId);
                settlementTiers.merge(settlementId, data.getSettlementTier(), Math::max);
                if (data.isGarrisoned()) settlementGarrisonStatus.put(settlementId, true);
            }
        }

        visibleChunks = compressHorizontalRuns(visibleChunks);
        visibleChunks.sort(Comparator.comparingDouble(chunk -> {
            ChunkPos pos = ChunkPos.unpack(chunk.packedChunkPos());
            double dx = pos.x() + chunk.runLength() / 2.0 - cameraX / 16.0, dz = pos.z() + .5 - cameraZ / 16.0;
            return dx * dx + dz * dz;
        }));

        List<MapDataPayload.StateSummary> stateSummaries = new ArrayList<>();
        for (UUID visibleStateId : visibleStateIds) {
            StateData state = stateManager.getState(visibleStateId);
            if (state == null) continue;
            boolean isViewerState = visibleStateId.equals(viewerStateId);
            stateSummaries.add(new MapDataPayload.StateSummary(
                    visibleStateId, state.getStateName(), state.getCurrentTier().name(),
                    visibleChunkCounts.getOrDefault(visibleStateId, 0),
                    isViewerState ? state.getTotalPopulation() : 0,
                    isViewerState ? state.getTreasuryBalance() : 0.0,
                    isViewerState, scouting.borderingStateIds().contains(visibleStateId)));
        }
        stateSummaries.sort(Comparator.comparing(MapDataPayload.StateSummary::stateName,
                String.CASE_INSENSITIVE_ORDER));
        for (UUID id : visibleStateIds) stateManager.observeNation(viewerStateId, id, level.getGameTime());
        Set<UUID> summarizedStates = new HashSet<>(); stateSummaries.forEach(s -> summarizedStates.add(s.stateId()));
        Set<UUID> rememberedOwners = new HashSet<>();
        for (var r : stateManager.getKnownRegions(viewerStateId)) { ChunkPos p = ChunkPos.unpack(r.key()); if (inViewport.test(new BlockPos(p.x() * 1024 + 512, 0, p.z() * 1024 + 512))) rememberedOwners.add(r.owner()); }
        for (var s : stateManager.getKnownSettlements(viewerStateId)) if (inViewport.test(s.position())) rememberedOwners.add(s.owner());
        for (var known : stateManager.getKnownNations(viewerStateId)) if (rememberedOwners.contains(known.id()) && summarizedStates.add(known.id()))
            stateSummaries.add(new MapDataPayload.StateSummary(known.id(), known.name(), known.tier().name(), 0, 0, 0, false, false));

        Collection<SettlementData> allSettlements = stateManager.getAllSettlements();
        Map<UUID, UUID> capitalSettlementByState = new HashMap<>();
        for (UUID id : visibleStateIds) {
            StateData state = stateManager.getState(id);
            if (state != null && state.getCapitalSettlementId() != null) capitalSettlementByState.put(id, state.getCapitalSettlementId());
        }
        List<MapDataPayload.SettlementSummary> settlementSummaries = new ArrayList<>();
        Set<String> summarizedSettlementIds = new HashSet<>();

        for (SettlementData settlement : allSettlements) {
            if (!inViewport.test(settlement.getCenterAltarPos()) || settlementSummaries.size() >= 8192) continue;
            UUID owningStateId = settlement.getOwningStateId();
            if (!visibleStateIds.contains(owningStateId)) continue;

            BlockPos altar = settlement.getCenterAltarPos();
            ChunkPos centerChunk = new ChunkPos(altar.getX() >> 4, altar.getZ() >> 4);
            boolean revealFull = owningStateId.equals(viewerStateId) || warVisibleStateIds.contains(owningStateId);
            if (!revealFull && !visibleChunkPositions.contains(centerChunk.pack())) continue;

            String settlementId = settlement.getSettlementId().toString();
            boolean isViewer = owningStateId.equals(viewerStateId);
            settlementSummaries.add(new MapDataPayload.SettlementSummary(
                    settlementId, owningStateId, settlement.getSettlementName(), centerChunk.pack(),
                    settlement.getSettlementTier(),
                    isViewer ? settlement.getLocalPopulation() : 0,
                    isViewer ? settlement.getGarrisonCapacity() : 0,
                    settlement.getSettlementId().equals(capitalSettlementByState.get(owningStateId)),
                    settlementGarrisonStatus.getOrDefault(settlementId, false), settlement.getKind()));
            summarizedSettlementIds.add(settlementId);
            stateManager.observeSettlement(viewerStateId, settlement.getSettlementId(), level.getGameTime());
        }
        for (var known : stateManager.getKnownSettlements(viewerStateId)) if (settlementSummaries.size() < 8192 && inViewport.test(known.position()) && summarizedSettlementIds.add(known.id().toString()))
            settlementSummaries.add(new MapDataPayload.SettlementSummary(known.id().toString(), known.owner(), known.name(), ChunkPos.containing(known.position()).pack(), known.tier(), 0, 0, false, false, known.kind()));

        // Phase 1 legacy: fallback for settlements without full SettlementData
        for (Map.Entry<String, ChunkPos> centerEntry : claimManager.getSettlementCentersView().entrySet()) {
            String settlementId = centerEntry.getKey();
            UUID owningStateId = settlementOwners.get(settlementId);
            ChunkPos centerChunk = centerEntry.getValue();
            if (owningStateId == null || summarizedSettlementIds.contains(settlementId)
                    || !visibleChunkPositions.contains(centerChunk.pack())) continue;

            settlementSummaries.add(new MapDataPayload.SettlementSummary(
                    settlementId, owningStateId, fallbackProvinceName(settlementId), centerChunk.pack(),
                    settlementTiers.getOrDefault(settlementId, 0), 0, 0, false,
                    settlementGarrisonStatus.getOrDefault(settlementId, false)));
        }

        settlementSummaries.sort(Comparator.comparing(MapDataPayload.SettlementSummary::settlementName,
                String.CASE_INSENSITIVE_ORDER));

        // Infrastructure uses the same current visibility mask as claims; never bridge unseen road cells.
        LogisticsManager logistics = LogisticsManager.get(level);
        List<MapDataPayload.RoadSummary> roads = new ArrayList<>();
        int roadPointCount = 0;
        boolean logisticsTruncated = false;
        // Owned straight crossings need only their two banks. Reserve them before ordinary road geometry.
        for (var bridge : logistics.getBridges().stream().filter(b -> viewerStateId.equals(b.ownerId())).sorted(Comparator.comparing(LogisticsManager.Bridge::id)).toList()) {
            if (roadPointCount + 2 > MapDataPayload.MAX_ROAD_POINTS) { logisticsTruncated = true; continue; }
            roads.add(new MapDataPayload.RoadSummary(bridge.id(), bridge.ownerId(), List.of(bridge.start(), bridge.end()), true)); roadPointCount += 2;
        }
        for (var road : logistics.getRoads().stream().sorted(Comparator.comparing(LogisticsManager.Road::id)).toList()) {
            List<BlockPos> run = new ArrayList<>();
            BlockPos previous = null;
            for (BlockPos point : road.points()) {
                long packed = new ChunkPos(point.getX() >> 4, point.getZ() >> 4).pack();
                boolean visible = visibleChunkPositions.contains(packed) && road.cells().contains(packed);
                boolean adjacent = previous == null || Math.abs((point.getX() >> 4) - (previous.getX() >> 4))
                        + Math.abs((point.getZ() >> 4) - (previous.getZ() >> 4)) <= 1;
                if (!visible || !adjacent || roadPointCount >= MapDataPayload.MAX_ROAD_POINTS) {
                    if (!run.isEmpty()) {
                        roads.add(new MapDataPayload.RoadSummary(road.id(), road.ownerId(), run));
                        run = new ArrayList<>();
                    }
                    previous = null;
                }
                if (!visible) continue;
                if (roadPointCount >= MapDataPayload.MAX_ROAD_POINTS) { logisticsTruncated = true; continue; }
                run.add(point); roadPointCount++; previous = point;
            }
            if (!run.isEmpty()) roads.add(new MapDataPayload.RoadSummary(road.id(), road.ownerId(), run));
        }
        for (var bridge : logistics.getBridges()) {
            if (viewerStateId.equals(bridge.ownerId())) continue;
            List<BlockPos> run = new ArrayList<>();
            for (BlockPos point : bridge.points()) {
                boolean visible = viewerStateId.equals(bridge.ownerId()) || visibleChunkPositions.contains(ChunkPos.containing(point).pack());
                if (!visible || roadPointCount >= MapDataPayload.MAX_ROAD_POINTS) {
                    if (!run.isEmpty()) { roads.add(new MapDataPayload.RoadSummary(bridge.id(), bridge.ownerId(), run, true)); run = new ArrayList<>(); }
                    if (visible) logisticsTruncated = true;
                    continue;
                }
                run.add(point); roadPointCount++;
            }
            if (!run.isEmpty()) roads.add(new MapDataPayload.RoadSummary(bridge.id(), bridge.ownerId(), run, true));
        }
        List<MapDataPayload.CampSummary> camps = new ArrayList<>();
        for (var camp : logistics.getCamps().stream().sorted(Comparator.comparing(LogisticsManager.Camp::id)).toList()) {
            long packed = new ChunkPos(camp.position().getX() >> 4, camp.position().getZ() >> 4).pack();
            if (!viewerStateId.equals(camp.ownerId()) && !visibleChunkPositions.contains(packed)) continue;
            if (camps.size() >= MapDataPayload.MAX_VISIBLE_CAMPS) { logisticsTruncated = true; continue; }
            camps.add(new MapDataPayload.CampSummary(camp.id(), camp.ownerId(), camp.position()));
        }

        return new MapDataPayload(viewerStateId, viewerState.getStateName(), visibleChunks,
                stateSummaries, settlementSummaries,
                BreachAlertService.getVisibleRecentAlerts(level, viewerStateId, visibleChunkPositions),
                roads, camps, logisticsTruncated, stateManager.getKnownRegions(viewerStateId).stream()
                    .filter(r -> { ChunkPos p = ChunkPos.unpack(r.key()); return inViewport.test(new BlockPos(p.x() * 1024 + 512, 0, p.z() * 1024 + 512)); })
                    .sorted(Comparator.comparingLong(StateManager.KnownRegion::observedAt).reversed()).limit(256)
                    .map(r -> new MapDataPayload.PoliticalRegion(r.key(), r.owner(), r.land(), r.observedAt())).toList(), claimsTruncated).bounded(level.registryAccess());
    }

    public static MapDataPayload.OverviewPayload buildOverview(ServerPlayer player, RequestMapDataPayload.OverviewRequest request) {
        ServerLevel level = player.level().getServer().overworld(); StateManager states = StateManager.get(level);
        StateData viewer = states.getStateByPlayer(player.getUUID()); if (viewer == null) return null;
        TerrainRuntime runtime = terrainRuntime(level); GeographyPreviewService geography = runtime.geography;
        // Input handling only queues the viewport; one tick worker samples all players fairly.
        var tiles = geography.preview(player.getUUID(), level.getGameTime(), request);
        int radius = request.span() / 2 + 1024;
        List<MapDataPayload.PoliticalRegion> regions = states.getKnownRegions(viewer.getStateId()).stream().filter(r -> {
            ChunkPos p = ChunkPos.unpack(r.key()); return Math.abs((long) p.x() * 1024 + 512 - request.centerX()) <= radius
                    && Math.abs((long) p.z() * 1024 + 512 - request.centerZ()) <= radius;
        }).sorted(Comparator.comparingLong(StateManager.KnownRegion::observedAt).reversed()).limit(256)
                .map(r -> new MapDataPayload.PoliticalRegion(r.key(), r.owner(), r.land(), r.observedAt())).toList();
        return new MapDataPayload.OverviewPayload(viewer.getStateId(), tiles, regions, geography.isUnsupported());
    }

    //chunk function for compressing horizontal runs of chunks with the same metadata into a single entry with a run length, improving network efficiency
    private static List<MapDataPayload.MapChunkData> compressHorizontalRuns(List<MapDataPayload.MapChunkData> individualChunks) {
        //we first sort the chunks by their z-coordinate, then by their x-coordinate, to ensure that we process them in a left-to-right, top-to-bottom order
        individualChunks.sort(Comparator.<MapDataPayload.MapChunkData>comparingInt(
                chunk -> ChunkPos.unpack(chunk.packedChunkPos()).z()
        ).thenComparingInt(chunk -> ChunkPos.unpack(chunk.packedChunkPos()).x()));

        //then we can go through the list and put together the chuinks that have the same metadata and adjacent, reducing data sent over the network
        List<MapDataPayload.MapChunkData> runs = new ArrayList<>();
        MapDataPayload.MapChunkData current = null;
        ChunkPos currentStart = null;

        //now we iterate through the list, checking to see if the current chunk can be combined with the previous one
        for (MapDataPayload.MapChunkData chunk : individualChunks) {
            ChunkPos position = ChunkPos.unpack(chunk.packedChunkPos());
            if (current != null && currentStart.z() == position.z()
                    && currentStart.x() + current.runLength() == position.x()
                    && sameChunkMetadata(current, chunk)) { //if statement checks chunks in the same row for same metadata/adjacency then combines them into one entry
                current = new MapDataPayload.MapChunkData(current.packedChunkPos(),
                        current.runLength() + 1, current.ownerStateId(), current.settlementId(),
                        current.garrisoned(), current.settlementTier(), current.contested()); //add the current chunk and update the current entry in the list
                runs.set(runs.size() - 1, current);
                continue;
            }
            current = chunk;
            currentStart = position;
            runs.add(chunk);
        }
        return runs;
    }

    private static boolean sameChunkMetadata(MapDataPayload.MapChunkData l, MapDataPayload.MapChunkData r) {
        return l.ownerStateId().equals(r.ownerStateId())
                && l.settlementId().equals(r.settlementId())
                && l.garrisoned() == r.garrisoned()
                && l.settlementTier() == r.settlementTier()
                && l.contested() == r.contested();
    }

    //visibility helper function to determine if a chunk is visible to the player based on ownership, war visibility, or scouting
    private static boolean isChunkVisible(ChunkPos position, UUID ownerStateId, UUID viewerStateId, Set<UUID> warVisible, Set<Long> scouted) {
        return ownerStateId.equals(viewerStateId) || warVisible.contains(ownerStateId) || scouted.contains(position.pack());
    }

    //helper function to find all scouted foreign chunks and their bordering state IDs, used for fog-of-war and visibility calculations
    private static ScoutingResult findScoutedForeignChunks(Map<ChunkPos, ChunkData> claims, UUID viewerStateId) {
        Set<Long> scouted = new HashSet<>();
        Set<UUID> bordering = new HashSet<>();
        for (Map.Entry<ChunkPos, ChunkData> entry : claims.entrySet()) {
            if (!viewerStateId.equals(entry.getValue().getOwnerUUID())) continue;
            ChunkPos position = entry.getKey();
            for (int[] d : CARDINAL_DIRECTIONS) {
                ChunkPos neighbor = new ChunkPos(position.x() + d[0], position.z() + d[1]);
                ChunkData nd = claims.get(neighbor);
                if (nd != null && nd.getOwnerUUID() != null && !viewerStateId.equals(nd.getOwnerUUID())) {
                    scouted.add(neighbor.pack());
                    bordering.add(nd.getOwnerUUID());
                }
            }
        }
        return new ScoutingResult(Set.copyOf(scouted), Set.copyOf(bordering));
    }

    private static Set<UUID> getWarVisibleStateIds(StateData viewerState) {
        // War authorizes combat; it does not reveal an entire foreign state through fog.
        return Set.of();
    }

    //capital selection logic: for each state, choose the settlement with the highest tier, and if tied, the name that comes first alphabetically
    private static Map<UUID, UUID> chooseCapitalSettlements(Collection<SettlementData> settlements, Set<UUID> visibleStateIds) {
        Map<UUID, SettlementData> best = new HashMap<>();
        for (SettlementData s : settlements) {
            if (!visibleStateIds.contains(s.getOwningStateId())) continue;
            best.merge(s.getOwningStateId(), s, (l, r) -> {
                if (r.getSettlementTier() != l.getSettlementTier()) {
                    return r.getSettlementTier() > l.getSettlementTier() ? r : l;
                }
                return r.getSettlementName().compareToIgnoreCase(l.getSettlementName()) < 0 ? r : l;
            });
        }
        Map<UUID, UUID> result = new HashMap<>();
        best.forEach((sid, s) -> result.put(sid, s.getSettlementId()));
        return result;
    }

    //fallback province name generator for settlements without a name, using the first 8 characters of the settlement ID
    private static String fallbackProvinceName(String settlementId) {
        if (settlementId == null || settlementId.isBlank()) return "Unnamed Province";
        String shortId = settlementId.length() <= 8 ? settlementId : settlementId.substring(0, 8);
        return "Province " + shortId;
    }

    //normalizes a settlement ID, returning an empty string if it's null or blank
    private static String normalizeSettlementId(String s) { return s == null ? "" : s; }

    private record ScoutingResult(Set<Long> scoutedForeignChunks, Set<UUID> borderingStateIds) {}

    // Converts 1->'1st', 2->'2nd', 3->'3rd', 4->'4th', etc.
    private static String ordinalName(int n) {
        if (n % 100 >= 11 && n % 100 <= 13) return n + "th";
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }

    //builds the netwoek army map payload
    //gives legion, army and independent cohort summaries, with movement orders only for the owner
    public static ArmyMapPayload buildArmyPayload(ServerPlayer player) {
        ServerLevel level = player.level().getServer().overworld();
        ArmyManager armyManager = ArmyManager.get(level);
        StateData viewerState = StateManager.get(level).getStateByPlayer(player.getUUID());
        if (viewerState == null) return ArmyMapPayload.empty();

        UUID viewerStateId = viewerState.getStateId();
        Set<BlockPos> origins = scoutingOrigins(armyManager, viewerStateId);
        ClaimManager claims = ClaimManager.get(level);
        var units = buildArmyPayload(viewerStateId, armyManager, pos -> militaryVisible(pos, viewerStateId, claims, origins));
        var battles = com.devc.minecraftempires.combat.BattleManager.get(level);
        var summaries = battles.getAllBattles().stream().filter(battle -> battle.isActive() && battles.canSpectate(player, battle)).limit(1024).map(battle -> {
            var attacking = armyManager.getArmy(battle.getAttackerArmyId()); var defending = armyManager.getArmy(battle.getDefenderArmyId());
            var first = attacking == null ? null : StateManager.get(level).getState(attacking.getOwningStateId());
            var second = defending == null ? null : StateManager.get(level).getState(defending.getOwningStateId());
            String title = (first == null ? "Attacker" : first.getStateName()) + " vs " + (second == null ? "Defender" : second.getStateName());
            if (title.length() > 160) title = title.substring(0, 160);
            return new ArmyMapPayload.BattleSummary(battle.getBattleId(), title, BlockPos.containing(battle.getMapOriginX(), 64, battle.getMapOriginZ()), battle.getPhase().name(),
                    battle.getAttackerCohorts().stream().mapToInt(com.devc.minecraftempires.combat.CohortData::getCurrentHealth).sum(), battle.getDefenderCohorts().stream().mapToInt(com.devc.minecraftempires.combat.CohortData::getCurrentHealth).sum());
        }).toList();
        return new ArmyMapPayload(units.legions(), units.armies(), units.cohorts(), summaries);
    }

    /** Build from actual formation bodies; shared by live snapshots and location/visibility regressions. */
    public static ArmyMapPayload buildArmyPayload(UUID viewerStateId, ArmyManager armyManager, java.util.function.Predicate<BlockPos> foreignVisible) {

        //legion cohort list for legions wiith free cohorts, including their position, available soldiers, and average morale
        List<ArmyMapPayload.LegionSummary> legionSummaries = new ArrayList<>();
        List<ArmyMapPayload.CohortSummary> cohortSummaries = new ArrayList<>();
        for (Legion legion : armyManager.getAllLegions()) {
            boolean owned = viewerStateId.equals(legion.getOwningStateId());
            var body = legion.getFieldCohorts();
            Cohort anchor = legion.getFieldCohort();
            if (anchor != null && (owned || foreignVisible.test(anchor.getStoredPosition()))) {
                legionSummaries.add(new ArmyMapPayload.LegionSummary(legion.getLegionId(), legion.getOwningStateId(),
                        ChunkPos.containing(anchor.getStoredPosition()).pack(), body.stream().mapToInt(Cohort::getSoldierCount).sum(),
                        (int)body.stream().mapToInt(Cohort::getMorale).average().orElse(0),
                        owned ? legion.getWaypoints().stream().limit(128).toList() : List.of()));
            }
            for (Cohort c : legion.allCohorts()) {
                if (!c.isAlive() || c.isDeployed() || c.isGarrisoned() || body.contains(c)
                        || !owned && !foreignVisible.test(c.getStoredPosition())) continue;
                cohortSummaries.add(new ArmyMapPayload.CohortSummary(c.getCohortId(), legion.getOwningStateId(),
                        owned ? legion.getLegionId() : new UUID(0, 0), c.getStoredPosition(), c.getSoldierCount(), c.getMorale(),
                        owned && c.isRetreating(), owned ? c.getWaypoints().stream().limit(128).toList() : List.of(), owned ? c.getTerrainStatus() : ""));
            }
        }

        // Army summaries — all active Armies for this state
        List<ArmyMapPayload.ArmySummary> armySummaries = new ArrayList<>();
        int armyOrdinal = 1;
        for (Army army : armyManager.getAllArmies()) {
            BlockPos pos = army.getStoredPosition();
            boolean owned = viewerStateId.equals(army.getOwningStateId());
            if (!owned && (!army.isViable(armyManager) || !foreignVisible.test(pos))) continue;
            ChunkPos cp = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
            int maintenance = army.getTotalStrength(armyManager);
            String displayName = owned ? ordinalName(armyOrdinal++) + " Army" : "Foreign army";
            armySummaries.add(new ArmyMapPayload.ArmySummary(
                    army.getArmyId(), army.getOwningStateId(), cp.pack(),
                    owned ? army.getWaypoints().stream().limit(128).toList() : List.of(),
                    displayName,
                    army.getTotalStrength(armyManager),
                    army.getBattleMorale(armyManager),
                    owned ? maintenance : 0, army.isEngaged(), army.getCurrentBattleId(), army.isOnCampaign(),
                    !owned ? "" : army.getDeployedCohortIds().stream().map(armyManager::resolveCohort).filter(java.util.Objects::nonNull).anyMatch(Cohort::isRetreatBlocked) ? "Retreat blocked"
                            : army.getDeployedCohortIds().stream().map(armyManager::resolveCohort).filter(java.util.Objects::nonNull).map(Cohort::getTerrainStatus).filter(status -> !status.isEmpty()).findFirst().orElse(army.isRetreating() ? "Retreating" : "")));
        }

        return new ArmyMapPayload(legionSummaries, armySummaries, cohortSummaries);
    }

    private static Set<BlockPos> scoutingOrigins(ArmyManager armies, UUID viewer) {
        Set<BlockPos> result = new HashSet<>();
        armies.getLegionsForState(viewer).stream().flatMap(l -> l.allCohorts().stream())
                .filter(c -> c.isAlive()).forEach(c -> result.add(c.getStoredPosition()));
        armies.getArmiesForState(viewer).stream().filter(a -> a.isViable(armies)).forEach(a -> result.add(a.getStoredPosition()));
        return result;
    }
    private static boolean militaryVisible(BlockPos pos, UUID viewer, ClaimManager claims, Set<BlockPos> origins) {
        var claim = claims.getClaim(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        return claim != null && viewer.equals(claim.getOwnerUUID()) || origins.stream().anyMatch(origin -> ArmyManager.near(origin, pos, 250));
    }

    // TERRAIN SAMPLING AND DISCOVERY
    // All live world reads stay on the server thread. getChunkNow never loads/generates a distant chunk.
    private static final Map<ServerLevel, TerrainRuntime> TERRAIN_RUNTIMES = new HashMap<>();
    private static final int TERRAIN_RESURVEY_TICKS = 200;

    /** Server terrain facts are independent of explored map pixels and state membership. */
    public enum Ground { UNKNOWN, BLOCKED, DRY, SHALLOW, DEEP, ICE, BRIDGE }
    public record Surface(Ground ground, double height, boolean ocean) {
        public boolean usable() { return ground == Ground.DRY || ground == Ground.SHALLOW || ground == Ground.ICE || ground == Ground.BRIDGE; }
        public boolean dry() { return ground == Ground.DRY; }
        public double travelCost() { return ground == Ground.SHALLOW ? 2.0 : 1.0; }
    }
    public static final Surface UNKNOWN_SURFACE = new Surface(Ground.UNKNOWN, 0, false);
    public static final Surface BLOCKED_SURFACE = new Surface(Ground.BLOCKED, 0, false);
    public enum Verification { PENDING, VERIFIED, BLOCKED, UNKNOWN, LIMITED }
    public record ReachableArea(Verification status, Set<ChunkPos> chunks, Set<ChunkPos> dryChunks, int qualifyingDryChunks, boolean dryOrigin, int originDryColumns) {}
    public record TerrainRoute(Verification status, List<BlockPos> points, double cost) {}
    private record AreaKey(int x, int z, int radius, boolean dry, boolean bridges) {
        AreaKey(int x, int z, int radius, boolean dry) { this(x, z, radius, dry, true); }
    }
    private record RouteKey(int x, int z, int endX, int endZ, int margin, UUID supplyOwner, long ownershipRevision, int boundMinX, int boundMinZ, int boundMaxX, int boundMaxZ, int clearance) {}
    private record TerrainNode(long position, double cost, double priority, TerrainNode previous) {}
    private record NavigationChunk(long version, Surface[] cells) {}
    private record ComponentKey(long chunk, boolean dry) {}
    private record LocalComponents(long version, int[] labels, List<int[]> cells) {}
    private static final int MAX_TERRAIN_JOBS = 64;
    private static final int MAX_NAVIGATION_CHUNKS = 8192;
    private static final int MAX_SEARCH_NODES = 350_000;
    private static final int NAVIGATION_REFRESH_TICKS = 40;

    private static long columnKey(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    private static int columnX(long key) { return (int) (key >> 32); }
    private static int columnZ(long key) { return (int) key; }

    public static long terrainRevision(ServerLevel level) { return terrainRuntime(level).navigationRevision; }
    public static boolean canPreviewTerrain(ServerLevel level, UUID viewer, BlockPos position) {
        var claim = ClaimManager.get(level).getClaim(ChunkPos.containing(position));
        if (claim != null && viewer.equals(claim.getOwnerUUID())) return true;
        var observed = TerrainSavedData.get(level).states.get(viewer);
        return observed != null && observed.containsKey(ChunkPos.containing(position).pack());
    }
    public static void crossingsChanged(ServerLevel level) {
        TerrainRuntime runtime = terrainRuntime(level);
        runtime.areas.clear(); runtime.routes.clear(); runtime.navigationRevision++;
        runtime.components.clear();
        ClaimManager.get(level).reconsiderInfill();
    }
    private static TerrainRuntime terrainRuntime(ServerLevel level) {
        return TERRAIN_RUNTIMES.computeIfAbsent(level, ignored -> new TerrainRuntime());
    }

    /** Invalidated cells fail closed until a new bounded survey completes. */
    public static void invalidateTerrain(ServerLevel level, BlockPos position) {
        if (level != level.getServer().overworld()) return;
        TerrainRuntime runtime = terrainRuntime(level);
        int minDx = (position.getX() & 15) == 0 ? -1 : 0, maxDx = (position.getX() & 15) == 15 ? 1 : 0;
        int minDz = (position.getZ() & 15) == 0 ? -1 : 0, maxDz = (position.getZ() & 15) == 15 ? 1 : 0;
        for (int dx = minDx; dx <= maxDx; dx++) for (int dz = minDz; dz <= maxDz; dz++) {
            long key = new ChunkPos((position.getX() >> 4) + dx, (position.getZ() >> 4) + dz).pack();
            boolean known = runtime.navigation.containsKey(key);
            if (known) runtime.invalidated.add(key);
            runtime.components.remove(new ComponentKey(key, true)); runtime.components.remove(new ComponentKey(key, false));
            if (known || runtime.survey != null && runtime.survey.packed == key) runtime.navigationRequests.add(key);
            runtime.navigationTimes.remove(key);
            runtime.missing.remove(key);
        }
        if (runtime.survey != null && runtime.invalidated.contains(runtime.survey.packed)) runtime.survey = null;
        runtime.navigationRevision++;
        LogisticsManager.get(level).invalidateTerrainDelivery();
        ClaimManager.get(level).terrainChanged();
    }

    /** A neighbor notification requests comparison, rather than declaring unchanged geometry impassable. */
    public static void refreshTerrain(ServerLevel level, BlockPos position) {
        if (level != level.getServer().overworld()) return;
        TerrainRuntime runtime = terrainRuntime(level); long packed = ChunkPos.containing(position).pack();
        if (runtime.navigation.containsKey(packed)) runtime.navigationRequests.add(packed);
    }

    /** Queues inspection, never calls getChunk or supplies a generation ticket. */
    public static Surface surface(ServerLevel level, int x, int z) {
        return surface(level, x, z, true);
    }
    private static Surface surface(ServerLevel level, int x, int z, boolean fresh) {
        Surface base = baseSurface(level, x, z, fresh);
        LogisticsManager.Bridge bridge = LogisticsManager.get(level).bridgeAt(x, z);
        if (bridge == null || base.ground == Ground.UNKNOWN) return base;
        Surface start = baseSurface(level, bridge.start().getX(), bridge.start().getZ(), fresh);
        Surface end = baseSurface(level, bridge.end().getX(), bridge.end().getZ(), fresh);
        if (start.ground == Ground.UNKNOWN || end.ground == Ground.UNKNOWN) return UNKNOWN_SURFACE;
        if (!start.dry() || !end.dry() || Math.abs(start.height - bridge.start().getY()) > 1
                || Math.abs(end.height - bridge.end().getY()) > 1) return base;
        double progress = bridge.progress(x + .5, z + .5);
        double deck = bridge.start().getY() + progress * (bridge.end().getY() - bridge.start().getY());
        if (base.ground == Ground.BLOCKED || base.height > deck + 1.000001) return base;
        if (base.dry() && (progress == 0 || progress == 1)) return base;
        return new Surface(Ground.BRIDGE, deck, base.ocean);
    }

    public static Surface baseSurface(ServerLevel level, int x, int z) {
        return baseSurface(level, x, z, true);
    }
    private static Surface baseSurface(ServerLevel level, int x, int z, boolean fresh) {
        if (Math.abs((long) x) > 29_999_984 || Math.abs((long) z) > 29_999_984
                || !level.getWorldBorder().isWithinBounds(new BlockPos(x, 0, z))) return BLOCKED_SURFACE;
        TerrainRuntime runtime = terrainRuntime(level);
        long packed = new ChunkPos(x >> 4, z >> 4).pack();
        NavigationChunk chunk = runtime.navigation.get(packed);
        long age = level.getGameTime() - runtime.navigationTimes.getOrDefault(packed, Long.MIN_VALUE / 2);
        if (chunk == null || runtime.invalidated.contains(packed) || fresh && age >= NAVIGATION_REFRESH_TICKS) {
            if (runtime.navigationRequests.size() < MAX_NAVIGATION_CHUNKS
                    && level.getGameTime() >= runtime.missing.getOrDefault(packed, 0L)) runtime.navigationRequests.add(packed);
        }
        // Old unloaded snapshots remain useful; loaded active corridors must be rechecked before stepping.
        if (chunk == null || runtime.invalidated.contains(packed) || fresh && age >= NAVIGATION_REFRESH_TICKS
                && level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null) return UNKNOWN_SURFACE;
        return chunk.cells[(z & 15) * 16 + (x & 15)];
    }

    public static Surface surface(ServerLevel level, BlockPos position) { return surface(level, position.getX(), position.getZ()); }

    /** The formation's saved bank pair is shared by strategic movement, battle entry and recovery. */
    public static boolean prepareFormation(ServerLevel level, com.devc.minecraftempires.combat.CohortData cohort,
                                           double originX, double originZ, double nextX, double nextZ) {
        return prepareFormation(level, cohort, originX, originZ, nextX, nextZ, true);
    }
    /** Strategic markers follow their verified surface route; deployed tactical ranks still need the full formation footprint. */
    public static boolean prepareStrategicFormation(ServerLevel level, com.devc.minecraftempires.combat.CohortData cohort,
                                                    double nextX, double nextZ) {
        return prepareFormation(level, cohort, 0, 0, nextX, nextZ, false);
    }
    private static boolean prepareFormation(ServerLevel level, com.devc.minecraftempires.combat.CohortData cohort,
                                            double originX, double originZ, double nextX, double nextZ, boolean deployed) {
        double x = originX + cohort.getPosition().x, z = originZ + cohort.getPosition().y;
        double dx = nextX - cohort.getPosition().x, dz = nextZ - cohort.getPosition().y;
        cohort.face(dx, dz);
        double fx = cohort.getHeadingX(), fz = cohort.getHeadingZ();
        Surface ahead = surface(level, (int) Math.floor(x + fx * 13), (int) Math.floor(z + fz * 13));
        Surface here = surface(level, (int) Math.floor(x), (int) Math.floor(z));
        List<BlockPos> plannedCrossing = cohort.isColumn() ? List.of() : crossingAlongOrders(level, cohort, originX, originZ);
        if (!plannedCrossing.isEmpty()) cohort.beginCrossing(plannedCrossing);
        if (!cohort.isColumn() && (ahead.ground == Ground.BRIDGE || here.ground == Ground.BRIDGE)) {
            int bx = (int) Math.floor(here.ground == Ground.BRIDGE ? x : x + fx * 13);
            int bz = (int) Math.floor(here.ground == Ground.BRIDGE ? z : z + fz * 13);
            var abstractBridge = LogisticsManager.get(level).bridgeAt(bx, bz);
            BlockPos first, last;
            if (abstractBridge != null) { first = abstractBridge.start(); last = abstractBridge.end(); }
            else {
                // Physical crossings have no purchase span limit. Stop pending inspection of either bank.
                int sx = Math.abs(fx) >= Math.abs(fz) ? (fx >= 0 ? 1 : -1) : 0;
                int sz = sx == 0 ? (fz >= 0 ? 1 : -1) : 0;
                first = physicalBank(level, bx, bz, -sx, -sz); last = physicalBank(level, bx, bz, sx, sz);
                if (first == null || last == null) return false;
            }
            if ((last.getX() - first.getX()) * fx + (last.getZ() - first.getZ()) * fz < 0) { BlockPos swap = first; first = last; last = swap; }
            if (verifySegment(level, first.getX() + .5, first.getZ() + .5, last.getX() + .5, last.getZ() + .5) != Verification.VERIFIED) return false;
            cohort.beginCrossing(first, last);
        }
        if (cohort.isColumn() && cohort.getCrossingEnd() != null) {
            BlockPos end = cohort.getCrossingEnd();
            double beyond = (x - end.getX() - .5) * cohort.getHeadingX() + (z - end.getZ() - .5) * cohort.getHeadingZ();
            if (beyond >= 0 && cohort.footprintAt(cohort.getPosition().x, cohort.getPosition().y).stream().noneMatch(point -> surface(level, (int) Math.floor(originX + point.x), (int) Math.floor(originZ + point.y)).ground == Ground.BRIDGE)
                    && formationClear(level, x, z, cohort.getHeadingX(), cohort.getHeadingZ(), 10, 10)) cohort.reform();
        }
        cohort.face(dx, dz);
        return verifySegment(level, x, z, originX + nextX, originZ + nextZ) == Verification.VERIFIED
                && (cohort.isColumn() ? columnClear(level, cohort, originX, originZ, nextX, nextZ)
                : !deployed || formationClear(level, originX + nextX, originZ + nextZ, cohort.getHeadingX(), cohort.getHeadingZ(), cohort.getWidth(), cohort.getLength()));
    }

    private static boolean columnClear(ServerLevel level, com.devc.minecraftempires.combat.CohortData cohort, double originX, double originZ, double x, double z) {
        var points = cohort.footprintAt(x, z);
        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i); var next = points.get(Math.min(i + 1, points.size() - 1));
            double dx = next.x - point.x, dz = next.y - point.y, distance = Math.hypot(dx, dz);
            double sx = distance < .00001 ? -cohort.getHeadingZ() : -dz / distance, sz = distance < .00001 ? cohort.getHeadingX() : dx / distance;
            for (int side = -1; side <= 1; side++) if (!surface(level, (int) Math.floor(originX + point.x + sx * side), (int) Math.floor(originZ + point.y + sz * side)).usable()) return false;
            if (i > 0 && verifySegment(level, originX + points.get(i - 1).x, originZ + points.get(i - 1).y, originX + point.x, originZ + point.y) != Verification.VERIFIED) return false;
        }
        return true;
    }

    /** Follow verified ordered bends to find physical banks; do not assume every bridge is straight. */
    private static List<BlockPos> crossingAlongOrders(ServerLevel level, com.devc.minecraftempires.combat.CohortData cohort, double originX, double originZ) {
        double x = originX + cohort.getPosition().x, z = originZ + cohort.getPosition().y, travelled = 0;
        BlockPos previous = BlockPos.containing(x, 0, z); boolean crossing = false;
        List<BlockPos> path = new ArrayList<>();
        for (var waypoint : cohort.getMovementWaypoints()) {
            double tx = originX + waypoint.x, tz = originZ + waypoint.y, length = Math.hypot(tx - x, tz - z);
            int steps = Math.max(1, (int) Math.ceil(length));
            for (int i = 1; i <= steps; i++) {
                double px = x + (tx - x) * i / steps, pz = z + (tz - z) * i / steps;
                Surface ground = surface(level, (int) Math.floor(px), (int) Math.floor(pz));
                BlockPos point = new BlockPos((int) Math.floor(px), (int) Math.floor(ground.height), (int) Math.floor(pz));
                if (ground.ground == Ground.BRIDGE) {
                    if (!crossing) {
                        Surface bank = surface(level, previous);
                        if (!bank.dry() && bank.ground != Ground.ICE) return List.of();
                        crossing = true; path.add(new BlockPos(previous.getX(), (int) bank.height, previous.getZ()));
                    }
                    if (!point.equals(previous)) path.add(point);
                } else if (crossing) {
                    if (!ground.dry() && ground.ground != Ground.ICE) return List.of();
                    path.add(point); List<BlockPos> result = new ArrayList<>(); result.add(path.getFirst()); result.addAll(ArmyManager.routeTurns(path)); return result.size() <= 256 ? List.copyOf(result) : List.of();
                } else if (travelled + length * i / steps > 13 || ground.ground == Ground.UNKNOWN) return List.of();
                if (travelled + length * i / steps > 4096) return List.of();
                previous = point;
            }
            travelled += length; x = tx; z = tz;
        }
        return List.of();
    }

    private static BlockPos physicalBank(ServerLevel level, int x, int z, int dx, int dz) {
        for (int length = 0; length <= 1024; length++, x += dx, z += dz) {
            Surface ground = surface(level, x, z);
            if (ground.dry() || ground.ground == Ground.ICE) return new BlockPos(x, (int) ground.height, z);
            if (ground.ground != Ground.BRIDGE) return null;
        }
        return null;
    }

    public static boolean formationClear(ServerLevel level, double x, double z, double hx, double hz, double width, double length) {
        return formationStatus(level, x, z, hx, hz, width, length) == Verification.VERIFIED;
    }
    public static Verification formationStatus(ServerLevel level, double x, double z, double hx, double hz, double width, double length) {
        return formationStatus(level, x, z, hx, hz, width, length, true);
    }
    /** Atomic layout planning shares the survey's inspected snapshot; explicit terrain changes still invalidate it. */
    public static Verification deploymentFormationStatus(ServerLevel level, double x, double z) {
        var status = cachedFormationStatus(level, x, z);
        if (status == Verification.UNKNOWN) {
            // An unavailable chunk is a rejected slot, not an inspection that can complete by waiting.
            TerrainRuntime runtime = terrainRuntime(level);
            for (int dx = 0; dx < 10; dx++) for (int dz = 0; dz < 10; dz++) {
                int cx = (int) Math.floor(x - 4.5) + dx, cz = (int) Math.floor(z - 4.5) + dz;
                if (runtime.missing.containsKey(new ChunkPos(cx >> 4, cz >> 4).pack()) && surface(level, cx, cz, false).ground == Ground.UNKNOWN) return Verification.BLOCKED;
            }
        }
        return status;
    }
    private static Verification formationStatus(ServerLevel level, double x, double z, double hx, double hz, double width, double length, boolean fresh) {
        // Ordinary formations use the same square clearance as the route planner. Column geometry follows its route.
        if (width == 10 && length == 10) { hx = 0; hz = 1; }
        Surface[] previousRow = new Surface[Math.max(1, (int) Math.ceil(width))];
        for (double along = -length / 2 + .5; along < length / 2; along++) for (double side = -width / 2 + .5; side < width / 2; side++) {
            Surface ground = surface(level, (int) Math.floor(x + hx * along - hz * side), (int) Math.floor(z + hz * along + hx * side), fresh);
            if (ground.ground == Ground.UNKNOWN) return Verification.UNKNOWN;
            if (!ground.usable()) return Verification.BLOCKED;
            int index = (int) Math.round(side + width / 2 - .5);
            if (previousRow[index] != null && !canStep(previousRow[index], ground, false)) return Verification.BLOCKED;
            if (index > 0 && !canStep(previousRow[index - 1], ground, false)) return Verification.BLOCKED;
            previousRow[index] = ground;
        }
        return Verification.VERIFIED;
    }
    /** Bounded local avoidance reuses inspected footprints; actual movement still verifies fresh ground. */
    public static boolean formationSegmentClear(ServerLevel level, double x, double z, double endX, double endZ, long deadline) {
        double length = Math.hypot(endX - x, endZ - z);
        if (length > 48 || verifySegment(level, x, z, endX, endZ) != Verification.VERIFIED) return false;
        int steps = Math.max(1, (int) Math.ceil(length));
        for (int i = 0; i <= steps; i++) if (System.nanoTime() >= deadline || cachedFormationStatus(level, x + (endX - x) * i / steps, z + (endZ - z) * i / steps) != Verification.VERIFIED) return false;
        return true;
    }
    private static Verification cachedFormationStatus(ServerLevel level, double x, double z) {
        TerrainRuntime runtime = terrainRuntime(level);
        if (runtime.clearanceRevision != runtime.navigationRevision) { runtime.clearances.clear(); runtime.clearanceRevision = runtime.navigationRevision; }
        long key = columnKey((int) Math.floor(x - 4.5), (int) Math.floor(z - 4.5));
        Verification cached = runtime.clearances.get(key); if (cached != null) return cached;
        Verification status = formationStatus(level, x, z, 0, 1, 10, 10, false);
        if (status != Verification.UNKNOWN) {
            if (runtime.clearances.size() >= 4096) runtime.clearances.remove(runtime.clearances.keySet().iterator().next());
            runtime.clearances.put(key, status);
        }
        return status;
    }

    /** Only a confirmed broken crossing causes the approved once-only health loss; pending samples do not. */
    public static boolean recoverCrossing(ServerLevel level, com.devc.minecraftempires.combat.CohortData cohort, double originX, double originZ) {
        BlockPos first = cohort.getCrossingStart(), last = cohort.getCrossingEnd();
        if (first == null || last == null) return false;
        List<BlockPos> path = cohort.getCrossingPath().isEmpty() ? List.of(first, last) : cohort.getCrossingPath();
        boolean broken = false; double total = 0, at = 0, nearest = Double.POSITIVE_INFINITY;
        double x = cohort.getPosition().x + originX, z = cohort.getPosition().y + originZ;
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1), b = path.get(i);
            broken |= verifySegment(level, a.getX() + .5, a.getZ() + .5, b.getX() + .5, b.getZ() + .5) == Verification.BLOCKED;
            double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ(), length = Math.hypot(dx, dz);
            double t = Math.clamp(((x - a.getX() - .5) * dx + (z - a.getZ() - .5) * dz) / Math.max(1, length * length), 0, 1);
            double distance = Math.hypot(x - a.getX() - .5 - t * dx, z - a.getZ() - .5 - t * dz);
            if (distance < nearest) { nearest = distance; at = total + t * length; } total += length;
        }
        if (!broken) return false;
        double progress = at / Math.max(1, total);
        if ((progress <= .000001 || progress >= .999999) && surface(level, (int) Math.floor(x), (int) Math.floor(z)).dry()) { cohort.reform(); cohort.clearOrders(); return false; }
        if (nearest > 2) { cohort.reform(); cohort.clearOrders(); return false; }
        if (progress < 0 || progress > 1) { cohort.reform(); cohort.clearOrders(); return false; }
        BlockPos bank = progress <= .5 ? first : last;
        if (!surface(level, bank).usable()) return false;
        cohort.applyDamage(LogisticsManager.collapseLoss(cohort.getCurrentHealth(), progress), 0);
        cohort.place(bank.getX() + .5 - originX, bank.getZ() + .5 - originZ);
        cohort.markRecovered(); return true;
    }

    /** A pure transition rule shared by floods, route searches and live movement checks. */
    public static boolean canStep(Surface from, Surface to, boolean dryOnly) {
        return from.usable() && to.usable() && (!dryOnly || from.dry() && to.dry())
                && Math.abs(from.height - to.height) <= 1.000001;
    }

    /** A three-cell deck is checked perpendicular to travel, including the approach cell. */
    private static Verification step(ServerLevel level, int x, int z, int nx, int nz, boolean dryOnly) {
        return step(level, x, z, nx, nz, dryOnly, true);
    }
    private static Verification step(ServerLevel level, int x, int z, int nx, int nz, boolean dryOnly, boolean fresh) {
        Surface from = surface(level, x, z, fresh), to = surface(level, nx, nz, fresh);
        if (from.ground == Ground.UNKNOWN || to.ground == Ground.UNKNOWN) return Verification.UNKNOWN;
        if (!canStep(from, to, dryOnly)) return Verification.BLOCKED;
        if (!dryOnly && (from.ground == Ground.BRIDGE || to.ground == Ground.BRIDGE)) {
            int sideX = nz - z, sideZ = x - nx;
            for (int sign : new int[]{-1, 1}) {
                Surface a = surface(level, x + sideX * sign, z + sideZ * sign, fresh);
                Surface b = surface(level, nx + sideX * sign, nz + sideZ * sign, fresh);
                if (a.ground == Ground.UNKNOWN || b.ground == Ground.UNKNOWN) return Verification.UNKNOWN;
                if (!a.usable() || !b.usable() || a.ground == Ground.SHALLOW || b.ground == Ground.SHALLOW
                        || Math.abs(a.height - from.height) > .500001 || Math.abs(b.height - to.height) > .500001
                        || !canStep(a, b, false)) return Verification.BLOCKED;
            }
        }
        return Verification.VERIFIED;
    }

    /** Supercover traversal prevents diagonal corner cuts and samples every crossed block. */
    public static Verification verifySegment(ServerLevel level, double x, double z, double endX, double endZ) {
        return verifySegment(level, x, z, endX, endZ, true);
    }
    private static Verification verifySegment(ServerLevel level, double x, double z, double endX, double endZ, boolean fresh) {
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
        int tx = (int) Math.floor(endX), tz = (int) Math.floor(endZ);
        if (Math.abs((long) tx - cx) + Math.abs((long) tz - cz) > 4096) return Verification.LIMITED;
        Surface initial = surface(level, cx, cz, fresh);
        if (initial.ground == Ground.UNKNOWN) return Verification.UNKNOWN;
        if (!initial.usable()) return Verification.BLOCKED;
        double dx = endX - x, dz = endZ - z;
        int sx = Integer.signum(tx - cx), sz = Integer.signum(tz - cz);
        double deltaX = sx == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dx);
        double deltaZ = sz == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dz);
        double nextX = sx == 0 ? Double.POSITIVE_INFINITY : ((sx > 0 ? cx + 1 : cx) - x) / dx;
        double nextZ = sz == 0 ? Double.POSITIVE_INFINITY : ((sz > 0 ? cz + 1 : cz) - z) / dz;
        while (cx != tx || cz != tz) {
            if (Math.abs(nextX - nextZ) < 1.0e-9) {
                Verification a = step(level, cx, cz, cx + sx, cz, false, fresh);
                Verification b = step(level, cx, cz, cx, cz + sz, false, fresh);
                Verification c = step(level, cx + sx, cz, cx + sx, cz + sz, false, fresh);
                Verification d = step(level, cx, cz + sz, cx + sx, cz + sz, false, fresh);
                if (a != Verification.VERIFIED || b != Verification.VERIFIED || c != Verification.VERIFIED || d != Verification.VERIFIED)
                    return a == Verification.UNKNOWN || b == Verification.UNKNOWN || c == Verification.UNKNOWN || d == Verification.UNKNOWN
                            ? Verification.UNKNOWN : Verification.BLOCKED;
                cx += sx; cz += sz; nextX += deltaX; nextZ += deltaZ;
            } else if (nextX < nextZ) {
                Verification status = step(level, cx, cz, cx + sx, cz, false, fresh);
                if (status != Verification.VERIFIED) return status;
                cx += sx; nextX += deltaX;
            } else {
                Verification status = step(level, cx, cz, cx, cz + sz, false, fresh);
                if (status != Verification.VERIFIED) return status;
                cz += sz; nextZ += deltaZ;
            }
        }
        return Verification.VERIFIED;
    }

    /** A transaction refreshes its whole corridor once, without requiring all chunks to be younger than two seconds together. */
    public static final class TerrainInspection {
        private final Set<Long> chunks = new LinkedHashSet<>();
        private final long requested;
        private boolean limited;
        public TerrainInspection(ServerLevel level, Collection<? extends List<BlockPos>> paths) {
            requested = level.getGameTime();
            int work = 0;
            for (List<BlockPos> path : paths) for (int index = 0; index < path.size(); index++) {
                BlockPos from = index == 0 ? path.getFirst() : path.get(index - 1), to = path.get(index);
                int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
                if (steps > 4096 || (work += steps + 1) > 262144) { limited = true; return; }
                for (int n = 0; n <= steps; n++) {
                    double t = steps == 0 ? 0 : (double) n / steps;
                    int x = (int) Math.floor(from.getX() + .5 + (to.getX() - from.getX()) * t);
                    int z = (int) Math.floor(from.getZ() + .5 + (to.getZ() - from.getZ()) * t);
                    // Includes supercover corners and the full three-block bridge width.
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                        chunks.add(ChunkPos.pack((x + dx) >> 4, (z + dz) >> 4));
                    if (chunks.size() > 1024) { limited = true; return; }
                }
            }
        }
        public Verification status(ServerLevel level) {
            if (limited || chunks.isEmpty()) return Verification.LIMITED;
            TerrainRuntime runtime = terrainRuntime(level);
            boolean pending = false, unknown = false;
            // Request every dependency, even if the first one is still pending.
            for (long key : chunks) {
                if (runtime.navigation.containsKey(key) && !runtime.invalidated.contains(key)
                        && runtime.navigationTimes.getOrDefault(key, Long.MIN_VALUE) >= requested) continue;
                if (runtime.missing.getOrDefault(key, 0L) > level.getGameTime()) { unknown = true; continue; }
                pending = true;
                if (runtime.navigationRequests.size() < MAX_NAVIGATION_CHUNKS
                        && (runtime.survey == null || runtime.survey.packed != key)) runtime.navigationRequests.add(key);
            }
            return pending ? Verification.PENDING : unknown ? Verification.UNKNOWN : Verification.VERIFIED;
        }
        public Surface baseSurface(ServerLevel level, BlockPos point) {
            if (!chunks.contains(ChunkPos.containing(point).pack())) return UNKNOWN_SURFACE;
            return MapDataService.baseSurface(level, point.getX(), point.getZ(), false);
        }
        public Verification verifySegment(ServerLevel level, BlockPos from, BlockPos to) {
            return MapDataService.verifySegment(level, from.getX() + .5, from.getZ() + .5, to.getX() + .5, to.getZ() + .5, false);
        }
    }

    public static ReachableArea reachableArea(ServerLevel level, BlockPos origin, int radius, boolean dryOnly) {
        TerrainRuntime runtime = terrainRuntime(level);
        AreaKey key = new AreaKey(origin.getX(), origin.getZ(), radius, dryOnly);
        TerrainSearch search = areaSearch(level, runtime, key);
        if (search == null) return new ReachableArea(Verification.PENDING, Set.of(), Set.of(), 0, false, 0);
        int qualifying = search.qualifyingChunks(runtime);
        return new ReachableArea(search.awaitingInvalidation(runtime) ? Verification.PENDING : search.status, Set.copyOf(search.chunks), Set.copyOf(search.dryChunks), qualifying, search.dryOrigin, search.originDryColumns);
    }
    private static TerrainSearch areaSearch(ServerLevel level, TerrainRuntime runtime, AreaKey key) {
        TerrainSearch search = runtime.areas.get(key);
        if (search == null || !search.current(runtime)) {
            if (search == null && !admitSearch(runtime.areas)) return null;
            TerrainSearch previous = search;
            search = new TerrainSearch(key);
            search.createdAt = previous == null ? level.getGameTime() : previous.createdAt;
            search.restarts = previous == null ? 0 : previous.restarts + 1;
            runtime.areas.put(key, search);
        }
        search.lastRequested = level.getGameTime();
        search.retryMissing(level, runtime);
        return search;
    }

    /** One shared flood proves deployment belongs to the same bank; it never spreads troops over a bridge. */
    public static Verification deploymentSite(ServerLevel level, BlockPos origin, BlockPos site) {
        TerrainRuntime runtime = terrainRuntime(level);
        TerrainSearch search = areaSearch(level, runtime, new AreaKey(origin.getX(), origin.getZ(), 128, false, false));
        if (search == null || search.awaitingInvalidation(runtime)) return Verification.PENDING;
        BitSet cells = search.reachableCells.get(ChunkPos.containing(site).pack());
        if (cells != null && cells.get((site.getZ() & 15) * 16 + (site.getX() & 15))) return Verification.VERIFIED;
        return search.status == Verification.VERIFIED ? Verification.BLOCKED : search.status;
    }

    /** Progress belongs to the existing request; polling never starts a second independent survey. */
    public static String areaProgress(ServerLevel level, BlockPos origin, int radius, boolean dryOnly) {
        TerrainRuntime runtime = terrainRuntime(level);
        TerrainSearch job = runtime.areas.get(new AreaKey(origin.getX(), origin.getZ(), radius, dryOnly));
        if (job == null) return "Waiting for a survey slot";
        long dirty = job.revisions.keySet().stream().filter(runtime.invalidated::contains).count();
        String phase = dirty > 0 ? "Refreshing " + dirty + " changed chunks" : job.status == Verification.UNKNOWN ? "Some surrounding terrain is unavailable"
                : job.status == Verification.LIMITED ? "Survey reached its work limit" : job.status == Verification.BLOCKED ? "Origin is not usable"
                : job.status == Verification.VERIFIED ? "Survey complete" : !job.started ? "Inspecting the altar chunk" : "Surveying connected terrain";
        return phase + "; " + job.chunks.size() + " chunks checked, " + job.qualifyingChunks(runtime) + "/9 qualifying; "
                + "work=" + job.workUnits + ", restarts=" + job.restarts + ", age=" + (level.getGameTime() - job.createdAt) + " ticks"
                + ", queued=" + runtime.navigationRequests.size() + ", sampler=" + (runtime.survey == null ? "idle" : ChunkPos.unpack(runtime.survey.packed) + ":" + runtime.survey.cursor)
                + ", dirty=" + job.revisions.keySet().stream().filter(runtime.invalidated::contains).map(ChunkPos::unpack).toList()
                + ", next=" + runtime.navigationRequests.stream().limit(8).map(ChunkPos::unpack).toList();
    }
    public static void requestFoundingSurvey(ServerLevel level, BlockPos origin) {
        TerrainRuntime runtime = terrainRuntime(level);
        List<ChunkPos> chunks = StateManager.squareChunks(origin, StateBalance.SETTLEMENT_RADIUS);
        chunks.sort(Comparator.comparingInt(chunk -> Math.abs(chunk.x() - (origin.getX() >> 4)) + Math.abs(chunk.z() - (origin.getZ() >> 4))));
        for (int i = chunks.size() - 1; i >= 0; i--) {
            long key = chunks.get(i).pack();
            if (!runtime.navigation.containsKey(key) || runtime.invalidated.contains(key)) runtime.navigationRequests.addFirst(key);
        }
    }

    public static TerrainRoute route(ServerLevel level, BlockPos start, BlockPos end, int margin) {
        return route(level, start, end, margin, null);
    }
    public static TerrainRoute route(ServerLevel level, BlockPos start, BlockPos end, int margin, UUID supplyOwner) {
        return routeWithin(level, start, end, margin, supplyOwner, -29_999_984, -29_999_984, 29_999_984, 29_999_984);
    }
    public static TerrainRoute battleRoute(ServerLevel level, BlockPos start, BlockPos end, double originX, double originZ) {
        int bound = (int) com.devc.minecraftempires.combat.BattleSession.FIELD_BOUND;
        return routeWithin(level, start, end, 256, null, (int) originX - bound, (int) originZ - bound, (int) originX + bound, (int) originZ + bound);
    }
    public static TerrainRoute formationRoute(ServerLevel level, BlockPos start, BlockPos end, double originX, double originZ) {
        int bound = (int) com.devc.minecraftempires.combat.BattleSession.FIELD_BOUND;
        return routeWithin(level, start, end, 256, null, (int) originX - bound, (int) originZ - bound, (int) originX + bound, (int) originZ + bound, 10);
    }
    private static TerrainRoute routeWithin(ServerLevel level, BlockPos start, BlockPos end, int margin, UUID supplyOwner, int minX, int minZ, int maxX, int maxZ) {
        return routeWithin(level, start, end, margin, supplyOwner, minX, minZ, maxX, maxZ, 0);
    }
    private static TerrainRoute routeWithin(ServerLevel level, BlockPos start, BlockPos end, int margin, UUID supplyOwner, int minX, int minZ, int maxX, int maxZ, int clearance) {
        if (start.getX() < minX || start.getX() > maxX || start.getZ() < minZ || start.getZ() > maxZ || end.getX() < minX || end.getX() > maxX || end.getZ() < minZ || end.getZ() > maxZ) return new TerrainRoute(Verification.BLOCKED, List.of(), 0);
        TerrainRuntime runtime = terrainRuntime(level);
        long rights = supplyOwner == null ? 0 : 31 * ClaimManager.get(level).getTopologyRevision() + StateManager.get(level).getLogisticsRevision();
        RouteKey key = new RouteKey(start.getX(), start.getZ(), end.getX(), end.getZ(), Math.clamp(margin, 16, 256), supplyOwner, rights, minX, minZ, maxX, maxZ, clearance);
        if (Math.abs((long) key.x - key.endX) + Math.abs((long) key.z - key.endZ) > 4096)
            return new TerrainRoute(Verification.LIMITED, List.of(), 0);
        TerrainSearch search = runtime.routes.get(key);
        if (search == null || !search.current(runtime)) {
            if (search == null && !admitSearch(runtime.routes)) return new TerrainRoute(Verification.PENDING, List.of(), 0);
            search = new TerrainSearch(key);
            runtime.routes.put(key, search);
        }
        search.lastRequested = level.getGameTime();
        search.retryMissing(level, runtime);
        return new TerrainRoute(search.awaitingInvalidation(runtime) ? Verification.PENDING : search.status, search.path, search.pathCost);
    }

    private static boolean admitSearch(Map<?, TerrainSearch> jobs) {
        if (jobs.size() < MAX_TERRAIN_JOBS) return true;
        var iterator = jobs.values().iterator();
        while (iterator.hasNext()) if (iterator.next().status != Verification.PENDING) { iterator.remove(); return true; }
        return false;
    }

    /** Chunk-local components retain separate banks; bridges use the full-width edge verifier. */
    private static LocalComponents components(ServerLevel level, TerrainRuntime runtime, long packed, boolean dry) {
        NavigationChunk sample = runtime.navigation.get(packed); if (sample == null) return null;
        ComponentKey key = new ComponentKey(packed, dry); LocalComponents cached = runtime.components.get(key);
        if (cached != null && cached.version == sample.version) return cached;
        int[] labels = new int[256]; Arrays.fill(labels, -1); List<int[]> groups = new ArrayList<>();
        ChunkPos chunk = ChunkPos.unpack(packed); Surface[] ground = new Surface[256];
        for (int i = 0; i < 256; i++) {
            ground[i] = surface(level, chunk.x() * 16 + (i & 15), chunk.z() * 16 + (i >> 4), false);
            if (ground[i].ground == Ground.UNKNOWN) return null;
        }
        for (int start = 0; start < 256; start++) {
            if (labels[start] >= 0 || !ground[start].usable() || ground[start].ground == Ground.BRIDGE || dry && !ground[start].dry()) continue;
            int[] queue = new int[256]; int head = 0, tail = 1; queue[0] = start; labels[start] = groups.size();
            while (head < tail) {
                int cell = queue[head++];
                for (int[] direction : CARDINAL_DIRECTIONS) {
                    int x = (cell & 15) + direction[0], z = (cell >> 4) + direction[1];
                    if (x < 0 || x > 15 || z < 0 || z > 15) continue;
                    int next = z * 16 + x;
                    if (labels[next] >= 0 || ground[next].ground == Ground.BRIDGE || !canStep(ground[cell], ground[next], dry)) continue;
                    labels[next] = groups.size(); queue[tail++] = next;
                }
            }
            groups.add(Arrays.copyOf(queue, tail));
        }
        LocalComponents result = new LocalComponents(sample.version, labels, List.copyOf(groups)); runtime.components.put(key, result); return result;
    }

    private static final class TerrainSearch {
        final int x, z, minX, minZ, maxX, maxZ;
        final boolean dryOnly;
        final boolean allowBridges;
        final RouteKey target;
        final Map<Long, Long> revisions = new HashMap<>();
        final Set<Long> unknownChunks = new HashSet<>();
        final Map<Long, Verification> footprints = new HashMap<>();
        final Map<Long, BitSet> reachableCells = new HashMap<>();
        final Map<Long, TerrainNode> reached = new HashMap<>();
        final PriorityQueue<TerrainNode> frontier = new PriorityQueue<>(Comparator.comparingDouble(TerrainNode::priority));
        final Set<ChunkPos> chunks = new LinkedHashSet<>();
        final Set<ChunkPos> dryChunks = new HashSet<>();
        final Map<Long, Integer> largestDry = new HashMap<>();
        final Map<Long, BitSet> completedComponents = new HashMap<>();
        long componentChunk = Long.MIN_VALUE;
        int componentId = -1, componentCursor;
        TerrainNode resumeNode;
        Verification status = Verification.PENDING;
        boolean dryOrigin, unknown, started;
        long retryAt, lastRequested;
        long createdAt, workUnits;
        long checkedRevision = Long.MIN_VALUE;
        boolean matchesRevision, waitingForInvalidation;
        int restarts;
        List<BlockPos> path = List.of();
        double pathCost;
        int qualifying = -1, originDryColumns;
        TerrainSearch(AreaKey key) {
            x = key.x; z = key.z; dryOnly = key.dry; allowBridges = key.bridges; target = null;
            minX = Math.floorDiv(x - key.radius, 16) * 16; minZ = Math.floorDiv(z - key.radius, 16) * 16;
            maxX = Math.floorDiv(x + key.radius, 16) * 16 + 15; maxZ = Math.floorDiv(z + key.radius, 16) * 16 + 15;
        }
        TerrainSearch(RouteKey key) {
            x = key.x; z = key.z; dryOnly = false; allowBridges = true; target = key;
            minX = Math.max(key.boundMinX, Math.min(x, key.endX) - key.margin); minZ = Math.max(key.boundMinZ, Math.min(z, key.endZ) - key.margin);
            maxX = Math.min(key.boundMaxX, Math.max(x, key.endX) + key.margin); maxZ = Math.min(key.boundMaxZ, Math.max(z, key.endZ) + key.margin);
        }
        boolean current(TerrainRuntime runtime) {
            if (checkedRevision == runtime.navigationRevision) return matchesRevision;
            checkedRevision = runtime.navigationRevision; matchesRevision = true; waitingForInvalidation = false;
            for (long chunk : unknownChunks) if (runtime.navigation.containsKey(chunk)) matchesRevision = false;
            for (var dependency : revisions.entrySet()) {
                NavigationChunk chunk = runtime.navigation.get(dependency.getKey());
                if (chunk == null || chunk.version != dependency.getValue()) matchesRevision = false;
                waitingForInvalidation |= runtime.invalidated.contains(dependency.getKey());
            }
            return matchesRevision;
        }
        boolean awaitingInvalidation(TerrainRuntime runtime) { current(runtime); return waitingForInvalidation; }
        void retryMissing(ServerLevel level, TerrainRuntime runtime) {
            if (status != Verification.UNKNOWN || level.getGameTime() < retryAt) return;
            retryAt = level.getGameTime() + 200;
            // Retain the completed result while asking for missing saved chunks again. A newly available sample invalidates it.
            for (long packed : unknownChunks) {
                ChunkPos chunk = ChunkPos.unpack(packed); baseSurface(level, chunk.x() * 16, chunk.z() * 16);
            }
        }
        void remember(TerrainRuntime runtime, int cx, int cz) {
            long key = new ChunkPos(cx >> 4, cz >> 4).pack();
            NavigationChunk chunk = runtime.navigation.get(key);
            if (chunk != null && revisions.put(key, chunk.version) == null) checkedRevision = Long.MIN_VALUE;
        }
        void advance(ServerLevel level, TerrainRuntime runtime, long deadline) {
            if (status != Verification.PENDING || level.getGameTime() < retryAt || !current(runtime)) return;
            if (!started) {
                Surface start = surface(level, x, z, false);
                if (start.ground == Ground.UNKNOWN) {
                    runtime.navigationRequests.addFirst(new ChunkPos(x >> 4, z >> 4).pack());
                    retryAt = level.getGameTime() + 100;
                    if (runtime.missing.containsKey(new ChunkPos(x >> 4, z >> 4).pack())) { status = Verification.UNKNOWN; unknownChunks.add(new ChunkPos(x >> 4, z >> 4).pack()); }
                    else retryAt = level.getGameTime() + 5;
                    return;
                }
                remember(runtime, x, z);
                dryOrigin = start.dry();
                if (!start.usable() || dryOnly && !dryOrigin || !allowBridges && start.ground == Ground.BRIDGE) { status = Verification.BLOCKED; return; }
                TerrainNode node = new TerrainNode(columnKey(x, z), 0, 0, null);
                frontier.add(node); reached.put(node.position, node); started = true;
            }
            for (int work = 0; work < 256 && System.nanoTime() < deadline && (!frontier.isEmpty() || resumeNode != null); work++) {
                TerrainNode node = resumeNode == null ? frontier.poll() : resumeNode; resumeNode = null;
                if (reached.get(node.position) != node) continue;
                int cx = columnX(node.position), cz = columnZ(node.position);
                workUnits++;
                // Searches retain immutable inspected samples across refreshes; live movement still requires fresh ground.
                Surface from = surface(level, cx, cz, false);
                if (from.ground == Ground.UNKNOWN) { runtime.navigationRequests.addFirst(new ChunkPos(cx >> 4, cz >> 4).pack()); frontier.add(node); retryAt = level.getGameTime() + 5; return; }
                remember(runtime, cx, cz);
                ChunkPos chunk = new ChunkPos(cx >> 4, cz >> 4);
                chunks.add(chunk);
                if (target == null && !allowBridges) reachableCells.computeIfAbsent(chunk.pack(), ignored -> new BitSet(256)).set((cz & 15) * 16 + (cx & 15));
                if (from.dry()) dryChunks.add(chunk);
                if (target == null && from.ground != Ground.BRIDGE) {
                    LocalComponents local = components(level, runtime, chunk.pack(), dryOnly);
                    if (local == null) { runtime.navigationRequests.addFirst(chunk.pack()); frontier.add(node); retryAt = level.getGameTime() + 5; return; }
                    int label = local.labels[(cz & 15) * 16 + (cx & 15)];
                    if (label >= 0) {
                        if (completedComponents.computeIfAbsent(chunk.pack(), ignored -> new BitSet()).get(label)) continue;
                        int state = advanceComponent(level, runtime, node, chunk, local, label, deadline);
                        if (state != 0) { resumeNode = node; if (state == 1) retryAt = level.getGameTime() + 5; return; }
                        continue;
                    }
                }
                if (target != null && cx == target.endX && cz == target.endZ) {
                    ArrayDeque<BlockPos> points = new ArrayDeque<>();
                    for (TerrainNode at = node; at != null; at = at.previous) {
                        Surface ground = surface(level, columnX(at.position), columnZ(at.position), false);
                        points.addFirst(new BlockPos(columnX(at.position), (int) Math.floor(ground.height), columnZ(at.position)));
                    }
                    path = List.copyOf(points); pathCost = node.cost; status = Verification.VERIFIED; return;
                }
                boolean waiting = false;
                for (int[] direction : CARDINAL_DIRECTIONS) {
                    int nx = cx + direction[0], nz = cz + direction[1];
                    if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                    if (target != null && target.supplyOwner != null) {
                        var claim = ClaimManager.get(level).getClaim(new ChunkPos(nx >> 4, nz >> 4));
                        if (claim != null && !target.supplyOwner.equals(claim.getOwnerUUID()) && !StateManager.get(level).hasPassage(target.supplyOwner, claim.getOwnerUUID())) continue;
                        if (claim == null && Math.hypot(nx - target.endX, nz - target.endZ) > StateBalance.FINAL_DELIVERY_RANGE) continue;
                    }
                    Verification edge = step(level, cx, cz, nx, nz, dryOnly, false);
                    // Missing saved chunks are unknown, never dry. Wait only for actual queued inspection.
                    if (edge == Verification.UNKNOWN) {
                        boolean missing = false;
                        for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
                            long packed = new ChunkPos((nx + ox) >> 4, (nz + oz) >> 4).pack();
                            if (runtime.missing.containsKey(packed)) { missing = true; unknownChunks.add(packed); }
                        }
                        if (missing) unknown = true; else { waiting = true; runtime.navigationRequests.addFirst(new ChunkPos(nx >> 4, nz >> 4).pack()); }
                        continue;
                    }
                    for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) remember(runtime, nx + ox, nz + oz);
                    if (edge != Verification.VERIFIED) continue;
                    if (!allowBridges && surface(level, nx, nz, false).ground == Ground.BRIDGE) continue;
                    if (target != null && target.clearance > 0) {
                        Verification footprint = footprint(level, nx, nz);
                        if (footprint == Verification.UNKNOWN) { waiting = true; continue; }
                        if (footprint != Verification.VERIFIED) continue;
                    }
                    Surface to = surface(level, nx, nz, false);
                    double slope = to.height - from.height;
                    double cost = node.cost + Math.hypot(1, slope) * Math.max(from.travelCost(), to.travelCost()) * (slope > 0 ? 1.25 : 1);
                    if (target != null && target.supplyOwner != null) {
                        double discount = LogisticsManager.get(level).usableRoadAt(cx, cz, level) && LogisticsManager.get(level).usableRoadAt(nx, nz, level) ? StateBalance.ROAD_TRANSPORT_MULTIPLIER : 1;
                        cost = node.cost + (cost - node.cost) * discount;
                    }
                    long key = columnKey(nx, nz);
                    TerrainNode previous = reached.get(key);
                    if (previous != null && previous.cost <= cost) continue;
                    double remaining = target == null ? 0 : Math.abs(nx - target.endX) + Math.abs(nz - target.endZ);
                    if (target != null && target.supplyOwner != null) remaining *= StateBalance.ROAD_TRANSPORT_MULTIPLIER;
                    TerrainNode next = new TerrainNode(key, cost, cost + remaining, node);
                    reached.put(key, next); frontier.add(next);
                }
                if (waiting) { frontier.add(node); retryAt = level.getGameTime() + 5; return; }
                if (reached.size() >= MAX_SEARCH_NODES) { status = Verification.LIMITED; return; }
            }
            if (frontier.isEmpty() && resumeNode == null) {
                status = unknown ? Verification.UNKNOWN : target == null ? Verification.VERIFIED : Verification.BLOCKED;
                retryAt = level.getGameTime() + 100;
            }
        }
        private int advanceComponent(ServerLevel level, TerrainRuntime runtime, TerrainNode node, ChunkPos chunk, LocalComponents local, int label, long deadline) {
            if (componentChunk != chunk.pack() || componentId != label) { componentChunk = chunk.pack(); componentId = label; componentCursor = 0; }
            int[] cells = local.cells.get(label);
            if (!allowBridges) {
                BitSet reachable = reachableCells.computeIfAbsent(chunk.pack(), ignored -> new BitSet(256));
                for (int cell : cells) reachable.set(cell);
            }
            if (dryOnly) {
                largestDry.merge(chunk.pack(), cells.length, Math::max);
                if ((x >> 4) == chunk.x() && (z >> 4) == chunk.z() && local.labels[(z & 15) * 16 + (x & 15)] == label) originDryColumns = cells.length;
            }
            while (componentCursor < cells.length) {
                if (System.nanoTime() >= deadline) return 2;
                int cell = cells[componentCursor], cx = chunk.x() * 16 + (cell & 15), cz = chunk.z() * 16 + (cell >> 4);
                if (surface(level, cx, cz, false).dry()) dryChunks.add(chunk);
                boolean waiting = false;
                for (int[] direction : CARDINAL_DIRECTIONS) {
                    int nx = cx + direction[0], nz = cz + direction[1];
                    if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                    if ((nx >> 4) == chunk.x() && (nz >> 4) == chunk.z() && local.labels[(nz & 15) * 16 + (nx & 15)] == label) continue;
                    var edge = step(level, cx, cz, nx, nz, dryOnly, false);
                    if (edge == Verification.UNKNOWN) {
                        boolean missing = false;
                        for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
                            long packed = new ChunkPos((nx + ox) >> 4, (nz + oz) >> 4).pack();
                            if (runtime.missing.containsKey(packed)) { missing = true; unknownChunks.add(packed); }
                        }
                        if (missing) unknown = true; else { waiting = true; runtime.navigationRequests.addFirst(new ChunkPos(nx >> 4, nz >> 4).pack()); }
                        continue;
                    }
                    for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) remember(runtime, nx + ox, nz + oz);
                    if (edge != Verification.VERIFIED) continue;
                    if (!allowBridges && surface(level, nx, nz, false).ground == Ground.BRIDGE) continue;
                    long key = columnKey(nx, nz);
                    if (!reached.containsKey(key)) { TerrainNode next = new TerrainNode(key, 0, 0, null); reached.put(key, next); frontier.add(next); }
                }
                if (waiting) return 1;
                componentCursor++;
            }
            completedComponents.get(chunk.pack()).set(label); componentChunk = Long.MIN_VALUE; componentId = -1; return 0;
        }
        int qualifyingChunks(TerrainRuntime runtime) {
            if (!dryOnly) return 0;
            return (int) largestDry.values().stream().filter(count -> count >= 128).count();
        }
        /** Tactical orders request a formation corridor; supported bridges retain their three-wide marching approach. */
        private Verification footprint(ServerLevel level, int cx, int cz) {
            long key = columnKey(cx, cz); Verification cached = footprints.get(key); if (cached != null) return cached;
            int width = target.clearance; boolean unknown = false, bridge = false;
            for (int[] direction : CARDINAL_DIRECTIONS) for (int i = 0; i <= 13; i++) {
                Surface sample = surface(level, cx + direction[0] * i, cz + direction[1] * i, false);
                remember(terrainRuntime(level), cx + direction[0] * i, cz + direction[1] * i);
                if (sample.ground == Ground.BRIDGE) { bridge = true; break; }
            }
            if (bridge) width = 3;
            Surface[] previousRow = new Surface[width];
            for (int dx = -(width - 1) / 2; dx <= width / 2; dx++) for (int dz = -(width - 1) / 2; dz <= width / 2; dz++) {
                Surface sample = surface(level, cx + dx, cz + dz, false); remember(terrainRuntime(level), cx + dx, cz + dz);
                if (sample.ground == Ground.UNKNOWN) {
                    long packed = new ChunkPos((cx + dx) >> 4, (cz + dz) >> 4).pack();
                    if (terrainRuntime(level).missing.containsKey(packed)) { this.unknown = true; unknownChunks.add(packed); return Verification.BLOCKED; }
                    unknown = true;
                }
                else if (!sample.usable()) { footprints.put(key, Verification.BLOCKED); return Verification.BLOCKED; }
                else {
                    int index = dz + (width - 1) / 2;
                    Surface before = previousRow[index], beside = index > 0 ? previousRow[index - 1] : null;
                    if (before != null && before.ground != Ground.UNKNOWN && !canStep(before, sample, false)
                            || beside != null && beside.ground != Ground.UNKNOWN && !canStep(beside, sample, false)) { footprints.put(key, Verification.BLOCKED); return Verification.BLOCKED; }
                }
                previousRow[dz + (width - 1) / 2] = sample;
            }
            if (unknown) return Verification.UNKNOWN;
            footprints.put(key, Verification.VERIFIED); return Verification.VERIFIED;
        }
    }

    /** Count local connected columns, never merge two patches through an adjoining chunk. */
    public static int dryComponentSize(Set<Long> columns, long start, java.util.function.LongFunction<Surface> surfaces) {
        if (!columns.contains(start)) return 0;
        Set<Long> seen = new HashSet<>(); ArrayDeque<Long> queue = new ArrayDeque<>();
        seen.add(start); queue.add(start);
        while (!queue.isEmpty()) {
            long current = queue.removeFirst();
            for (int[] direction : CARDINAL_DIRECTIONS) {
                long next = columnKey(columnX(current) + direction[0], columnZ(current) + direction[1]);
                if (columns.contains(next) && !seen.contains(next) && canStep(surfaces.apply(current), surfaces.apply(next), true)) { seen.add(next); queue.add(next); }
            }
        }
        return seen.size();
    }

    public static int largestDryComponent(Set<Long> columns) {
        return largestDryComponent(columns, ignored -> new Surface(Ground.DRY, 0, false));
    }
    public static int largestDryComponent(Set<Long> columns, java.util.function.LongFunction<Surface> surfaces) {
        Set<Long> remaining = new HashSet<>(columns);
        int largest = 0;
        ArrayDeque<Long> queue = new ArrayDeque<>();
        while (!remaining.isEmpty()) {
            long first = remaining.iterator().next(); remaining.remove(first); queue.add(first);
            int count = 0;
            while (!queue.isEmpty()) {
                long current = queue.removeFirst(); count++;
                for (int[] direction : CARDINAL_DIRECTIONS) {
                    long next = columnKey(columnX(current) + direction[0], columnZ(current) + direction[1]);
                    if (remaining.contains(next) && canStep(surfaces.apply(current), surfaces.apply(next), true)) {
                        remaining.remove(next); queue.add(next);
                    }
                }
            }
            largest = Math.max(largest, count);
        }
        return largest;
    }

    /** Collision queries use the inspected snapshot, including saved chunks, never remote live reads. */
    private static BlockGetter terrainView(ServerLevel level, Function<BlockPos, BlockState> blocks) {
        return new BlockGetter() {
            public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            public BlockState getBlockState(BlockPos pos) { return blocks.apply(pos); }
            public FluidState getFluidState(BlockPos pos) { return blocks.apply(pos).getFluidState(); }
            public int getHeight() { return level.getHeight(); }
            public int getMinY() { return level.getMinY(); }
        };
    }

    /** Bounded surface-only inspection: no seabed scan is needed once water exceeds one block. */
    public static Surface inspectSurface(BlockGetter view, int x, int z, int top, boolean ocean) {
        int bottom = Math.max(view.getMinY(), top - 96);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = top; y >= bottom; y--) {
            pos.set(x, y, z);
            BlockState state = view.getBlockState(pos);
            if (state.is(BlockTags.LEAVES) || state.is(Blocks.POWDER_SNOW)) continue;
            var fluid = state.getFluidState();
            if (fluid.is(FluidTags.LAVA)) return new Surface(Ground.BLOCKED, y, ocean);
            if (fluid.is(FluidTags.WATER) && state.getCollisionShape(view, pos).isEmpty()) {
                // Water immediately below proves a depth greater than one, including flowing/falling water.
                BlockPos floorPos = pos.below();
                BlockState floor = view.getBlockState(floorPos);
                var shape = floor.getCollisionShape(view, floorPos);
                if (floor.getFluidState().is(FluidTags.LAVA) || shape.isEmpty() || floor.is(BlockTags.LEAVES))
                    return new Surface(Ground.DEEP, y + 1, ocean);
                double height = y - 1 + shape.max(Direction.Axis.Y);
                double depth = y + fluid.getOwnHeight() - height;
                return new Surface(depth <= 1.000001 && clearHeadroom(view, x, z, height, true) ? Ground.SHALLOW : Ground.DEEP, height, ocean);
            }
            var shape = state.getCollisionShape(view, pos);
            if (shape.isEmpty()) continue;
            double height = y + shape.max(Direction.Axis.Y);
            // Fences/walls and point supports are not a continuous walking deck.
            if (shape.max(Direction.Axis.X) - shape.min(Direction.Axis.X) < .5
                    || shape.max(Direction.Axis.Z) - shape.min(Direction.Axis.Z) < .5) continue;
            if (!clearHeadroom(view, x, z, height, fluid.is(FluidTags.WATER))) continue;
            if (state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE)) return new Surface(Ground.ICE, height, ocean);
            boolean overWater = false;
            // Keep bridge decks distinct from natural ground; owning/building a deck does not create dry founding land.
            for (int below = y - 1; below >= Math.max(bottom, y - 64); below--) {
                BlockState under = view.getBlockState(new BlockPos(x, below, z));
                if (under.getFluidState().is(FluidTags.WATER)) { overWater = true; break; }
                if (!under.getCollisionShape(view, new BlockPos(x, below, z)).isEmpty()) break;
            }
            if (fluid.is(FluidTags.WATER) && height < y + fluid.getOwnHeight() - .000001)
                return new Surface(Ground.SHALLOW, height, ocean);
            return new Surface(overWater ? Ground.BRIDGE : Ground.DRY, height, ocean);
        }
        return new Surface(Ground.BLOCKED, 0, ocean);
    }

    private static boolean clearHeadroom(BlockGetter view, int x, int z, double floor, boolean waterAllowed) {
        for (int y = (int) Math.floor(floor + .000001); y < Math.ceil(floor + 2); y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = view.getBlockState(pos);
            if (state.getFluidState().is(FluidTags.LAVA) || !waterAllowed && !state.getFluidState().isEmpty()) return false;
            for (var box : state.getCollisionShape(view, pos).toAabbs())
                if (box.maxY + y > floor + .000001 && box.minY + y < floor + 2
                        && box.maxX > .25 && box.minX < .75 && box.maxZ > .25 && box.minZ < .75) return false;
        }
        return true;
    }

    private static final class NavigationSurvey {
        final long packed;
        final Surface[] cells = new Surface[256];
        final IntBinaryOperator heights;
        final BlockGetter view;
        final java.util.function.BiPredicate<Integer, Integer> ocean;
        int cursor;
        NavigationSurvey(long packed, IntBinaryOperator heights, BlockGetter view, java.util.function.BiPredicate<Integer, Integer> ocean) {
            this.packed = packed; this.heights = heights; this.view = view; this.ocean = ocean;
        }
        boolean advance(long deadline) {
            ChunkPos chunk = ChunkPos.unpack(packed);
            while (cursor < 256 && System.nanoTime() < deadline) {
                int x = cursor & 15, z = cursor >> 4;
                cells[cursor++] = inspectSurface(view, chunk.x() * 16 + x, chunk.z() * 16 + z, heights.applyAsInt(x, z), ocean.test(x, z));
            }
            return cursor == 256;
        }
    }

    private static void tickNavigation(ServerLevel level, TerrainRuntime runtime, long deadline) {
        runtime.areas.values().removeIf(job -> level.getGameTime() - job.lastRequested > 600);
        runtime.routes.values().removeIf(job -> level.getGameTime() - job.lastRequested > 600);
        for (int checked = 0, count = Math.min(32, runtime.navigationRequests.size()); runtime.survey == null && checked < count; checked++) {
            long packed = runtime.navigationRequests.iterator().next();
            ChunkPos pos = ChunkPos.unpack(packed);
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk != null) {
                runtime.navigationRequests.remove(packed);
                runtime.survey = new NavigationSurvey(packed, (x, z) -> chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z),
                        terrainView(level, chunk::getBlockState), (x, z) -> chunk.getNoiseBiome((pos.x() * 16 + x) >> 2, 16, (pos.z() * 16 + z) >> 2).is(BiomeTags.IS_OCEAN));
            } else {
                runtime.backfill.add(packed);
                // An asynchronous saved-chunk read must not hold up already loaded terrain behind it.
                runtime.navigationRequests.remove(packed); runtime.navigationRequests.add(packed);
            }
        }
        // Sampling and searching each receive part of the allowance, including under a continuous refresh backlog.
        long samplingDeadline = Math.min(deadline, System.nanoTime() + 400_000L);
        if (runtime.survey != null && runtime.survey.advance(samplingDeadline)) {
            NavigationSurvey survey = runtime.survey;
            NavigationChunk previous = runtime.navigation.get(survey.packed);
            long version = previous != null && Arrays.equals(previous.cells, survey.cells) ? previous.version : ++runtime.navigationRevision;
            runtime.navigation.put(survey.packed, new NavigationChunk(version, survey.cells));
            runtime.invalidated.remove(survey.packed);
            runtime.navigationTimes.put(survey.packed, level.getGameTime());
            runtime.missing.remove(survey.packed);
            runtime.survey = null;
            if (runtime.navigation.size() > MAX_NAVIGATION_CHUNKS) {
                long removed = runtime.navigation.keySet().iterator().next();
                runtime.navigation.remove(removed); runtime.navigationTimes.remove(removed); runtime.navigationRevision++;
                runtime.components.remove(new ComponentKey(removed, true)); runtime.components.remove(new ComponentKey(removed, false));
            }
        }
        // Rotate the searches as well as the samples so one large campaign cannot starve a founder or route.
        List<TerrainSearch> jobs = new ArrayList<>(runtime.areas.values()); jobs.addAll(runtime.routes.values());
        int advanced = 0;
        if (!jobs.isEmpty()) for (int checked = 0; checked < jobs.size() && System.nanoTime() < deadline; checked++) {
            TerrainSearch job = jobs.get(Math.floorMod(runtime.searchCursor++, jobs.size()));
            if (job.status != Verification.PENDING || job.retryAt > level.getGameTime() || !job.current(runtime)) continue;
            job.advance(level, runtime, Math.min(deadline, System.nanoTime() + 175_000L));
            if (job.status != Verification.PENDING) { job.reached.clear(); job.frontier.clear(); job.completedComponents.clear(); job.footprints.clear(); job.resumeNode = null; }
            if (++advanced == 4) break;
        }
    }

    public static void watchTerrainChunk(ServerLevel level, ChunkPos position) {
        if (level != level.getServer().overworld()) return;
        TerrainRuntime runtime = TERRAIN_RUNTIMES.computeIfAbsent(level, ignored -> new TerrainRuntime());
        long packed = position.pack();
        if (runtime.watched.add(packed)) {
            runtime.rotation.addLast(packed);
            runtime.missing.remove(packed);
            runtime.navigationTimes.remove(packed);
            if (runtime.navigation.containsKey(packed)) { runtime.invalidated.add(packed); runtime.navigationRevision++; }
        }
    }

    public static void unwatchTerrainChunk(ServerLevel level, ChunkPos position) {
        TerrainRuntime runtime = TERRAIN_RUNTIMES.get(level);
        if (runtime != null) {
            runtime.watched.remove(position.pack());
            runtime.samples.remove(position.pack());
        }
    }

    public static void clearTerrainRuntime() { TERRAIN_RUNTIMES.clear(); VIEWPORTS.clear(); }

    /** Reuse existing state-surveyed surface flags; unknown land never forces chunk loading/generation. */
    public static boolean isKnownWaterOrLava(ServerLevel level, UUID stateId, BlockPos position) {
        ServerLevel overworld = level.getServer().overworld();
        Map<Long, SurveyedChunk> knowledge = TerrainSavedData.get(overworld).states.get(stateId);
        if (knowledge == null) return false;
        SurveyedChunk sample = knowledge.get(new ChunkPos(position.getX() >> 4, position.getZ() >> 4).pack());
        if (sample == null) return false;
        int flags = sample.features[(position.getZ() & 15) * 16 + (position.getX() & 15)];
        return (flags & MapDataPayload.TERRAIN_KNOWN) != 0
                && (flags & (MapDataPayload.TERRAIN_WATER | MapDataPayload.TERRAIN_LAVA)) != 0;
    }

    public static void tickTerrain(ServerLevel level) {
        TerrainRuntime runtime = TERRAIN_RUNTIMES.computeIfAbsent(level, ignored -> new TerrainRuntime());
        runtime.geography.tickPreview(level);
        long time = level.getGameTime();
        runtime.navigationTick = time;
        long deadline = System.nanoTime() + 2_000_000L;
        TerrainSavedData saved = TerrainSavedData.get(level);
        ClaimManager claims = ClaimManager.get(level);
        tickTerrainBackfill(level, runtime, saved, claims);
        // Reserve part of the shared allowance for map pixels; navigation must not starve the battle background.
        tickNavigation(level, runtime, Math.min(deadline, System.nanoTime() + 1_100_000L));
        claims.tickInfill(level, Math.min(deadline, System.nanoTime() + 200_000L));
        LogisticsManager.get(level).tickTerrainDelivery(level, Math.min(deadline, System.nanoTime() + 200_000L));
        if (time % 20 == 0 || runtime.origins.isEmpty()) refreshTerrainOrigins(level, runtime);
        if (runtime.origins.isEmpty()) return;
        int sampled = 0;
        // Rotate through loaded chunks fairly; at most two chunks and two milliseconds of work per tick.
        for (int checked = 0; checked < 32 && !runtime.rotation.isEmpty(); checked++) {
            long packed = runtime.rotation.removeFirst();
            if (!runtime.watched.contains(packed)) continue;
            runtime.rotation.addLast(packed);
            ChunkPos position = ChunkPos.unpack(packed);
            LevelChunk chunk = level.getChunkSource().getChunkNow(position.x(), position.z());
            if (chunk == null) { runtime.watched.remove(packed); continue; }
            List<UUID> observers = terrainObservers(position, claims, runtime);
            if (observers.isEmpty()) continue;

            TimedTerrain cached = runtime.samples.get(packed);
            if (cached == null || time - cached.time >= TERRAIN_RESURVEY_TICKS) {
                if (sampled >= 2 || System.nanoTime() >= deadline) break;
                SurveyedChunk surface = sampleTerrainChunk(level, chunk);
                cached = new TimedTerrain(time, surface);
                runtime.samples.put(packed, cached);
                sampled++;
                if (runtime.samples.size() > 2048) runtime.samples.remove(runtime.samples.keySet().iterator().next());
            }
            for (UUID observer : observers) saved.observe(observer, packed, cached.chunk);
            if (System.nanoTime() >= deadline) break;
        }
    }

    private static void refreshTerrainOrigins(ServerLevel level, TerrainRuntime runtime) {
        runtime.origins.clear();
        StateManager states = StateManager.get(level);
        ArmyManager armies = ArmyManager.get(level);
        for (StateData state : states.getAllStates()) {
            UUID id = state.getStateId();
            List<SurveyOrigin> origins = new ArrayList<>();
            for (BlockPos pos : scoutingOrigins(armies, id)) origins.add(new SurveyOrigin(pos, 250));
            for (ServerPlayer player : level.players()) {
                StateData membership = states.getStateByPlayer(player.getUUID());
                if (membership != null && id.equals(membership.getStateId())) {
                    origins.add(new SurveyOrigin(player.blockPosition(), 128));
                    // Also bootstrap chunks that were already loaded when a player joined their state.
                    int cx = player.blockPosition().getX() >> 4, cz = player.blockPosition().getZ() >> 4;
                    for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
                        if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) != null)
                            watchTerrainChunk(level, new ChunkPos(cx + dx, cz + dz));
                    }
                }
            }
            // Ownership is another surveying source, even when the state's players are offline.
            runtime.origins.put(id, origins);
        }
    }

    private static List<UUID> terrainObservers(ChunkPos chunk, ClaimManager claims, TerrainRuntime runtime) {
        List<UUID> result = new ArrayList<>();
        var claim = claims.getClaim(chunk);
        double centerX = chunk.x() * 16.0 + 8, centerZ = chunk.z() * 16.0 + 8;
        for (var entry : runtime.origins.entrySet()) {
            boolean owned = claim != null && entry.getKey().equals(claim.getOwnerUUID());
            boolean nearby = entry.getValue().stream().anyMatch(origin -> {
                double dx = centerX - origin.position.getX(), dz = centerZ - origin.position.getZ();
                return dx * dx + dz * dz <= (double) origin.radius * origin.radius;
            });
            if (owned || nearby) result.add(entry.getKey());
        }
        return result;
    }

    private static SurveyedChunk sampleTerrainChunk(ServerLevel level, LevelChunk chunk) {
        return sampleTerrainColumns(level, chunk.getPos(),
                // ChunkAccess returns the occupied surface Y; Level.getHeight returns the first air Y.
                (x, z) -> chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z),
                (x, z) -> chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z),
                chunk::getBlockState);
    }

    private static SurveyedChunk sampleTerrainColumns(ServerLevel level, ChunkPos position,
            IntBinaryOperator surfaceHeight, IntBinaryOperator groundHeight, Function<BlockPos, BlockState> blockAt) {
        byte[] colors = new byte[256], features = new byte[256];
        int[] heights = new int[256];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int baseX = position.x() * 16, baseZ = position.z() * 16;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int index = z * 16 + x;
            int surfaceY = surfaceHeight.applyAsInt(x, z);
            cursor.set(baseX + x, surfaceY, baseZ + z);
            BlockState surface = blockAt.apply(cursor);
            int flags = MapDataPayload.TERRAIN_KNOWN;
            if (surface.getFluidState().is(FluidTags.WATER)) flags |= MapDataPayload.TERRAIN_WATER;
            if (surface.getFluidState().is(FluidTags.LAVA)) flags |= MapDataPayload.TERRAIN_LAVA;
            if (surface.is(BlockTags.LEAVES) || surface.is(BlockTags.LOGS)) flags |= MapDataPayload.TERRAIN_FOREST;
            if (surface.is(Blocks.SNOW) || surface.is(Blocks.SNOW_BLOCK) || surface.is(Blocks.POWDER_SNOW)) flags |= MapDataPayload.TERRAIN_SNOW;
            if (surface.is(BlockTags.SAND)) flags |= MapDataPayload.TERRAIN_SAND;
            if (surface.blocksMotion() && !naturalGround(surface)
                    && (flags & (MapDataPayload.TERRAIN_FOREST | MapDataPayload.TERRAIN_WATER | MapDataPayload.TERRAIN_LAVA | MapDataPayload.TERRAIN_SNOW)) == 0)
                flags |= MapDataPayload.TERRAIN_STRUCTURE;
            colors[index] = (byte) surface.getMapColor(level, cursor).id;

            // Ignore canopy/logs and identifiable roofs when finding relief. Natural-stone roofs are ambiguous.
            int groundY = groundHeight.applyAsInt(x, z);
            int bottom = Math.max(level.getMinY(), groundY - 64);
            cursor.set(baseX + x, groundY, baseZ + z);
            while (groundY > bottom && !naturalGround(blockAt.apply(cursor))) cursor.setY(--groundY);
            heights[index] = Math.clamp(groundY, Short.MIN_VALUE, Short.MAX_VALUE);
            features[index] = (byte) flags;
        }
        return new SurveyedChunk(0, colors, heights, features);
    }

    // EXISTING-SAVE BACKFILL: read one existing region-file chunk asynchronously, without adding a chunk ticket.
    // Decode only saved sections/heightmaps; no entities, block entities, generation, or live chunk promotion.
    private static void tickTerrainBackfill(ServerLevel level, TerrainRuntime runtime,
            TerrainSavedData saved, ClaimManager claims) {
        if (runtime.pendingRead != null && runtime.pendingRead.isDone() && runtime.survey == null) {
            long packed = runtime.pendingPosition;
            boolean navigationRequested = runtime.navigationRequests.remove(packed);
            boolean inspected = false;
            try {
                Optional<CompoundTag> data = runtime.pendingRead.join();
                if (data.isPresent() && level.getChunkSource().getChunkNow(ChunkPos.unpack(packed).x(), ChunkPos.unpack(packed).z()) == null) {
                    var storage = level.getChunkSource().chunkMap;
                    CompoundTag upgraded = storage.upgradeChunkTag(data.get(), -1);
                    SerializableChunkData parsed = SerializableChunkData.parse(level, level.palettedContainerFactory(), upgraded);
                    if (parsed != null && parsed.chunkStatus() == ChunkStatus.FULL && parsed.chunkPos().pack() == packed) {
                        if (navigationRequested) {
                            long[] heightBits = parsed.heightmaps().get(Heightmap.Types.WORLD_SURFACE);
                            if (heightBits != null) {
                                SimpleBitStorage heights = new SimpleBitStorage(Mth.ceillog2(level.getHeight() + 1), 256, heightBits);
                                Map<Integer, LevelChunkSection> sections = new HashMap<>();
                                for (var section : parsed.sectionData()) if (section.chunkSection() != null) sections.put(section.y(), section.chunkSection());
                                Function<BlockPos, BlockState> blocks = pos -> {
                                    LevelChunkSection section = sections.get(pos.getY() >> 4);
                                    return section == null ? Blocks.AIR.defaultBlockState() : section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                                };
                                runtime.survey = new NavigationSurvey(packed, (x, z) -> heights.get(z * 16 + x) + level.getMinY() - 1,
                                        terrainView(level, blocks), (x, z) -> {
                                            int y = heights.get(z * 16 + x) + level.getMinY() - 1;
                                            LevelChunkSection section = sections.get(y >> 4);
                                            return section != null && section.getNoiseBiome(x >> 2, (y & 15) >> 2, z >> 2).is(BiomeTags.IS_OCEAN);
                                        });
                                inspected = true;
                            }
                        }
                        List<UUID> observers = terrainObservers(parsed.chunkPos(), claims, runtime);
                        if (!observers.isEmpty()) {
                            SurveyedChunk surface = sampleSavedTerrain(level, parsed);
                            if (surface != null) for (UUID observer : observers) saved.observe(observer, packed, surface);
                        }
                    }
                }
            } catch (RuntimeException exception) {
                com.devc.minecraftempires.MinecraftEmpires.LOGGER.warn("Could not survey stored chunk {}", ChunkPos.unpack(packed), exception);
            }
            if (navigationRequested && !inspected) {
                ChunkPos position = ChunkPos.unpack(packed);
                if (level.getChunkSource().getChunkNow(position.x(), position.z()) != null) runtime.navigationRequests.add(packed);
                else runtime.missing.put(packed, level.getGameTime() + 200);
            }
            runtime.pendingRead = null;
        }
        if (runtime.pendingRead != null || level.getGameTime() % 5 != 0 || runtime.backfill.isEmpty()) return;
        long packed = runtime.backfill.iterator().next(); runtime.backfill.remove(packed);
        ChunkPos pos = ChunkPos.unpack(packed);
        if (!runtime.navigationRequests.contains(packed) && terrainObservers(pos, claims, runtime).isEmpty()) return;
        if (level.getChunkSource().getChunkNow(pos.x(), pos.z()) != null) { watchTerrainChunk(level, pos); return; }
        runtime.pendingPosition = packed;
        runtime.pendingRead = level.getChunkSource().chunkMap.read(pos);
    }

    private static SurveyedChunk sampleSavedTerrain(ServerLevel level, SerializableChunkData data) {
        long[] surfaceBits = data.heightmaps().get(Heightmap.Types.WORLD_SURFACE);
        long[] groundBits = data.heightmaps().get(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        if (surfaceBits == null || groundBits == null) return null;
        int bits = Mth.ceillog2(level.getHeight() + 1);
        SimpleBitStorage surface = new SimpleBitStorage(bits, 256, surfaceBits);
        SimpleBitStorage ground = new SimpleBitStorage(bits, 256, groundBits);
        Map<Integer, LevelChunkSection> sections = new HashMap<>();
        for (var section : data.sectionData()) if (section.chunkSection() != null) sections.put(section.y(), section.chunkSection());
        return sampleTerrainColumns(level, data.chunkPos(),
                (x, z) -> surface.get(z * 16 + x) + level.getMinY() - 1,
                (x, z) -> ground.get(z * 16 + x) + level.getMinY() - 1,
                pos -> {
                    var section = sections.get(pos.getY() >> 4);
                    return section == null ? Blocks.AIR.defaultBlockState() : section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                });
    }

    private static boolean naturalGround(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.TERRACOTTA) || state.is(net.neoforged.neoforge.common.Tags.Blocks.ORES)
                || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.BEDROCK)
                || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.ICE)
                || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE) || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.FARMLAND) || !state.getFluidState().isEmpty();
    }

    // TERRAIN REQUESTS: the saved per-state samples are the only source of outgoing pixels.
    // Never copy hidden pixels from another state's live terrain cache into a partly explored tile.
    public static MapDataPayload.TerrainPayload buildTerrainPayload(ServerPlayer player,
            RequestMapDataPayload.RequestTerrainPayload request) {
        ServerLevel level = player.level().getServer().overworld();
        // Empire geography belongs to the Overworld even if its viewer is visiting another dimension.
        StateData state = StateManager.get(level).getStateByPlayer(player.getUUID());
        if (state == null) return null;
        if (request.battleId() != null) {
            var manager = BattleManager.get(level);
            var battle = manager.getBattle(request.battleId());
            if (battle == null || !battle.isActive() || !manager.canSpectate(player, battle)
                    || !battle.getSpectatingPlayerIds().contains(player.getUUID())) return null;
        }
        TerrainSavedData saved = TerrainSavedData.get(level);
        TerrainRuntime runtime = TERRAIN_RUNTIMES.computeIfAbsent(level, ignored -> new TerrainRuntime());
        Map<Long, SurveyedChunk> knowledge = saved.states.getOrDefault(state.getStateId(), Map.of());
        List<MapDataPayload.TerrainTile> tiles = new ArrayList<>();
        Set<MapDataPayload.TerrainKey> visited = new HashSet<>();
        int bootstrapBudget = 512;
        for (var wanted : request.tiles()) {
            if (!visited.add(wanted.key())) continue;
            // Rotate a bounded part of each requested tile into loaded sampling or existing-save backfill.
            if (bootstrapBudget > 0) {
                int chunks = wanted.key().blocks() / 16;
                int cx = wanted.key().tileX() * chunks, cz = wanted.key().tileZ() * chunks;
                int start = runtime.bootstrapOffsets.getOrDefault(wanted.key(), 0);
                int count = Math.min(64, Math.min(bootstrapBudget, chunks * chunks));
                for (int i = 0; i < count; i++) {
                    int offset = (start + i) % (chunks * chunks), dx = offset % chunks, dz = offset / chunks;
                    ChunkPos position = new ChunkPos(cx + dx, cz + dz);
                    if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) != null) watchTerrainChunk(level, position);
                    else if (runtime.backfill.size() < 1024 && !knowledge.containsKey(position.pack())
                            && terrainObservers(position, ClaimManager.get(level), runtime).contains(state.getStateId()))
                        runtime.backfill.add(position.pack());
                }
                runtime.bootstrapOffsets.put(wanted.key(), (start + count) % (chunks * chunks));
                bootstrapBudget -= count;
            }
            long version = terrainVersion(wanted.key(), knowledge);
            if (version != wanted.knownVersion()) {
                tiles.add(terrainTile(wanted.key(), version, knowledge));
                if (tiles.size() == MapDataPayload.TerrainPayload.MAX_TILES) break;
            }
        }
        while (runtime.bootstrapOffsets.size() > 512)
            runtime.bootstrapOffsets.remove(runtime.bootstrapOffsets.keySet().iterator().next());
        return new MapDataPayload.TerrainPayload(state.getStateId(), request.battleId(), tiles);
    }

    private static long terrainVersion(MapDataPayload.TerrainKey key, Map<Long, SurveyedChunk> knowledge) {
        long hash = 0xcbf29ce484222325L;
        int chunks = key.blocks() / 16;
        int cx = key.tileX() * chunks, cz = key.tileZ() * chunks;
        for (int z = -1; z <= chunks; z++) for (int x = -1; x <= chunks; x++) {
            SurveyedChunk chunk = knowledge.get(new ChunkPos(cx + x, cz + z).pack());
            hash = (hash ^ (chunk == null ? 0 : chunk.version)) * 0x100000001b3L;
        }
        return hash == Long.MIN_VALUE ? 0 : hash;
    }

    private static MapDataPayload.TerrainTile terrainTile(MapDataPayload.TerrainKey key, long version,
            Map<Long, SurveyedChunk> knowledge) {
        int side = key.side(), step = key.step();
        byte[] colors = new byte[side * side], heights = new byte[side * side * 2], features = new byte[side * side];
        int baseX = key.tileX() * key.blocks();
        int baseZ = key.tileZ() * key.blocks();
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
            int wx = baseX + (x - 1) * step, wz = baseZ + (z - 1) * step;
            SurveyedChunk chunk = knowledge.get(new ChunkPos(wx >> 4, wz >> 4).pack());
            if (chunk == null) continue;
            int sample = (wz & 15) * 16 + (wx & 15), index = z * side + x;
            colors[index] = chunk.colors[sample];
            features[index] = chunk.features[sample];
            int height = chunk.heights[sample];
            heights[index * 2] = (byte) (height >> 8); heights[index * 2 + 1] = (byte) height;
        }
        return new MapDataPayload.TerrainTile(key, version, colors, heights, features);
    }

    private record SurveyOrigin(BlockPos position, int radius) {}
    private record TimedTerrain(long time, SurveyedChunk chunk) {}
    private record SurveyedChunk(long version, byte[] colors, int[] heights, byte[] features) {
        private boolean sameSurface(SurveyedChunk other) {
            return Arrays.equals(colors, other.colors) && Arrays.equals(heights, other.heights)
                    && Arrays.equals(features, other.features);
        }
    }
    private static final class TerrainRuntime {
        private final GeographyPreviewService geography = new GeographyPreviewService();
        private final Map<Long, NavigationChunk> navigation = new LinkedHashMap<>(256, .75f, true);
        private final Map<Long, Long> navigationTimes = new HashMap<>();
        private final Map<ComponentKey, LocalComponents> components = new HashMap<>();
        private final LinkedHashSet<Long> navigationRequests = new LinkedHashSet<>();
        private final Set<Long> invalidated = new HashSet<>();
        private final Map<Long, Long> missing = new HashMap<>();
        private final Map<AreaKey, TerrainSearch> areas = new LinkedHashMap<>();
        private final Map<RouteKey, TerrainSearch> routes = new LinkedHashMap<>();
        private final LinkedHashMap<Long, Verification> clearances = new LinkedHashMap<>(256, .75f, true);
        private long clearanceRevision = Long.MIN_VALUE;
        private NavigationSurvey survey;
        private long navigationRevision, navigationTick;
        private int searchCursor;
        private final Set<Long> watched = new HashSet<>();
        private final ArrayDeque<Long> rotation = new ArrayDeque<>();
        private final Map<Long, TimedTerrain> samples = new LinkedHashMap<>(256, .75f, true);
        private final Map<UUID, List<SurveyOrigin>> origins = new HashMap<>();
        private final Set<Long> backfill = new LinkedHashSet<>();
        private final Map<MapDataPayload.TerrainKey, Integer> bootstrapOffsets = new LinkedHashMap<>();
        private CompletableFuture<Optional<CompoundTag>> pendingRead;
        private long pendingPosition;
    }

    // PERSISTENT EXPLORATION: this nested SavedData is independent of claims and survives server restarts.
    public static final class TerrainSavedData extends SavedData {
        private static final Codec<TerrainSavedData> CODEC = CompoundTag.CODEC.xmap(TerrainSavedData::fromTag, TerrainSavedData::toTag);
        private static final SavedDataType<TerrainSavedData> TYPE = new SavedDataType<>(
                Identifier.fromNamespaceAndPath("minecraftempires", "terrain"), TerrainSavedData::new, CODEC, null);
        private final Map<UUID, Map<Long, SurveyedChunk>> states = new HashMap<>();
        private long nextVersion = 1;
        private static TerrainSavedData get(ServerLevel level) { return level.getDataStorage().computeIfAbsent(TYPE); }

        private void observe(UUID state, long packed, SurveyedChunk sample) {
            Map<Long, SurveyedChunk> knowledge = states.computeIfAbsent(state, ignored -> new HashMap<>());
            SurveyedChunk previous = knowledge.get(packed);
            if (previous != null && previous.sameSurface(sample)) return;
            knowledge.put(packed, new SurveyedChunk(nextVersion++, sample.colors, sample.heights, sample.features));
            setDirty();
        }
        private CompoundTag toTag() {
            CompoundTag root = new CompoundTag();
            root.putInt("Format", 1); root.putLong("NextVersion", nextVersion);
            ListTag stateTags = new ListTag();
            for (var state : states.entrySet()) {
                CompoundTag stateTag = new CompoundTag();
                stateTag.putString("State", state.getKey().toString());
                ListTag chunks = new ListTag();
                for (var entry : state.getValue().entrySet()) {
                    CompoundTag tag = new CompoundTag(); SurveyedChunk chunk = entry.getValue();
                    tag.putLong("Position", entry.getKey()); tag.putLong("Version", chunk.version);
                    tag.putByteArray("Colors", chunk.colors); tag.putIntArray("Heights", chunk.heights);
                    tag.putByteArray("Features", chunk.features); chunks.add(tag);
                }
                stateTag.put("Chunks", chunks); stateTags.add(stateTag);
            }
            root.put("States", stateTags); return root;
        }
        private static TerrainSavedData fromTag(CompoundTag root) {
            TerrainSavedData result = new TerrainSavedData();
            if (root.getInt("Format").orElse(1) != 1) return result;
            result.nextVersion = Math.max(1, root.getLong("NextVersion").orElse(1L));
            for (var value : root.getListOrEmpty("States")) {
                if (!(value instanceof CompoundTag stateTag)) continue;
                UUID id;
                try { id = UUID.fromString(stateTag.getString("State").orElse("")); }
                catch (IllegalArgumentException ignored) { continue; }
                Map<Long, SurveyedChunk> chunks = new HashMap<>();
                for (var item : stateTag.getListOrEmpty("Chunks")) {
                    if (!(item instanceof CompoundTag tag)) continue;
                    byte[] colors = tag.getByteArray("Colors").orElse(new byte[0]);
                    byte[] features = tag.getByteArray("Features").orElse(new byte[0]);
                    int[] heights = tag.getIntArray("Heights").orElse(new int[0]);
                    if (colors.length != 256 || features.length != 256 || heights.length != 256) continue;
                    long version = tag.getLong("Version").orElse(0L);
                    chunks.put(tag.getLong("Position").orElse(0L), new SurveyedChunk(version, colors, heights, features));
                    result.nextVersion = Math.max(result.nextVersion, version + 1);
                }
                result.states.put(id, chunks);
            }
            return result;
        }
    }
}
