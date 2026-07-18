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
    private static final Map<ServerLevel, Deque<MapDataPayload.BreachAlert>> ALERTS =
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

        MapDataPayload.BreachAlert alert = new MapDataPayload.BreachAlert(
                chunkPos.pack(),
                defenderStateId,
                attackerStateId,
                level.getGameTime()
        );

        synchronized (ALERTS) {
            Deque<MapDataPayload.BreachAlert> levelAlerts = ALERTS.computeIfAbsent(level, ignored -> new ArrayDeque<>());
            levelAlerts.addFirst(alert);
            while (levelAlerts.size() > MAX_ALERTS_PER_LEVEL) {
                levelAlerts.removeLast();
            }
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
            Deque<MapDataPayload.BreachAlert> levelAlerts = ALERTS.get(level);
            if (levelAlerts == null) {
                return List.of();
            }

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
}
