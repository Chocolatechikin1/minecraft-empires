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
public final class MapDataService {
    private static final int[][] CARDINAL_DIRECTIONS = {
            {0, -1},
            {1, 0},
            {0, 1},
            {-1, 0}
    };

    private MapDataService() {}

    //builds the map data payload for a given player, applying fog-of-war
    public static MapDataPayload buildPayload(ServerPlayer player) {
        //get all player details and managers
        ServerLevel level = player.level().getServer().overworld();
        StateManager stateManager = StateManager.get(level);
        ClaimManager claimManager = ClaimManager.get(level);
        StateData viewerState = stateManager.getStateByPlayer(player.getUUID());

        if (viewerState == null) return MapDataPayload.empty();

        //get the viewer's state ID and all claims, then determine which states are visible due to war or scouting
        UUID viewerStateId = viewerState.getStateId();
        Map<ChunkPos, ChunkData> allClaims = claimManager.getClaimsView();
        Set<UUID> warVisibleStateIds = getWarVisibleStateIds(viewerState);
        ScoutingResult scouting = findScoutedForeignChunks(allClaims, viewerStateId);
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

        for (Map.Entry<ChunkPos, ChunkData> entry : allClaims.entrySet()) { //while iterating through all claims, check if the chunk is visible to the player based on ownership, war visibility, or scouting
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

        Collection<SettlementData> allSettlements = stateManager.getAllSettlements();
        Map<UUID, UUID> capitalSettlementByState = new HashMap<>();
        for (UUID id : visibleStateIds) {
            StateData state = stateManager.getState(id);
            if (state != null && state.getCapitalSettlementId() != null) capitalSettlementByState.put(id, state.getCapitalSettlementId());
        }
        List<MapDataPayload.SettlementSummary> settlementSummaries = new ArrayList<>();
        Set<String> summarizedSettlementIds = new HashSet<>();

        for (SettlementData settlement : allSettlements) {
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
                    settlementGarrisonStatus.getOrDefault(settlementId, false)));
            summarizedSettlementIds.add(settlementId);
        }

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
                roads, camps, logisticsTruncated);
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
        return buildArmyPayload(viewerStateId, armyManager, pos -> militaryVisible(pos, viewerStateId, claims, origins));
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
                        owned && c.isRetreating(), owned ? c.getWaypoints().stream().limit(128).toList() : List.of()));
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
                    owned ? maintenance : 0, army.isEngaged(), army.getCurrentBattleId(), army.isOnCampaign()));
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

    public static void watchTerrainChunk(ServerLevel level, ChunkPos position) {
        if (level != level.getServer().overworld()) return;
        TerrainRuntime runtime = TERRAIN_RUNTIMES.computeIfAbsent(level, ignored -> new TerrainRuntime());
        long packed = position.pack();
        if (runtime.watched.add(packed)) runtime.rotation.addLast(packed);
    }

    public static void unwatchTerrainChunk(ServerLevel level, ChunkPos position) {
        TerrainRuntime runtime = TERRAIN_RUNTIMES.get(level);
        if (runtime != null) {
            runtime.watched.remove(position.pack());
            runtime.samples.remove(position.pack());
        }
    }

    public static void clearTerrainRuntime() { TERRAIN_RUNTIMES.clear(); }

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
        long time = level.getGameTime();
        if (time % 20 == 0 || runtime.origins.isEmpty()) refreshTerrainOrigins(level, runtime);
        if (runtime.origins.isEmpty()) return;

        TerrainSavedData saved = TerrainSavedData.get(level);
        ClaimManager claims = ClaimManager.get(level);
        tickTerrainBackfill(level, runtime, saved, claims);
        int sampled = 0;
        long deadline = System.nanoTime() + 2_000_000L;
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
        if (runtime.pendingRead != null && runtime.pendingRead.isDone()) {
            long packed = runtime.pendingPosition;
            try {
                Optional<CompoundTag> data = runtime.pendingRead.join();
                if (data.isPresent() && level.getChunkSource().getChunkNow(ChunkPos.unpack(packed).x(), ChunkPos.unpack(packed).z()) == null) {
                    var storage = level.getChunkSource().chunkMap;
                    CompoundTag upgraded = storage.upgradeChunkTag(data.get(), -1);
                    SerializableChunkData parsed = SerializableChunkData.parse(level, level.palettedContainerFactory(), upgraded);
                    if (parsed != null && parsed.chunkStatus() == ChunkStatus.FULL && parsed.chunkPos().pack() == packed) {
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
            runtime.pendingRead = null;
        }
        if (runtime.pendingRead != null || level.getGameTime() % 5 != 0 || runtime.backfill.isEmpty()) return;
        long packed = runtime.backfill.iterator().next(); runtime.backfill.remove(packed);
        ChunkPos pos = ChunkPos.unpack(packed);
        if (terrainObservers(pos, claims, runtime).isEmpty()) return;
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
