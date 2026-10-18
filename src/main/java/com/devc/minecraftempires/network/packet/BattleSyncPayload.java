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
                                double mapOriginX, double mapOriginZ, String attackerFormation, String defenderFormation, String deploymentMessage) implements CustomPacketPayload {
    public BattleSyncPayload(UUID id, List<CohortSnapshot> attackers, List<CohortSnapshot> defenders, String phase, int ticks, String result, boolean a, boolean d, double x, double z) {
        this(id, attackers, defenders, phase, ticks, result, a, d, x, z, "LINE", "LINE", "");
    }
    public static final Type<BattleSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "battle_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BattleSyncPayload> STREAM_CODEC = StreamCodec.ofMember(BattleSyncPayload::write, BattleSyncPayload::new);
    public BattleSyncPayload { attackerCohorts = List.copyOf(attackerCohorts); defenderCohorts = List.copyOf(defenderCohorts); }
    public BattleSyncPayload(RegistryFriendlyByteBuf buf) {
        this(buf.readUUID(), readSnapshots(buf), readSnapshots(buf), buf.readUtf(24), buf.readVarInt(), buf.readUtf(24), buf.readBoolean(), buf.readBoolean(), buf.readDouble(), buf.readDouble(), buf.readUtf(24), buf.readUtf(24), buf.readUtf(128));
    }
    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(battleId); writeSnapshots(buf, attackerCohorts); writeSnapshots(buf, defenderCohorts);
        buf.writeUtf(battlePhase, 24); buf.writeVarInt(deploymentTicksRemaining); buf.writeUtf(result, 24);
        buf.writeBoolean(controlsAttacker); buf.writeBoolean(controlsDefender);
        // Local battle coordinates map to real blocks relative to this world origin.
        buf.writeDouble(mapOriginX); buf.writeDouble(mapOriginZ);
        buf.writeUtf(attackerFormation, 24); buf.writeUtf(defenderFormation, 24); buf.writeUtf(deploymentMessage, 128);
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
        return new BattleSyncPayload(session.getBattleId(), snapshots(session.getAttackerCohorts(), session, attacker), snapshots(session.getDefenderCohorts(), session, defender),
                session.getPhase().name(), session.getDeploymentTicksRemaining(), session.getResultLabel(), attacker, defender,
                session.getMapOriginX(), session.getMapOriginZ(), session.getFormation(true), session.getFormation(false), session.getDeploymentMessage());
    }
    private static List<CohortSnapshot> snapshots(List<CohortData> cohorts, BattleSession session, boolean controlled) {
        return cohorts.stream().map(c -> new CohortSnapshot(c.getCohortId(), c.getPosition().x, c.getPosition().y,
                c.getCurrentHealth(), c.getMaxHealth(), c.getMorale(), c.getType(), c.isRouting(), c.isEngaged(),
                c.isWithdrawing(), c.hasEscaped(), c.getStrength(), c.getEndurance(), c.getSpeed(), c.getWidth(), c.getLength(), c.getHeadingX(), c.getHeadingZ(),
                c.isColumn() ? c.footprintAt(c.getPosition().x, c.getPosition().y).stream().map(point -> new FormationPoint(point.x, point.y)).toList() : List.of(),
                controlled ? c.getMovementWaypoints().stream().map(point -> new FormationPoint(point.x, point.y)).toList() : List.of(),
                controlled ? c.getOrderObjectives().stream().map(point -> new FormationPoint(point.x, point.y)).toList() : List.of(),
                controlled ? session.getPendingObjectives(c.getCohortId()).stream().map(point -> new FormationPoint(point.x, point.y)).toList() : List.of(), controlled ? c.getOrderStatus() : "")).toList();
    }
    public record FormationPoint(double x, double z) {}
    public record CohortSnapshot(UUID cohortId, double x, double z, int currentHealth, int maxHealth, int morale,
                                  String type, boolean isRouting, boolean isEngaged, boolean isWithdrawing,
                                  boolean escaped, int strength, int endurance, double speed, double width, double length, double headingX, double headingZ, List<FormationPoint> body,
                                  List<FormationPoint> route, List<FormationPoint> objectives, List<FormationPoint> pending, String orderStatus) {
        public CohortSnapshot {
            body = checked(body); route = checked(route); objectives = checked(objectives); pending = checked(pending);
        }
        private static List<FormationPoint> checked(List<FormationPoint> points) { if (points.size() > 64) throw new IllegalArgumentException("Formation path too large"); return List.copyOf(points); }
        public CohortSnapshot(UUID id, double x, double z, int health, int max, int morale, String type, boolean routing, boolean engaged, boolean withdrawing, boolean escaped, int strength, int endurance, double speed) {
            this(id, x, z, health, max, morale, type, routing, engaged, withdrawing, escaped, strength, endurance, speed, 10, 10, 0, 1, List.of(), List.of(), List.of(), List.of(), "");
        }
        CohortSnapshot(RegistryFriendlyByteBuf buf) {
            this(buf.readUUID(), buf.readDouble(), buf.readDouble(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readUtf(32), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                    buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), readBody(buf), readBody(buf), readBody(buf), readBody(buf), buf.readUtf(128));
        }
        private static List<FormationPoint> readBody(RegistryFriendlyByteBuf buf) {
            int size = buf.readVarInt(); if (size < 0 || size > 64) throw new IllegalArgumentException("Invalid formation footprint");
            List<FormationPoint> points = new ArrayList<>(); for (int i = 0; i < size; i++) points.add(new FormationPoint(buf.readDouble(), buf.readDouble())); return points;
        }
        void write(RegistryFriendlyByteBuf buf) {
            buf.writeUUID(cohortId); buf.writeDouble(x); buf.writeDouble(z); buf.writeVarInt(currentHealth);
            buf.writeVarInt(maxHealth); buf.writeVarInt(morale); buf.writeUtf(type, 32); buf.writeBoolean(isRouting);
            buf.writeBoolean(isEngaged); buf.writeBoolean(isWithdrawing); buf.writeBoolean(escaped);
            buf.writeVarInt(strength); buf.writeVarInt(endurance); buf.writeDouble(speed);
            buf.writeDouble(width); buf.writeDouble(length); buf.writeDouble(headingX); buf.writeDouble(headingZ);
            buf.writeVarInt(body.size()); for (FormationPoint point : body) { buf.writeDouble(point.x()); buf.writeDouble(point.z()); }
            for (List<FormationPoint> path : List.of(route, objectives, pending)) { buf.writeVarInt(path.size()); for (FormationPoint point : path) { buf.writeDouble(point.x()); buf.writeDouble(point.z()); } }
            buf.writeUtf(orderStatus, 128);
        }
    }
}
