package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.combat.BattleManager;
import com.devc.minecraftempires.combat.BattleSession;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * CLIENT → SERVER.
 * Sent when a player closes BattleMapScreen (via Close button, Auto-Resolve, or Escape).
 *
 * The server removes the player from the session's spectating set. Close continues the battle
 * under AI control. An explicit, authorized AUTO_RESOLVE command instead permits resolution
 * after all viewers leave for 200 game ticks (10 seconds).
 */
public record LeaveSpectatePayload(UUID battleId) implements CustomPacketPayload {
    public static final Type<LeaveSpectatePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "leave_spectate"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LeaveSpectatePayload> STREAM_CODEC =
            StreamCodec.ofMember(LeaveSpectatePayload::write, LeaveSpectatePayload::new);

    public LeaveSpectatePayload(RegistryFriendlyByteBuf buf) {
        this(buf.readUUID());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(battleId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(LeaveSpectatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            BattleSession session = BattleManager.get(player.level()).getBattle(payload.battleId());
            if (session == null) return; // session may have already resolved — that's fine

            session.removeSpectator(player.getUUID());
            MinecraftEmpires.LOGGER.debug("Player {} left spectating battle {}.",
                    player.getUUID(), payload.battleId());
        });
    }
}
