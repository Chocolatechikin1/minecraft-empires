package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

/** keep this uintil loyalty system is added
 * Sent by the client to garrison or un-garrison a Cohort at a settlement.
 *
 * When garrisoning (ungarrison = false):
 *  - cohort.isGarrisoned is set true
 *  - cohort.garrisonedSettlementId is set
 *  - settlement.isMartialLaw is set true (stub — loyalty system pending)
 *
 * When un-garrisoning (ungarrison = true):
 *  - Reverses all of the above
 */
//packet sent when a player garrisons or ungarrisons a cohort at a settlement, client to server
public record GarrisonCohortPayload(List<UUID> cohortIds, UUID settlementId, boolean ungarrison) implements CustomPacketPayload {
    public GarrisonCohortPayload {
        cohortIds = List.copyOf(cohortIds);
        if (cohortIds.size() > 150) throw new IllegalArgumentException("Too many selected cohorts");
    }

    public GarrisonCohortPayload(UUID cohortId, UUID settlementId, boolean ungarrison) {
        this(List.of(cohortId), settlementId, ungarrison);
    }
    public static final Type<GarrisonCohortPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "garrison_cohort"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GarrisonCohortPayload> STREAM_CODEC = StreamCodec.ofMember(GarrisonCohortPayload::write, GarrisonCohortPayload::new);

    public GarrisonCohortPayload(RegistryFriendlyByteBuf buffer) {
        this(readIds(buffer), buffer.readUUID(), buffer.readBoolean());
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(cohortIds.size());
        for (UUID id : cohortIds) buffer.writeUUID(id);
        buffer.writeUUID(settlementId);
        buffer.writeBoolean(ungarrison);
    }

    private static List<UUID> readIds(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > 150) throw new IllegalArgumentException("Invalid cohort selection size");
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) ids.add(buffer.readUUID());
        return ids;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
