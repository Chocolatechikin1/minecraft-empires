package com.devc.minecraftempires.client;

import com.devc.minecraftempires.client.map.ClientMapData;
import com.devc.minecraftempires.network.packet.MapDataPayload;
import com.devc.minecraftempires.network.packet.RequestMapDataPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client-only network registration and map request helper. */
public final class ClientNetworking {
    private ClientNetworking() {}

    public static void registerPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(MapDataPayload.TYPE, ClientNetworking::handleMapData);
    }

    private static void handleMapData(MapDataPayload payload, IPayloadContext context) {
        ClientMapData.accept(payload);
    }

    public static void requestMapData() {
        ClientPacketDistributor.sendToServer(new RequestMapDataPayload());
    }
}
