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
public record RequestMapDataPayload(int centerX, int centerZ, int span) implements CustomPacketPayload {
    public RequestMapDataPayload() { this(0, 0, 0); }
    public RequestMapDataPayload {
        if (Math.abs((long) centerX) > 29_999_984 || Math.abs((long) centerZ) > 29_999_984 || span != 0 && (span < 256 || span > 65536)) throw new IllegalArgumentException("Invalid map viewport");
    }
    public static final Type<RequestMapDataPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "request_map_data")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestMapDataPayload> STREAM_CODEC = StreamCodec.ofMember(
            RequestMapDataPayload::write,
            RequestMapDataPayload::new
    );

    public RequestMapDataPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readInt(), buffer.readInt(), buffer.readVarInt());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        // The authenticated player remains the viewer; coordinates only bound output geometry.
        buffer.writeInt(centerX); buffer.writeInt(centerZ); buffer.writeVarInt(span);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // Terrain travels separately from ownership/unit snapshots. Nested packets keep the file count down.
    public record TerrainRequest(MapDataPayload.TerrainKey key, long knownVersion) {}
    public record OverviewRequest(int centerX, int centerZ, int span, List<MapDataPayload.CoastStamp> known) implements CustomPacketPayload {
        public static final Type<OverviewRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "request_overview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OverviewRequest> STREAM_CODEC = StreamCodec.ofMember(OverviewRequest::write, OverviewRequest::new);
        public OverviewRequest {
            known = List.copyOf(known);
            if (Math.abs((long) centerX) > 29_999_984 || Math.abs((long) centerZ) > 29_999_984 || span < 256 || span > 65536 || known.size() > 192) throw new IllegalArgumentException("Invalid overview viewport");
        }
        public OverviewRequest(int x, int z, int span) { this(x, z, span, List.of()); }
        public OverviewRequest(RegistryFriendlyByteBuf b) { this(b.readInt(), b.readInt(), b.readVarInt(), readKnown(b)); }
        private static List<MapDataPayload.CoastStamp> readKnown(RegistryFriendlyByteBuf b) {
            int count = b.readVarInt(); if (count < 0 || count > 192) throw new IllegalArgumentException("Too many coast revisions");
            List<MapDataPayload.CoastStamp> result = new ArrayList<>(count); for (int i = 0; i < count; i++) result.add(new MapDataPayload.CoastStamp(b)); return result;
        }
        private void write(RegistryFriendlyByteBuf b) { b.writeInt(centerX); b.writeInt(centerZ); b.writeVarInt(span); b.writeVarInt(known.size()); for (var stamp : known) stamp.write(b); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

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
