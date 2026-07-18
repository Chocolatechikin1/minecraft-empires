package com.devc.minecraftempires.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import java.util.UUID;

public record MapDataPayload(List<MapChunkData> chunks) implements CustomPacketPayload {

    //payload definition for the map data packet
    public static final Type<MapDataPayload> TYPE = 
        new Type<>(Identifier.fromNamespaceAndPath("minecraftempires", "map_data"));

    //network transmitter
    public static final StreamCodec<FriendlyByteBuf, MapDataPayload> STREAM_CODEC = StreamCodec.ofMember(
        MapDataPayload::write,
        MapDataPayload::new
    );

    //constructor
    public MapDataPayload(FriendlyByteBuf buffer) {
        this(buffer.readList(MapChunkData::new));
    }

    //writing to ByteBuf for network transmission
    public void write(FriendlyByteBuf buffer) {
        buffer.writeCollection(this.chunks, (buf, data) -> data.write(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Lightweight DTO (Data Transfer Object) record.
     * We use this instead of sending full ChunkData to keep network traffic fast and memory-safe.
     */
    public record MapChunkData(ChunkPos pos, UUID ownerUUID, String settlementID, int tier, boolean isGarrisoned) {
        
        public MapChunkData(FriendlyByteBuf buf) {
            this(buf.readChunkPos(), buf.readUUID(), buf.readUtf(), buf.readInt(), buf.readBoolean());
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeChunkPos(this.pos);
            buf.writeUUID(this.ownerUUID);
            buf.writeUtf(this.settlementID);
            buf.writeInt(this.tier);
            buf.writeBoolean(this.isGarrisoned);
        }
    }
}