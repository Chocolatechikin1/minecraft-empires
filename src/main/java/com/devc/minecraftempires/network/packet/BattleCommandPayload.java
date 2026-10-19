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

/** Validated tactical movement, enemy targeting, withdrawal, or deployment formation. */
public record BattleCommandPayload(UUID battleId, UUID cohortId, double targetX, double targetZ,
                                   boolean clearExisting, String action, UUID targetCohortId) implements CustomPacketPayload {
    public static final Type<BattleCommandPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "battle_command"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BattleCommandPayload> STREAM_CODEC = StreamCodec.ofMember(BattleCommandPayload::write, BattleCommandPayload::new);
    public BattleCommandPayload(UUID battleId, UUID cohortId, double x, double z, boolean clear) {
        this(battleId, cohortId, x, z, clear, "MOVE");
    }
    public BattleCommandPayload(UUID battleId, UUID cohortId, double x, double z, boolean clear, String action) {
        this(battleId, cohortId, x, z, clear, action, null);
    }
    public BattleCommandPayload(RegistryFriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readUUID(), buf.readDouble(), buf.readDouble(), buf.readBoolean(), buf.readUtf(24), buf.readBoolean() ? buf.readUUID() : null);
    }
    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(battleId); buf.writeUUID(cohortId); buf.writeDouble(targetX); buf.writeDouble(targetZ);
        buf.writeBoolean(clearExisting); buf.writeUtf(action, 24);
        buf.writeBoolean(targetCohortId != null); if (targetCohortId != null) buf.writeUUID(targetCohortId);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(BattleCommandPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            BattleManager manager = BattleManager.get(player.level());
            BattleSession session = manager.getBattle(payload.battleId());
            if (session == null || !session.isActive() || session.getCohortById(payload.cohortId()).isEmpty()) return;
            boolean attacker = session.isAttackerCohort(payload.cohortId());
            if (!manager.canControlSide(player, session, attacker)) return;
            switch (payload.action()) {
                case "ATTACK" -> {
                    if (!session.issueAttackOrder(payload.cohortId(), payload.targetCohortId()))
                        session.getCohortById(payload.cohortId()).ifPresent(cohort -> cohort.setOrderStatus("Target order rejected: choose a live enemy during combat"));
                }
                case "MOVE" -> {
                    if (!session.issueOrder(payload.cohortId(), payload.targetX(), payload.targetZ(), payload.clearExisting()))
                        session.getCohortById(payload.cohortId()).ifPresent(cohort -> cohort.setOrderStatus(session.getPhase() == BattleSession.BattlePhase.DEPLOYMENT
                                ? "Deployment order rejected: ground, range or occupied space" : session.getPhase() == BattleSession.BattlePhase.PREPARATION
                                ? "Wait for deployment preparation" : "Order rejected: unit cannot move now"));
                }
                case "WITHDRAW" -> session.withdrawCohort(payload.cohortId());
                case "LINE" -> session.applyFormation(attacker, BattleSession.Formation.LINE);
                case "TRIPLEX" -> session.applyFormation(attacker, BattleSession.Formation.TRIPLEX);
                case "WEDGE" -> session.applyFormation(attacker, BattleSession.Formation.WEDGE);
                case "AUTO_RESOLVE" -> session.requestAutoResolve();
                default -> { return; }
            }
            manager.setDirty();
        });
    }
}
