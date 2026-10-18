package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

/** Positions sent by clients are hints only; the server uses each unit's saved position. */
public record ArmyActionPayload(String action, UUID targetId, BlockPos position) implements CustomPacketPayload {
    public static final Type<ArmyActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "army_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ArmyActionPayload> STREAM_CODEC = StreamCodec.ofMember(ArmyActionPayload::write, ArmyActionPayload::new);
    public ArmyActionPayload(RegistryFriendlyByteBuf buffer) { this(buffer.readUtf(32), buffer.readUUID(), buffer.readBlockPos()); }
    private void write(RegistryFriendlyByteBuf buffer) { buffer.writeUtf(action, 32); buffer.writeUUID(targetId); buffer.writeBlockPos(position); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** A server-issued proposal token; accepting it never trusts a client-supplied route. */
    public record RoutePreview(UUID proposalId, String description, boolean detour, List<List<BlockPos>> visibleRuns) implements CustomPacketPayload {
        public static final Type<RoutePreview> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "route_preview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RoutePreview> STREAM_CODEC = StreamCodec.ofMember(RoutePreview::write, RoutePreview::new);
        public RoutePreview { visibleRuns = visibleRuns.stream().map(List::copyOf).toList(); if (visibleRuns.size() > 256 || visibleRuns.stream().mapToInt(List::size).sum() > 4096) throw new IllegalArgumentException("Route preview too large"); }
        public RoutePreview(UUID id, String description, boolean detour) { this(id, description, detour, List.of()); }
        public RoutePreview(RegistryFriendlyByteBuf buffer) { this(buffer.readUUID(), buffer.readUtf(1024), buffer.readBoolean(), readRuns(buffer)); }
        private static List<List<BlockPos>> readRuns(RegistryFriendlyByteBuf buffer) {
            int count = buffer.readVarInt(), total = 0;
            if (count < 0 || count > 256) throw new IllegalArgumentException("Invalid route count");
            List<List<BlockPos>> runs = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int size = buffer.readVarInt(); total += size;
                if (size < 0 || total > 4096) throw new IllegalArgumentException("Invalid route size");
                List<BlockPos> points = new ArrayList<>(); for (int n = 0; n < size; n++) points.add(buffer.readBlockPos()); runs.add(points);
            }
            return runs;
        }
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(proposalId); buffer.writeUtf(description, 1024); buffer.writeBoolean(detour); buffer.writeVarInt(visibleRuns.size());
            for (List<BlockPos> run : visibleRuns) { buffer.writeVarInt(run.size()); run.forEach(buffer::writeBlockPos); }
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
