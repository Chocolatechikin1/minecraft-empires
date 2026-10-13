package com.devc.minecraftempires.network.packet;
import com.devc.minecraftempires.MinecraftEmpires;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

//packet sent to the client to open the founding screen when a player interacts with a city altar
public record OpenFoundingPayload(BlockPos altarPos) implements CustomPacketPayload {
    public static final Type<OpenFoundingPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "open_founding"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenFoundingPayload> STREAM_CODEC = StreamCodec.ofMember(OpenFoundingPayload::write, OpenFoundingPayload::new);
    public OpenFoundingPayload(RegistryFriendlyByteBuf buf) { this(buf.readBlockPos()); }
    private void write(RegistryFriendlyByteBuf buf) { buf.writeBlockPos(altarPos); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
