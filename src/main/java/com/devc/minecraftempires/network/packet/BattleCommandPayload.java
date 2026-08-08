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

//waypoint command class, sent when a player issues a waypoint (client to server)
public record BattleCommandPayload(UUID battleId, UUID cohortId, double targetX, double targetZ, boolean clearExisting) implements CustomPacketPayload {   // true = overwrite queue; false = append
    public static final Type<BattleCommandPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "battle_command"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BattleCommandPayload> STREAM_CODEC = StreamCodec.ofMember(BattleCommandPayload::write, BattleCommandPayload::new);

    public BattleCommandPayload(RegistryFriendlyByteBuf buf) { //decode the data from the buffer when received
        this(
            buf.readUUID(),
            buf.readUUID(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readBoolean()
        );
    }

    //encode the data into the buffer for transmission
    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(battleId);
        buf.writeUUID(cohortId);
        buf.writeDouble(targetX);
        buf.writeDouble(targetZ);
        buf.writeBoolean(clearExisting);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    //handle the received payload on the server side
    public static void handle(BattleCommandPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            BattleSession session = BattleManager.get(player.level()).getBattle(payload.battleId());
            if (session == null || !session.isActive()) return;

            session.getCohortById(payload.cohortId()).ifPresent(cohort -> {
                if (payload.clearExisting()) cohort.clearOrders();
                cohort.queueWaypoint(payload.targetX(), payload.targetZ());
            });
        });
    }
}
