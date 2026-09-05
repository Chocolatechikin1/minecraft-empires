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

    //method logic for handling settlement abandonment on the server side
    public static void handle(AbandonSettlementPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) return; //get the player
            net.minecraft.server.level.ServerLevel level = serverPlayer.level(); //get the server level

            com.devc.minecraftempires.state.StateManager stateManager = com.devc.minecraftempires.state.StateManager.get(level);

            //verify the player's state actually owns this settlement
            com.devc.minecraftempires.state.StateData playerState = stateManager.getStateByPlayer(serverPlayer.getUUID());
            if (playerState == null) { //check if the player is in a state
                serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Minecraft Empires] You are not in a state."));
                return;
            }

            com.devc.minecraftempires.territory.SettlementData settlement = stateManager.getSettlement(payload.settlementId()); //get the settlement data
            if (settlement == null) { //if settlement doesn't exist, send error message
                serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Minecraft Empires] Settlement not found."));
                return;
            }

            //if the settlement is not owned by the player's state, send error message
            if (!settlement.getOwningStateId().equals(playerState.getStateId())) { 
                serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Minecraft Empires] You do not own this settlement."));
                return;
            }

            //safety checks passed, proceed with abandonment
            String settlementName = settlement.getSettlementName();

            //wipe the settlement data and unlock the altar block for breaking
            stateManager.disbandSettlement(payload.settlementId(), level);
            stateManager.markAltarAbandoned(payload.altarPos());

            //inform player
            serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.literal("§6[Minecraft Empires] §eAbandoned §f'" + settlementName + "§f'§e. Altar block breakable."));
        });
    }
}
