package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

//SERVER FACING packet that streams the positions and ownership of all visible legions to the map UI
public record ArmyMapPayload(List<LegionSummary> legions) implements CustomPacketPayload {

    public static final Type<ArmyMapPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "army_map")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, ArmyMapPayload> STREAM_CODEC = StreamCodec.ofMember(
            ArmyMapPayload::write,
            ArmyMapPayload::new
    );

    //show only what the map record needs to draw the legions
    public record LegionSummary(UUID legionId, UUID ownerStateId, long packedChunkPos) {
        public LegionSummary(RegistryFriendlyByteBuf buffer) {
            this(buffer.readUUID(), buffer.readUUID(), buffer.readLong());
        }

        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(legionId);
            buffer.writeUUID(ownerStateId);
            buffer.writeLong(packedChunkPos);
        }
    }

    // Canonical compact constructor — defensive copy so the list is always immutable
    public ArmyMapPayload {
        legions = List.copyOf(legions);
    }

    // Decoding constructor
    public ArmyMapPayload(RegistryFriendlyByteBuf buffer) {
        this(readList(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(legions.size());
        for (LegionSummary summary : legions) {
            summary.write(buffer);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static ArmyMapPayload empty() {
        return new ArmyMapPayload(List.of());
    }

    private static List<LegionSummary> readList(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<LegionSummary> list = new ArrayList<>(size); //array list is ideal here as the size is known already, avoiding resizing and giving O(1) access time
        for (int i = 0; i < size; i++) {
            list.add(new LegionSummary(buffer));
        }
        return list;
    }
}
