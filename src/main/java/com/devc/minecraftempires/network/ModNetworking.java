package com.devc.minecraftempires.network;

import com.devc.minecraftempires.network.packet.ArmyMapPayload;
import com.devc.minecraftempires.network.packet.BattleCommandPayload;
import com.devc.minecraftempires.network.packet.BattleSyncPayload;
import com.devc.minecraftempires.network.packet.MapDataPayload;
import com.devc.minecraftempires.network.packet.OpenBattleMapPayload;
import com.devc.minecraftempires.network.packet.RequestMapDataPayload;
import com.devc.minecraftempires.network.packet.LeaveSpectatePayload;
import com.devc.minecraftempires.network.packet.RequestSpectatePayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.devc.minecraftempires.network.packet.DispatchArmyPayload;
import com.devc.minecraftempires.army.ArmyManager; 
import com.devc.minecraftempires.network.packet.DisbandArmyPayload;
import com.devc.minecraftempires.network.packet.ComposeArmyPayload;
import com.devc.minecraftempires.network.packet.DispatchLegionPayload;
import com.devc.minecraftempires.network.packet.GarrisonCohortPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

//registering and handling the custom packets for requesting and sending map data between the client and server
public final class ModNetworking {
    private ModNetworking() {}

    //tracks the last time a player opened the map, done to prevent spamming
    private static final Map<UUID, Long> mapRequestCooldowns = new HashMap<>();
    private static final long MAP_REQUEST_COOLDOWN_MS = 2000L; // 2 seconds between map refreshes

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToServer(
                RequestMapDataPayload.TYPE,
                RequestMapDataPayload.STREAM_CODEC,
                ModNetworking::handleMapRequest
        );
        registrar.playToClient(MapDataPayload.TYPE, MapDataPayload.STREAM_CODEC);
        registrar.playToClient(ArmyMapPayload.TYPE, ArmyMapPayload.STREAM_CODEC);
        registrar.playToServer(
                DispatchArmyPayload.TYPE,
                DispatchArmyPayload.STREAM_CODEC,
                ArmyManager::handleDispatchArmy
        );
        registrar.playToServer(
                DisbandArmyPayload.TYPE,
                DisbandArmyPayload.STREAM_CODEC,
                ArmyManager::handleDisbandArmy
        );
        registrar.playToServer(
                ComposeArmyPayload.TYPE,
                ComposeArmyPayload.STREAM_CODEC,
                ArmyManager::handleComposeArmy
        );
        registrar.playToServer(
                DispatchLegionPayload.TYPE,
                DispatchLegionPayload.STREAM_CODEC,
                ArmyManager::handleDispatchLegion
        );
        registrar.playToServer(
                GarrisonCohortPayload.TYPE,
                GarrisonCohortPayload.STREAM_CODEC,
                ArmyManager::handleGarrisonCohort
        );

        // ── Battle System ─────────────────────────────────────────────────────
        // Server → Client
        registrar.playToClient(BattleSyncPayload.TYPE, BattleSyncPayload.STREAM_CODEC);
        registrar.playToClient(OpenBattleMapPayload.TYPE, OpenBattleMapPayload.STREAM_CODEC);
        // Client → Server
        registrar.playToServer(
                BattleCommandPayload.TYPE,
                BattleCommandPayload.STREAM_CODEC,
                BattleCommandPayload::handle
        );
        registrar.playToServer(
                RequestSpectatePayload.TYPE,
                RequestSpectatePayload.STREAM_CODEC,
                RequestSpectatePayload::handle
        );
        registrar.playToServer(
                LeaveSpectatePayload.TYPE,
                LeaveSpectatePayload.STREAM_CODEC,
                LeaveSpectatePayload::handle
        );
    }

    private static void handleMapRequest(RequestMapDataPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }

        //checks to see last time player requested map data, if it was less than 2 seconds ago, ignore the request
        long now = System.currentTimeMillis();
        Long lastRequest = mapRequestCooldowns.get(player.getUUID());
        if (lastRequest != null && (now - lastRequest) < MAP_REQUEST_COOLDOWN_MS) {
            return; // still within cooldown — silently drop the request
        }
        mapRequestCooldowns.put(player.getUUID(), now);

        PacketDistributor.sendToPlayer(player, MapDataService.buildPayload(player));
        PacketDistributor.sendToPlayer(player, MapDataService.buildArmyPayload(player));
    }
}
