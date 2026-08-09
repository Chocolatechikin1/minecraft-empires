package com.devc.minecraftempires.client.map;

import com.devc.minecraftempires.network.packet.BattleSyncPayload;

import java.util.*;

/**
 * Client-side cache for the battle the player is currently spectating.
 *
 * Thread-safety: updated from the networking thread via accept(), read from the render thread.
 * The snapshot is swapped atomically via volatile reference, so no explicit locking is needed
 * as long as individual fields are not mutated across threads.
 *
 * Lerp:
 *  Each CohortRenderState stores both the previous and current server positions.
 *  BattleGridWidget calls getRenderX/Z(cohortId, partialTick) to get the smoothly
 *  interpolated screen position at 60+ FPS even though the server only updates every 5 ticks.
 */
//client facing cache class for the battle (seems to be specating only, may need to adjust)
//stores the current state of the battle, including troop positions, health, morale, and routing status
//utilizes thread safety via accept(), snapshot is swapped atomically via volatile reference, so no explicit locking is needed as long as individual fields are not mutated across threads
//utilizes linear interpolation (lerp) to smoothly interpolate troop positions between server updates, allowing for smooth rendering of troop movements on the client side
public final class ClientBattleData {
    private static volatile Snapshot snapshot = Snapshot.empty();

    private ClientBattleData() {}

    public static Snapshot get() { return snapshot; }
    //battle updater function, called from ClientNetworking when a BattleSyncPayload arrives, preserves previous positions for client-side linear interpolation (lerp)
    public static void accept(BattleSyncPayload payload) {
        Snapshot prev = snapshot;
        snapshot = Snapshot.from(payload, prev);
    }

    public static void advanceTick() {
        snapshot = snapshot.advanceTick();
    }

    public static void clear() {
        snapshot = Snapshot.empty();
    }

    public record Snapshot(UUID battleId, Map<UUID, CohortRenderState> cohorts, String battlePhase, int deploymentTicksRemaining, int ticksSinceLastPacket) {
        static Snapshot empty() {
            return new Snapshot(null, Map.of(), "DEPLOYMENT", 0, 0);
        }

        //if no previous snapshot is available, create a new snapshot from the given payload with no previous positions for lerp
        static Snapshot from(BattleSyncPayload payload, Snapshot prev) {
            Map<UUID, CohortRenderState> map = new LinkedHashMap<>();

            for (BattleSyncPayload.CohortSnapshot s : payload.attackerCohorts()) {
                CohortRenderState old = prev.cohorts.get(s.cohortId());
                map.put(s.cohortId(), CohortRenderState.from(s, old, true));
            }
            for (BattleSyncPayload.CohortSnapshot s : payload.defenderCohorts()) {
                CohortRenderState old = prev.cohorts.get(s.cohortId());
                map.put(s.cohortId(), CohortRenderState.from(s, old, false));
            }

            return new Snapshot(payload.battleId(), Collections.unmodifiableMap(map), payload.battlePhase(), payload.deploymentTicksRemaining(), 0);
        }

        //called each client tick to advance the snapshot's tick counter, used for lerp calculations
        public Snapshot advanceTick() {
            return new Snapshot(battleId, cohorts, battlePhase, deploymentTicksRemaining, ticksSinceLastPacket + 1);
        }

        public boolean hasData() { return battleId != null && !cohorts.isEmpty(); }

        public boolean isDeploymentPhase() { return "DEPLOYMENT".equals(battlePhase); }

       //x coordinate lerp function, returns the interpolated X coordinate for a cohort at a given partialTick (0.0 – 1.0)
        public double getRenderX(UUID cohortId, float partialTick) {
            CohortRenderState state = cohorts.get(cohortId);
            if (state == null) return 0;
            //lerp spans the full 5-tick server update window for smooth 60fps rendering.
            float t = (ticksSinceLastPacket + partialTick) / 5.0f;
            return state.prevX + (state.currentX - state.prevX) * t;
        }

        //z coordinate lerp function, returns the interpolated Z coordinate for a cohort at a given partialTick (0.0 – 1.0)
        public double getRenderZ(UUID cohortId, float partialTick) {
            CohortRenderState state = cohorts.get(cohortId);
            if (state == null) return 0;
            //lerp spans the full 5-tick server update window for smooth 60fps rendering.
            float t = (ticksSinceLastPacket + partialTick) / 5.0f;
            return state.prevZ + (state.currentZ - state.prevZ) * t;
        }
    }

    //cohort renger state class, stores the current and previous positions of a cohort, as well as its health, morale, and routing status
    public static final class CohortRenderState {
        public final UUID   cohortId;
        public final String type;
        public final boolean isAttacker;
        public final boolean isRouting;

        // Current position (from latest BattleSyncPayload)
        public final double currentX;
        public final double currentZ;

        // Previous position (from the tick before — used for lerp)
        public final double prevX;
        public final double prevZ;

        // Stats for panel rendering
        public final int currentHealth;
        public final int maxHealth;
        public final int morale;

        private CohortRenderState(UUID cohortId, String type, boolean isAttacker, boolean isRouting, double currentX, double currentZ, double prevX, double prevZ, int currentHealth, int maxHealth, int morale) {
            this.cohortId = cohortId;
            this.type = type;
            this.isAttacker = isAttacker;
            this.isRouting = isRouting;
            this.currentX = currentX;
            this.currentZ = currentZ;
            this.prevX = prevX;
            this.prevZ = prevZ;
            this.currentHealth = currentHealth;
            this.maxHealth = maxHealth;
            this.morale = morale;
        }

        static CohortRenderState from(BattleSyncPayload.CohortSnapshot snap, CohortRenderState previous, boolean isAttacker) {
            double prevX = previous != null ? previous.currentX : snap.x();
            double prevZ = previous != null ? previous.currentZ : snap.z();
            return new CohortRenderState(
                    snap.cohortId(), snap.type(), isAttacker, snap.isRouting(),
                    snap.x(), snap.z(),
                    prevX, prevZ,
                    snap.currentHealth(), snap.maxHealth(), snap.morale()
            );
        }
    }
}
