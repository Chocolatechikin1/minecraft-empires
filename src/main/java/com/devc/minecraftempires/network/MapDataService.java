package com.devc.minecraftempires.network;

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

/**
 * Builds a server-authoritative map snapshot and applies the Phase 3 fog-of-war filter.
 */
public final class MapDataService {
    private static final int[][] CARDINAL_DIRECTIONS = {
            {0, -1},
            {1, 0},
            {0, 1},
            {-1, 0}
    };

    private MapDataService() {}

    public static MapDataPayload buildPayload(ServerPlayer player) {
        ServerLevel level = player.level();
        StateManager stateManager = StateManager.get(level);
        ClaimManager claimManager = ClaimManager.get(level);
        StateData viewerState = stateManager.getStateByPlayer(player.getUUID());

        if (viewerState == null) {
            return MapDataPayload.empty();
        }

        UUID viewerStateId = viewerState.getStateId();
        Map<ChunkPos, ChunkData> allClaims = claimManager.getClaimsView();
        Set<UUID> warVisibleStateIds = getWarVisibleStateIds(viewerState);
        ScoutingResult scouting = findScoutedForeignChunks(allClaims, viewerStateId);

        Set<UUID> visibleStateIds = new HashSet<>();
        visibleStateIds.add(viewerStateId);
        visibleStateIds.addAll(scouting.borderingStateIds());
        visibleStateIds.addAll(warVisibleStateIds);

        List<MapDataPayload.MapChunkData> visibleChunks = new ArrayList<>();
        Map<UUID, Integer> visibleChunkCounts = new HashMap<>();
        Map<String, Boolean> settlementGarrisonStatus = new HashMap<>();
        Map<String, UUID> settlementOwners = new HashMap<>();
        Map<String, Integer> settlementTiers = new HashMap<>();
        Set<Long> visibleChunkPositions = new HashSet<>();

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

        visibleChunks = compressHorizontalRuns(visibleChunks);

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

        Collection<SettlementData> allSettlements = stateManager.getAllSettlements();
        Map<UUID, UUID> capitalSettlementByState = chooseCapitalSettlements(allSettlements, visibleStateIds);
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

    /**
     * Phase 4/5 extension point. Once formal war relations exist, add those enemy state IDs here.
     */
    private static Set<UUID> getWarVisibleStateIds(StateData viewerState) {
        return Set.of();
    }

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
}
