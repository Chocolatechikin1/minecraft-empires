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
    private final Set<Long> roadCells = new HashSet<>();
    private final Map<UUID, Long> activeClocks = new HashMap<>();
    private final Map<UUID, Network> networks = new HashMap<>();
    private final Map<UUID, String> previousWarnings = new HashMap<>();
    private final Map<UUID, Boolean> adequatelySupplied = new HashMap<>();
    private long lastGameTime = -1;
    private long infrastructureRevision;
    private long inspectionTick = Long.MIN_VALUE;

    public static LogisticsManager get(Level level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    //road record
    public record Road(UUID id, UUID ownerId, List<BlockPos> points, List<BlockPos> controlPoints, Set<Long> cells) {
        public Road {
            points = List.copyOf(points); controlPoints = List.copyOf(controlPoints); cells = Set.copyOf(cells);
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
    public static void clearPlayer(UUID playerId) { SELECTIONS.remove(playerId); }
    public static void clearAllSelections() { SELECTIONS.clear(); }

    // Clear only derived search data. Saved geometry, stock and active-time clocks are unaffected.
    private void infrastructureChanged() {
        infrastructureRevision++;
        rebuildRoadCells();
        // A deleted road discount or camp relay cannot remain usable through a previous search tree.
        networks.clear();
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
            ChunkData claim = claims.getClaim(ChunkPos.containing(point));
            if (!level.getWorldBorder().isWithinBounds(point) || claim == null || !state.getStateId().equals(claim.getOwnerUUID()))
                return "Error. The entire road selection must lie in your current territory.";
            if (!remove && MapDataService.isKnownWaterOrLava(level, state.getStateId(), point))
                return "Error. The selected road crosses known water or lava. Draw another route.";
        }
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
        ChunkData claim = ClaimManager.get(level).getClaim(ChunkPos.containing(position));
        if (claim == null || !state.getStateId().equals(claim.getOwnerUUID()) || !level.getWorldBorder().isWithinBounds(position))
            return "Error. Establish camps inside your own territory.";
        if (MapDataService.isKnownWaterOrLava(level, state.getStateId(), position)) return "Error. A camp needs dry ground.";
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

    /** Called by the existing once-per-second cadence. No wall-clock catch-up and no offline supply effects. */
    public void tick(ServerLevel level) {
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
        long deadline = System.nanoTime() + 6_000_000L;
        for (StateData state : states.getAllStates()) {
            Network network = ensureNetwork(state.getStateId(), level);
            advanceNetwork(network, state.getStateId(), level, deadline);
            if (!online.contains(state.getStateId()) || elapsed <= 0) continue;
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
                                && !lineInterrupted(c.position(), cohort.getStoredPosition(), network.enemies))
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
                    case "exhausted" -> "Some formations have exhausted supply reserves. Restore a route, reach stocked camps or retreat.";
                    case "reserves" -> "Some formations lost their supply connection and are using reserves.";
                    default -> "Your formations' supply connections have been restored.";
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
            ChunkData claim = ClaimManager.get(level).getClaim(ChunkPos.containing(camp.position()));
            if (claim == null || claim.getOwnerUUID().equals(camp.ownerId()) || !states.areAtWar(camp.ownerId(), claim.getOwnerUUID())) continue;
            UUID attacker = claim.getOwnerUUID();
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
        ChunkData claim = claims.getClaim(ChunkPos.containing(camp.position()));
        return claim != null && camp.ownerId().equals(claim.getOwnerUUID());
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
        ChunkData claim = claims.getClaim(ChunkPos.unpack(packed));
        return claim != null && (stateId.equals(claim.getOwnerUUID()) || states.hasPassage(stateId, claim.getOwnerUUID()));
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
        for (SettlementData settlement : states.getAllSettlements()) if (owner.equals(settlement.getOwningStateId()) && settlement.isSupplyReady())
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
            for (SettlementData settlement : states.getAllSettlements()) if (owner.equals(settlement.getOwningStateId()) && settlement.isSupplyReady()) {
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
        Camp camp = camps.values().stream().filter(c -> c.stockTicks > 0 && stateId.equals(c.ownerId()) && ownedCampChunk(c, level)
                        && ArmyManager.near(c.position(), cohort.getStoredPosition(), StateBalance.CAMP_SUPPLY_RANGE)
                        && !lineInterrupted(c.position(), cohort.getStoredPosition(), ensureNetwork(stateId, level).enemies))
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
        Network building = ensureNetwork(stateId, level);
        return querySupply(stateId, position, ClaimManager.get(level), StateManager.get(level), building, level.getGameTime());
    }

    /**
     * Evaluate strategic supply from saved territory/infrastructure without loading a
     * Minecraft world. This completes the same incremental engine used by live play;
     * only derived caches change, never reserves, camp stock or saved clocks.
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
                return root != null && stateId.equals(root.getOwningStateId()) && root.isSupplyReady();
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
        Network current = ensureNetwork(camp.ownerId(), level);
        Network network = current.complete ? current : current.previous;
        Node node = network == null ? null : network.best.get(ChunkPos.containing(camp.position()).pack());
        return node != null && network.connectedCamps.contains(campId) && validRoute(camp.ownerId(), node, level, current, 0);
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
        return new SelectedRoute(target, route.size() > MAX_ROUTE_DISPLAY ? route.subList(0, MAX_ROUTE_DISPLAY) : route, route.size() > MAX_ROUTE_DISPLAY);
    }

    /** Spend the movement time on either side of a chunk boundary at that side's road speed. */
    public double movementDistance(UUID owner, double x, double z, double directionX, double directionZ, double baseDistance, ServerLevel level) {
        return movementDistance(x, z, directionX, directionZ, baseDistance, cell -> accessible(owner, cell, level));
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
        manager.rebuildRoadCells(); return manager;
    }
}
