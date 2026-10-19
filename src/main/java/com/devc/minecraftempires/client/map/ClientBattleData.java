package com.devc.minecraftempires.client.map;

import com.devc.minecraftempires.network.packet.BattleSyncPayload;
import java.util.*;

/** Client battle view; accept and advanceTick are called on the client thread. */
public final class ClientBattleData {
    private static Snapshot snapshot = Snapshot.empty();
    private ClientBattleData() {}
    public static Snapshot get() { return snapshot; }
    public static void accept(BattleSyncPayload payload) { snapshot = Snapshot.from(payload, snapshot); }
    public static void advanceTick() { snapshot = snapshot.advanceTick(); }
    public static void clear() { snapshot = Snapshot.empty(); }

    public record Snapshot(UUID battleId, Map<UUID, CohortRenderState> cohorts, String battlePhase,
                           int deploymentTicksRemaining, int ticksSinceLastPacket, String result,
                           boolean controlsAttacker, boolean controlsDefender, double mapOriginX, double mapOriginZ, String attackerFormation, String defenderFormation, String deploymentMessage) {
        static Snapshot empty() { return new Snapshot(null, Map.of(), "DEPLOYMENT", 0, 0, "ONGOING", false, false, 0, 0, "LINE", "LINE", ""); }
        static Snapshot from(BattleSyncPayload payload, Snapshot previous) {
            if (!Objects.equals(payload.battleId(), previous.battleId())) previous = empty();
            Map<UUID, CohortRenderState> map = new LinkedHashMap<>();
            for (var cohort : payload.attackerCohorts()) map.put(cohort.cohortId(), CohortRenderState.from(cohort, previous.cohorts.get(cohort.cohortId()), true));
            for (var cohort : payload.defenderCohorts()) map.put(cohort.cohortId(), CohortRenderState.from(cohort, previous.cohorts.get(cohort.cohortId()), false));
            return new Snapshot(payload.battleId(), Collections.unmodifiableMap(map), payload.battlePhase(),
                    payload.deploymentTicksRemaining(), 0, payload.result(), payload.controlsAttacker(), payload.controlsDefender(), payload.mapOriginX(), payload.mapOriginZ(), payload.attackerFormation(), payload.defenderFormation(), payload.deploymentMessage());
        }
        public Snapshot advanceTick() {
            return new Snapshot(battleId, cohorts, battlePhase, deploymentTicksRemaining, Math.min(5, ticksSinceLastPacket + 1), result, controlsAttacker, controlsDefender, mapOriginX, mapOriginZ, attackerFormation, defenderFormation, deploymentMessage);
        }
        public boolean hasData() { return battleId != null; }
        public boolean isDeploymentPhase() { return "DEPLOYMENT".equals(battlePhase) && !hasEnded(); }
        public boolean isPreparing() { return "PREPARATION".equals(battlePhase) && !hasEnded(); }
        public boolean hasEnded() { return !"ONGOING".equals(result); }
        // No selection is normal on the first frame; Map.of() rejects null lookup keys.
        public CohortRenderState getCohort(UUID id) { return id == null ? null : cohorts.get(id); }
        public boolean canControl(CohortRenderState cohort) {
            return cohort != null && !hasEnded() && (cohort.isAttacker ? controlsAttacker : controlsDefender);
        }
        public double getRenderX(UUID id, float partialTick) {
            CohortRenderState cohort = getCohort(id);
            if (cohort == null) return 0;
            double t = Math.clamp((ticksSinceLastPacket + partialTick) / 5.0, 0, 1);
            return cohort.prevX + (cohort.currentX - cohort.prevX) * t;
        }
        public double getRenderZ(UUID id, float partialTick) {
            CohortRenderState cohort = getCohort(id);
            if (cohort == null) return 0;
            double t = Math.clamp((ticksSinceLastPacket + partialTick) / 5.0, 0, 1);
            return cohort.prevZ + (cohort.currentZ - cohort.prevZ) * t;
        }
        public double getRenderFacing(UUID id, float partialTick) {
            CohortRenderState cohort = getCohort(id);
            if (cohort == null) return Math.PI / 2;
            double t = Math.clamp((ticksSinceLastPacket + partialTick) / 5.0, 0, 1);
            return interpolateFacing(cohort.previousFacing, cohort.currentFacing, t);
        }
    }
    public static double interpolateFacing(double previous, double current, double fraction) {
        double delta = Math.atan2(Math.sin(current - previous), Math.cos(current - previous));
        return previous + delta * Math.clamp(fraction, 0, 1);
    }
    public static final class CohortRenderState {
        public final UUID cohortId;
        public final UUID attackTarget;
        public final String type;
        public final boolean isAttacker, isRouting, isEngaged, isWithdrawing, escaped;
        public final double currentX, currentZ, prevX, prevZ, speed;
        public final int currentHealth, maxHealth, morale, strength, endurance;
        public final double width, length, headingX, headingZ;
        public final double previousFacing, currentFacing;
        public final List<BattleSyncPayload.FormationPoint> body;
        public final List<BattleSyncPayload.FormationPoint> route, objectives, pending;
        public final String orderStatus;
        private CohortRenderState(BattleSyncPayload.CohortSnapshot cohort, CohortRenderState previous, boolean attacker) {
            cohortId = cohort.cohortId(); attackTarget = cohort.attackTarget(); type = cohort.type(); isAttacker = attacker; isRouting = cohort.isRouting();
            isEngaged = cohort.isEngaged(); isWithdrawing = cohort.isWithdrawing(); escaped = cohort.escaped();
            currentX = cohort.x(); currentZ = cohort.z(); prevX = previous == null ? currentX : previous.currentX;
            prevZ = previous == null ? currentZ : previous.currentZ; currentHealth = cohort.currentHealth();
            maxHealth = cohort.maxHealth(); morale = cohort.morale(); strength = cohort.strength();
            endurance = cohort.endurance(); speed = cohort.speed();
            width = cohort.width(); length = cohort.length(); headingX = cohort.headingX(); headingZ = cohort.headingZ();
            currentFacing = Math.atan2(cohort.combatFacingZ(), cohort.combatFacingX());
            previousFacing = previous == null ? currentFacing : previous.currentFacing;
            body = cohort.body();
            route = cohort.route(); objectives = cohort.objectives(); pending = cohort.pending(); orderStatus = cohort.orderStatus();
        }
        static CohortRenderState from(BattleSyncPayload.CohortSnapshot cohort, CohortRenderState previous, boolean attacker) {
            return new CohortRenderState(cohort, previous, attacker);
        }
    }
}
