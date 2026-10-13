package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.UUID;

/** Positions sent by clients are hints only; the server uses each unit's saved position. */
public record ArmyActionPayload(String action, UUID targetId, BlockPos position) implements CustomPacketPayload {
    public static final Type<ArmyActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "army_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ArmyActionPayload> STREAM_CODEC = StreamCodec.ofMember(ArmyActionPayload::write, ArmyActionPayload::new);
    public ArmyActionPayload(RegistryFriendlyByteBuf buffer) { this(buffer.readUtf(32), buffer.readUUID(), buffer.readBlockPos()); }
    private void write(RegistryFriendlyByteBuf buffer) { buffer.writeUtf(action, 32); buffer.writeUUID(targetId); buffer.writeBlockPos(position); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
