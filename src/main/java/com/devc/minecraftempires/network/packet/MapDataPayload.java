package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-to-client snapshot used by the Phase 3 map dashboard.
 *
 * Only information allowed by fog-of-war rules is placed in this payload.
 */
public record MapDataPayload(
        UUID viewerStateId,
        String viewerStateName,
        List<MapChunkData> chunks,
        List<StateSummary> states,
        List<SettlementSummary> settlements,
        List<BreachAlert> breachAlerts
) implements CustomPacketPayload {
    public static final Type<MapDataPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "map_data")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, MapDataPayload> STREAM_CODEC = StreamCodec.ofMember(
            MapDataPayload::write,
            MapDataPayload::new
    );

    public MapDataPayload {
        viewerStateName = viewerStateName == null ? "" : viewerStateName;
        chunks = List.copyOf(chunks);
        states = List.copyOf(states);
        settlements = List.copyOf(settlements);
        breachAlerts = List.copyOf(breachAlerts);
    }

    public MapDataPayload(RegistryFriendlyByteBuf buffer) {
        this(
                readNullableUuid(buffer),
                buffer.readUtf(),
                readList(buffer, MapChunkData::new),
                readList(buffer, StateSummary::new),
                readList(buffer, SettlementSummary::new),
                readList(buffer, BreachAlert::new)
        );
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        writeNullableUuid(buffer, viewerStateId);
        buffer.writeUtf(viewerStateName);
        writeList(buffer, chunks, MapChunkData::write);
        writeList(buffer, states, StateSummary::write);
        writeList(buffer, settlements, SettlementSummary::write);
        writeList(buffer, breachAlerts, BreachAlert::write);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static MapDataPayload empty() {
        return new MapDataPayload(null, "", List.of(), List.of(), List.of(), List.of());
    }

    public record MapChunkData(
            long packedChunkPos,
            int runLength,
            UUID ownerStateId,
            String settlementId,
            boolean garrisoned,
            int settlementTier,
            boolean contested
    ) {
        public MapChunkData {
            settlementId = settlementId == null ? "" : settlementId;
            runLength = Math.max(1, runLength);
        }

        public MapChunkData(RegistryFriendlyByteBuf buffer) {
            this(
                    buffer.readLong(),
                    buffer.readVarInt(),
                    buffer.readUUID(),
                    buffer.readUtf(),
                    buffer.readBoolean(),
                    buffer.readVarInt(),
                    buffer.readBoolean()
            );
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(packedChunkPos);
            buffer.writeVarInt(runLength);
            buffer.writeUUID(ownerStateId);
            buffer.writeUtf(settlementId);
            buffer.writeBoolean(garrisoned);
            buffer.writeVarInt(settlementTier);
            buffer.writeBoolean(contested);
        }
    }

    public record StateSummary(
            UUID stateId,
            String stateName,
            String tierName,
            int visibleChunkCount,
            int population,
            double treasury,
            boolean viewerState,
            boolean borderingState
    ) {
        public StateSummary {
            stateName = stateName == null ? "Unknown State" : stateName;
            tierName = tierName == null ? "UNKNOWN" : tierName;
        }

        public StateSummary(RegistryFriendlyByteBuf buffer) {
            this(
                    buffer.readUUID(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readDouble(),
                    buffer.readBoolean(),
                    buffer.readBoolean()
            );
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(stateId);
            buffer.writeUtf(stateName);
            buffer.writeUtf(tierName);
            buffer.writeVarInt(visibleChunkCount);
            buffer.writeVarInt(population);
            buffer.writeDouble(treasury);
            buffer.writeBoolean(viewerState);
            buffer.writeBoolean(borderingState);
        }
    }

    public record SettlementSummary(
            String settlementId,
            UUID stateId,
            String settlementName,
            long packedCenterChunk,
            int tier,
            int population,
            int garrisonCapacity,
            boolean capital,
            boolean garrisoned
    ) {
        public SettlementSummary {
            settlementId = settlementId == null ? "" : settlementId;
            settlementName = settlementName == null ? "Unnamed Province" : settlementName;
        }

        public SettlementSummary(RegistryFriendlyByteBuf buffer) {
            this(
                    buffer.readUtf(),
                    buffer.readUUID(),
                    buffer.readUtf(),
                    buffer.readLong(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readBoolean(),
                    buffer.readBoolean()
            );
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(settlementId);
            buffer.writeUUID(stateId);
            buffer.writeUtf(settlementName);
            buffer.writeLong(packedCenterChunk);
            buffer.writeVarInt(tier);
            buffer.writeVarInt(population);
            buffer.writeVarInt(garrisonCapacity);
            buffer.writeBoolean(capital);
            buffer.writeBoolean(garrisoned);
        }
    }

    public record BreachAlert(
            long packedChunkPos,
            UUID defenderStateId,
            UUID attackerStateId,
            long gameTime
    ) {
        public BreachAlert(RegistryFriendlyByteBuf buffer) {
            this(buffer.readLong(), buffer.readUUID(), buffer.readUUID(), buffer.readLong());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(packedChunkPos);
            buffer.writeUUID(defenderStateId);
            buffer.writeUUID(attackerStateId);
            buffer.writeLong(gameTime);
        }
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(RegistryFriendlyByteBuf buffer);
    }

    @FunctionalInterface
    private interface Writer<T> {
        void write(T value, RegistryFriendlyByteBuf buffer);
    }

    private static <T> List<T> readList(RegistryFriendlyByteBuf buffer, Reader<T> reader) {
        int size = buffer.readVarInt();
        List<T> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            values.add(reader.read(buffer));
        }
        return values;
    }

    private static <T> void writeList(RegistryFriendlyByteBuf buffer, List<T> values, Writer<T> writer) {
        buffer.writeVarInt(values.size());
        for (T value : values) {
            writer.write(value, buffer);
        }
    }

    private static UUID readNullableUuid(RegistryFriendlyByteBuf buffer) {
        return buffer.readBoolean() ? buffer.readUUID() : null;
    }

    private static void writeNullableUuid(RegistryFriendlyByteBuf buffer, UUID value) {
        buffer.writeBoolean(value != null);
        if (value != null) {
            buffer.writeUUID(value);
        }
    }
}
