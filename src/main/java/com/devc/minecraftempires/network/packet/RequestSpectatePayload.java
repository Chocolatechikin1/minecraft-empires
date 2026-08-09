package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.combat.BattleManager;
import com.devc.minecraftempires.combat.BattleSession;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * CLIENT → SERVER.
 * Sent when a player clicks the eye (spectate) button on an engaged army in MapScreen.
 *
 * The server:
 *  1. Finds the BattleSession by battleId.
 *  2. Adds the player to the spectating set (sets isSpectated = true → switches from auto-resolve
 *     to tick simulation).
 *  3. Responds with OpenBattleMapPayload so the client opens BattleMapScreen.
 *  4. Also sends an initial BattleSyncPayload with the current state.
 */
//packet sent when a player clicks the eye (spectate) button on an engaged army in MapScreen, client to server
//the server first finds the BAttleSession by the battleId, adds the player to the spectating set
//then responds with OpenBattleMapPayload so the client opens BattleMapScreen, and also sends an initial BattleSyncPayload with the current state
public record RequestSpectatePayload(UUID battleId) implements CustomPacketPayload {
    public static final Type<RequestSpectatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "request_spectate"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestSpectatePayload> STREAM_CODEC = StreamCodec.ofMember(RequestSpectatePayload::write, RequestSpectatePayload::new);

    public RequestSpectatePayload(RegistryFriendlyByteBuf buf) {
        this(buf.readUUID());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(battleId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    //handle the received payload on the server side
    public static void handle(RequestSpectatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            BattleSession session = BattleManager.get(player.level()).getBattle(payload.battleId());
            if (session == null || !session.isActive()) return;

            // Register this player as a spectator
            session.addSpectator(player.getUUID());

            // Tell them to open the BattleMapScreen
            PacketDistributor.sendToPlayer(player, new OpenBattleMapPayload(
                    session.getBattleId(),
                    session.getAttackerArmyId(),
                    session.getDefenderArmyId()
            ));

            // Send them the current battle state immediately
            PacketDistributor.sendToPlayer(player, BattleSyncPayload.fromSession(session));
        });
    }
}
