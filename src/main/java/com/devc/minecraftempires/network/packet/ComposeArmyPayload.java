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
//The same bounded selection also supports detaching in place and independent map movement.
public record ComposeArmyPayload(List<UUID> selectedCohortIds, BlockPos initialPosition, String action, boolean queue) implements CustomPacketPayload {
    public ComposeArmyPayload(List<UUID> ids, BlockPos position) { this(ids, position, "COMPOSE", false); }
    public ComposeArmyPayload {
        selectedCohortIds = List.copyOf(selectedCohortIds);
        if (selectedCohortIds.size() > 150) throw new IllegalArgumentException("Too many selected cohorts");
    }
    public static final Type<ComposeArmyPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "compose_army"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ComposeArmyPayload> STREAM_CODEC = StreamCodec.ofMember(ComposeArmyPayload::write, ComposeArmyPayload::new);

    public ComposeArmyPayload(RegistryFriendlyByteBuf buffer) {
        this(readUUIDList(buffer), buffer.readBlockPos(), buffer.readUtf(16), buffer.readBoolean());
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(selectedCohortIds.size());
        for (UUID id : selectedCohortIds) buffer.writeUUID(id);
        buffer.writeBlockPos(initialPosition);
        buffer.writeUtf(action, 16); buffer.writeBoolean(queue);
    }

    private static List<UUID> readUUIDList(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > 150) throw new IllegalArgumentException("Invalid cohort selection size");
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) ids.add(buffer.readUUID());
        return ids;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
