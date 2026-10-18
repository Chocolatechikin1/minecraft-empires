package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

//army and legion metadata data container network class. server sends payloads to client
//sends the current location, stats, and waypoints
// 3 lists: free legion bodies, active armies, and independently located cohorts
public record ArmyMapPayload(List<LegionSummary> legions, List<ArmySummary> armies, List<CohortSummary> cohorts, List<BattleSummary> battles) implements CustomPacketPayload {
    public ArmyMapPayload(List<LegionSummary> legions, List<ArmySummary> armies) { this(legions, armies, List.of(), List.of()); }
    public ArmyMapPayload(List<LegionSummary> legions, List<ArmySummary> armies, List<CohortSummary> cohorts) { this(legions, armies, cohorts, List.of()); }
    /** Only battles the requesting player may open are included, independently of map visibility. */
    public record BattleSummary(UUID battleId, String title, BlockPos position, String phase, int attackers, int defenders) {
        BattleSummary(RegistryFriendlyByteBuf b) { this(b.readUUID(), b.readUtf(160), b.readBlockPos(), b.readUtf(24), b.readVarInt(), b.readVarInt()); }
        void write(RegistryFriendlyByteBuf b) { b.writeUUID(battleId); b.writeUtf(title, 160); b.writeBlockPos(position); b.writeUtf(phase, 24); b.writeVarInt(attackers); b.writeVarInt(defenders); }
    }
    public static final Type<ArmyMapPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "army_map")); //initialize each packet with a UUID, allowing packet identification
    public static final StreamCodec<RegistryFriendlyByteBuf, ArmyMapPayload> STREAM_CODEC = StreamCodec.ofMember(ArmyMapPayload::write, ArmyMapPayload::new); //define the codec for serializing and deserializing the packet data

    public record LegionSummary(
            UUID legionId,
            UUID ownerStateId,
            long packedChunkPos,
            int availableSoldiers,  // soldiers physically present in the free legion body
            int averageMorale,       // average morale of that field body
            List<BlockPos> waypoints
    ) {
        public LegionSummary { waypoints = checkedWaypoints(waypoints); }
        public LegionSummary(UUID id, UUID owner, long chunk, int soldiers, int morale) { this(id, owner, chunk, soldiers, morale, List.of()); }
        public LegionSummary(RegistryFriendlyByteBuf buffer) {
            this(buffer.readUUID(), buffer.readUUID(), buffer.readLong(), buffer.readVarInt(), buffer.readVarInt(), readPath(buffer));
        }

        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(legionId);
            buffer.writeUUID(ownerStateId);
            buffer.writeLong(packedChunkPos);
            buffer.writeVarInt(availableSoldiers);
            buffer.writeVarInt(averageMorale);
            writePath(buffer, waypoints);
        }
    }

    public record ArmySummary(
            UUID armyId,
            UUID ownerStateId,
            long packedChunkPos,
            List<BlockPos> waypoints,
            String displayName,     // e.g. "1st Army", "2nd Army" — generated server-side
            int troops,             // total soldiers (including garrisoned cohorts)
            int morale,             // average morale of non-garrisoned cohorts
            int maintenance,        // daily upkeep cost in emeralds
            boolean isEngaged,
            UUID battleId,          // null if not engaged
            boolean isOnCampaign,
            String terrainStatus
    ) {
        public ArmySummary { waypoints = checkedWaypoints(waypoints); }
        public ArmySummary(UUID id, UUID owner, long chunk, List<BlockPos> path, String name, int troops, int morale, int cost, boolean engaged, UUID battle, boolean campaign) {
            this(id, owner, chunk, path, name, troops, morale, cost, engaged, battle, campaign, "");
        }
        public ArmySummary(RegistryFriendlyByteBuf buffer) {
            this(
                    buffer.readUUID(),
                    buffer.readUUID(),
                    buffer.readLong(),
                    readWaypoints(buffer),
                    buffer.readUtf(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readBoolean(),
                    buffer.readBoolean() ? buffer.readUUID() : null,
                    buffer.readBoolean(), buffer.readUtf(64)
            );
        }

        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(armyId);
            buffer.writeUUID(ownerStateId);
            buffer.writeLong(packedChunkPos);
            buffer.writeVarInt(waypoints.size());
            for (BlockPos pos : waypoints) buffer.writeBlockPos(pos);
            buffer.writeUtf(displayName);
            buffer.writeVarInt(troops);
            buffer.writeVarInt(morale);
            buffer.writeVarInt(maintenance);
            buffer.writeBoolean(isEngaged);
            buffer.writeBoolean(battleId != null);
            if (battleId != null) buffer.writeUUID(battleId);
            buffer.writeBoolean(isOnCampaign);
            buffer.writeUtf(terrainStatus, 64);
        }

        private static List<BlockPos> readWaypoints(RegistryFriendlyByteBuf buffer) { //reads the list of waypoints from the buffer, returning a list of BlockPos objects
            return readPath(buffer);
        }
    }

    /** Independent formations keep their actual coordinates; only the owner receives marching orders. */
    public record CohortSummary(UUID cohortId, UUID ownerStateId, UUID legionId, BlockPos position,
            int soldiers, int morale, boolean retreating, List<BlockPos> waypoints, String terrainStatus) {
        public CohortSummary { position = position.immutable(); waypoints = checkedWaypoints(waypoints); }
        public CohortSummary(UUID id, UUID owner, UUID legion, BlockPos pos, int soldiers, int morale, boolean retreating, List<BlockPos> path) { this(id, owner, legion, pos, soldiers, morale, retreating, path, ""); }
        CohortSummary(RegistryFriendlyByteBuf b) {
            this(b.readUUID(), b.readUUID(), b.readUUID(), b.readBlockPos(), b.readVarInt(), b.readVarInt(), b.readBoolean(), readPath(b), b.readUtf(64));
        }
        void write(RegistryFriendlyByteBuf b) {
            b.writeUUID(cohortId); b.writeUUID(ownerStateId); b.writeUUID(legionId); b.writeBlockPos(position);
            b.writeVarInt(soldiers); b.writeVarInt(morale); b.writeBoolean(retreating); writePath(b, waypoints);
            b.writeUtf(terrainStatus, 64);
        }
    }
    private static List<BlockPos> checkedWaypoints(List<BlockPos> points) {
        if (points.size() > 128) throw new IllegalArgumentException("Too many movement waypoints");
        return List.copyOf(points);
    }
    private static List<BlockPos> readPath(RegistryFriendlyByteBuf b) {
        int count = b.readVarInt();
        if (count < 0 || count > 128 || count > b.readableBytes() / 8) throw new IllegalArgumentException("Invalid movement path");
        List<BlockPos> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) points.add(b.readBlockPos());
        return points;
    }
    private static void writePath(RegistryFriendlyByteBuf b, List<BlockPos> points) {
        b.writeVarInt(points.size()); for (BlockPos point : points) b.writeBlockPos(point);
    }
    private static int readCount(RegistryFriendlyByteBuf b) {
        int count = b.readVarInt();
        if (count < 0 || count > b.readableBytes()) throw new IllegalArgumentException("Invalid unit list size");
        return count;
    }

    public ArmyMapPayload {
        legions = List.copyOf(legions);
        armies  = List.copyOf(armies);
        cohorts = List.copyOf(cohorts);
        battles = List.copyOf(battles);
        if (battles.size() > 1024) throw new IllegalArgumentException("Too many battles");
    }

    public ArmyMapPayload(RegistryFriendlyByteBuf buffer) {
        this(readLegionList(buffer), readArmyList(buffer), readCohortList(buffer), readBattleList(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(legions.size());
        for (LegionSummary s : legions) s.write(buffer);

        buffer.writeVarInt(armies.size());
        for (ArmySummary s : armies) s.write(buffer);
        buffer.writeVarInt(cohorts.size()); for (CohortSummary s : cohorts) s.write(buffer);
        buffer.writeVarInt(battles.size()); for (BattleSummary s : battles) s.write(buffer);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static ArmyMapPayload empty() {
        return new ArmyMapPayload(List.of(), List.of());
    }
    private static List<BattleSummary> readBattleList(RegistryFriendlyByteBuf buffer) {
        int size = readCount(buffer); if (size > 1024) throw new IllegalArgumentException("Invalid battle list size");
        List<BattleSummary> list = new ArrayList<>(size); for (int i = 0; i < size; i++) list.add(new BattleSummary(buffer)); return list;
    }

    private static List<LegionSummary> readLegionList(RegistryFriendlyByteBuf buffer) {
        int size = readCount(buffer);
        List<LegionSummary> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) list.add(new LegionSummary(buffer));
        return list;
    }

    private static List<ArmySummary> readArmyList(RegistryFriendlyByteBuf buffer) {
        int size = readCount(buffer);
        List<ArmySummary> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) list.add(new ArmySummary(buffer));
        return list;
    }
    private static List<CohortSummary> readCohortList(RegistryFriendlyByteBuf buffer) {
        int size = readCount(buffer);
        List<CohortSummary> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) list.add(new CohortSummary(buffer));
        return list;
    }
}
