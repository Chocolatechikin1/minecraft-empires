package com.devc.minecraftempires.client.map;

import com.devc.minecraftempires.network.packet.ArmyMapPayload;
import net.minecraft.world.level.ChunkPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import net.minecraft.core.BlockPos;

//CLIENT-FACING cache for army positions
//class called with ClientArmyData.get() to retrieve the current snapshot of army positions for rendering on the map
public final class ClientArmyData {

    private static volatile Snapshot snapshot = Snapshot.empty();

    private ClientArmyData() {}

    public static Snapshot get() {
        return snapshot;
    }

    //called from ClientNetworking on the client thread when an ArmyMapPayload arrives
    public static void accept(ArmyMapPayload payload) {
        snapshot = Snapshot.from(payload);
    }

    public record Snapshot(
            //get a specific legion by its UUID
            Map<UUID, ArmyMapPayload.LegionSummary> byId,
            //map of chunk positions (packed long) to legion UUIDs to check which legion occupies a given chunk (O(1) lookup)
            Map<Long, UUID> byChunk
    ) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of());
        }

        static Snapshot from(ArmyMapPayload payload) {
            Map<UUID, ArmyMapPayload.LegionSummary> byId = new HashMap<>();
            Map<Long, UUID> byChunk = new HashMap<>();

            for (ArmyMapPayload.LegionSummary summary : payload.legions()) {
                byId.put(summary.legionId(), summary);
                //a legion can only occupy one chunk at a time; overwrite if somehow two legions share a chunk
                byChunk.put(summary.packedChunkPos(), summary.legionId());
            }

            return new Snapshot(
                    Collections.unmodifiableMap(byId),
                    Collections.unmodifiableMap(byChunk)
            );
        }

        public boolean hasArmies() {
            return !byId.isEmpty();
        }

        //get the legion at a given coordiante
        public UUID getLegionIdAtChunk(int chunkX, int chunkZ) {
            return byChunk.get(new ChunkPos(chunkX, chunkZ).pack());
        }
    }
}
