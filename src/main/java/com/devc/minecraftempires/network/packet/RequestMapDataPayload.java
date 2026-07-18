package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

//same map showing method, but this one only shows areas that the player has access to, and is sent from the client to the server to request map data
public record RequestMapDataPayload() implements CustomPacketPayload {
    public static final Type<RequestMapDataPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "request_map_data")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestMapDataPayload> STREAM_CODEC = StreamCodec.ofMember(
            RequestMapDataPayload::write,
            RequestMapDataPayload::new
    );

    public RequestMapDataPayload(RegistryFriendlyByteBuf buffer) {
        this();
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        // No fields are required. The server identifies the requesting player from the payload context.
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
