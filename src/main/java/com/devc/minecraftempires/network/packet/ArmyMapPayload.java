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

//army and legion metadata data container network class. server sends payloads to client
//sends the current location, stats, and waypoints
// 2 lists: legions that have at least 1 cohort and all active armies 
public record ArmyMapPayload(List<LegionSummary> legions, List<ArmySummary> armies) implements CustomPacketPayload {
    public static final Type<ArmyMapPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "army_map")); //initialize each packet with a UUID, allowing packet identification
    public static final StreamCodec<RegistryFriendlyByteBuf, ArmyMapPayload> STREAM_CODEC = StreamCodec.ofMember(ArmyMapPayload::write, ArmyMapPayload::new); //define the codec for serializing and deserializing the packet data

    public record LegionSummary(
            UUID legionId,
            UUID ownerStateId,
            long packedChunkPos,
            int availableSoldiers,  // soldiers from undeployed cohorts only
            int averageMorale       // average morale across all cohorts
    ) {
        public LegionSummary(RegistryFriendlyByteBuf buffer) {
            this(buffer.readUUID(), buffer.readUUID(), buffer.readLong(), buffer.readVarInt(), buffer.readVarInt());
        }

        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(legionId);
            buffer.writeUUID(ownerStateId);
            buffer.writeLong(packedChunkPos);
            buffer.writeVarInt(availableSoldiers);
            buffer.writeVarInt(averageMorale);
        }
    }

    public record ArmySummary(
            UUID armyId,
            UUID ownerStateId,
            long packedChunkPos,
            List<BlockPos> waypoints,
            String displayName,     // e.g. "1st Army", "2nd Army" — generated server-side
            int troops,             // total soldiers (including garrisoned cohorts)
            int morale,             // average morale of non-garrisoned cohorts
            int maintenance,        // daily upkeep cost in emeralds
            boolean isEngaged,
            UUID battleId,          // null if not engaged
            boolean isOnCampaign
    ) {
        public ArmySummary(RegistryFriendlyByteBuf buffer) {
            this(
                    buffer.readUUID(),
                    buffer.readUUID(),
                    buffer.readLong(),
                    readWaypoints(buffer),
                    buffer.readUtf(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readBoolean(),
                    buffer.readBoolean() ? buffer.readUUID() : null,
                    buffer.readBoolean()
            );
        }

        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(armyId);
            buffer.writeUUID(ownerStateId);
            buffer.writeLong(packedChunkPos);
            buffer.writeVarInt(waypoints.size());
            for (BlockPos pos : waypoints) buffer.writeBlockPos(pos);
            buffer.writeUtf(displayName);
            buffer.writeVarInt(troops);
            buffer.writeVarInt(morale);
            buffer.writeVarInt(maintenance);
            buffer.writeBoolean(isEngaged);
            buffer.writeBoolean(battleId != null);
            if (battleId != null) buffer.writeUUID(battleId);
            buffer.writeBoolean(isOnCampaign);
        }

        private static List<BlockPos> readWaypoints(RegistryFriendlyByteBuf buffer) { //reads the list of waypoints from the buffer, returning a list of BlockPos objects
            int size = buffer.readVarInt();
            List<BlockPos> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) list.add(buffer.readBlockPos());
            return list;
        }
    }

    public ArmyMapPayload {
        legions = List.copyOf(legions);
        armies  = List.copyOf(armies);
    }

    public ArmyMapPayload(RegistryFriendlyByteBuf buffer) {
        this(readLegionList(buffer), readArmyList(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(legions.size());
        for (LegionSummary s : legions) s.write(buffer);

        buffer.writeVarInt(armies.size());
        for (ArmySummary s : armies) s.write(buffer);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static ArmyMapPayload empty() {
        return new ArmyMapPayload(List.of(), List.of());
    }

    private static List<LegionSummary> readLegionList(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<LegionSummary> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) list.add(new LegionSummary(buffer));
        return list;
    }

    private static List<ArmySummary> readArmyList(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<ArmySummary> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) list.add(new ArmySummary(buffer));
        return list;
    }
}
