package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** good desc ill keep
 * CLIENT → SERVER.
 * Sent when a player confirms "Abandon Settlement" in SettlementManagementScreen.
 *
 * The server:
 *  1. Verifies the player's state owns the settlement (security check).
 *  2. Calls StateManager.disbandSettlement() to wipe data and claims.
 *  3. Calls StateManager.markAltarAbandoned(altarPos) so the block can now be broken.
 *  4. Sends the player a confirmation message.
 */
public record AbandonSettlementPayload(UUID settlementId, BlockPos altarPos) implements CustomPacketPayload {
    public static final Type<AbandonSettlementPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "abandon_settlement"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AbandonSettlementPayload> STREAM_CODEC =
            StreamCodec.ofMember(AbandonSettlementPayload::write, AbandonSettlementPayload::new);

    public AbandonSettlementPayload(RegistryFriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readBlockPos());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(settlementId);
        buf.writeBlockPos(altarPos);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // TODO (Phase 2): implement server-side handler
    public static void handle(AbandonSettlementPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // TODO: verify player owns the settlement's state
            // TODO: call StateManager.disbandSettlement(payload.settlementId(), level)
            // TODO: call StateManager.markAltarAbandoned(payload.altarPos())
            // TODO: send confirmation message to player
            MinecraftEmpires.LOGGER.debug("AbandonSettlementPayload received — handler not yet implemented.");
        });
    }
}
