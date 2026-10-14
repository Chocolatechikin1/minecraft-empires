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
                           boolean controlsAttacker, boolean controlsDefender, double mapOriginX, double mapOriginZ) {
        static Snapshot empty() { return new Snapshot(null, Map.of(), "DEPLOYMENT", 0, 0, "ONGOING", false, false, 0, 0); }
        static Snapshot from(BattleSyncPayload payload, Snapshot previous) {
            Map<UUID, CohortRenderState> map = new LinkedHashMap<>();
            for (var cohort : payload.attackerCohorts()) map.put(cohort.cohortId(), CohortRenderState.from(cohort, previous.cohorts.get(cohort.cohortId()), true));
            for (var cohort : payload.defenderCohorts()) map.put(cohort.cohortId(), CohortRenderState.from(cohort, previous.cohorts.get(cohort.cohortId()), false));
            return new Snapshot(payload.battleId(), Collections.unmodifiableMap(map), payload.battlePhase(),
                    payload.deploymentTicksRemaining(), 0, payload.result(), payload.controlsAttacker(), payload.controlsDefender(), payload.mapOriginX(), payload.mapOriginZ());
        }
        public Snapshot advanceTick() {
            return new Snapshot(battleId, cohorts, battlePhase, deploymentTicksRemaining, Math.min(5, ticksSinceLastPacket + 1), result, controlsAttacker, controlsDefender, mapOriginX, mapOriginZ);
        }
        public boolean hasData() { return battleId != null; }
        public boolean isDeploymentPhase() { return "DEPLOYMENT".equals(battlePhase) && !hasEnded(); }
        public boolean hasEnded() { return !"ONGOING".equals(result); }
        public boolean canControl(CohortRenderState cohort) {
            return cohort != null && !hasEnded() && (cohort.isAttacker ? controlsAttacker : controlsDefender);
        }
        public double getRenderX(UUID id, float partialTick) {
            CohortRenderState cohort = cohorts.get(id);
            if (cohort == null) return 0;
            double t = Math.clamp((ticksSinceLastPacket + partialTick) / 5.0, 0, 1);
            return cohort.prevX + (cohort.currentX - cohort.prevX) * t;
        }
        public double getRenderZ(UUID id, float partialTick) {
            CohortRenderState cohort = cohorts.get(id);
            if (cohort == null) return 0;
            double t = Math.clamp((ticksSinceLastPacket + partialTick) / 5.0, 0, 1);
            return cohort.prevZ + (cohort.currentZ - cohort.prevZ) * t;
        }
    }
    public static final class CohortRenderState {
        public final UUID cohortId;
        public final String type;
        public final boolean isAttacker, isRouting, isEngaged, isWithdrawing, escaped;
        public final double currentX, currentZ, prevX, prevZ, speed;
        public final int currentHealth, maxHealth, morale, strength, endurance;
        private CohortRenderState(BattleSyncPayload.CohortSnapshot cohort, CohortRenderState previous, boolean attacker) {
            cohortId = cohort.cohortId(); type = cohort.type(); isAttacker = attacker; isRouting = cohort.isRouting();
            isEngaged = cohort.isEngaged(); isWithdrawing = cohort.isWithdrawing(); escaped = cohort.escaped();
            currentX = cohort.x(); currentZ = cohort.z(); prevX = previous == null ? currentX : previous.currentX;
            prevZ = previous == null ? currentZ : previous.currentZ; currentHealth = cohort.currentHealth();
            maxHealth = cohort.maxHealth(); morale = cohort.morale(); strength = cohort.strength();
            endurance = cohort.endurance(); speed = cohort.speed();
        }
        static CohortRenderState from(BattleSyncPayload.CohortSnapshot cohort, CohortRenderState previous, boolean attacker) {
            return new CohortRenderState(cohort, previous, attacker);
        }
    }
}
