package com.devc.minecraftempires.network;

import com.devc.minecraftempires.army.Army;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Legion;
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
        ServerLevel level = player.level();
        StateManager stateManager = StateManager.get(level);
        ClaimManager claimManager = ClaimManager.get(level);
        StateData viewerState = stateManager.getStateByPlayer(player.getUUID());

        if (viewerState == null) return MapDataPayload.empty();

        //get the viewer's state ID and all claims, then determine which states are visible due to war or scouting
        UUID viewerStateId = viewerState.getStateId();
        Map<ChunkPos, ChunkData> allClaims = claimManager.getClaimsView();
        Set<UUID> warVisibleStateIds = getWarVisibleStateIds(viewerState);
        ScoutingResult scouting = findScoutedForeignChunks(allClaims, viewerStateId);

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
        Map<UUID, UUID> capitalSettlementByState = chooseCapitalSettlements(allSettlements, visibleStateIds);
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

        return new MapDataPayload(viewerStateId, viewerState.getStateName(), visibleChunks,
                stateSummaries, settlementSummaries,
                BreachAlertService.getVisibleRecentAlerts(level, viewerStateId, visibleChunkPositions));
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
        return Set.of(); // expanded in sprint 6+
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

    //builds the netwoek army map payload
    //gives 2 lists: a legion summary and an army summary, which are used to display the player's own units on the map
    public static ArmyMapPayload buildArmyPayload(ServerPlayer player) {
        ServerLevel level = player.level();
        ArmyManager armyManager = ArmyManager.get(level);
        StateData viewerState = StateManager.get(level).getStateByPlayer(player.getUUID());
        if (viewerState == null) return ArmyMapPayload.empty();

        UUID viewerStateId = viewerState.getStateId();

        //legion cohort list for legions wiith free cohorts, including their position, available soldiers, and average morale
        List<ArmyMapPayload.LegionSummary> legionSummaries = new ArrayList<>();
        for (Legion legion : armyManager.getLegionsForState(viewerStateId)) {
            if (!legion.hasAvailableCohorts()) continue;
            BlockPos pos = legion.getStoredPosition();
            ChunkPos cp = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
            legionSummaries.add(new ArmyMapPayload.LegionSummary(
                    legion.getLegionId(), legion.getOwningStateId(), cp.pack(),
                    legion.getAvailableSoldiers(), legion.getAverageMorale()));
        }

        // Army summaries — all active Armies for this state
        // Per-soldier upkeep: 750 emeralds per full 500-soldier legion = 1.5 per soldier/day (fix this, shouldnt have decimals for emeralds)
        final double UPKEEP_PER_SOLDIER = 1.5;
        List<ArmyMapPayload.ArmySummary> armySummaries = new ArrayList<>();
        for (Army army : armyManager.getArmiesForState(viewerStateId)) {
            BlockPos pos = army.getStoredPosition();
            ChunkPos cp = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
            int maintenance = (int) Math.round(army.getTotalStrength(armyManager) * UPKEEP_PER_SOLDIER);
            armySummaries.add(new ArmyMapPayload.ArmySummary(
                    army.getArmyId(), army.getOwningStateId(), cp.pack(),
                    new ArrayList<>(army.getWaypoints()),
                    army.getTotalStrength(armyManager),
                    army.getBattleMorale(armyManager),
                    maintenance, army.isEngaged(), army.getCurrentBattleId(), army.isOnCampaign()));
        }

        return new ArmyMapPayload(legionSummaries, armySummaries);
    }
}
