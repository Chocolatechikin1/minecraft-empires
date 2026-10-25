package com.devc.minecraftempires.army;

import com.devc.minecraftempires.network.MapDataService;
import com.devc.minecraftempires.state.StateBalance;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateManager;
import com.devc.minecraftempires.territory.ChunkData;
import com.devc.minecraftempires.territory.ClaimManager;
import com.devc.minecraftempires.territory.SettlementData;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;
import java.util.function.LongPredicate;

//logistics manager file
//handles the logistics of the game, including roads, camps, and supply routes for armies and cohorts
//does not manage cohort supplies
public final class LogisticsManager extends SavedData {
    private static final Codec<LogisticsManager> CODEC = CompoundTag.CODEC.xmap(LogisticsManager::fromTag, LogisticsManager::toTag);
    public static final SavedDataType<LogisticsManager> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("minecraftempires", "logistics"), LogisticsManager::new, CODEC, DataFixTypes.LEVEL);
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int MAX_ROUTE_DISPLAY = 2048;
    private static final int SEARCH_WORK_PER_TICK = 4096;
    private static final int MAX_SEARCH_CELLS = 300_000;
    private static final Map<UUID, UUID> SELECTIONS = new HashMap<>();

    private final Map<UUID, Road> roads = new LinkedHashMap<>();
    private final Map<UUID, Camp> camps = new LinkedHashMap<>();
    private final Map<UUID, Bridge> bridges = new LinkedHashMap<>();
    private final Map<Long, Bridge> bridgeColumns = new HashMap<>();
    private final Map<UUID, BridgePlan> bridgePlans = new LinkedHashMap<>();
    private static final class BridgePlan {
        final UUID id, owner;
        final List<BlockPos> points;
        MapDataService.TerrainInspection inspection;
        boolean purchaseRequested;
        long expires;
        String phase = "CHECKING", message = "Checking both banks and the full three-block crossing. Your points are saved.";
        BridgePlan(UUID id, UUID owner, List<BlockPos> points, ServerLevel level) {
            this.id = id; this.owner = owner; this.points = List.copyOf(points);
            inspection = new MapDataService.TerrainInspection(level, List.of(points)); expires = level.getGameTime() + 2400;
        }
    }
    private record BridgeResult(String phase, String message) {}
    private final Set<Long> roadCells = new HashSet<>();
    private final Map<UUID, Long> activeClocks = new HashMap<>();
    private final Map<UUID, Network> networks = new HashMap<>();
    private final Map<UUID, String> previousWarnings = new HashMap<>();
    private final Map<UUID, Boolean> adequatelySupplied = new HashMap<>();
    private long lastGameTime = -1;
    private long infrastructureRevision;
    private long inspectionTick = Long.MIN_VALUE;
    private final Map<String, SupplyResult> terrainSupplyQueries = new HashMap<>();
    private long terrainSupplyTick = Long.MIN_VALUE;
    private record SupplySource(UUID id, String name, BlockPos position, List<BlockPos> path, boolean camp) {}
    private record SupplySources(List<SupplySource> values, boolean pending) {}
    private final Map<UUID, SupplySources> terrainSources = new HashMap<>();
    private record DeliveryKey(UUID owner, BlockPos start, BlockPos end, double maximum, boolean local, long rights, int enemies) {}
    private static final class DeliveryCheck {
        final DeliveryKey key;
        final List<BlockPos> path, enemies;
        int cursor = 1;
        double cost, delivery;
        boolean finalLeg;
        long requested, completed = Long.MIN_VALUE;
        MapDataService.Verification status = MapDataService.Verification.PENDING;
        DeliveryCheck(DeliveryKey key, List<BlockPos> path, List<BlockPos> enemies, long time) { this.key = key; this.path = path; this.enemies = enemies; requested = time; }
        MapDataService.TerrainRoute result() { return new MapDataService.TerrainRoute(status, status == MapDataService.Verification.VERIFIED ? path : List.of(), cost); }
    }
    private final Map<DeliveryKey, DeliveryCheck> deliveries = new LinkedHashMap<>();
    public void invalidateTerrainDelivery() { deliveries.clear(); terrainSupplyQueries.clear(); terrainSources.clear(); }
    public boolean hasRoadAt(int x, int z) { return roadCells.contains(ChunkPos.pack(x >> 4, z >> 4)); }
    private final Map<Long, Boolean> usableRoads = new HashMap<>();
    private long roadSampleTick = Long.MIN_VALUE, roadSampleRevision = Long.MIN_VALUE;
    public boolean usableRoadAt(int x, int z, ServerLevel level) {
        long cell = ChunkPos.pack(x >> 4, z >> 4);
        if (!roadCells.contains(cell)) return false;
        long revision = MapDataService.terrainRevision(level) + infrastructureRevision;
        if (roadSampleTick != level.getGameTime() || roadSampleRevision != revision) { usableRoads.clear(); roadSampleTick = level.getGameTime(); roadSampleRevision = revision; }
        return usableRoads.computeIfAbsent(cell, ignored -> {
            BlockPos position = center(cell);
            if (!MapDataService.surface(level, position).usable()) return false;
            ChunkPos chunk = ChunkPos.unpack(cell);
            for (int[] direction : DIRECTIONS) {
                long adjacent = ChunkPos.pack(chunk.x() + direction[0], chunk.z() + direction[1]);
                if (!roadCells.contains(adjacent)) continue;
                BlockPos next = center(adjacent);
                if (MapDataService.verifySegment(level, position.getX() + .5, position.getZ() + .5, next.getX() + .5, next.getZ() + .5) != MapDataService.Verification.VERIFIED) return false;
            }
            return true;
        });
    }

    public static LogisticsManager get(Level level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    //road record
    public record Road(UUID id, UUID ownerId, List<BlockPos> points, List<BlockPos> controlPoints, Set<Long> cells) {
        public Road {
            points = List.copyOf(points); controlPoints = List.copyOf(controlPoints); cells = Set.copyOf(cells);
        }
    }

    /** Bank centers, deck elevation and width remain usable by later physical construction. */
    public record Bridge(UUID id, UUID ownerId, BlockPos start, BlockPos end, int width) {
        public Bridge {
            start = start.immutable(); end = end.immutable();
            int length = Math.abs(start.getX() - end.getX()) + Math.abs(start.getZ() - end.getZ());
            if (width != 3 || length < 2 || length > 64 || start.getX() != end.getX() && start.getZ() != end.getZ()
                    || Math.abs(start.getY() - end.getY()) > 1) throw new IllegalArgumentException("Choose a straight bridge of 2–64 blocks with level banks and a three-block width.");
        }
        public int length() { return Math.abs(start.getX() - end.getX()) + Math.abs(start.getZ() - end.getZ()); }
        public long cost() { return length() * 10L; }
        public double progress(double x, double z) {
            double dx = end.getX() - start.getX(), dz = end.getZ() - start.getZ();
            return Math.clamp(((x - start.getX() - .5) * dx + (z - start.getZ() - .5) * dz) / (dx * dx + dz * dz), 0, 1);
        }
        public List<BlockPos> points() {
            List<BlockPos> result = new ArrayList<>();
            int dx = Integer.signum(end.getX() - start.getX()), dz = Integer.signum(end.getZ() - start.getZ());
            for (int i = 0; i <= length(); i++) result.add(start.offset(dx * i, 0, dz * i));
            return List.copyOf(result);
        }
        public BlockPos nearestBank(double progress) { return progress <= .5 ? start : end; }
    }

    public static int collapseLoss(int remainingHealth, double progress) {
        if (remainingHealth <= 0) return 0;
        double fraction = progress >= .25 && progress <= .75 ? .5 : .25;
        return Math.min(remainingHealth, Math.max(1, (int) Math.ceil(remainingHealth * fraction)));
    }

    public Collection<Bridge> getBridges() { return Collections.unmodifiableCollection(bridges.values()); }
    public Bridge bridgeAt(int x, int z) { return bridgeColumns.get(BlockPos.asLong(x, 0, z)); }
    private void rebuildBridgeColumns() {
        bridgeColumns.clear();
        for (Bridge bridge : bridges.values()) {
            int sx = Integer.signum(bridge.end.getZ() - bridge.start.getZ()), sz = Integer.signum(bridge.start.getX() - bridge.end.getX());
            for (BlockPos point : bridge.points()) for (int side = -1; side <= 1; side++)
                bridgeColumns.put(BlockPos.asLong(point.getX() + sx * side, 0, point.getZ() + sz * side), bridge);
        }
    }

    //bridge construction function: checks the proposed bridge points for validity, and if valid, either returns a ready status or completes the purchase and builds the bridge
    private BridgeResult buildBridge(ServerLevel level, StateData state, List<BlockPos> points, MapDataService.TerrainInspection inspection, boolean purchase) {
        if (points.size() != 2) return bridgeFailure("Choose two bank positions for the bridge.");
        var readiness = inspection.status(level);
        if (readiness == MapDataService.Verification.PENDING) return new BridgeResult("CHECKING", "Checking both banks and the full three-block crossing. Your points are saved.");
        if (readiness != MapDataService.Verification.VERIFIED) return bridgeFailure("The crossing includes terrain that cannot currently be inspected. Visit the area, then retry these saved points.");
        var first = inspection.baseSurface(level, points.getFirst());
        var last = inspection.baseSurface(level, points.getLast());
        if (!first.dry() || !last.dry()) return bridgeFailure("Both bridge approaches need usable dry ground.");
        Bridge bridge;
        try { bridge = new Bridge(UUID.randomUUID(), state.getStateId(),
                new BlockPos(points.getFirst().getX(), (int) Math.floor(first.height()), points.getFirst().getZ()),
                new BlockPos(points.getLast().getX(), (int) Math.floor(last.height()), points.getLast().getZ()), 3); }
        catch (IllegalArgumentException invalid) { return bridgeFailure(invalid.getMessage()); }
        ClaimManager claims = ClaimManager.get(level);
        ChunkData bank = claims.getClaim(ChunkPos.containing(bridge.start));
        if (bank == null || !state.getStateId().equals(bank.getOwnerUUID())) return bridgeFailure("Start the bridge on your own bank.");
        int sx = Integer.signum(bridge.end.getZ() - bridge.start.getZ()), sz = Integer.signum(bridge.start.getX() - bridge.end.getX());
        boolean water = false;
        for (BlockPos center : bridge.points()) for (int side = -1; side <= 1; side++) {
            BlockPos point = center.offset(sx * side, 0, sz * side);
            UUID owner = claims.getStrategicOwner(ChunkPos.containing(point));
            if (!level.getWorldBorder().isWithinBounds(point) || owner != null && !state.getStateId().equals(owner))
                return bridgeFailure("The deck and both approaches cannot occupy another state's territory.");
            if (bridgeAt(point.getX(), point.getZ()) != null) return bridgeFailure("A bridge already occupies part of this crossing.");
            var ground = inspection.baseSurface(level, point);
            if (ground.ground() == MapDataService.Ground.UNKNOWN) return bridgeFailure("Part of the crossing could not be inspected. Retry these saved points.");
            boolean approach = center.equals(bridge.start) || center.getX() == bridge.end.getX() && center.getZ() == bridge.end.getZ();
            if (approach && (!ground.dry() || Math.abs(ground.height() - (center.equals(bridge.start) ? first.height() : last.height())) > .5))
                return bridgeFailure("Both banks need three blocks of clear, level approach space.");
            if (!approach && (ground.ground() == MapDataService.Ground.BLOCKED || ground.height() > Math.max(first.height(), last.height()) + .001))
                return bridgeFailure("An obstacle blocks the proposed deck.");
            water |= ground.ground() == MapDataService.Ground.DEEP || ground.ground() == MapDataService.Ground.SHALLOW;
        }
        if (!water) return bridgeFailure("Choose a crossing over water.");
        if (!purchase) return new BridgeResult("READY", "Crossing checked. Purchase this bridge for " + bridge.cost() + " emeralds.");
        if (state.getTreasuryBalance() < bridge.cost()) return bridgeFailure("This bridge costs " + bridge.cost() + " emeralds.");
        state.deductFunds(bridge.cost()); bridges.put(bridge.id, bridge); rebuildBridgeColumns();
        MapDataService.crossingsChanged(level); StateManager.get(level).setDirty(); infrastructureChanged();
        return new BridgeResult("BUILT", "Three-block-wide bridge purchased for " + bridge.cost() + " emeralds.");
    }
    private static BridgeResult bridgeFailure(String message) { return new BridgeResult("FAILED", message); }

    //bridge planning function: handles the bridge planning process, including checking the proposed points, requesting a purchase, and building the bridge if valid
    public String handleBridgePlan(String action, ServerPlayer player, StateData state, UUID id, List<BlockPos> points) {
        if (player.level() != player.level().getServer().overworld() || !player.getUUID().equals(state.getLeaderId()))
            return "Only the state leader may plan bridges in the Overworld.";
        BridgePlan plan = bridgePlans.get(player.getUUID());
        if (action.equals("CANCEL_BRIDGE")) {
            if (plan != null && plan.id.equals(id)) bridgePlans.remove(player.getUUID());
            return "";
        }
        // Bound geometry before requesting any terrain, including malicious client coordinates.
        try {
            if (points.size() != 2) throw new IllegalArgumentException("Choose two bank positions for the bridge.");
            new Bridge(id, state.getStateId(), new BlockPos(points.getFirst().getX(), 0, points.getFirst().getZ()),
                    new BlockPos(points.getLast().getX(), 0, points.getLast().getZ()), 3);
            if (points.stream().anyMatch(p -> !player.level().getWorldBorder().isWithinBounds(p))) throw new IllegalArgumentException("Choose banks within the world border.");
        } catch (IllegalArgumentException invalid) {
            sendBridgeStatus(player, id, state.getStateId(), points, "FAILED", invalid.getMessage()); return "";
        }
        boolean matching = plan != null && plan.id.equals(id) && plan.owner.equals(state.getStateId()) && plan.points.equals(points);
        if (matching && plan.phase.equals("BUILT")) {
            sendBridgeStatus(player, plan.id, plan.owner, plan.points, plan.phase, plan.message); return "";
        }
        if (matching && plan.purchaseRequested && plan.phase.equals("CHECKING") && action.equals("BUILD_BRIDGE")) {
            sendBridgeStatus(player, plan.id, plan.owner, plan.points, plan.phase, plan.message); return "";
        }
        if (action.equals("BUILD_BRIDGE") && (!matching || !plan.phase.equals("READY"))) {
            sendBridgeStatus(player, id, state.getStateId(), points, "FAILED", "Check these bridge points before purchasing."); return "";
        }
        if (!matching || plan.phase.equals("FAILED") || player.level().getGameTime() > plan.expires) {
            if (bridgePlans.size() >= 128 && !bridgePlans.containsKey(player.getUUID())) return "Too many bridge checks are active. Retry shortly.";
            plan = new BridgePlan(id, state.getStateId(), points, player.level()); bridgePlans.put(player.getUUID(), plan);
        }
        if (action.equals("BUILD_BRIDGE")) {
            // A ready preview authorizes only this exact crossing. Refresh once more, then finish this purchase over ticks.
            plan.inspection = new MapDataService.TerrainInspection(player.level(), List.of(plan.points));
            plan.purchaseRequested = true; plan.expires = player.level().getGameTime() + 2400;
        }
        if (!plan.phase.equals("BUILT")) {
            var result = buildBridge(player.level(), state, plan.points, plan.inspection, plan.purchaseRequested);
            plan.phase = result.phase; plan.message = result.message;
        }
        sendBridgeStatus(player, plan.id, plan.owner, plan.points, plan.phase, plan.message);
        return "";
    }

    //bridge status notification service
    private static void sendBridgeStatus(ServerPlayer player, UUID id, UUID owner, List<BlockPos> points, String phase, String message) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new com.devc.minecraftempires.network.packet.StateActionPayload.BridgeStatus(id, owner, points, phase, message));
    }
    private void tickBridgePlans(ServerLevel level) {
        for (var entry : new ArrayList<>(bridgePlans.entrySet())) {
            BridgePlan plan = entry.getValue(); ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            StateData state = StateManager.get(level).getState(plan.owner);
            if (player == null || player.level() != level || state == null || !player.getUUID().equals(state.getLeaderId())
                    || !plan.owner.equals(Optional.ofNullable(StateManager.get(level).getStateByPlayer(player.getUUID())).map(StateData::getStateId).orElse(null))) {
                bridgePlans.remove(entry.getKey()); continue;
            }
            if (level.getGameTime() > plan.expires) {
                if (!plan.phase.equals("BUILT")) sendBridgeStatus(player, plan.id, plan.owner, plan.points, "FAILED", "The bridge check expired. Retry uses the same saved points.");
                bridgePlans.remove(entry.getKey()); continue;
            }
            if (!plan.phase.equals("CHECKING")) continue;
            var result = buildBridge(level, state, plan.points, plan.inspection, plan.purchaseRequested);
            if (!result.phase.equals(plan.phase)) {
                plan.phase = result.phase; plan.message = result.message;
                sendBridgeStatus(player, plan.id, plan.owner, plan.points, plan.phase, plan.message);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(plan.message));
                if (plan.phase.equals("BUILT")) com.devc.minecraftempires.network.ModNetworking.sendSnapshots(player);
            }
        }
    }

    //bridge removal function: checks if the player has a valid bridge to remove, and if so, removes it from the game world and updates the state
    private String removeBridge(ServerLevel level, StateData state, UUID bridgeId) {
        Bridge bridge = bridges.get(bridgeId);
        if (bridge == null || !state.getStateId().equals(bridge.ownerId)) return "Choose a bridge controlled by your state.";
        ArmyManager armies = ArmyManager.get(level);
        boolean present = armies.getLegionsForState(state.getStateId()).stream().flatMap(l -> l.allCohorts().stream()).anyMatch(c -> {
            Army army = armies.getArmy(c.getAssignedArmyId());
            return c.isAlive() && !c.isRetreating() && !c.isGarrisoned() && (army == null || !army.isEngaged() && !army.isRetreating())
                    && (ArmyManager.near(c.getStoredPosition(), bridge.start, StateBalance.CAMP_OPERATION_RANGE)
                    || ArmyManager.near(c.getStoredPosition(), bridge.end, StateBalance.CAMP_OPERATION_RANGE));
        });
        if (!present) return "Bring an available army, legion or cohort to either bridge approach.";
        if (hostileWithin(level, state.getStateId(), bridge.start, ArmyManager.ENGAGEMENT_RANGE)
                || hostileWithin(level, state.getStateId(), bridge.end, ArmyManager.ENGAGEMENT_RANGE)) return "Nearby enemies prevent bridge dismantling.";
        bridges.remove(bridgeId); rebuildBridgeColumns(); MapDataService.crossingsChanged(level); infrastructureChanged();
        return "Bridge removed. Construction costs are not refunded.";
    }

    /** Control may change, but bridge ownership never blocks an enemy's physical crossing. */
    //bridge status function: checks the status of the bridges in the game world, and updates the state if any bridges have been captured by enemy states
    private void captureBridges(ServerLevel level) {
        StateManager states = StateManager.get(level); ClaimManager claims = ClaimManager.get(level); ArmyManager armies = ArmyManager.get(level);
        for (Bridge bridge : new ArrayList<>(bridges.values())) {
            ChunkData first = claims.getClaim(ChunkPos.containing(bridge.start)), last = claims.getClaim(ChunkPos.containing(bridge.end));
            if (first == null || last == null || !Objects.equals(first.getOwnerUUID(), last.getOwnerUUID())) continue;
            UUID owner = first.getOwnerUUID();
            if (!states.areAtWar(owner, bridge.ownerId) || !states.mayInitiateHostility(level, owner, bridge.ownerId)) continue;
            var forces = armies.getArmiesForState(owner).stream().filter(a -> a.isViable(armies) && !a.isEngaged() && !a.isRetreating()).toList();
            if (forces.stream().noneMatch(a -> ArmyManager.near(a.getStoredPosition(), bridge.start, 16))
                    || forces.stream().noneMatch(a -> ArmyManager.near(a.getStoredPosition(), bridge.end, 16))
                    || hostileWithin(level, owner, bridge.start, 16) || hostileWithin(level, owner, bridge.end, 16)) continue;
            bridges.put(bridge.id, new Bridge(bridge.id, owner, bridge.start, bridge.end, bridge.width));
            rebuildBridgeColumns(); infrastructureChanged();
        }
    }

    //camp record
    public static final class Camp {
        private final UUID id;
        private UUID ownerId;
        private final BlockPos position;
        private long stockTicks;
        public Camp(UUID id, UUID ownerId, BlockPos position) {
            this.id = id; this.ownerId = ownerId; this.position = position.immutable();
        }
        public UUID id() { return id; }
        public UUID ownerId() { return ownerId; }
        public BlockPos position() { return position; }
        public long stock() { return stockTicks / StateBalance.TICKS_PER_DAY; }
        public long stockSoldierTicks() { return stockTicks; }
    }

    //supply result record
    public record SupplyResult(String status, String reason, UUID sourceId, String sourceName, double cost,
                               List<BlockPos> route, boolean connected, UUID campId, boolean recalculating) {}
    //route record
    public record SelectedRoute(UUID targetId, List<BlockPos> points, boolean truncated) {}
    //query key record
    private record QueryKey(long chunk, int x, int z) {}
    //node record
    private record Node(long cell, double cost, UUID sourceId, String sourceName, Node previous) {}

    //network class
    /** A build is resumed over ticks instead of searching the entire state during a packet request. */
    private static final class Network {
        ClaimManager claimContext;
        StateManager stateContext;
        long fingerprint;
        long checkedTick = -1;
        long checkedRevision;
        long queryTick = -1;
        boolean complete;
        boolean truncated;
        final Map<Long, Node> best = new HashMap<>();
        final PriorityQueue<Node> frontier = new PriorityQueue<>(Comparator.comparingDouble(Node::cost));
        final Set<UUID> connectedCamps = new HashSet<>();
        final Map<QueryKey, SupplyResult> queries = new HashMap<>();
        List<BlockPos> enemies = List.of();
        Map<Long, List<BlockPos>> enemyBuckets = Map.of();
        Network previous;
    }

    public Collection<Road> getRoads() { return Collections.unmodifiableCollection(roads.values()); }
    public Collection<Camp> getCamps() { return Collections.unmodifiableCollection(camps.values()); }
    private long interactionSequence;
    public long getInteractionSequence() { return interactionSequence; }
    public CompoundTag interactionSnapshot(Set<UUID> owners) {
        CompoundTag all = toTag(), result = new CompoundTag();
        for (String key : List.of("Roads", "Camps", "Bridges")) {
            ListTag list = new ListTag();
            for (var value : all.getListOrEmpty(key)) { CompoundTag tag = (CompoundTag) value;
                if (owners.contains(UUID.fromString(tag.getString("Owner").orElseThrow()))) list.add(tag.copy()); }
            result.put(key, list);
        }
        return result;
    }
    public void applySurrender(long sequence, UUID donor, UUID recipient, CompoundTag snapshot) {
        installInteractionSnapshot(sequence, donor, recipient, snapshot, true);
    }
    public void applyProvinceSnapshot(long sequence, UUID donor, UUID recipient, CompoundTag snapshot) {
        installInteractionSnapshot(sequence, donor, recipient, snapshot, false);
    }
    private void installInteractionSnapshot(long sequence, UUID donor, UUID recipient, CompoundTag snapshot, boolean surrender) {
        if (interactionSequence >= sequence) return;
        Set<UUID> owners = Set.of(donor, recipient); CompoundTag restored = snapshot.copy();
        for (String key : List.of("Roads", "Camps", "Bridges")) for (var value : restored.getListOrEmpty(key)) {
            CompoundTag tag = (CompoundTag) value;
            if (surrender && donor.toString().equals(tag.getString("Owner").orElse(""))) tag.putString("Owner", recipient.toString());
        }
        LogisticsManager source = fromTag(restored);
        roads.values().removeIf(r -> owners.contains(r.ownerId())); camps.values().removeIf(c -> owners.contains(c.ownerId()));
        bridges.values().removeIf(b -> owners.contains(b.ownerId()));
        roads.putAll(source.roads); camps.putAll(source.camps); bridges.putAll(source.bridges);
        bridgePlans.values().removeIf(p -> owners.contains(p.owner));
        networks.clear(); invalidateTerrainDelivery(); rebuildRoadCells(); rebuildBridgeColumns();
        interactionSequence = sequence; infrastructureChanged(); setDirty();
    }
    public static void clearPlayer(UUID playerId) { SELECTIONS.remove(playerId); }
    public static void clearAllSelections() { SELECTIONS.clear(); }

    // Clear only derived search data. Saved geometry, stock and active-time clocks are unaffected.
    private void infrastructureChanged() {
        infrastructureRevision++;
        rebuildRoadCells();
        // A deleted road discount or camp relay cannot remain usable through a previous search tree.
        networks.clear();
        terrainSupplyQueries.clear();
        terrainSources.clear();
        deliveries.clear();
        setDirty();
    }
    private void rebuildRoadCells() {
        roadCells.clear();
        roads.values().forEach(road -> roadCells.addAll(road.cells()));
    }

    //deterministic cardinal expansion function shared by purchases/removals and domain tests.
    public static List<BlockPos> expandRoad(List<BlockPos> controls) {
        if (controls == null || controls.size() < 2 || controls.size() > StateBalance.MAX_ROAD_POINTS)
            throw new IllegalArgumentException("Choose between 2 and " + StateBalance.MAX_ROAD_POINTS + " road points.");
        List<BlockPos> result = new ArrayList<>();
        for (int index = 0; index < controls.size(); index++) {
            BlockPos end = controls.get(index);
            if (Math.abs((long) end.getX()) > 29_999_984 || Math.abs((long) end.getZ()) > 29_999_984)
                throw new IllegalArgumentException("Road point is outside the world limits.");
            int tx = end.getX() >> 4, tz = end.getZ() >> 4;
            int x = result.isEmpty() ? tx : result.getLast().getX() >> 4;
            int z = result.isEmpty() ? tz : result.getLast().getZ() >> 4;
            if ((long) Math.abs(tx - x) + Math.abs(tz - z) + result.size() > StateBalance.MAX_ROAD_CHUNKS)
                throw new IllegalArgumentException("Road is too long; build it in smaller sections.");
            if (result.isEmpty()) result.add(new BlockPos(x * 16 + 8, end.getY(), z * 16 + 8));
            while (x != tx || z != tz) {
                // Interleave axes so diagonal control points form a continuous staircase rather than a long elbow.
                if (x != tx && (z == tz || Math.abs(tx - x) >= Math.abs(tz - z))) x += Integer.signum(tx - x);
                else z += Integer.signum(tz - z);
                result.add(new BlockPos(x * 16 + 8, end.getY(), z * 16 + 8));
            }
        }
        return List.copyOf(result);
    }

    //convert a list of points to a set of chunk cells for fast lookup and comparison
    private static Set<Long> cellsOf(List<BlockPos> points) {
        Set<Long> cells = new LinkedHashSet<>();
        points.forEach(point -> cells.add(ChunkPos.containing(point).pack()));
        return cells;
    }

    //road validation and construction/removal. The entire selection must be valid before any changes are made
    //validate the whole transaction before charging or changing a single road cell.
    public String changeRoad(ServerLevel level, StateData state, List<BlockPos> controls, boolean remove) {
        List<BlockPos> path;
        try { path = expandRoad(controls); }
        catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
        ClaimManager claims = ClaimManager.get(level);
        for (BlockPos point : path) {
            UUID owner = claims.getStrategicOwner(ChunkPos.containing(point));
            if (!level.getWorldBorder().isWithinBounds(point) || !state.getStateId().equals(owner))
                return "Error. The entire road selection must lie in your current territory.";
            if (!remove && !MapDataService.surface(level, point).usable())
                return "The road needs verified usable ground or a crossing. Inspect the route and retry.";
        }
        if (!remove) for (int i = 1; i < path.size(); i++) if (MapDataService.verifySegment(level, path.get(i - 1).getX() + .5, path.get(i - 1).getZ() + .5, path.get(i).getX() + .5, path.get(i).getZ() + .5) != MapDataService.Verification.VERIFIED)
            return "The road crosses blocked or unverified terrain. Inspect the full route or choose another path.";
        Set<Long> selected = cellsOf(path);
        if (remove) {
            int before = roadCells.size();
            for (Road road : new ArrayList<>(roads.values())) {
                Set<Long> remaining = new LinkedHashSet<>(road.cells());
                remaining.removeAll(selected);
                if (remaining.isEmpty()) roads.remove(road.id());
                else if (remaining.size() != road.cells().size()) roads.put(road.id(), new Road(road.id(), road.ownerId(),
                        road.points().stream().filter(p -> remaining.contains(ChunkPos.containing(p).pack())).toList(), road.controlPoints(), remaining));
            }
            infrastructureChanged();
            return (before - roadCells.size()) + " road segments removed. Note: Construction costs are not refunded.";
        }
        selected.removeAll(roadCells);
        long cost = (long) selected.size() * StateBalance.ROAD_COST_PER_CHUNK;
        if (selected.isEmpty()) return "Error. These road segments already exist. No emeralds charged.";
        if (state.getTreasuryBalance() < cost) return "Error. This road costs " + cost + " emeralds; the treasury has insufficient funds.";
        state.deductFunds(cost);
        Road road = new Road(UUID.randomUUID(), state.getStateId(), path.stream()
                .filter(p -> selected.contains(ChunkPos.containing(p).pack())).toList(), controls, selected);
        roads.put(road.id(), road);
        StateManager.get(level).setDirty();
        infrastructureChanged();
        return selected.size() + " road segments constructed for " + cost + " emeralds. Unsurveyed terrain remains unverified.";
    }

    //logistics handling function
    //handle logistics actions from the player, including building/removing roads, creating/dismantling camps, and selecting supply sources
    public String handleAction(String action, ServerPlayer player, StateData state, UUID targetId, List<BlockPos> points) {
        if (player.level() != player.level().getServer().overworld()
                || (!action.equals("SELECT_SUPPLY") && !player.getUUID().equals(state.getLeaderId())))
            return "Only the state leader may manage logistics in the Overworld.";
        ServerLevel level = player.level();
        return switch (action) {
            case "BUILD_ROAD" -> changeRoad(level, state, points, false);
            case "REMOVE_ROAD" -> changeRoad(level, state, points, true);
            case "CHECK_BRIDGE", "BUILD_BRIDGE", "CANCEL_BRIDGE" -> handleBridgePlan(action, player, state, targetId, points);
            case "REMOVE_BRIDGE" -> removeBridge(level, state, targetId);
            case "CREATE_CAMP" -> createCamp(level, state, targetId);
            case "DISMANTLE_CAMP" -> dismantleCamp(level, state, targetId);
            case "SELECT_SUPPLY" -> {
                if (targetId == null || targetId.equals(new UUID(0, 0))) { SELECTIONS.remove(player.getUUID()); yield ""; }
                Cohort cohort = selectedCohort(level, state.getStateId(), targetId);
                if (cohort == null) yield "Choose one of your living armies or cohorts.";
                SELECTIONS.put(player.getUUID(), targetId);
                yield "";
            }
            default -> "Unknown logistics action.";
        };
    }

    //camp creation function
    //create a camp at the position of the specified army, if the army is valid and the position is suitable for a camp
    private String createCamp(ServerLevel level, StateData state, UUID armyId) {
        ArmyManager armies = ArmyManager.get(level);
        Army army = armies.getArmy(armyId);
        if (army == null || !state.getStateId().equals(army.getOwningStateId()) || army.isEngaged() || army.isRetreating() || !army.isViable(armies))
            return "Select a living, unengaged army to establish a camp at its position.";
        BlockPos position = army.getStoredPosition();
        UUID owner = ClaimManager.get(level).getStrategicOwner(ChunkPos.containing(position));
        if (!state.getStateId().equals(owner) || !level.getWorldBorder().isWithinBounds(position))
            return "Error. Establish camps inside your own territory.";
        if (!MapDataService.surface(level, position).dry()) return "A camp needs verified dry ground. Inspect the site and retry.";
        if (StateManager.get(level).getAllSettlements().stream().anyMatch(s -> !state.getStateId().equals(s.getOwningStateId())
                && StateManager.isInSettlementFootprint(s, ChunkPos.containing(position))))
            return "Error. A foreign settlement's protected footprint prevents camp construction.";
        if (hostileWithin(level, state.getStateId(), position, ArmyManager.ENGAGEMENT_RANGE)) return "Warning.Hostile forces prevent camp construction.";
        if (camps.values().stream().anyMatch(c -> ArmyManager.near(position, c.position(), StateBalance.CAMP_MIN_SPACING)))
            return "Warning, another camp is too close; leave " + StateBalance.CAMP_MIN_SPACING + " blocks between camps.";
        if (camps.values().stream().filter(c -> state.getStateId().equals(c.ownerId())).count() >= StateBalance.MAX_CAMPS)
            return "This state has reached its camp limit.";
        if (state.getTreasuryBalance() < StateBalance.CAMP_COST) return "A camp costs " + StateBalance.CAMP_COST + " emeralds.";
        state.deductFunds(StateBalance.CAMP_COST);
        Camp camp = new Camp(UUID.randomUUID(), state.getStateId(), position);
        camps.put(camp.id(), camp);
        StateManager.get(level).setDirty(); infrastructureChanged();
        return "Camp established. To supply the camp, establish a supply line";
    }

    //camp dismantling function
    private String dismantleCamp(ServerLevel level, StateData state, UUID campId) {
        Camp camp = camps.get(campId);
        if (camp == null || !state.getStateId().equals(camp.ownerId())) return "Choose your camp.";
        if (!ownedCampChunk(camp, level)) return "Error. You no longer control this camp's territory.";
        boolean present = ArmyManager.get(level).getArmiesForState(state.getStateId()).stream().anyMatch(a ->
                !a.isEngaged() && !a.isRetreating() && a.isViable(ArmyManager.get(level)) && ArmyManager.near(a.getStoredPosition(), camp.position(), StateBalance.CAMP_OPERATION_RANGE));
        if (!present) return "Error. Bring an unengaged army within " + StateBalance.CAMP_OPERATION_RANGE + " blocks to dismantle the camp.";
        camps.remove(campId); infrastructureChanged();
        return "Camp dismantled";
    }

    /** Called once per second. No wall-clock catch-up; human supply pauses offline, explicit AI supply does not. */
    public void tick(ServerLevel level) {
        tickBridgePlans(level);
        captureBridges(level);
        long now = level.getGameTime();
        long elapsed = lastGameTime < 0 || now < lastGameTime ? 0 : Math.min(20, now - lastGameTime);
        lastGameTime = now;
        StateManager states = StateManager.get(level);
        ArmyManager armies = ArmyManager.get(level);
        captureAndCleanCamps(level);
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            StateData state = states.getStateByPlayer(player.getUUID());
            if (state != null) online.add(state.getStateId());
        }
        long deadline = System.nanoTime() + 250_000L;
        for (StateData state : states.getAllStates()) {
            if (state.isInitializationPending() || states.getAIWars().blocks(state.getStateId())) continue;
            Network network = ensureNetwork(state.getStateId(), level);
            advanceNetwork(network, state.getStateId(), level, deadline);
            if ((!state.isAI() && !online.contains(state.getStateId())) || elapsed <= 0) continue;
            UUID stateId = state.getStateId();
            long oldClock = activeClocks.getOrDefault(stateId, 0L);
            activeClocks.put(stateId, oldClock + elapsed);
            for (SettlementData settlement : states.settlementsFor(state)) {
                if (!settlement.isSupplyReady()) {
                    settlement.advanceSupplyPreparationTicks(elapsed); states.setDirty();
                    if (settlement.isSupplyReady()) states.markLogisticsChanged();
                }
            }
            List<Cohort> cohorts = armies.getLegionsForState(stateId).stream().flatMap(l -> l.allCohorts().stream())
                    .filter(Cohort::isAlive).sorted(Comparator.comparing(Cohort::getCohortId)).toList();
            List<Camp> availableCamps = camps.values().stream().filter(c -> stateId.equals(c.ownerId()) && ownedCampChunk(c, level))
                    .sorted(Comparator.comparing(Camp::id)).toList();
            Map<UUID, List<Camp>> eligible = new HashMap<>();
            Map<UUID, SupplyResult> results = new HashMap<>();
            for (Cohort cohort : cohorts) {
                Army army = armies.getArmy(cohort.getAssignedArmyId());
                if (army != null && army.isEngaged()) continue;
                SupplyResult result = describe(stateId, cohort, level);
                results.put(cohort.getCohortId(), result);
                if (result.connected() || result.recalculating()) continue;
                List<Camp> nearby = availableCamps.stream().filter(c -> c.stockTicks > 0 && ArmyManager.near(c.position(), cohort.getStoredPosition(), StateBalance.CAMP_SUPPLY_RANGE)
                                && localCampDelivery(stateId, c, cohort.getStoredPosition(), level).status() == MapDataService.Verification.VERIFIED)
                        .sorted(Comparator.comparingDouble(c -> distance(c.position(), cohort.getStoredPosition()))).toList();
                eligible.put(cohort.getCohortId(), nearby);
            }
            // Each camp apportions its finite stock fairly; other eligible camps can fill a partial allocation.
            Map<UUID, Long> providedByCohort = new HashMap<>();
            for (Camp camp : availableCamps) {
                long demand = 0;
                for (Cohort cohort : cohorts) if (eligible.getOrDefault(cohort.getCohortId(), List.of()).contains(camp))
                    demand += elapsed * cohort.getSoldierCount() - providedByCohort.getOrDefault(cohort.getCohortId(), 0L);
                if (demand <= 0) continue;
                long stock = camp.stockTicks;
                for (Cohort cohort : cohorts) if (eligible.getOrDefault(cohort.getCohortId(), List.of()).contains(camp)) {
                    long need = elapsed * cohort.getSoldierCount() - providedByCohort.getOrDefault(cohort.getCohortId(), 0L);
                    long allocation = stock >= demand ? need : (long) ((double) stock * need / demand);
                    allocation = Math.min(allocation, camp.stockTicks);
                    providedByCohort.merge(cohort.getCohortId(), allocation, Long::sum);
                    camp.stockTicks -= allocation;
                }
            }
            String warning = "";
            for (Cohort cohort : cohorts) {
                SupplyResult result = results.get(cohort.getCohortId());
                if (result == null || result.recalculating()) continue;
                long provided = providedByCohort.getOrDefault(cohort.getCohortId(), 0L);
                boolean adequate = result.connected() || cohort.getCarriedSupplySoldierTicks() + provided >= elapsed * cohort.getSoldierCount();
                long used = cohort.processSupply(elapsed, result.connected(), provided);
                adequatelySupplied.put(cohort.getCohortId(), adequate);
                if (!result.connected() && cohort.isSupplyExhausted() && used < elapsed * cohort.getSoldierCount()) warning = "exhausted";
                else if (!result.connected() && warning.isEmpty()) warning = "reserves";
            }
            if (oldClock / StateBalance.TICKS_PER_DAY != (oldClock + elapsed) / StateBalance.TICKS_PER_DAY)
            for (Camp camp : availableCamps) if (isCampConnected(camp.id(), level)) {
                long refill = StateBalance.TICKS_PER_DAY * StateBalance.CAMP_DAILY_REFILL;
                camp.stockTicks = Math.min(StateBalance.CAMP_STOCK_CAPACITY * StateBalance.TICKS_PER_DAY, camp.stockTicks + refill);
            }
            String previous = previousWarnings.put(stateId, warning);
            if (!warning.equals(previous) && !(previous == null && warning.isEmpty())) {
                states.notifyState(level, stateId, switch (warning) {
                    case "exhausted" -> "Some units have exhausted supply reserves. Restore a route, reach stocked camps or retreat.";
                    case "reserves" -> "Some units have lost their supply connection and are using reserves.";
                    default -> "Your units supply connections have been restored.";
                });
            }
        }
        activeClocks.keySet().removeIf(id -> states.getState(id) == null);
        networks.keySet().removeIf(id -> states.getState(id) == null);
        previousWarnings.keySet().removeIf(id -> states.getState(id) == null);
        Set<UUID> livingCohorts = new HashSet<>();
        for (Legion legion : armies.getAllLegions()) for (Cohort cohort : legion.allCohorts())
            if (cohort.isAlive()) livingCohorts.add(cohort.getCohortId());
        adequatelySupplied.keySet().retainAll(livingCohorts);
        setDirty(); armies.setDirty();
    }

    //camp capture and cleanup function
    //handles camp capture by hostile armies and removes camps whose owners no longer exist
    private void captureAndCleanCamps(ServerLevel level) {
        StateManager states = StateManager.get(level);
        ArmyManager armies = ArmyManager.get(level);
        boolean changed = camps.values().removeIf(c -> states.getState(c.ownerId()) == null);
        for (Camp camp : camps.values()) {
            UUID attacker = ClaimManager.get(level).getStrategicOwner(ChunkPos.containing(camp.position()));
            if (attacker == null || attacker.equals(camp.ownerId()) || !states.areAtWar(camp.ownerId(), attacker)) continue;
            if (!states.mayInitiateHostility(level, attacker, camp.ownerId())) continue;
            boolean present = armies.getArmiesForState(attacker).stream().anyMatch(a -> !a.isEngaged() && !a.isRetreating() && a.isViable(armies)
                    && ArmyManager.near(a.getStoredPosition(), camp.position(), StateBalance.CAMP_OPERATION_RANGE));
            if (present && !hostileWithin(level, attacker, camp.position(), ArmyManager.ENGAGEMENT_RANGE)) {
                UUID defender = camp.ownerId(); camp.ownerId = attacker; changed = true;
                states.notifyState(level, defender, "Warning. A marching camp was captured.");
                states.notifyState(level, attacker, "A marching camp and its remaining stores were captured.");
            }
        }
        if (changed) infrastructureChanged();
    }

    private static boolean ownedCampChunk(Camp camp, ServerLevel level) {
        return ownedCampChunk(camp, ClaimManager.get(level));
    }
    private static boolean ownedCampChunk(Camp camp, ClaimManager claims) {
        return camp.ownerId().equals(claims.getStrategicOwner(ChunkPos.containing(camp.position())));
    }
    private static double distance(BlockPos first, BlockPos second) {
        return Math.hypot((double) first.getX() - second.getX(), (double) first.getZ() - second.getZ());
    }

    //hostile position detection function
    //returns a list of positions of hostile cohorts for the specified state
    private static List<BlockPos> hostilePositions(ServerLevel level, UUID stateId) {
        List<BlockPos> result = new ArrayList<>();
        StateManager states = StateManager.get(level);
        ArmyManager armies = ArmyManager.get(level);
        for (Legion legion : armies.getAllLegions()) if (states.areAtWar(stateId, legion.getOwningStateId()))
            for (Cohort cohort : legion.allCohorts()) if (cohort.isAlive()) result.add(cohort.getStoredPosition());
        return result.stream().distinct().sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
    }
    private static boolean hostileWithin(ServerLevel level, UUID owner, BlockPos position, int radius) {
        return hostilePositions(level, owner).stream().anyMatch(p -> ArmyManager.near(p, position, radius));
    }

    /** Distance to the actual route segment; a hostile near the final delivery leg can also intercept it. */
    //supply line interruption detection function
    public static boolean lineInterrupted(BlockPos first, BlockPos second, List<BlockPos> enemies) {
        double dx = (double) second.getX() - first.getX(), dz = (double) second.getZ() - first.getZ();
        double lengthSquared = dx * dx + dz * dz;
        for (BlockPos enemy : enemies) {
            double t = lengthSquared == 0 ? 0 : Math.clamp(((enemy.getX() - (double) first.getX()) * dx
                    + (enemy.getZ() - (double) first.getZ()) * dz) / lengthSquared, 0, 1);
            double ex = first.getX() + t * dx - enemy.getX(), ez = first.getZ() + t * dz - enemy.getZ();
            if (ex * ex + ez * ez <= (double) StateBalance.SUPPLY_INTERCEPTION_RANGE * StateBalance.SUPPLY_INTERCEPTION_RANGE) return true;
        }
        return false;
    }

    private boolean accessible(UUID stateId, long packed, ServerLevel level) {
        return accessible(stateId, packed, ClaimManager.get(level), StateManager.get(level));
    }
    private static boolean accessible(UUID stateId, long packed, ClaimManager claims, StateManager states) {
        UUID owner = claims.getStrategicOwner(ChunkPos.unpack(packed));
        return owner != null && (stateId.equals(owner) || states.hasPassage(stateId, owner));
    }

    //network management function - helper method
    private Network ensureNetwork(UUID owner, ServerLevel level) {
        StateManager states = StateManager.get(level);
        Network current = networks.get(owner);
        long revision = 31 * ClaimManager.get(level).getTopologyRevision() + states.getLogisticsRevision() + 131 * infrastructureRevision;
        if (current != null && current.checkedTick == level.getGameTime() && current.checkedRevision == revision) return current;
        return ensureNetwork(owner, ClaimManager.get(level), states, hostilePositions(level, owner), level.getGameTime());
    }

    //network management function - core method
    //primary logistics engine. It builds a search tree of reachable chunks from all supply sources, and caches the results for incremental updates
    private Network ensureNetwork(UUID owner, ClaimManager claims, StateManager states, List<BlockPos> enemies, long queryTick) {
        Network current = networks.get(owner);
        long revision = 31 * claims.getTopologyRevision() + states.getLogisticsRevision() + 131 * infrastructureRevision;
        Map<Long, List<BlockPos>> currentBuckets = new HashMap<>();
        for (BlockPos enemy : enemies) {
            int x = enemy.getX() >> 4, z = enemy.getZ() >> 4;
            for (int ox = -4; ox <= 4; ox++) for (int oz = -4; oz <= 4; oz++)
                currentBuckets.computeIfAbsent(ChunkPos.pack(x + ox, z + oz), ignored -> new ArrayList<>()).add(enemy);
        }
        long stamp = revision;
        for (SettlementData settlement : states.getAllSettlements()) if (owner.equals(settlement.getOwningStateId()) && settlement.isCenterVerified() && settlement.isSupplyReady())
            stamp = stamp * 31 + settlement.getSettlementId().hashCode();
        stamp = stamp * 31 + enemies.hashCode();
        // An in-flight build keeps its queue, but all result validation uses live hostile positions.
        //utilizes a binary search tree
        if (current != null) { current.enemies = enemies; current.enemyBuckets = currentBuckets; }
        if (current == null || current.claimContext != claims || current.stateContext != states || (current.complete && current.fingerprint != stamp)) {
            Network next = new Network(); next.fingerprint = stamp; next.enemies = enemies;
            next.claimContext = claims; next.stateContext = states;
            boolean sameContext = current != null && current.claimContext == claims && current.stateContext == states;
            next.previous = !sameContext ? null : current.complete ? current : current.previous;
            if (next.previous != null) next.previous.previous = null;
            next.enemyBuckets = currentBuckets;
            for (SettlementData settlement : states.getAllSettlements()) if (owner.equals(settlement.getOwningStateId()) && settlement.isCenterVerified() && settlement.isSupplyReady()) {
                long cell = ChunkPos.containing(settlement.getCenterAltarPos()).pack();
                if (accessible(owner, cell, claims, states) && !blocked(next, cell)) {
                    Node root = new Node(cell, 0, settlement.getSettlementId(), settlement.getSettlementName(), null);
                    if (next.best.putIfAbsent(cell, root) == null) next.frontier.add(root);
                }
            }
            networks.put(owner, next); current = next;
        }
        current.checkedTick = queryTick; current.checkedRevision = revision;
        return current;
    }

    private static BlockPos center(long packed) {
        ChunkPos pos = ChunkPos.unpack(packed);
        return new BlockPos(pos.x() * 16 + 8, 64, pos.z() * 16 + 8);
    }
    private static boolean blocked(Network network, long cell) {
        return lineInterrupted(center(cell), center(cell), network.enemyBuckets.getOrDefault(cell, List.of()));
    }

    //helper method to advance the network search for a given state, using the current game level and a deadline for processing time
    private void advanceNetwork(Network network, UUID stateId, ServerLevel level, long deadline) {
        advanceNetwork(network, stateId, ClaimManager.get(level), StateManager.get(level), deadline);
    }
    //road network search function (and camp too)
    //core method to advance the network search for a given state, using the current game level and a deadline for processing time
    private void advanceNetwork(Network network, UUID stateId, ClaimManager claims, StateManager states, long deadline) {
        int work = 0;
        Map<Long, Camp> relays = new HashMap<>();
        for (Camp camp : camps.values()) if (stateId.equals(camp.ownerId()) && ownedCampChunk(camp, claims))
            relays.put(ChunkPos.containing(camp.position()).pack(), camp);
        while (!network.frontier.isEmpty() && work++ < SEARCH_WORK_PER_TICK && System.nanoTime() < deadline) {
            Node node = network.frontier.poll();
            if (network.best.get(node.cell()) != node) continue;
            Camp relay = relays.get(node.cell());
            if (relay != null && network.connectedCamps.add(relay.id())) {
                Node restarted = new Node(node.cell(), 0, relay.id(), "Camp " + relay.id().toString().substring(0, 8), node);
                network.best.put(node.cell(), restarted); network.frontier.add(restarted); continue;
            }
            ChunkPos current = ChunkPos.unpack(node.cell());
            for (int[] direction : DIRECTIONS) {
                long next = ChunkPos.pack(current.x() + direction[0], current.z() + direction[1]);
                if (!accessible(stateId, next, claims, states) || blocked(network, next)) continue;
                if (lineInterrupted(center(node.cell()), center(next), network.enemyBuckets.getOrDefault(next, List.of()))) continue;
                double step = 16 * (roadCells.contains(node.cell()) && roadCells.contains(next) ? StateBalance.ROAD_TRANSPORT_MULTIPLIER : 1);
                double cost = node.cost() + step;
                if (cost > StateBalance.SUPPLY_RANGE) continue;
                Node old = network.best.get(next);
                if (old == null || cost < old.cost()) {
                    Node value = new Node(next, cost, node.sourceId(), node.sourceName(), node);
                    network.best.put(next, value); network.frontier.add(value);
                }
            }
            // Bound work per tick, not the state's territory: clearing a large search would pause shortages forever.
        }
        network.complete = network.frontier.isEmpty();
    }

    public SupplyResult describe(UUID stateId, Cohort cohort, ServerLevel level) {
        SupplyResult network = supplyAt(stateId, cohort.getStoredPosition(), level);
        if (network.connected() || network.recalculating()) return network;
        for (Camp candidate : camps.values()) if (candidate.stockTicks > 0 && stateId.equals(candidate.ownerId()) && ownedCampChunk(candidate, level)
                && ArmyManager.near(candidate.position(), cohort.getStoredPosition(), StateBalance.CAMP_SUPPLY_RANGE)) {
            var status = localCampDelivery(stateId, candidate, cohort.getStoredPosition(), level).status();
            if (status == MapDataService.Verification.PENDING || status == MapDataService.Verification.LIMITED)
                return new SupplyResult("Recalculating", "Checking camp delivery terrain", null, "", 0, List.of(), false, null, true);
        }
        Camp camp = camps.values().stream().filter(c -> c.stockTicks > 0 && stateId.equals(c.ownerId()) && ownedCampChunk(c, level)
                        && ArmyManager.near(c.position(), cohort.getStoredPosition(), StateBalance.CAMP_SUPPLY_RANGE)
                        && localCampDelivery(stateId, c, cohort.getStoredPosition(), level).status() == MapDataService.Verification.VERIFIED)
                .min(Comparator.comparingDouble(c -> distance(c.position(), cohort.getStoredPosition()))).orElse(null);
        return new SupplyResult(camp != null ? "Using camp stock" : cohort.isSupplyExhausted() ? "Exhausted" : "Using carried reserves",
                network.reason(), camp == null ? null : camp.id(), camp == null ? "" : "Camp stores", network.cost(),
                camp == null ? List.of() : List.of(camp.position(), cohort.getStoredPosition()), false, camp == null ? null : camp.id(), false);
    }

    /** Finances and logistics both constrain recovery; paying soldiers cannot feed an exhausted formation. */
    public boolean canRecoverMorale(UUID stateId, Cohort cohort, ServerLevel level) {
        Boolean adequate = adequatelySupplied.get(cohort.getCohortId());
        if (adequate != null) return adequate;
        SupplyResult result = describe(stateId, cohort, level);
        return !result.recalculating() && (result.connected() || !cohort.isSupplyExhausted());
    }

    private SupplyResult supplyAt(UUID stateId, BlockPos position, ServerLevel level) {
        if (terrainSupplyTick != level.getGameTime()) { terrainSupplyQueries.clear(); terrainSources.clear(); terrainSupplyTick = level.getGameTime(); }
        return terrainSupply(stateId, position, level);
    }

    /** Live delivery uses continuous block routes from actual altars and camp positions. */
    //supply line verification function: checks if a supply line exists from a settlement or camp to a given destination, and returns the result
    private SupplyResult terrainSupply(UUID owner, BlockPos destination, ServerLevel level) {
        String key = owner + ":" + destination.getX() + ":" + destination.getZ();
        SupplyResult cached = terrainSupplyQueries.get(key); if (cached != null) return cached;
        SupplySources sources = terrainSources.computeIfAbsent(owner, ignored -> connectedSources(owner, level));
        SupplyResult best = null; boolean pending = sources.pending();
        for (SupplySource source : sources.values()) {
            if (distance(source.position(), destination) > StateBalance.SUPPLY_RANGE / StateBalance.ROAD_TRANSPORT_MULTIPLIER) continue;
            var route = deliveryRoute(owner, source.position(), destination, level, StateBalance.SUPPLY_RANGE, false);
            pending |= route.status() == MapDataService.Verification.PENDING || route.status() == MapDataService.Verification.LIMITED;
            if (route.status() == MapDataService.Verification.VERIFIED && (best == null || route.cost() < best.cost())) {
                List<BlockPos> path = new ArrayList<>(source.path()); path.addAll(route.points());
                best = new SupplyResult("Supplied", "Verified settlement-rooted route", source.id(), source.name(), route.cost(), List.copyOf(path), true, source.camp() ? source.id() : null, false);
            }
        }
        if (best == null) best = new SupplyResult(pending ? "Recalculating" : "Using carried reserves", pending ? "Verifying terrain along supply routes" : "No usable supply route within range", null, "", 0, List.of(), false, null, pending);
        terrainSupplyQueries.put(key, best);
        return best;
    }

    /** Shared, iterative relay discovery avoids recursive camp cycles and repeats for each cohort. */
    private SupplySources connectedSources(UUID owner, ServerLevel level) {
        List<SupplySource> sources = new ArrayList<>(); Set<UUID> reachedCamps = new HashSet<>(); boolean pending = false; int attempts = 0;
        for (SettlementData root : StateManager.get(level).getAllSettlements()) if (owner.equals(root.getOwningStateId()) && root.isCenterVerified() && root.isSupplyReady()
                && accessible(owner, ChunkPos.containing(root.getCenterAltarPos()).pack(), level))
            sources.add(new SupplySource(root.getSettlementId(), root.getSettlementName(), root.getCenterAltarPos(), List.of(), false));
        for (int cursor = 0; cursor < sources.size(); cursor++) {
            SupplySource source = sources.get(cursor);
            for (Camp camp : camps.values()) {
                if (reachedCamps.contains(camp.id()) || !owner.equals(camp.ownerId()) || !ownedCampChunk(camp, level)
                        || distance(source.position(), camp.position()) > StateBalance.SUPPLY_RANGE / StateBalance.ROAD_TRANSPORT_MULTIPLIER) continue;
                if (++attempts > 256) return new SupplySources(List.copyOf(sources), true);
                var route = deliveryRoute(owner, source.position(), camp.position(), level, StateBalance.SUPPLY_RANGE, false);
                pending |= route.status() == MapDataService.Verification.PENDING || route.status() == MapDataService.Verification.LIMITED;
                if (route.status() == MapDataService.Verification.VERIFIED) {
                    reachedCamps.add(camp.id()); List<BlockPos> path = new ArrayList<>(source.path()); path.addAll(route.points());
                    sources.add(new SupplySource(camp.id(), "Camp relay", camp.position(), List.copyOf(path), true));
                }
            }
        }
        return new SupplySources(List.copyOf(sources), pending);
    }

    private MapDataService.TerrainRoute localCampDelivery(UUID owner, Camp camp, BlockPos destination, ServerLevel level) {
        return deliveryRoute(owner, camp.position(), destination, level, StateBalance.CAMP_SUPPLY_RANGE, true);
    }

    //supply line verification function: verifies the terrain route between a supply source and a destination, taking into account hostile positions and maximum distance
    private MapDataService.TerrainRoute deliveryRoute(UUID owner, BlockPos start, BlockPos end, ServerLevel level, double maximum, boolean local) {
        var route = MapDataService.route(level, start, end, 128, owner);
        if (route.status() != MapDataService.Verification.VERIFIED) return route;
        List<BlockPos> enemies = ensureNetwork(owner, level).enemies;
        long rights = ClaimManager.get(level).getTopologyRevision() * 31 + StateManager.get(level).getLogisticsRevision();
        DeliveryKey key = new DeliveryKey(owner, new BlockPos(start.getX(), 0, start.getZ()), new BlockPos(end.getX(), 0, end.getZ()), maximum, local, rights, enemies.hashCode());
        DeliveryCheck check = deliveries.get(key);
        if (check == null || check.path != route.points() || check.completed != Long.MIN_VALUE && level.getGameTime() - check.completed >= 20) {
            if (check == null && deliveries.size() >= 512) {
                deliveries.values().removeIf(job -> job.status != MapDataService.Verification.PENDING || level.getGameTime() - job.requested > 200);
                if (deliveries.size() >= 512) return new MapDataService.TerrainRoute(MapDataService.Verification.PENDING, List.of(), 0);
            }
            check = new DeliveryCheck(key, route.points(), List.copyOf(enemies), level.getGameTime()); deliveries.put(key, check);
        }
        check.requested = level.getGameTime(); return check.result();
    }

    /** Delivery validation shares the terrain tick budget; many troops reuse one completed corridor. */
    public void tickTerrainDelivery(ServerLevel level, long deadline) {
        for (DeliveryCheck check : new ArrayList<>(deliveries.values())) {
            if (System.nanoTime() >= deadline) break;
            if (check.status != MapDataService.Verification.PENDING) continue;
            if (level.getGameTime() - check.requested > 200) { deliveries.remove(check.key); continue; }
            DeliveryKey key = check.key;
            int examined = 0;
            while (check.cursor < check.path.size() && examined++ < 64 && System.nanoTime() < deadline) {
                BlockPos previous = check.path.get(check.cursor - 1), point = check.path.get(check.cursor);
                var verified = MapDataService.verifySegment(level, previous.getX() + .5, previous.getZ() + .5, point.getX() + .5, point.getZ() + .5);
                if (verified != MapDataService.Verification.VERIFIED) { if (verified == MapDataService.Verification.BLOCKED) check.status = verified; break; }
                if (lineInterrupted(previous, point, check.enemies)) { check.status = MapDataService.Verification.BLOCKED; break; }
                var a = MapDataService.surface(level, previous); var b = MapDataService.surface(level, point);
                double slope = b.height() - a.height(); double length = Math.hypot(distance(previous, point), slope);
                double step = length * Math.max(a.travelCost(), b.travelCost()) * (slope > 0 ? 1.25 : 1);
                boolean access = accessible(key.owner, ChunkPos.containing(point).pack(), level);
                check.finalLeg |= !access; if (check.finalLeg) check.delivery += step;
                check.cost += step * (!key.local && access && usableRoadAt(previous.getX(), previous.getZ(), level) && usableRoadAt(point.getX(), point.getZ(), level) ? StateBalance.ROAD_TRANSPORT_MULTIPLIER : 1);
                if (check.cost > key.maximum || check.delivery > StateBalance.FINAL_DELIVERY_RANGE) { check.status = MapDataService.Verification.BLOCKED; break; }
                check.cursor++;
            }
            if (check.cursor >= check.path.size()) check.status = MapDataService.Verification.VERIFIED;
            if (check.status != MapDataService.Verification.PENDING) check.completed = level.getGameTime();
            deliveries.remove(key); deliveries.put(key, check);
        }
    }

    /**
     * Evaluate strategic supply from saved territory/infrastructure without loading a
     * Minecraft world. This is the topology/range policy oracle; live play additionally
     * requires continuous verified terrain. Neither path mutates reserves, stock or clocks.
     */
    public SupplyResult inspectSupply(UUID owner, BlockPos position, ClaimManager claims, StateManager states, List<BlockPos> enemies) {
        long queryTick = inspectionTick++;
        Network network = ensureNetwork(owner, claims, states, List.copyOf(enemies), queryTick);
        while (!network.complete) advanceNetwork(network, owner, claims, states, Long.MAX_VALUE);
        return querySupply(owner, position, claims, states, network, queryTick);
    }

    //supply line queue and caching function
    private SupplyResult querySupply(UUID stateId, BlockPos position, ClaimManager claims, StateManager states, Network building, long queryTick) {
        Network network = building.complete ? building : building.previous;
        if (network == null) return new SupplyResult("Recalculating", "Calculating the supply network", null, "", 0, List.of(), false, null, true);
        if (network.queryTick != queryTick) { network.queries.clear(); network.queryTick = queryTick; }
        QueryKey key = new QueryKey(ChunkPos.containing(position).pack(), position.getX(), position.getZ());
        SupplyResult cached = network.queries.get(key);
        if (cached != null) return cached;
        Node best = null; double bestCost = Double.POSITIVE_INFINITY;
        int cx = position.getX() >> 4, cz = position.getZ() >> 4, radius = StateBalance.FINAL_DELIVERY_RANGE / 16 + 1;
        for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) {
            Node node = network.best.get(ChunkPos.pack(x, z));
            if (node == null) continue;
            double finalDistance = distance(center(node.cell()), position);
            double cost = node.cost() + finalDistance;
            if (finalDistance > StateBalance.FINAL_DELIVERY_RANGE || cost > StateBalance.SUPPLY_RANGE || cost >= bestCost) continue;
            if (lineInterrupted(center(node.cell()), position, building.enemies)) continue;
            best = node; bestCost = cost;
        }
        SupplyResult result;
        if (best != null) {
            List<BlockPos> route = trace(best);
            if (!validRoute(stateId, best, claims, states, building, distance(center(best.cell()), position))) {
                result = new SupplyResult("Recalculating", "Supply conditions changed; recalculating", null, "", 0, List.of(), false, null, !building.complete);
            } else {
                List<BlockPos> full = new ArrayList<>(route); full.add(position);
                result = new SupplyResult("Supplied", "Connected to a settlement-rooted network", best.sourceId(), best.sourceName(),
                        bestCost, List.copyOf(full), true, camps.containsKey(best.sourceId()) ? best.sourceId() : null, false);
            }
        } else {
            boolean pending = !building.complete || network.truncated;
            result = new SupplyResult(pending ? "Recalculating" : "Using carried reserves", pending ? "Supply search is still incomplete" :
                    network.best.isEmpty() ? "No ready, accessible supply source" : "Outside usable supply reach or route unavailable",
                    null, "", 0, List.of(), false, null, pending);
        }
        network.queries.put(key, result);
        return result;
    }

    /**
     * Validates the route from a given node to the source of the supply.
     *
     * @param stateId The ID of the state for which to validate the route.
     * @param node The node from which to start the validation.
     * @param level The game level.
     * @param network The network for which to validate the route.
     * @param deliveryCost The cost of delivering to the current node.
     * @return true if the route is valid, false otherwise.
     */
    private boolean validRoute(UUID stateId, Node node, ServerLevel level, Network network, double deliveryCost) {
        return validRoute(stateId, node, ClaimManager.get(level), StateManager.get(level), network, deliveryCost);
    }
    private boolean validRoute(UUID stateId, Node node, ClaimManager claims, StateManager states, Network network, double deliveryCost) {
        int visited = 0;
        double legCost = deliveryCost;
        for (Node current = node; current != null && visited++ < MAX_SEARCH_CELLS; current = current.previous()) {
            if (!accessible(stateId, current.cell(), claims, states)) return false;
            if (current.previous() != null && lineInterrupted(center(current.cell()), center(current.previous().cell()),
                    network.enemyBuckets.getOrDefault(current.cell(), List.of()))) return false;
            if (current.previous() != null && current.cell() == current.previous().cell() && current.cost() == 0) {
                Camp relay = camps.get(current.sourceId());
                if (relay == null || !stateId.equals(relay.ownerId()) || !ownedCampChunk(relay, claims)) return false;
                if (legCost > StateBalance.SUPPLY_RANGE) return false;
                legCost = 0;
            } else if (current.previous() != null) {
                legCost += 16 * (roadCells.contains(current.cell()) && roadCells.contains(current.previous().cell())
                        ? StateBalance.ROAD_TRANSPORT_MULTIPLIER : 1);
                if (legCost > StateBalance.SUPPLY_RANGE) return false;
            }
            if (current.previous() == null) {
                SettlementData root = states.getSettlement(current.sourceId());
                return root != null && stateId.equals(root.getOwningStateId()) && root.isCenterVerified() && root.isSupplyReady();
            }
        }
        return false;
    }
    private static List<BlockPos> trace(Node node) {
        List<BlockPos> points = new ArrayList<>();
        for (Node current = node; current != null && points.size() <= MAX_ROUTE_DISPLAY; current = current.previous())
            points.add(center(current.cell()));
        Collections.reverse(points);
        return List.copyOf(points);
    }
    /**
     * Checks if a camp is connected to the supply network.
     *
     * @param campId The ID of the camp to check.
     * @param level The game level.
     * @return true if the camp is connected, false otherwise.
     */
    public boolean isCampConnected(UUID campId, ServerLevel level) {
        Camp camp = camps.get(campId);
        if (camp == null || !ownedCampChunk(camp, level)) return false;
        return supplyAt(camp.ownerId(), camp.position(), level).connected();
    }

    //cohort selection function
    private Cohort selectedCohort(ServerLevel level, UUID owner, UUID target) {
        ArmyManager armies = ArmyManager.get(level);
        Cohort cohort = armies.resolveCohort(target);
        Legion parent = cohort == null ? null : armies.getParentLegion(target);
        if (cohort != null && cohort.isAlive() && parent != null && owner.equals(parent.getOwningStateId())) return cohort;
        Army army = armies.getArmy(target);
        if (army != null && owner.equals(army.getOwningStateId())) return army.getDeployedCohortIds().stream()
                .map(armies::resolveCohort).filter(Objects::nonNull).filter(Cohort::isAlive).findFirst().orElse(null);
        return null;
    }
    //cohort route selection function
    public SelectedRoute selectedRoute(ServerPlayer player) {
        UUID target = SELECTIONS.get(player.getUUID());
        StateData state = StateManager.get(player.level()).getStateByPlayer(player.getUUID());
        Cohort cohort = state == null || target == null ? null : selectedCohort(player.level(), state.getStateId(), target);
        if (cohort == null) return new SelectedRoute(null, List.of(), false);
        List<BlockPos> route = describe(state.getStateId(), cohort, player.level()).route();
        List<BlockPos> visible = new ArrayList<>();
        for (BlockPos point : route) { if (visible.size() >= MAX_ROUTE_DISPLAY || !MapDataService.canPreviewTerrain(player.level(), state.getStateId(), point)) break; visible.add(point); }
        return new SelectedRoute(target, List.copyOf(visible), visible.size() < route.size());
    }

    /** Spend the movement time on either side of a chunk boundary at that side's road speed. */
    public double movementDistance(UUID owner, double x, double z, double directionX, double directionZ, double baseDistance, ServerLevel level) {
        return movementDistance(x, z, directionX, directionZ, baseDistance, cell -> {
            if (!accessible(owner, cell, level)) return false;
            BlockPos center = center(cell);
            if (!MapDataService.surface(level, center).usable()) return false;
            ChunkPos chunk = ChunkPos.unpack(cell);
            for (int[] direction : DIRECTIONS) {
                long adjacent = ChunkPos.pack(chunk.x() + direction[0], chunk.z() + direction[1]);
                if (!roadCells.contains(adjacent)) continue;
                BlockPos next = center(adjacent);
                if (MapDataService.verifySegment(level, center.getX() + .5, center.getZ() + .5, next.getX() + .5, next.getZ() + .5) != MapDataService.Verification.VERIFIED) return false;
            }
            return true;
        });
    }
    /** Shared strategic movement calculation; the caller supplies current transit rights. */
    public double movementDistance(double x, double z, double directionX, double directionZ, double baseDistance, LongPredicate accessibleCell) {
        double remaining = baseDistance, travelled = 0;
        for (int step = 0; step < 8 && remaining > 1.0e-9; step++) {
            double px = x + directionX * travelled, pz = z + directionZ * travelled;
            int cellX = (int) Math.floor((px + directionX * 1.0e-8) / 16);
            int cellZ = (int) Math.floor((pz + directionZ * 1.0e-8) / 16);
            long cell = ChunkPos.pack(cellX, cellZ);
            double multiplier = roadCells.contains(cell) && accessibleCell.test(cell) ? StateBalance.ROAD_SPEED_MULTIPLIER : 1;
            double bx = directionX == 0 ? Double.POSITIVE_INFINITY : ((directionX > 0 ? (cellX + 1) * 16.0 : cellX * 16.0) - px) / directionX;
            double bz = directionZ == 0 ? Double.POSITIVE_INFINITY : ((directionZ > 0 ? (cellZ + 1) * 16.0 : cellZ * 16.0) - pz) / directionZ;
            double distance = Math.min(remaining * multiplier, Math.max(1.0e-7, Math.min(bx, bz)) + 1.0e-7);
            travelled += distance; remaining -= distance / multiplier;
        }
        return travelled;
    }

    /* 


    
    //all data serialization and deserialization functions below


    */

    /** Only durable data is serialized; searches and player selections always rebuild safely. */
    public CompoundTag toTag() {
        CompoundTag root = new CompoundTag(); root.putInt("Version", 1); root.putLong("LastGameTime", lastGameTime);
        root.putLong("InteractionSequence", interactionSequence);
        ListTag roadTags = new ListTag();
        for (Road road : roads.values()) {
            CompoundTag tag = new CompoundTag(); tag.putString("Id", road.id().toString()); tag.putString("Owner", road.ownerId().toString());
            tag.putString("Dimension", "minecraft:overworld"); tag.putInt("GeometryVersion", 1);
            tag.put("Points", positionTags(road.points())); tag.put("ControlPoints", positionTags(road.controlPoints())); roadTags.add(tag);
        }
        root.put("Roads", roadTags);
        ListTag campTags = new ListTag();
        for (Camp camp : camps.values()) {
            CompoundTag tag = new CompoundTag(); tag.putString("Id", camp.id().toString()); tag.putString("Owner", camp.ownerId().toString());
            tag.putLong("Position", camp.position().asLong()); tag.putLong("StockTicks", camp.stockTicks); campTags.add(tag);
        }
        root.put("Camps", campTags);
        ListTag bridgeTags = new ListTag();
        for (Bridge bridge : bridges.values()) {
            CompoundTag tag = new CompoundTag(); tag.putString("Id", bridge.id.toString()); tag.putString("Owner", bridge.ownerId.toString());
            tag.putLong("Start", bridge.start.asLong()); tag.putLong("End", bridge.end.asLong()); tag.putInt("Width", bridge.width);
            bridgeTags.add(tag);
        }
        root.put("Bridges", bridgeTags);
        CompoundTag clocks = new CompoundTag(); activeClocks.forEach((id, ticks) -> clocks.putLong(id.toString(), ticks)); root.put("ActiveClocks", clocks);
        return root;
    }
    private static ListTag positionTags(List<BlockPos> points) {
        ListTag result = new ListTag();
        points.forEach(point -> { CompoundTag tag = new CompoundTag(); tag.putLong("Position", point.asLong()); result.add(tag); });
        return result;
    }
    private static List<BlockPos> readPositions(CompoundTag tag, String key, int limit) {
        List<BlockPos> points = new ArrayList<>();
        tag.getList(key).ifPresent(list -> {
            for (int i = 0; i < Math.min(limit, list.size()); i++) list.getCompound(i).ifPresent(item ->
                    item.getLong("Position").ifPresent(position -> points.add(BlockPos.of(position))));
        });
        return points;
    }
    public static LogisticsManager fromTag(CompoundTag root) {
        LogisticsManager manager = new LogisticsManager(); manager.lastGameTime = root.getLong("LastGameTime").orElse(-1L);
        manager.interactionSequence = Math.max(0, root.getLong("InteractionSequence").orElse(0L));
        root.getList("Roads").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) list.getCompound(i).ifPresent(tag -> {
                try {
                    List<BlockPos> points = readPositions(tag, "Points", StateBalance.MAX_ROAD_CHUNKS);
                    Road road = new Road(UUID.fromString(tag.getString("Id").orElseThrow()), UUID.fromString(tag.getString("Owner").orElseThrow()),
                            points, readPositions(tag, "ControlPoints", StateBalance.MAX_ROAD_POINTS), cellsOf(points));
                    manager.roads.put(road.id(), road);
                } catch (RuntimeException ignored) { /* Skip a malformed optional road without losing the world's other systems. */ }
            });
        });
        root.getList("Camps").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) list.getCompound(i).ifPresent(tag -> {
                try {
                    Camp camp = new Camp(UUID.fromString(tag.getString("Id").orElseThrow()), UUID.fromString(tag.getString("Owner").orElseThrow()),
                            BlockPos.of(tag.getLong("Position").orElseThrow()));
                    camp.stockTicks = Math.clamp(tag.getLong("StockTicks").orElse(0L), 0, StateBalance.CAMP_STOCK_CAPACITY * StateBalance.TICKS_PER_DAY);
                    manager.camps.put(camp.id(), camp);
                } catch (RuntimeException ignored) { /* Malformed camps are optional; retained settlements remain supply roots. */ }
            });
        });
        root.getCompound("ActiveClocks").ifPresent(clocks -> clocks.keySet().forEach(key -> {
            try { manager.activeClocks.put(UUID.fromString(key), Math.max(0, clocks.getLong(key).orElse(0L))); }
            catch (IllegalArgumentException ignored) { /* Invalid state identifier in an old optional clock. */ }
        }));
        for (var value : root.getListOrEmpty("Bridges")) {
            if (!(value instanceof CompoundTag tag)) continue;
            try {
                Bridge bridge = new Bridge(UUID.fromString(tag.getString("Id").orElseThrow()), UUID.fromString(tag.getString("Owner").orElseThrow()),
                        BlockPos.of(tag.getLong("Start").orElseThrow()), BlockPos.of(tag.getLong("End").orElseThrow()), tag.getInt("Width").orElse(3));
                manager.bridges.put(bridge.id, bridge);
            } catch (RuntimeException ignored) { /* Keep other infrastructure when one bridge record is malformed. */ }
        }
        manager.rebuildRoadCells(); manager.rebuildBridgeColumns(); return manager;
    }
}
