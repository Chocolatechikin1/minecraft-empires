package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

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
public record GarrisonCohortPayload(UUID cohortId, UUID settlementId, boolean ungarrison) implements CustomPacketPayload {
    public static final Type<GarrisonCohortPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "garrison_cohort"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GarrisonCohortPayload> STREAM_CODEC = StreamCodec.ofMember(GarrisonCohortPayload::write, GarrisonCohortPayload::new);

    public GarrisonCohortPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readUUID(), buffer.readUUID(), buffer.readBoolean());
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(cohortId);
        buffer.writeUUID(settlementId);
        buffer.writeBoolean(ungarrison);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
