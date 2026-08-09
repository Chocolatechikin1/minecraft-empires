package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

//legion and army dispatch packet sender, from client to server
//this class is called when the user right clicks a legion or army on the main map and moves it
public record DispatchLegionPayload(UUID legionId, BlockPos targetPos, boolean isQueueing) implements CustomPacketPayload {
    public static final Type<DispatchLegionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "dispatch_legion"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DispatchLegionPayload> STREAM_CODEC = StreamCodec.ofMember(DispatchLegionPayload::write, DispatchLegionPayload::new);

    public DispatchLegionPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readUUID(), buffer.readBlockPos(), buffer.readBoolean());
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(legionId);
        buffer.writeBlockPos(targetPos);
        buffer.writeBoolean(isQueueing);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
