package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

//same map showing method, but this one only shows areas that the player has access to, and is sent from the client to the server to request map data
public record RequestMapDataPayload() implements CustomPacketPayload {
    public static final Type<RequestMapDataPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "request_map_data")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestMapDataPayload> STREAM_CODEC = StreamCodec.ofMember(
            RequestMapDataPayload::write,
            RequestMapDataPayload::new
    );

    public RequestMapDataPayload(RegistryFriendlyByteBuf buffer) {
        this();
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        // No fields are required. The server identifies the requesting player from the payload context.
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // Terrain travels separately from ownership/unit snapshots. Nested packets keep the file count down.
    public record TerrainRequest(MapDataPayload.TerrainKey key, long knownVersion) {}

    public record RequestTerrainPayload(UUID battleId, List<TerrainRequest> tiles) implements CustomPacketPayload {
        public static final int MAX_TILES = 16;
        public static final Type<RequestTerrainPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "request_terrain"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestTerrainPayload> STREAM_CODEC = StreamCodec.ofMember(
                RequestTerrainPayload::write, RequestTerrainPayload::new);

        public RequestTerrainPayload {
            tiles = List.copyOf(tiles);
            if (tiles.size() > MAX_TILES) throw new IllegalArgumentException("Too many terrain requests");
        }

        public RequestTerrainPayload(RegistryFriendlyByteBuf buffer) {
            this(buffer.readBoolean() ? buffer.readUUID() : null, readTiles(buffer));
        }

        private static List<TerrainRequest> readTiles(RegistryFriendlyByteBuf buffer) {
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_TILES) throw new IllegalArgumentException("Invalid terrain request count");
            List<TerrainRequest> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                result.add(new TerrainRequest(new MapDataPayload.TerrainKey(
                        buffer.readInt(), buffer.readInt(), buffer.readVarInt()), buffer.readLong()));
            }
            return result;
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(battleId != null);
            if (battleId != null) buffer.writeUUID(battleId);
            buffer.writeVarInt(tiles.size());
            for (TerrainRequest tile : tiles) {
                buffer.writeInt(tile.key().tileX());
                buffer.writeInt(tile.key().tileZ());
                buffer.writeVarInt(tile.key().step());
                buffer.writeLong(tile.knownVersion());
            }
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
