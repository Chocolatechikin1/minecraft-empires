package com.devc.minecraftempires.territory;

import com.devc.minecraftempires.network.packet.MapDataPayload;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Stores recent border breaches for the Phase 3 map and emits immediate chat warnings.
 */
public final class BreachAlertService {
    private static final int MAX_ALERTS_PER_LEVEL = 64;
    private static final long ALERT_LIFETIME_TICKS = 2_400L;
    private static final Map<ServerLevel, BreachHistory> ALERTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private BreachAlertService() {}

    public static void recordBreach(
            ServerLevel level,
            ChunkPos chunkPos,
            UUID defenderStateId,
            UUID attackerStateId
    ) {
        if (defenderStateId == null || attackerStateId == null || defenderStateId.equals(attackerStateId)) {
            return;
        }

        synchronized (ALERTS) {
            BreachHistory history = ALERTS.computeIfAbsent(level, ignored -> new BreachHistory());
            if (!history.record(chunkPos.pack(), defenderStateId, attackerStateId, level.getGameTime())) return;
        }

        StateManager stateManager = StateManager.get(level);
        StateData attacker = stateManager.getState(attackerStateId);
        String attackerName = attacker == null ? "Unknown State" : attacker.getStateName();

        for (ServerPlayer player : level.players()) {
            StateData playerState = stateManager.getStateByPlayer(player.getUUID());
            if (playerState != null && playerState.getStateId().equals(defenderStateId)) {
                player.sendSystemMessage(Component.translatable(
                        "message.minecraftempires.border_breach",
                        attackerName,
                        chunkPos.x(),
                        chunkPos.z()
                ));
            }
        }
    }

    public static List<MapDataPayload.BreachAlert> getVisibleRecentAlerts(
            ServerLevel level,
            UUID viewerStateId,
            Set<Long> visibleChunkPositions
    ) {
        long cutoff = level.getGameTime() - ALERT_LIFETIME_TICKS;
        List<MapDataPayload.BreachAlert> result = new ArrayList<>();

        synchronized (ALERTS) {
            BreachHistory history = ALERTS.get(level);
            if (history == null) {
                return List.of();
            }
            Deque<MapDataPayload.BreachAlert> levelAlerts = history.alerts;
            levelAlerts.removeIf(alert -> alert.gameTime() < cutoff);
            for (MapDataPayload.BreachAlert alert : levelAlerts) {
                boolean viewerInvolved = viewerStateId != null
                        && (viewerStateId.equals(alert.defenderStateId())
                        || viewerStateId.equals(alert.attackerStateId()));
                if (viewerInvolved || visibleChunkPositions.contains(alert.packedChunkPos())) {
                    result.add(alert);
                }
            }
        }

        return result;
    }

    //breach history class, stores recent breaches for a given world and allows for retrieval of alerts for players to see
    //also handles quiet periods for repeated breaches and limits the number of stored alerts to prevent memory issues
    /** Per-world bookkeeping also supports domain checks without a Minecraft server. */
    public static final class BreachHistory {
        private record Breach(long chunk, UUID defender, UUID attacker) {}
        private record Sides(UUID defender, UUID attacker) {}
        private final Deque<MapDataPayload.BreachAlert> alerts = new ArrayDeque<>();
        private final LinkedHashMap<Breach, Long> lastSeen = new LinkedHashMap<>(128, .75f, true);
        private final Map<Sides, Long> lastWarning = new LinkedHashMap<>();
        private long lastTick = Long.MIN_VALUE;

        /** Record each continuous breach once; chat combines one side's captures in the same server pass. */
        public boolean record(long chunk, UUID defender, UUID attacker, long now) {
            if (defender == null || attacker == null || defender.equals(attacker)) return false;
            if (now < lastTick) { alerts.clear(); lastSeen.clear(); lastWarning.clear(); }
            if (now != lastTick) {
                long cutoff = now - ALERT_LIFETIME_TICKS;
                lastSeen.values().removeIf(tick -> tick < cutoff);
                lastWarning.values().removeIf(tick -> tick < cutoff);
                alerts.removeIf(alert -> alert.gameTime() < cutoff);
                lastTick = now;
            }
            Long previous = lastSeen.put(new Breach(chunk, defender, attacker), now);
            // Refresh the quiet-period clock even on a repeated flip, so ongoing border churn stays silent.
            if (previous != null && now - previous <= ALERT_LIFETIME_TICKS) return false;
            while (lastSeen.size() > 16_384) lastSeen.remove(lastSeen.keySet().iterator().next());
            alerts.addFirst(new MapDataPayload.BreachAlert(chunk, defender, attacker, now));
            while (alerts.size() > MAX_ALERTS_PER_LEVEL) alerts.removeLast();
            Long last = lastWarning.put(new Sides(defender, attacker), now);
            return last == null || last != now;
        }
    }
}
