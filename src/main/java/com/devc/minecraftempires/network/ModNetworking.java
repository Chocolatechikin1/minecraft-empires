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
import com.devc.minecraftempires.army.LogisticsManager;
import com.devc.minecraftempires.network.packet.DisbandArmyPayload;
import com.devc.minecraftempires.network.packet.ComposeArmyPayload;
import com.devc.minecraftempires.network.packet.DispatchLegionPayload;
import com.devc.minecraftempires.network.packet.GarrisonCohortPayload;
import com.devc.minecraftempires.network.packet.AbandonSettlementPayload;
import com.devc.minecraftempires.network.packet.ManagementDataPayload;
import com.devc.minecraftempires.network.packet.StateActionPayload;
import com.devc.minecraftempires.network.packet.ArmyActionPayload;
import com.devc.minecraftempires.network.packet.OpenFoundingPayload;
import com.devc.minecraftempires.network.packet.OpenSettlementPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

//registering and handling the custom packets for requesting and sending map data between the client and server
public final class ModNetworking {
    private ModNetworking() {}

    //tracks the last time a player opened the map, done to prevent spamming
    private static final Map<UUID, Long> mapRequestCooldowns = new HashMap<>();
    private static final long MAP_REQUEST_COOLDOWN_MS = 2000L; // 2 seconds between map refreshes
    private static final Map<UUID, Long> terrainRequestCooldowns = new HashMap<>();
    private static final Map<UUID, Long> supplySelectionCooldowns = new HashMap<>();
    private static final long SUPPLY_SELECTION_COOLDOWN_MS = 200L;

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("11");
        registrar.playToServer(ArmyActionPayload.CampaignAction.TYPE, ArmyActionPayload.CampaignAction.STREAM_CODEC, ArmyManager::handleCampaignAction);
        registrar.playToClient(ArmyActionPayload.RoutePreview.TYPE, ArmyActionPayload.RoutePreview.STREAM_CODEC);
        registrar.playToClient(StateActionPayload.BridgeStatus.TYPE, StateActionPayload.BridgeStatus.STREAM_CODEC);
        // Independent packets let the client reuse terrain while army positions keep updating.
        registrar.playToClient(MapDataPayload.TerrainPayload.TYPE, MapDataPayload.TerrainPayload.STREAM_CODEC);
        registrar.playToServer(RequestMapDataPayload.RequestTerrainPayload.TYPE,
                RequestMapDataPayload.RequestTerrainPayload.STREAM_CODEC, ModNetworking::handleTerrainRequest);
        registrar.playToClient(ManagementDataPayload.TYPE, ManagementDataPayload.STREAM_CODEC);
        registrar.playToClient(OpenFoundingPayload.TYPE, OpenFoundingPayload.STREAM_CODEC);
        registrar.playToClient(OpenSettlementPayload.TYPE, OpenSettlementPayload.STREAM_CODEC);
        registrar.playToServer(StateActionPayload.TYPE, StateActionPayload.STREAM_CODEC, StateActionPayload::handle);
        registrar.playToServer(ArmyActionPayload.TYPE, ArmyActionPayload.STREAM_CODEC, ArmyManager::handleArmyAction);

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

        // ── Settlement Management ──────────────────────────────────────────────
        // Client → Server
        registrar.playToServer(
                AbandonSettlementPayload.TYPE,
                AbandonSettlementPayload.STREAM_CODEC,
                AbandonSettlementPayload::handle
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

        sendSnapshots(player);
    }

    public static void sendSnapshots(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, MapDataService.buildPayload(player));
        PacketDistributor.sendToPlayer(player, MapDataService.buildArmyPayload(player));
        PacketDistributor.sendToPlayer(player, ManagementDataService.build(player));
    }
    /** Selection is saved before this response is throttled; the next normal refresh always uses the latest target. */
    public static void sendSupplySelectionSnapshot(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long previous = supplySelectionCooldowns.get(player.getUUID());
        if (previous != null && now >= previous && now - previous < SUPPLY_SELECTION_COOLDOWN_MS) return;
        supplySelectionCooldowns.put(player.getUUID(), now);
        PacketDistributor.sendToPlayer(player, ManagementDataService.build(player));
    }
    private static void handleTerrainRequest(RequestMapDataPayload.RequestTerrainPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            long now = System.currentTimeMillis();
            Long previous = terrainRequestCooldowns.get(player.getUUID());
            if (previous != null && now - previous < 250) return;
            terrainRequestCooldowns.put(player.getUUID(), now);
            MapDataPayload.TerrainPayload response = MapDataService.buildTerrainPayload(player, payload);
            if (response != null) PacketDistributor.sendToPlayer(player, response);
        });
    }
    public static void clearPlayer(UUID id) {
        mapRequestCooldowns.remove(id); terrainRequestCooldowns.remove(id); supplySelectionCooldowns.remove(id); LogisticsManager.clearPlayer(id);
    }
    public static void clearAll() {
        mapRequestCooldowns.clear(); terrainRequestCooldowns.clear(); supplySelectionCooldowns.clear(); LogisticsManager.clearAllSelections();
    }
}
