package com.devc.minecraftempires.network;

import com.devc.minecraftempires.network.packet.ArmyMapPayload;
import com.devc.minecraftempires.network.packet.MapDataPayload;
import com.devc.minecraftempires.network.packet.RequestMapDataPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.devc.minecraftempires.network.packet.DispatchArmyPayload;
import com.devc.minecraftempires.army.ArmyManager; 
import com.devc.minecraftempires.network.packet.DisbandArmyPayload;

//registering and handling the custom packets for requesting and sending map data between the client and server
public final class ModNetworking {
    private ModNetworking() {}

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
    }

    private static void handleMapRequest(RequestMapDataPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }

        PacketDistributor.sendToPlayer(player, MapDataService.buildPayload(player));
        PacketDistributor.sendToPlayer(player, MapDataService.buildArmyPayload(player));
    }
}
