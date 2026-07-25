package com.devc.minecraftempires.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public record DisbandArmyPayload(UUID armyId) implements CustomPacketPayload {

    // Unique identifier for this packet
    public static final Type<DisbandArmyPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minecraftempires", "disband_army"));

    // NeoForge 26.x CalVer StreamCodec
    public static final StreamCodec<FriendlyByteBuf, DisbandArmyPayload> STREAM_CODEC = StreamCodec.ofMember(
            DisbandArmyPayload::write,
            DisbandArmyPayload::new
    );

    // Decoding constructor
    public DisbandArmyPayload(FriendlyByteBuf buffer) {
        this(buffer.readUUID());
    }

    // Encoding method
    public void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(this.armyId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}