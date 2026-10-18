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

//server to client packet for sending map data to the client, including chunk ownership, settlement information, and breach alerts
public record MapDataPayload( //weird ai formatting, i like it tho
        UUID viewerStateId,
        String viewerStateName,
        List<MapChunkData> chunks,
        List<StateSummary> states,
        List<SettlementSummary> settlements,
        List<BreachAlert> breachAlerts,
        List<RoadSummary> roads,
        List<CampSummary> camps,
        boolean logisticsTruncated
) implements CustomPacketPayload {
    //method to register the packet type with the network system
    public static final Type<MapDataPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "map_data")
    );

    //stream codec for serializing and deserializing the packet data
    public static final StreamCodec<RegistryFriendlyByteBuf, MapDataPayload> STREAM_CODEC = StreamCodec.ofMember(
            MapDataPayload::write,
            MapDataPayload::new
    );

    //constructor
    public MapDataPayload {
        viewerStateName = viewerStateName == null ? "" : viewerStateName;
        chunks = List.copyOf(chunks);
        states = List.copyOf(states);
        settlements = List.copyOf(settlements);
        breachAlerts = List.copyOf(breachAlerts);
        roads = List.copyOf(roads);
        camps = List.copyOf(camps);
        if (roads.size() > MAX_ROAD_POINTS || camps.size() > MAX_VISIBLE_CAMPS
                || roads.stream().mapToInt(road -> road.points().size()).sum() > MAX_ROAD_POINTS)
            throw new IllegalArgumentException("Too much map infrastructure geometry");
    }

    // Existing snapshots/tests can omit infrastructure while the extended codec stays versioned.
    public MapDataPayload(UUID viewerStateId, String viewerStateName, List<MapChunkData> chunks,
            List<StateSummary> states, List<SettlementSummary> settlements, List<BreachAlert> breachAlerts) {
        this(viewerStateId, viewerStateName, chunks, states, settlements, breachAlerts, List.of(), List.of(), false);
    }

    //constructor for deserializing the packet data from a buffer
    public MapDataPayload(RegistryFriendlyByteBuf buffer) {
        this(
                readNullableUuid(buffer),
                buffer.readUtf(),
                readList(buffer, MapChunkData::new),
                readList(buffer, StateSummary::new),
                readList(buffer, SettlementSummary::new),
                readList(buffer, BreachAlert::new),
                readRoads(buffer),
                readBoundedList(buffer, CampSummary::new, MAX_VISIBLE_CAMPS),
                buffer.readBoolean()
        );
    }

    //method for serializing the packet data to a buffer
    private void write(RegistryFriendlyByteBuf buffer) {
        writeNullableUuid(buffer, viewerStateId);
        buffer.writeUtf(viewerStateName);
        writeList(buffer, chunks, MapChunkData::write);
        writeList(buffer, states, StateSummary::write);
        writeList(buffer, settlements, SettlementSummary::write);
        writeList(buffer, breachAlerts, BreachAlert::write);
        writeList(buffer, roads, RoadSummary::write);
        writeList(buffer, camps, CampSummary::write);
        buffer.writeBoolean(logisticsTruncated);
    }

    //method for getting the packet type
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    //method for creating an empty packet
    public static MapDataPayload empty() {
        return new MapDataPayload(null, "", List.of(), List.of(), List.of(), List.of());
    }

    //method for creating a packet with only the viewer state information
    public record MapChunkData(
            long packedChunkPos,
            int runLength,
            UUID ownerStateId,
            String settlementId,
            boolean garrisoned,
            int settlementTier,
            boolean contested
    ) {
        public MapChunkData { 
            settlementId = settlementId == null ? "" : settlementId;
            runLength = Math.max(1, runLength);
        }

        //constructor for deserializing the chunk data from a buffer
        public MapChunkData(RegistryFriendlyByteBuf buffer) {
            this(
                    buffer.readLong(),
                    buffer.readVarInt(),
                    buffer.readUUID(),
                    buffer.readUtf(),
                    buffer.readBoolean(),
                    buffer.readVarInt(),
                    buffer.readBoolean()
            );
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(packedChunkPos);
            buffer.writeVarInt(runLength);
            buffer.writeUUID(ownerStateId);
            buffer.writeUtf(settlementId);
            buffer.writeBoolean(garrisoned);
            buffer.writeVarInt(settlementTier);
            buffer.writeBoolean(contested);
        }
    }

    //record for storing state info
    public record StateSummary(
            UUID stateId,
            String stateName,
            String tierName,
            int visibleChunkCount,
            int population,
            double treasury,
            boolean viewerState,
            boolean borderingState
    ) {
        public StateSummary {
            stateName = stateName == null ? "Unknown State" : stateName;
            tierName = tierName == null ? "UNKNOWN" : tierName;
        }

        public StateSummary(RegistryFriendlyByteBuf buffer) {
            this(
                    buffer.readUUID(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readDouble(),
                    buffer.readBoolean(),
                    buffer.readBoolean()
            );
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(stateId);
            buffer.writeUtf(stateName);
            buffer.writeUtf(tierName);
            buffer.writeVarInt(visibleChunkCount);
            buffer.writeVarInt(population);
            buffer.writeDouble(treasury);
            buffer.writeBoolean(viewerState);
            buffer.writeBoolean(borderingState);
        }
    }

    //record for storing settlement info
    public record SettlementSummary(
            String settlementId,
            UUID stateId,
            String settlementName,
            long packedCenterChunk,
            int tier,
            int population,
            int garrisonCapacity,
            boolean capital,
            boolean garrisoned
    ) {
        public SettlementSummary {
            settlementId = settlementId == null ? "" : settlementId;
            settlementName = settlementName == null ? "Unnamed Province" : settlementName;
        }

        public SettlementSummary(RegistryFriendlyByteBuf buffer) {
            this(
                    buffer.readUtf(),
                    buffer.readUUID(),
                    buffer.readUtf(),
                    buffer.readLong(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readBoolean(),
                    buffer.readBoolean()
            );
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(settlementId);
            buffer.writeUUID(stateId);
            buffer.writeUtf(settlementName);
            buffer.writeLong(packedCenterChunk);
            buffer.writeVarInt(tier);
            buffer.writeVarInt(population);
            buffer.writeVarInt(garrisonCapacity);
            buffer.writeBoolean(capital);
            buffer.writeBoolean(garrisoned);
        }
    }

    //record for storing border breached info
    public record BreachAlert(
            long packedChunkPos,
            UUID defenderStateId,
            UUID attackerStateId,
            long gameTime
    ) {
        public BreachAlert(RegistryFriendlyByteBuf buffer) {
            this(buffer.readLong(), buffer.readUUID(), buffer.readUUID(), buffer.readLong());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(packedChunkPos);
            buffer.writeUUID(defenderStateId);
            buffer.writeUUID(attackerStateId);
            buffer.writeLong(gameTime);
        }
    }

    public static final int MAX_ROAD_POINTS = 8192;
    public static final int MAX_VISIBLE_CAMPS = 2048;

    private static List<RoadSummary> readRoads(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ROAD_POINTS) throw new IllegalArgumentException("Invalid road count");
        List<RoadSummary> roads = new ArrayList<>(count);
        int points = 0;
        for (int i = 0; i < count; i++) {
            RoadSummary road = new RoadSummary(buffer);
            points += road.points().size();
            if (points > MAX_ROAD_POINTS) throw new IllegalArgumentException("Too many road points");
            roads.add(road);
        }
        return roads;
    }

    // A road can have several visible runs with the same ID. Never join across hidden or removed cells.
    public record RoadSummary(UUID id, UUID ownerId, List<BlockPos> points, boolean bridge) {
        public RoadSummary(UUID id, UUID ownerId, List<BlockPos> points) { this(id, ownerId, points, false); }
        public RoadSummary {
            points = points.stream().map(BlockPos::immutable).toList();
            if (points.size() > MAX_ROAD_POINTS) throw new IllegalArgumentException("Road geometry too large");
        }
        public RoadSummary(RegistryFriendlyByteBuf buffer) {
            this(buffer.readUUID(), buffer.readUUID(), readBoundedList(buffer, value -> value.readBlockPos(), MAX_ROAD_POINTS), buffer.readBoolean());
        }
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(id); buffer.writeUUID(ownerId);
            writeList(buffer, points, (point, b) -> b.writeBlockPos(point));
            buffer.writeBoolean(bridge);
        }
    }

    // Public markers carry no foreign stock, connection status or source information.
    public record CampSummary(UUID id, UUID ownerId, BlockPos position) {
        public CampSummary(RegistryFriendlyByteBuf buffer) { this(buffer.readUUID(), buffer.readUUID(), buffer.readBlockPos()); }
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(id); buffer.writeUUID(ownerId); buffer.writeBlockPos(position);
        }
    }

    //gets the interface
    @FunctionalInterface
    private interface Reader<T> {
        T read(RegistryFriendlyByteBuf buffer);
    }

    //global terrain constants for the map data packet, used for rendering and analysis of the terrain data
    // Shared terrain format: each sample stores map colour, signed ground height, and feature flags.
    public static final int TERRAIN_TILE_BLOCKS = 256;
    public static final int TERRAIN_KNOWN = 1;
    public static final int TERRAIN_WATER = 2;
    public static final int TERRAIN_FOREST = 4;
    public static final int TERRAIN_SNOW = 8;
    public static final int TERRAIN_SAND = 16;
    public static final int TERRAIN_STRUCTURE = 32;
    public static final int TERRAIN_LAVA = 64;

    public record TerrainKey(int tileX, int tileZ, int step) {
        public TerrainKey {
            if ((step != 1 && step != 4 && step != 16)
                    || Math.abs((long) tileX) > 117188 || Math.abs((long) tileZ) > 117188) {
                throw new IllegalArgumentException("Invalid terrain tile");
            }
        }
        // The extra row/column on each edge lets shading and contours cross tile seams.
        public int blocks() { return step == 16 ? TERRAIN_TILE_BLOCKS * 4 : TERRAIN_TILE_BLOCKS; }
        public int side() { return blocks() / step + 2; }
    }

    public record TerrainTile(TerrainKey key, long version, byte[] colors, byte[] heights, byte[] features) {
        public TerrainTile {
            int samples = key.side() * key.side();
            if (colors.length != samples || heights.length != samples * 2 || features.length != samples) {
                throw new IllegalArgumentException("Invalid terrain sample lengths");
            }
            colors = colors.clone(); heights = heights.clone(); features = features.clone();
        }
        public int heightAt(int index) {
            return (short) ((heights[index * 2] & 255) << 8 | heights[index * 2 + 1] & 255);
        }
        public boolean knownAt(int index) { return (features[index] & TERRAIN_KNOWN) != 0; }
    }

    public record TerrainPayload(UUID stateId, UUID battleId, List<TerrainTile> tiles) implements CustomPacketPayload {
        public static final int MAX_TILES = 2;
        private static final int MAX_SAMPLES = 258 * 258;
        public static final Type<TerrainPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "terrain"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TerrainPayload> STREAM_CODEC = StreamCodec.ofMember(
                TerrainPayload::write, TerrainPayload::new);

        public TerrainPayload {
            tiles = List.copyOf(tiles);
            if (tiles.size() > MAX_TILES) throw new IllegalArgumentException("Too many terrain tiles");
        }
        public TerrainPayload(RegistryFriendlyByteBuf buffer) {
            this(buffer.readUUID(), readNullableUuid(buffer), readTiles(buffer));
        }
        private static List<TerrainTile> readTiles(RegistryFriendlyByteBuf buffer) {
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_TILES) throw new IllegalArgumentException("Invalid terrain tile count");
            List<TerrainTile> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                TerrainKey key = new TerrainKey(buffer.readInt(), buffer.readInt(), buffer.readVarInt());
                result.add(new TerrainTile(key, buffer.readLong(), buffer.readByteArray(MAX_SAMPLES),
                        buffer.readByteArray(MAX_SAMPLES * 2), buffer.readByteArray(MAX_SAMPLES)));
            }
            return result;
        }
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(stateId); writeNullableUuid(buffer, battleId);
            buffer.writeVarInt(tiles.size());
            for (TerrainTile tile : tiles) {
                buffer.writeInt(tile.key().tileX()); buffer.writeInt(tile.key().tileZ());
                buffer.writeVarInt(tile.key().step()); buffer.writeLong(tile.version());
                buffer.writeByteArray(tile.colors()); buffer.writeByteArray(tile.heights());
                buffer.writeByteArray(tile.features());
            }
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    @FunctionalInterface
    private interface Writer<T> {
        void write(T value, RegistryFriendlyByteBuf buffer);
    }

    //helper methods for reading and writing lists and nullable UUIDs
    private static <T> List<T> readList(RegistryFriendlyByteBuf buffer, Reader<T> reader) {
        return readBoundedList(buffer, reader, Integer.MAX_VALUE);
    }

    private static <T> List<T> readBoundedList(RegistryFriendlyByteBuf buffer, Reader<T> reader, int maximum) {
        int size = buffer.readVarInt();
        // Every record consumes bytes. Bound allocation by the actual packet rather than
        // imposing a new territory limit that the legacy snapshot writer does not share.
        if (size < 0 || size > maximum || size > buffer.readableBytes()) throw new IllegalArgumentException("Invalid map list size");
        List<T> values = new ArrayList<>(size); //use ArrayList for better performance when the size is known
        for (int index = 0; index < size; index++) {
            values.add(reader.read(buffer));//use the reader to read each value from the buffer and add it to the list
        }
        return values;
    }

    //helper method for writing a list of values to a buffer
    private static <T> void writeList(RegistryFriendlyByteBuf buffer, List<T> values, Writer<T> writer) {
        buffer.writeVarInt(values.size());
        for (T value : values) {
            writer.write(value, buffer);
        }
    }

    //helper method for reading a nullable UUID from a buffer
    private static UUID readNullableUuid(RegistryFriendlyByteBuf buffer) {
        return buffer.readBoolean() ? buffer.readUUID() : null;
    }

    //helper method for writing a nullable UUID to a buffer
    private static void writeNullableUuid(RegistryFriendlyByteBuf buffer, UUID value) {
        buffer.writeBoolean(value != null);
        if (value != null) {
            buffer.writeUUID(value);
        }
    }
}
