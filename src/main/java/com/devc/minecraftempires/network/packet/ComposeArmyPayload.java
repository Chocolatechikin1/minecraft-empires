package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

//packet sender from client to server when user forms a new army from "Form Army"
//server will validate the selected cohorts and create a new army if valid
public record ComposeArmyPayload(List<UUID> selectedCohortIds, BlockPos initialPosition) implements CustomPacketPayload {
    public static final Type<ComposeArmyPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "compose_army"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ComposeArmyPayload> STREAM_CODEC = StreamCodec.ofMember(ComposeArmyPayload::write, ComposeArmyPayload::new);

    public ComposeArmyPayload(RegistryFriendlyByteBuf buffer) {
        this(readUUIDList(buffer), buffer.readBlockPos());
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(selectedCohortIds.size());
        for (UUID id : selectedCohortIds) buffer.writeUUID(id);
        buffer.writeBlockPos(initialPosition);
    }

    private static List<UUID> readUUIDList(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) ids.add(buffer.readUUID());
        return ids;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
