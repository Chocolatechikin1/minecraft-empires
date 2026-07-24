package com.devc.minecraftempires.client;

import com.devc.minecraftempires.client.map.ClientArmyData;
import com.devc.minecraftempires.client.map.ClientMapData;
import com.devc.minecraftempires.network.packet.ArmyMapPayload;
import com.devc.minecraftempires.network.packet.MapDataPayload;
import com.devc.minecraftempires.network.packet.RequestMapDataPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

//Client-only network registration and map request helper
public final class ClientNetworking {
    private ClientNetworking() {}

    public static void registerPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(MapDataPayload.TYPE, ClientNetworking::handleMapData);
        event.register(ArmyMapPayload.TYPE, ClientNetworking::handleArmyData);
    }

    private static void handleMapData(MapDataPayload payload, IPayloadContext context) {
        ClientMapData.accept(payload);
    }

    //receives the army snapshot from the server and stores it in ClientArmyData for the map widget
    private static void handleArmyData(ArmyMapPayload payload, IPayloadContext context) {
        ClientArmyData.accept(payload);
    }

    public static void requestMapData() {
        ClientPacketDistributor.sendToServer(new RequestMapDataPayload());
    }
}
