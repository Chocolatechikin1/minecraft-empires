package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

//class handles packet payload for dispatching an army to a target position with an option to queue the dispatch
public record DispatchArmyPayload(UUID armyId, BlockPos targetPos, boolean isQueueing) implements CustomPacketPayload {

    // Unique identifier for this packet over the network
    public static final Type<DispatchArmyPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "dispatch_army"));

    // StreamCodec for byte serialization
    public static final StreamCodec<RegistryFriendlyByteBuf, DispatchArmyPayload> STREAM_CODEC = StreamCodec.ofMember(
            DispatchArmyPayload::write,
            DispatchArmyPayload::new
    );

    // Decoding constructor (Server reads the bytes sent by Client)
    public DispatchArmyPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readUUID(), buffer.readBlockPos(), buffer.readBoolean());
    }

    // Encoding method (Client writes the bytes to send to Server)
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(this.armyId);
        buffer.writeBlockPos(this.targetPos);
        buffer.writeBoolean(this.isQueueing);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}