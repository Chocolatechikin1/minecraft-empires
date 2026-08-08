package com.devc.minecraftempires.client.map;

import com.devc.minecraftempires.network.packet.ArmyMapPayload;
import net.minecraft.world.level.ChunkPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

//client side data store for the army map, storing the current state of legions and armies on the map
//legions are standalone legions with available cohorts, while armies are operational army groupings actively on the map
//armies take visual priority over legions in chunk-based lookups
public final class ClientArmyData {

    private static volatile Snapshot snapshot = Snapshot.empty();

    private ClientArmyData() {}

    public static Snapshot get() { return snapshot; }
    public static void accept(ArmyMapPayload payload) {
        snapshot = Snapshot.from(payload);
    }

    public record Snapshot(
           //legion data
            Map<UUID, ArmyMapPayload.LegionSummary> legionsById,
            Map<Long, UUID> legionsByChunk,

            //army data
            Map<UUID, ArmyMapPayload.ArmySummary> armiesById,
            Map<Long, UUID> armiesByChunk
    ) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of());
        }

        //returns a snapshot of the current state of legions and armies on the map, based on the given payload from the server
        static Snapshot from(ArmyMapPayload payload) {
            Map<UUID, ArmyMapPayload.LegionSummary> legionsById  = new HashMap<>();
            Map<Long, UUID>                          legionsByChunk = new HashMap<>();
            Map<UUID, ArmyMapPayload.ArmySummary>   armiesById   = new HashMap<>();
            Map<Long, UUID>                          armiesByChunk = new HashMap<>();

            for (ArmyMapPayload.LegionSummary s : payload.legions()) {
                legionsById.put(s.legionId(), s);
                legionsByChunk.put(s.packedChunkPos(), s.legionId());
            }

            for (ArmyMapPayload.ArmySummary s : payload.armies()) {
                armiesById.put(s.armyId(), s);
                armiesByChunk.put(s.packedChunkPos(), s.armyId());
            }

            return new Snapshot(
                    Collections.unmodifiableMap(legionsById),
                    Collections.unmodifiableMap(legionsByChunk),
                    Collections.unmodifiableMap(armiesById),
                    Collections.unmodifiableMap(armiesByChunk)
            );
        }

        public boolean hasAnyUnits() { return !legionsById.isEmpty() || !armiesById.isEmpty(); }

        @Deprecated
        public boolean hasArmies() { return !armiesById.isEmpty(); }

        //get legion at the chunk, or null. Armies take priority for visual rendering, so this will return null if an army is present at the chunk.
        public UUID getLegionIdAtChunk(int chunkX, int chunkZ) {
            return legionsByChunk.get(new ChunkPos(chunkX, chunkZ).pack());
        }

        //get army at the chunk
        public UUID getArmyIdAtChunk(int chunkX, int chunkZ) {
            return armiesByChunk.get(new ChunkPos(chunkX, chunkZ).pack());
        }
    }
}
