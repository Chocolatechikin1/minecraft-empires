package com.devc.minecraftempires.network;

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
public final class MapDataService {
    private static final int[][] CARDINAL_DIRECTIONS = { //2D array of cardinal directions for checking neighboring chunks
            {0, -1},
            {1, 0},
            {0, 1},
            {-1, 0}
    };

    //constructor
    private MapDataService() {}

    //builds the map data payload for a given player, applying the fog-of-war filter to only show what the player is allowed to see
    public static MapDataPayload buildPayload(ServerPlayer player) {
        //get state info first
        ServerLevel level = player.level();
        StateManager stateManager = StateManager.get(level);
        ClaimManager claimManager = ClaimManager.get(level);
        StateData viewerState = stateManager.getStateByPlayer(player.getUUID());

        if (viewerState == null) {
            return MapDataPayload.empty();
        }

        //get user info and claims
        UUID viewerStateId = viewerState.getStateId();
        Map<ChunkPos, ChunkData> allClaims = claimManager.getClaimsView();
        Set<UUID> warVisibleStateIds = getWarVisibleStateIds(viewerState);
        ScoutingResult scouting = findScoutedForeignChunks(allClaims, viewerStateId);

        //determine which states and chunks are visible to the player, we'll use a hashset to store the visible state IDs and a list to store the visible chunks
        Set<UUID> visibleStateIds = new HashSet<>();
        visibleStateIds.add(viewerStateId);
        visibleStateIds.addAll(scouting.borderingStateIds());
        visibleStateIds.addAll(warVisibleStateIds);

        //build the list of visible chunks, and also keep track of the number of visible chunks per state, as well as settlement information
        //utilizing hash  maps for quick lookups and avoiding duplicates and an arraylist for the visible chunks to maintain order and allow for easy iteration
        List<MapDataPayload.MapChunkData> visibleChunks = new ArrayList<>(); //we can use an arraylist as we already know the number of visible chunks
        Map<UUID, Integer> visibleChunkCounts = new HashMap<>();
        Map<String, Boolean> settlementGarrisonStatus = new HashMap<>();
        Map<String, UUID> settlementOwners = new HashMap<>();
        Map<String, Integer> settlementTiers = new HashMap<>();
        Set<Long> visibleChunkPositions = new HashSet<>();

        //for each claim, check if the player is allowed to see it, and if so, add it to the list of visible chunks and update the counts and settlement information
        for (Map.Entry<ChunkPos, ChunkData> entry : allClaims.entrySet()) {
            ChunkPos position = entry.getKey();
            ChunkData data = entry.getValue();
            UUID ownerStateId = data.getOwnerUUID();
            if (ownerStateId == null || !isChunkVisible(
                    position,
                    ownerStateId,
                    viewerStateId,
                    warVisibleStateIds,
                    scouting.scoutedForeignChunks()
            )) {
                continue;
            }

            String settlementId = normalizeSettlementId(data.getSettlementID());
            visibleChunks.add(new MapDataPayload.MapChunkData(
                    position.pack(),
                    1,
                    ownerStateId,
                    settlementId,
                    data.isGarrisoned(),
                    data.getSettlementTier(),
                    false
            ));
            visibleChunkPositions.add(position.pack());
            visibleChunkCounts.merge(ownerStateId, 1, Integer::sum);

            if (!settlementId.isEmpty()) {
                settlementOwners.putIfAbsent(settlementId, ownerStateId);
                settlementTiers.merge(settlementId, data.getSettlementTier(), Math::max);
                if (data.isGarrisoned()) {
                    settlementGarrisonStatus.put(settlementId, true);
                }
            }
        }

        //compress horizontal runs of chunks to reduce the size of the payload
        visibleChunks = compressHorizontalRuns(visibleChunks);

        //build the list of state summaries, which will include the state name, tier, number of visible chunks, and other relevant information
        //arraylist as we know the parameter sizes that we're feeding it
        List<MapDataPayload.StateSummary> stateSummaries = new ArrayList<>();
        for (UUID visibleStateId : visibleStateIds) {
            StateData state = stateManager.getState(visibleStateId);
            if (state == null) {
                continue;
            }

            boolean isViewerState = visibleStateId.equals(viewerStateId);
            stateSummaries.add(new MapDataPayload.StateSummary(
                    visibleStateId,
                    state.getStateName(),
                    state.getCurrentTier().name(),
                    visibleChunkCounts.getOrDefault(visibleStateId, 0),
                    isViewerState ? state.getTotalPopulation() : 0,
                    isViewerState ? state.getTreasuryBalance() : 0.0,
                    isViewerState,
                    scouting.borderingStateIds().contains(visibleStateId)
            ));
        }
        stateSummaries.sort(Comparator.comparing(MapDataPayload.StateSummary::stateName, String.CASE_INSENSITIVE_ORDER));

        //gets all settlements and chooses the capital settlement for each state, then builds the list of settlement summaries, which will include the settlement name, tier, center chunk position, and other relevant information
        Collection<SettlementData> allSettlements = stateManager.getAllSettlements();
        Map<UUID, UUID> capitalSettlementByState = chooseCapitalSettlements(allSettlements, visibleStateIds); //not sure how i feel about auto selecting capitals, may adjust later
        List<MapDataPayload.SettlementSummary> settlementSummaries = new ArrayList<>();
        Set<String> summarizedSettlementIds = new HashSet<>();

        for (SettlementData settlement : allSettlements) {
            UUID owningStateId = settlement.getOwningStateId();
            if (!visibleStateIds.contains(owningStateId)) {
                continue;
            }

            BlockPos altar = settlement.getCenterAltarPos();
            ChunkPos centerChunk = new ChunkPos(altar.getX() >> 4, altar.getZ() >> 4);
            boolean revealFullSettlement = owningStateId.equals(viewerStateId)
                    || warVisibleStateIds.contains(owningStateId);
            if (!revealFullSettlement && !visibleChunkPositions.contains(centerChunk.pack())) {
                continue;
            }

            String settlementId = settlement.getSettlementId().toString();
            boolean isViewerSettlement = owningStateId.equals(viewerStateId);
            settlementSummaries.add(new MapDataPayload.SettlementSummary(
                    settlementId,
                    owningStateId,
                    settlement.getSettlementName(),
                    centerChunk.pack(),
                    settlement.getSettlementTier(),
                    isViewerSettlement ? settlement.getLocalPopulation() : 0,
                    isViewerSettlement ? settlement.getGarrisonCapacity() : 0,
                    settlement.getSettlementId().equals(capitalSettlementByState.get(owningStateId)),
                    settlementGarrisonStatus.getOrDefault(settlementId, false)
            ));
            summarizedSettlementIds.add(settlementId);
        }

        // Phase 1 worlds may have a registered City Altar center before Sprint 2B has a full
        // SettlementData object. Preserve that useful anchor without exposing hidden locations.
        for (Map.Entry<String, ChunkPos> centerEntry : claimManager.getSettlementCentersView().entrySet()) {
            String settlementId = centerEntry.getKey();
            UUID owningStateId = settlementOwners.get(settlementId);
            ChunkPos centerChunk = centerEntry.getValue();
            if (owningStateId == null
                    || summarizedSettlementIds.contains(settlementId)
                    || !visibleChunkPositions.contains(centerChunk.pack())) {
                continue;
            }

            settlementSummaries.add(new MapDataPayload.SettlementSummary(
                    settlementId,
                    owningStateId,
                    fallbackProvinceName(settlementId),
                    centerChunk.pack(),
                    settlementTiers.getOrDefault(settlementId, 0),
                    0,
                    0,
                    false,
                    settlementGarrisonStatus.getOrDefault(settlementId, false)
            ));
        }

        settlementSummaries.sort(Comparator.comparing(
                MapDataPayload.SettlementSummary::settlementName,
                String.CASE_INSENSITIVE_ORDER
        ));

        return new MapDataPayload(
                viewerStateId,
                viewerState.getStateName(),
                visibleChunks,
                stateSummaries,
                settlementSummaries,
                BreachAlertService.getVisibleRecentAlerts(
                        level,
                        viewerStateId,
                        visibleChunkPositions
                )
        );
    }

    //helper method to compress horizontal runs of chunks with the same metadata into a single entry with a run length, to reduce the size of the payload
    private static List<MapDataPayload.MapChunkData> compressHorizontalRuns(
            List<MapDataPayload.MapChunkData> individualChunks
    ) {
        individualChunks.sort(
                Comparator.<MapDataPayload.MapChunkData>comparingInt(
                        chunk -> ChunkPos.unpack(chunk.packedChunkPos()).z()
                ).thenComparingInt(chunk -> ChunkPos.unpack(chunk.packedChunkPos()).x())
        );

        List<MapDataPayload.MapChunkData> runs = new ArrayList<>();
        MapDataPayload.MapChunkData current = null;
        ChunkPos currentStart = null;

        for (MapDataPayload.MapChunkData chunk : individualChunks) {
            ChunkPos position = ChunkPos.unpack(chunk.packedChunkPos());
            if (current != null
                    && currentStart.z() == position.z()
                    && currentStart.x() + current.runLength() == position.x()
                    && sameChunkMetadata(current, chunk)) {
                current = new MapDataPayload.MapChunkData(
                        current.packedChunkPos(),
                        current.runLength() + 1,
                        current.ownerStateId(),
                        current.settlementId(),
                        current.garrisoned(),
                        current.settlementTier(),
                        current.contested()
                );
                runs.set(runs.size() - 1, current);
                continue;
            }

            current = chunk;
            currentStart = position;
            runs.add(chunk);
        }

        return runs;
    }

    //helper method to check if two chunks have the same metadata, used for compressing horizontal runs of chunks
    private static boolean sameChunkMetadata(
            MapDataPayload.MapChunkData left,
            MapDataPayload.MapChunkData right
    ) {
        return left.ownerStateId().equals(right.ownerStateId())
                && left.settlementId().equals(right.settlementId())
                && left.garrisoned() == right.garrisoned()
                && left.settlementTier() == right.settlementTier()
                && left.contested() == right.contested();
    }

    //helper method to determine if a chunk is visible to the player, based on the owner state ID, viewer state ID, fog of war, and scouted foreign chunks
    private static boolean isChunkVisible(
            ChunkPos position,
            UUID ownerStateId,
            UUID viewerStateId,
            Set<UUID> warVisibleStateIds,
            Set<Long> scoutedForeignChunks
    ) {
        return ownerStateId.equals(viewerStateId)
                || warVisibleStateIds.contains(ownerStateId)
                || scoutedForeignChunks.contains(position.pack());
    }

    //helper method to find all foreign chunks that are adjacent to the player's own chunks, and return a set of their positions and the IDs of the bordering states
    private static ScoutingResult findScoutedForeignChunks(
            Map<ChunkPos, ChunkData> claims,
            UUID viewerStateId
    ) {
        Set<Long> scoutedForeignChunks = new HashSet<>();
        Set<UUID> borderingStates = new HashSet<>();

        for (Map.Entry<ChunkPos, ChunkData> entry : claims.entrySet()) {
            ChunkData ownData = entry.getValue();
            if (!viewerStateId.equals(ownData.getOwnerUUID())) {
                continue;
            }

            ChunkPos position = entry.getKey();
            for (int[] direction : CARDINAL_DIRECTIONS) {
                ChunkPos neighborPosition = new ChunkPos(
                        position.x() + direction[0],
                        position.z() + direction[1]
                );
                ChunkData neighbor = claims.get(neighborPosition);
                if (neighbor != null
                        && neighbor.getOwnerUUID() != null
                        && !viewerStateId.equals(neighbor.getOwnerUUID())) {
                    scoutedForeignChunks.add(neighborPosition.pack());
                    borderingStates.add(neighbor.getOwnerUUID());
                }
            }
        }

        return new ScoutingResult(Set.copyOf(scoutedForeignChunks), Set.copyOf(borderingStates));
    }

    //method for storing "enemy" states, to be modified in sprint 4 and 5
    private static Set<UUID> getWarVisibleStateIds(StateData viewerState) {
        return Set.of();
    }

    //this method will choose the capital settlement for each state, based on the highest tier and then alphabetically by name, and return a map of state ID to capital settlement ID
    //not too sure if this is what i want, know to look here to adjust if needed
    private static Map<UUID, UUID> chooseCapitalSettlements(
            Collection<SettlementData> settlements,
            Set<UUID> visibleStateIds
    ) {
        Map<UUID, SettlementData> bestByState = new HashMap<>();

        for (SettlementData settlement : settlements) {
            if (!visibleStateIds.contains(settlement.getOwningStateId())) {
                continue;
            }

            bestByState.merge(
                    settlement.getOwningStateId(),
                    settlement,
                    (left, right) -> {
                        if (right.getSettlementTier() != left.getSettlementTier()) {
                            return right.getSettlementTier() > left.getSettlementTier() ? right : left;
                        }
                        return right.getSettlementName().compareToIgnoreCase(left.getSettlementName()) < 0 ? right : left;
                    }
            );
        }

        Map<UUID, UUID> result = new HashMap<>();
        bestByState.forEach((stateId, settlement) -> result.put(stateId, settlement.getSettlementId()));
        return result;
    }

    //default names for provinces and settlements
    private static String fallbackProvinceName(String settlementId) {
        if (settlementId == null || settlementId.isBlank()) {
            return "Unnamed Province";
        }
        String shortId = settlementId.length() <= 8 ? settlementId : settlementId.substring(0, 8);
        return "Province " + shortId;
    }

    private static String normalizeSettlementId(String settlementId) {
        return settlementId == null ? "" : settlementId;
    }

    private record ScoutingResult(
            Set<Long> scoutedForeignChunks,
            Set<UUID> borderingStateIds
    ) {}

    //builds an army map payload for a given player, which includes the positions of all legions owned by the player's state, and is used for displaying army icons on the map
    // Phase 1 scope: only the viewer's own legions are included.
    // Enemy visibility will be added in Sprint 6 alongside war-state logic.
    public static ArmyMapPayload buildArmyPayload(ServerPlayer player) {
        ServerLevel level = player.level();
        StateManager stateManager = StateManager.get(level);
        ArmyManager armyManager = ArmyManager.get(level);

        StateData viewerState = stateManager.getStateByPlayer(player.getUUID());
        if (viewerState == null) {
            return ArmyMapPayload.empty();
        }

        List<ArmyMapPayload.LegionSummary> summaries = new ArrayList<>();
        for (Legion legion : armyManager.getLegionsForState(viewerState.getStateId())) {
            BlockPos pos = legion.getStoredPosition();
            ChunkPos chunkPos = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
            summaries.add(new ArmyMapPayload.LegionSummary(
                    legion.getLegionId(),
                    legion.getOwningStateId(),
                    chunkPos.pack(),
                    new ArrayList<>(legion.getWaypoints()),
                    legion.getTotalStrength(),       // total soldiers across all cohorts
                    0,                              // morale: stub until per-cohort average is added
                    Legion.DAILY_UPKEEP_EMERALDS    // maintenance cost per day
            ));
        }

        return new ArmyMapPayload(summaries);
    }
}
