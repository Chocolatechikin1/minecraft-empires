package com.devc.minecraftempires.network;

import com.devc.minecraftempires.network.packet.MapDataPayload;
import com.devc.minecraftempires.network.packet.RequestMapDataPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Common-side registration and server payload handlers. */
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
    }

    private static void handleMapRequest(RequestMapDataPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }

        PacketDistributor.sendToPlayer(player, MapDataService.buildPayload(player));
    }
}
