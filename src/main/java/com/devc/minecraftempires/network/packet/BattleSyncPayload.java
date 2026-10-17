package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.combat.BattleSession;
import com.devc.minecraftempires.combat.CohortData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

//position, morale and health tracking for all cohorts in a battle. server to client
/** A complete server snapshot including terminal results and viewer command permissions. */
public record BattleSyncPayload(UUID battleId, List<CohortSnapshot> attackerCohorts, List<CohortSnapshot> defenderCohorts,
                                String battlePhase, int deploymentTicksRemaining, String result,
                                boolean controlsAttacker, boolean controlsDefender,
                                double mapOriginX, double mapOriginZ) implements CustomPacketPayload {
    public static final Type<BattleSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "battle_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BattleSyncPayload> STREAM_CODEC = StreamCodec.ofMember(BattleSyncPayload::write, BattleSyncPayload::new);
    public BattleSyncPayload { attackerCohorts = List.copyOf(attackerCohorts); defenderCohorts = List.copyOf(defenderCohorts); }
    public BattleSyncPayload(RegistryFriendlyByteBuf buf) {
        this(buf.readUUID(), readSnapshots(buf), readSnapshots(buf), buf.readUtf(24), buf.readVarInt(), buf.readUtf(24), buf.readBoolean(), buf.readBoolean(), buf.readDouble(), buf.readDouble());
    }
    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(battleId); writeSnapshots(buf, attackerCohorts); writeSnapshots(buf, defenderCohorts);
        buf.writeUtf(battlePhase, 24); buf.writeVarInt(deploymentTicksRemaining); buf.writeUtf(result, 24);
        buf.writeBoolean(controlsAttacker); buf.writeBoolean(controlsDefender);
        // Local battle coordinates map to real blocks relative to this world origin.
        buf.writeDouble(mapOriginX); buf.writeDouble(mapOriginZ);
    }
    private static void writeSnapshots(RegistryFriendlyByteBuf buf, List<CohortSnapshot> list) {
        buf.writeVarInt(list.size()); list.forEach(c -> c.write(buf));
    }
    private static List<CohortSnapshot> readSnapshots(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > 1024) throw new IllegalArgumentException("Invalid battle roster size");
        List<CohortSnapshot> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) list.add(new CohortSnapshot(buf));
        return list;
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static BattleSyncPayload fromSession(BattleSession session) { return fromSession(session, false, false); }
    public static BattleSyncPayload fromSession(BattleSession session, boolean attacker, boolean defender) {
        return new BattleSyncPayload(session.getBattleId(), snapshots(session.getAttackerCohorts()), snapshots(session.getDefenderCohorts()),
                session.getPhase().name(), session.getDeploymentTicksRemaining(), session.getResult().name(), attacker, defender,
                session.getMapOriginX(), session.getMapOriginZ());
    }
    private static List<CohortSnapshot> snapshots(List<CohortData> cohorts) {
        return cohorts.stream().map(c -> new CohortSnapshot(c.getCohortId(), c.getPosition().x, c.getPosition().y,
                c.getCurrentHealth(), c.getMaxHealth(), c.getMorale(), c.getType(), c.isRouting(), c.isEngaged(),
                c.isWithdrawing(), c.hasEscaped(), c.getStrength(), c.getEndurance(), c.getSpeed())).toList();
    }
    public record CohortSnapshot(UUID cohortId, double x, double z, int currentHealth, int maxHealth, int morale,
                                  String type, boolean isRouting, boolean isEngaged, boolean isWithdrawing,
                                  boolean escaped, int strength, int endurance, double speed) {
        CohortSnapshot(RegistryFriendlyByteBuf buf) {
            this(buf.readUUID(), buf.readDouble(), buf.readDouble(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readUtf(32), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                    buf.readVarInt(), buf.readVarInt(), buf.readDouble());
        }
        void write(RegistryFriendlyByteBuf buf) {
            buf.writeUUID(cohortId); buf.writeDouble(x); buf.writeDouble(z); buf.writeVarInt(currentHealth);
            buf.writeVarInt(maxHealth); buf.writeVarInt(morale); buf.writeUtf(type, 32); buf.writeBoolean(isRouting);
            buf.writeBoolean(isEngaged); buf.writeBoolean(isWithdrawing); buf.writeBoolean(escaped);
            buf.writeVarInt(strength); buf.writeVarInt(endurance); buf.writeDouble(speed);
        }
    }
}
