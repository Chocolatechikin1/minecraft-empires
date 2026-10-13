package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.UUID;

//packet sent to the client to open the settlement management screen when a player interacts with a city altar
public record OpenSettlementPayload(UUID settlementId, String name, BlockPos position) implements CustomPacketPayload {
    public static final Type<OpenSettlementPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "open_settlement"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenSettlementPayload> STREAM_CODEC = StreamCodec.ofMember(OpenSettlementPayload::write, OpenSettlementPayload::new);
    
    //constructor for reading from the network buffer
    private OpenSettlementPayload(RegistryFriendlyByteBuf b){ 
        this(b.readUUID(), b.readUtf(80), b.readBlockPos()); 
    }

    //constructor for writing to the network buffer
    private void write(RegistryFriendlyByteBuf b){ 
        b.writeUUID(settlementId); b.writeUtf(name, 80); b.writeBlockPos(position);
    }

    @Override public Type<? extends CustomPacketPayload> type(){ 
        return TYPE; 
    }
}
