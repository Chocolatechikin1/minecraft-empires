package com.devc.minecraftempires.client;

import com.devc.minecraftempires.client.gui.screen.BattleMapScreen;
import com.devc.minecraftempires.client.map.ClientArmyData;
import com.devc.minecraftempires.client.map.ClientBattleData;
import com.devc.minecraftempires.client.map.ClientMapData;
import com.devc.minecraftempires.network.packet.ArmyMapPayload;
import com.devc.minecraftempires.network.packet.BattleSyncPayload;
import com.devc.minecraftempires.network.packet.MapDataPayload;
import com.devc.minecraftempires.network.packet.OpenBattleMapPayload;
import com.devc.minecraftempires.network.packet.LeaveSpectatePayload;
import com.devc.minecraftempires.network.packet.RequestMapDataPayload;
import com.devc.minecraftempires.network.packet.RequestSpectatePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

//Client-only network registration and map request helper
public final class ClientNetworking {
    private ClientNetworking() {}

    public static void registerPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        //handlers for the main map and army map
        event.register(MapDataPayload.TYPE, ClientNetworking::handleMapData);
        event.register(ArmyMapPayload.TYPE, ClientNetworking::handleArmyData);
        //handlers for the battle map and battle system
        event.register(BattleSyncPayload.TYPE, ClientNetworking::handleBattleSync);
        event.register(OpenBattleMapPayload.TYPE, ClientNetworking::handleOpenBattleMap);
    }

    private static void handleMapData(MapDataPayload payload, IPayloadContext context) {
        ClientMapData.accept(payload);
    }

    //receives the army snapshot from the server and stores it in ClientArmyData for the map widget
    private static void handleArmyData(ArmyMapPayload payload, IPayloadContext context) {
        ClientArmyData.accept(payload);
    }

    //updates client side battle data for the given battle, including troop counts, morale, and siege progress
    private static void handleBattleSync(BattleSyncPayload payload, IPayloadContext context) {
        ClientBattleData.accept(payload);
    }

    //function calls the client thread to open the battle map screen for the given battle, passing in the attacker and defender army IDs
    private static void handleOpenBattleMap(OpenBattleMapPayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() -> { //executes a client thread to host the battle
            Minecraft mc = Minecraft.getInstance(); //get the instance of the Minecraft client
            if (mc.player != null) { //creates a new battle map screen and sets it as the current screen for the player
                if (mc.gui.screen() instanceof BattleMapScreen) return; // Break the infinite open loop
                ClientBattleData.clear(); //clear any stale data
                // Note: mc.gui.setScreen() is the correct API for MC 26.2 (screen management moved to the Gui class).
                // mc.setScreen() was tested and caused issues in this version — do not switch back.
                mc.gui.setScreen(new BattleMapScreen(payload.battleId(),payload.attackerArmyId(),payload.defenderArmyId()));
                // Note: requestSpectate is also sent by BattleMapScreen.init() — this call fires it a second time.
                // Low priority: the duplicate is harmless but can be removed if the server starts logging double-spectate warnings.
                requestSpectate(payload.battleId());
            }
        });
    }

    //request map data from the server
    public static void requestMapData() {
        ClientPacketDistributor.sendToServer(new RequestMapDataPayload());
    }

    //getter for spectating
    public static void requestSpectate(UUID battleId) {
        ClientPacketDistributor.sendToServer(new RequestSpectatePayload(battleId));
    }

    //notifies the server that the player has closed the battle map screen
    public static void leaveSpectate(UUID battleId) {
        ClientPacketDistributor.sendToServer(new LeaveSpectatePayload(battleId));
    }
}

