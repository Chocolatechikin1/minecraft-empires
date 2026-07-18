package com.devc.minecraftempires.client.map;

import com.devc.minecraftempires.network.packet.MapDataPayload;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-only immutable cache. Expensive border and province-anchor calculations happen once
 * when a server payload arrives rather than once per rendered frame.
 */
public final class ClientMapData {
    public static final int BORDER_NORTH = 1;
    public static final int BORDER_EAST = 1 << 1;
    public static final int BORDER_SOUTH = 1 << 2;
    public static final int BORDER_WEST = 1 << 3;

    private static final AtomicInteger VERSION_COUNTER = new AtomicInteger();
    private static volatile Snapshot snapshot = Snapshot.empty(0);

    private ClientMapData() {}

    public static Snapshot get() {
        return snapshot;
    }

    public static void accept(MapDataPayload payload) {
        snapshot = Snapshot.from(payload, VERSION_COUNTER.incrementAndGet());
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
                    0,
                    0,
                    0,
                    0
            );
        }

        private static Snapshot from(MapDataPayload payload, int version) {
            if (payload == null || payload.chunks().isEmpty()) {
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
                    minX,
                    maxX,
                    minZ,
                    maxZ
            );
        }

        public boolean hasData() {
            return !chunksByPosition.isEmpty();
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

    private static Map<UUID, MapDataPayload.StateSummary> indexStates(
            List<MapDataPayload.StateSummary> states
    ) {
        Map<UUID, MapDataPayload.StateSummary> indexed = new LinkedHashMap<>();
        for (MapDataPayload.StateSummary state : states) {
            indexed.put(state.stateId(), state);
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static Map<String, MapDataPayload.SettlementSummary> indexSettlements(
            List<MapDataPayload.SettlementSummary> settlements
    ) {
        Map<String, MapDataPayload.SettlementSummary> indexed = new LinkedHashMap<>();
        for (MapDataPayload.SettlementSummary settlement : settlements) {
            indexed.put(settlement.settlementId(), settlement);
        }
        return Collections.unmodifiableMap(indexed);
    }

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

        Map<String, Accumulator> groups = new HashMap<>();
        Set<Long> unorganizedChunks = new java.util.HashSet<>();
        for (MapDataPayload.MapChunkData chunk : chunks.values()) {
            String settlementId = chunk.settlementId();
            if (settlementId == null || settlementId.isBlank()) {
                unorganizedChunks.add(chunk.packedChunkPos());
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
                // Commanderies and legacy provinces have no known capital marker, so their
                // label falls back to the mathematical center of their visible claim.
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

        addUnorganizedAnchors(chunks, unorganizedChunks, anchors);
        anchors.sort((left, right) -> left.displayName().compareToIgnoreCase(right.displayName()));
        return anchors;
    }

    private static void addUnorganizedAnchors(
            Map<Long, MapDataPayload.MapChunkData> chunks,
            Set<Long> unvisited,
            List<ProvinceAnchor> anchors
    ) {
        int componentIndex = 0;
        while (!unvisited.isEmpty()) {
            long seed = unvisited.iterator().next();
            unvisited.remove(seed);

            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
            queue.add(seed);
            MapDataPayload.MapChunkData seedChunk = chunks.get(seed);
            UUID stateId = seedChunk.ownerStateId();
            long sumX = 0;
            long sumZ = 0;
            int count = 0;
            boolean garrisoned = false;

            while (!queue.isEmpty()) {
                long packed = queue.removeFirst();
                MapDataPayload.MapChunkData current = chunks.get(packed);
                if (current == null || !stateId.equals(current.ownerStateId())) {
                    continue;
                }

                ChunkPos position = ChunkPos.unpack(packed);
                sumX += position.x();
                sumZ += position.z();
                count++;
                garrisoned |= current.garrisoned();

                addUnorganizedNeighbor(position.x(), position.z() - 1, stateId, chunks, unvisited, queue);
                addUnorganizedNeighbor(position.x() + 1, position.z(), stateId, chunks, unvisited, queue);
                addUnorganizedNeighbor(position.x(), position.z() + 1, stateId, chunks, unvisited, queue);
                addUnorganizedNeighbor(position.x() - 1, position.z(), stateId, chunks, unvisited, queue);
            }

            if (count > 0) {
                anchors.add(new ProvinceAnchor(
                        "unorganized:" + stateId + ":" + componentIndex++,
                        "Unorganized Territory",
                        stateId,
                        (double) sumX / count + 0.5,
                        (double) sumZ / count + 0.5,
                        false,
                        garrisoned,
                        false
                ));
            }
        }
    }

    private static void addUnorganizedNeighbor(
            int x,
            int z,
            UUID stateId,
            Map<Long, MapDataPayload.MapChunkData> chunks,
            Set<Long> unvisited,
            java.util.ArrayDeque<Long> queue
    ) {
        long packed = new ChunkPos(x, z).pack();
        if (!unvisited.contains(packed)) {
            return;
        }

        MapDataPayload.MapChunkData neighbor = chunks.get(packed);
        if (neighbor != null
                && neighbor.settlementId().isBlank()
                && stateId.equals(neighbor.ownerStateId())) {
            unvisited.remove(packed);
            queue.addLast(packed);
        }
    }

    private static String abbreviatedId(String settlementId) {
        if (settlementId.length() <= 12) {
            return settlementId;
        }
        return settlementId.substring(0, 8) + "...";
    }
}
