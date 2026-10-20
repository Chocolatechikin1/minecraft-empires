package com.devc.minecraftempires.state;

import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Legion;
import com.devc.minecraftempires.territory.ClaimManager;
import com.devc.minecraftempires.territory.SettlementData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/*
 * This class manages the war-related operations for AI states.
    * It handles the declaration of war, clashes, captures, dissolutions, and recruitment of AI states.
    * It also manages the routing of forces, supply lines, and the assessment of defeat for AI states.
    * The class ensures that all operations are performed in a stable and consistent manner, preventing any conflicts or inconsistencies in the game state.
 */
public final class AIWarManager {
    public static final long INTERVAL = StateBalance.TICKS_PER_DAY / 4;
    public static final int MAX_OPERATIONS = 8, FRONTIER_VISITS = 8, MAX_SEARCH = 128, MAX_PATH = 16;
    public static final int MAJOR_LOSS_PERCENT = 50;
    private static final long JOURNAL_BYTES = 2 * 1024 * 1024, STORE_BYTES = 64 * 1024 * 1024;
    private static final int[][] DIRECTIONS = {{0,-1},{-1,0},{1,0},{0,1}};
    public enum Kind { DECLARE, CLASH, CAPTURE, DISSOLVE, RECRUIT }
    public enum RouteStatus { FOUND, BLOCKED, PENDING }
    public enum Defeat { NONE, RECOVER, SURRENDER, DISSOLVE, PENDING }
    public record Route(RouteStatus status, List<Long> regions) {
        public Route { regions = List.copyOf(regions); }
    }
    public record RegionChange(long key, UUID owner, long revision, UUID recipient) {
        public RegionChange {
            if (owner == null || revision < 0 || revision == Long.MAX_VALUE || owner.equals(recipient)) throw new IllegalArgumentException("Invalid macro region change");
        }
        CompoundTag toNBT() {
            CompoundTag tag = new CompoundTag(); tag.putLong("Key", key); tag.putString("Owner", owner.toString()); tag.putLong("Revision", revision);
            if (recipient != null) tag.putString("Recipient", recipient.toString()); return tag;
        }
        static RegionChange fromNBT(CompoundTag tag) {
            return new RegionChange(tag.getLong("Key").orElseThrow(), UUID.fromString(tag.getString("Owner").orElseThrow()),
                    tag.getLong("Revision").orElseThrow(), tag.getString("Recipient").map(UUID::fromString).orElse(null));
        }
    }
    /*
     * this record represents the outcome of a war event.
     * It includes information about the sequence, time, kind, and participants of the event.
     * It also includes information about the focus, retired, and the changes to the regions.
     */
    public record Outcome(long sequence, long time, Kind kind, UUID actor, UUID target, String actorName, String targetName,
                          UUID focus, String focusName, UUID retired, List<CompoundTag> states, List<CompoundTag> settlements,
                          List<CompoundTag> forces, List<RegionChange> regions) {
        public Outcome {
            if (sequence < 1 || time < 0 || kind == null || actor == null || states.isEmpty() || states.size() > 2
                    || settlements.size() > 2048 || forces.size() > 128 || regions.size() > 8192) throw new IllegalArgumentException("Invalid macro outcome size");
            states = states.stream().map(CompoundTag::copy).toList(); settlements = settlements.stream().map(CompoundTag::copy).toList();
            forces = forces.stream().map(CompoundTag::copy).toList(); regions = List.copyOf(regions);
            Set<UUID> owners = new HashSet<>(), sites = new HashSet<>(), troops = new HashSet<>(); Set<Long> cells = new HashSet<>();
            for (CompoundTag tag : states) {
                StateData state = StateData.fromNBT(tag);
                if (!state.isAI() || state.isInitializationPending() || !owners.add(state.getStateId())) throw new IllegalArgumentException("Invalid macro participant");
            }
            if (!owners.contains(actor) || kind != Kind.DISSOLVE && kind != Kind.RECRUIT && (target == null || !owners.contains(target) || target.equals(actor))
                    || retired != null && (!retired.equals(actor) || kind != Kind.DISSOLVE)) throw new IllegalArgumentException("Invalid macro disposition");
            if (kind == Kind.DISSOLVE && retired == null || kind == Kind.RECRUIT && (owners.size() != 1 || target != null)
                    || kind == Kind.CAPTURE && regions.size() != 1 || kind != Kind.CAPTURE && kind != Kind.DISSOLVE && !regions.isEmpty())
                throw new IllegalArgumentException("Invalid macro operation shape");
            for (CompoundTag tag : settlements) {
                SettlementData site = SettlementData.fromNBT(tag);
                if (!owners.contains(site.getOwningStateId()) || !site.isAbstractSettlement() || !sites.add(site.getSettlementId())
                        || site.getOwningStateId().equals(retired)) throw new IllegalArgumentException("Invalid macro site");
            }
            for (CompoundTag tag : forces) {
                var force = ArmyManager.StrategicForce.fromNBT(tag);
                if (!owners.contains(force.owner()) || !troops.add(force.id()) || force.owner().equals(retired)) throw new IllegalArgumentException("Invalid macro roster");
            }
            for (RegionChange change : regions) if (!cells.add(change.key()) || !owners.contains(change.owner())
                    || change.recipient() != null && !owners.contains(change.recipient())
                    || kind == Kind.CAPTURE && (!change.owner().equals(target) || !actor.equals(change.recipient()))
                    || kind == Kind.DISSOLVE && (!change.owner().equals(retired) || change.recipient() != null)) throw new IllegalArgumentException("Foreign macro transfer");
        }
        public Set<UUID> participants() {
            Set<UUID> owners = new HashSet<>(); states.forEach(tag -> owners.add(StateData.fromNBT(tag).getStateId())); return Set.copyOf(owners);
        }
        public UUID eventId() { return UUID.nameUUIDFromBytes(("minecraftempires:macro:" + sequence).getBytes(StandardCharsets.UTF_8)); }
        public CompoundTag toNBT() {
            CompoundTag tag = new CompoundTag(); tag.putLong("Sequence", sequence); tag.putLong("Time", time); tag.putString("Kind", kind.name());
            tag.putString("Actor", actor.toString()); tag.putString("ActorName", actorName); tag.putString("TargetName", targetName); tag.putString("FocusName", focusName);
            if (target != null) tag.putString("Target", target.toString()); if (focus != null) tag.putString("Focus", focus.toString()); if (retired != null) tag.putString("Retired", retired.toString());
            putTags(tag, "States", states); putTags(tag, "Settlements", settlements); putTags(tag, "Forces", forces);
            putTags(tag, "Regions", regions.stream().map(RegionChange::toNBT).toList()); return tag;
        }
        public static Outcome fromNBT(CompoundTag tag) {
            return new Outcome(tag.getLong("Sequence").orElseThrow(), tag.getLong("Time").orElseThrow(), Kind.valueOf(tag.getString("Kind").orElseThrow()),
                    UUID.fromString(tag.getString("Actor").orElseThrow()), tag.getString("Target").map(UUID::fromString).orElse(null),
                    tag.getString("ActorName").orElse(""), tag.getString("TargetName").orElse(""), tag.getString("Focus").map(UUID::fromString).orElse(null),
                    tag.getString("FocusName").orElse(""), tag.getString("Retired").map(UUID::fromString).orElse(null), tags(tag, "States", 2),
                    tags(tag, "Settlements", 2048), tags(tag, "Forces", 128), tags(tag, "Regions", 8192).stream().map(RegionChange::fromNBT).toList());
        }
    }

    //end of record class

    private record Journal(long sequence, Outcome outcome) {}
    private record SupplyKey(UUID owner, long position, long claims, long diplomacy) {}
    private record RouteKey(UUID owner, long start, long goal, UUID enemy, long claims, long diplomacy) {}
    private final StateManager states;
    private final Map<SupplyKey, Route> supplyCache = new LinkedHashMap<>();
    private final LinkedHashMap<RouteKey, CorridorSearch> searches = new LinkedHashMap<>();
    private final Map<RouteKey, Route> routes = new LinkedHashMap<>();
    private final Set<UUID> locked = new HashSet<>();
    private CompletableFuture<?> io;
    private Outcome applying;
    private Path file;
    private String phase = "NEW", failure = "";
    private boolean started, closed;
    private long sequence, lastTick = Long.MIN_VALUE;

    public AIWarManager(StateManager states) { this.states = Objects.requireNonNull(states); }
    public String status() { return phase + (failure.isEmpty() ? "" : ": " + failure) + "; outcomes=" + sequence + ", locked=" + locked.size(); }
    public boolean blocks(UUID owner) {
        if (states.getPlayerConflicts().blocks(owner)) return true;
        StateData state = states.getState(owner);
        return state != null && state.isAI() && started && (closed || !failure.isEmpty() || phase.equals("OPENING") || locked.contains(owner));
    }
    public boolean idle() { return started && !closed && !states.getPlayerConflicts().busy() && sequence < Long.MAX_VALUE && failure.isEmpty() && io == null && applying == null && phase.equals("IDLE"); }

    /** Only already-initialized masks participate; this service never requests Minecraft terrain. */
    public static boolean connected(ClaimManager claims, long from, long to) {
        var a = claims.getStrategicRegions().get(from); var b = claims.getStrategicRegions().get(to);
        if (a == null || b == null || Math.abs((long) a.x() - b.x()) + Math.abs((long) a.z() - b.z()) != 1) return false;
        int size = ClaimManager.STRATEGIC_REGION_CHUNKS;
        for (int n = 0; n < size; n++) {
            int ax = a.x() * size + (b.x() > a.x() ? size - 1 : b.x() < a.x() ? 0 : n);
            int az = a.z() * size + (b.z() > a.z() ? size - 1 : b.z() < a.z() ? 0 : n);
            ChunkPos edge = new ChunkPos(ax, az), next = new ChunkPos(ax + Integer.signum(b.x() - a.x()), az + Integer.signum(b.z() - a.z()));
            if (a.isLand(edge) && b.isLand(next) && Objects.equals(a.owner(), claims.getStrategicOwner(edge))
                    && Objects.equals(b.owner(), claims.getStrategicOwner(next))) return true;
        }
        return false;
    }
    public static Route route(StateManager states, ClaimManager claims, UUID owner, long start, long goal, UUID enemy) {
        return new CorridorSearch(owner, start, goal, enemy).advance(states, claims, MAX_SEARCH);
    }
    /*
     * this class performs a search for a route between two strategic regions.
        * It uses a breadth-first search algorithm to explore the neighboring regions and find a valid path.
        * The search is limited by a budget to prevent excessive computation and ensure performance.
        * The result of the search is stored in a Route object, which indicates whether a route was found, blocked, or still pending.
     */
    public static final class CorridorSearch {
        private final UUID owner, enemy;
        private final long start, goal;
        private final ArrayDeque<Long> pending = new ArrayDeque<>();
        private final Map<Long, Long> parents = new HashMap<>();
        private final Map<Long, Integer> depths = new HashMap<>();
        private Route result;
        public CorridorSearch(UUID owner, long start, long goal, UUID enemy) {
            this.owner = Objects.requireNonNull(owner); this.start = start; this.goal = goal; this.enemy = enemy;
            pending.add(start); parents.put(start, start); depths.put(start, 0);
        }
        public Route advance(StateManager states, ClaimManager claims, int budget) {
            if (result != null) return result;
            if (!claims.getStrategicRegions().containsKey(start) || !claims.getStrategicRegions().containsKey(goal)) return new Route(RouteStatus.PENDING, List.of());
            StateData origin = states.getState(claims.getStrategicRegions().get(start).owner());
            if (origin == null || !origin.isAI() || !goalAllowed(states, claims, owner, goal, enemy)) return result = new Route(RouteStatus.BLOCKED, List.of());
            for (int work = 0; !pending.isEmpty() && work < Math.clamp(budget, 0, MAX_SEARCH); work++) {
                long cell = pending.removeFirst();
                if (cell == goal) {
                    List<Long> path = new ArrayList<>(); long current = cell;
                    do { path.add(current); if (current == start) break; current = parents.get(current); } while (true);
                    Collections.reverse(path); return result = new Route(RouteStatus.FOUND, path);
                }
                if (depths.get(cell) >= MAX_PATH - 1) continue;
                var area = claims.getStrategicRegions().get(cell);
                for (int[] d : DIRECTIONS) {
                    long key = new ChunkPos(area.x() + d[0], area.z() + d[1]).pack(); var next = claims.getStrategicRegions().get(key);
                    if (next == null || parents.containsKey(key) || next.owner() == null) continue;
                    StateData nation = states.getState(next.owner());
                    if (nation == null || !nation.isAI() || nation.isInitializationPending()) continue;
                    boolean allowed = owner.equals(next.owner()) || states.hasPassage(owner, next.owner()) || next.owner().equals(enemy);
                    if (allowed && connected(claims, cell, key)) { parents.put(key, cell); depths.put(key, depths.get(cell) + 1); pending.addLast(key); }
                }
            }
            if (pending.isEmpty()) return result = new Route(RouteStatus.BLOCKED, List.of());
            return new Route(RouteStatus.PENDING, List.of());
        }
    }

    /**
     * Finds a route between two strategic regions.
     * @param claims The claim manager.
     * @param owner The owner of the starting region.
     * @param start The position of the starting region.
     * @param goal The position of the goal region.
     * @param enemy The enemy player.
     * @return A Route object representing the result of the search.
     */
    private Route findRoute(ClaimManager claims, UUID owner, long start, long goal, UUID enemy) {
        RouteKey key = new RouteKey(owner, start, goal, enemy, claims.getTopologyRevision(), states.getLogisticsRevision());
        Route known = routes.get(key); if (known != null) return known;
        if (claims.getStrategicRegions().containsKey(goal) && !goalAllowed(states, claims, owner, goal, enemy)) return new Route(RouteStatus.BLOCKED, List.of());
        if (start == goal || connected(claims, start, goal)) return route(states, claims, owner, start, goal, enemy);
        if (!searches.containsKey(key) && searches.size() < 16) searches.put(key, new CorridorSearch(owner, start, goal, enemy));
        return new Route(RouteStatus.PENDING, List.of());
    }
    
    //tracks whether a goal region is allowed for a given owner and enemy player.
    private static boolean goalAllowed(StateManager states, ClaimManager claims, UUID owner, long goal, UUID enemy) {
        var area = claims.getStrategicRegions().get(goal); if (area == null) return false;
        StateData nation = states.getState(area.owner());
        return nation != null && nation.isAI() && !nation.isInitializationPending()
                && (owner.equals(area.owner()) || states.hasPassage(owner, area.owner()) || area.owner().equals(enemy));
    }

    //advances the search for routes between strategic regions, removing any searches that are no longer valid due to changes in the topology or diplomacy.
    private void advanceSearch(ClaimManager claims) {
        long topology = claims.getTopologyRevision(), diplomacy = states.getLogisticsRevision();
        searches.keySet().removeIf(key -> key.claims() != topology || key.diplomacy() != diplomacy);
        routes.keySet().removeIf(key -> key.claims() != topology || key.diplomacy() != diplomacy);
        if (searches.isEmpty()) return;
        var first = searches.entrySet().iterator().next(); RouteKey key = first.getKey(); CorridorSearch job = first.getValue(); searches.remove(key);
        Route route = job.advance(states, claims, MAX_SEARCH);
        if (route.status() == RouteStatus.PENDING) searches.put(key, job);
        else { if (routes.size() >= 64) routes.clear(); routes.put(key, route); }
    }

    public static double power(ArmyManager.StrategicForce force) {
        double equipment = 1 + force.gear().ordinal() * .25;
        double readiness = (.25 + force.morale() / 100.0) * (force.supply() > 0 ? 1 : .35) * (1 - force.fatigue() / 100.0);
        return (force.infantry() + force.cavalry() * 1.5) * equipment * readiness * (1 + Math.min(2000, force.experience()) / 10000.0);
    }
    /**
     * Determines if a strategic force is capable of holding its ground.
     * @param force The strategic force to evaluate.
     * @return true if the force is capable, false otherwise.
     */
    public static boolean capable(ArmyManager.StrategicForce force) { return force.soldiers() > Legion.AUTO_DISBAND_STRENGTH; }
    public static Defeat assess(boolean major, boolean complete, int settlements, boolean military, boolean capitalLost,
                                long ownSoldiers, long enemySoldiers, int unpaidDays, long deadline, long now) {
        if (!major) return Defeat.NONE;
        if (!complete) return Defeat.PENDING;
        boolean survives = settlements > 0 && military;
        if (deadline >= 0) return now < deadline ? Defeat.SURRENDER : survives ? Defeat.RECOVER : Defeat.DISSOLVE;
        if (!survives || capitalLost && (settlements <= 1 || ownSoldiers < enemySoldiers / 2) || unpaidDays >= 3 && ownSoldiers < enemySoldiers)
            return Defeat.SURRENDER;
        return Defeat.RECOVER;
    }

    //this method checks if a state is eligible for macro-level operations, such as declaring war or making strategic decisions
    private boolean macroOnly(StateData state, ArmyManager armies, ClaimManager claims) {
        return state != null && state.isAI() && !state.isInitializationPending() && claims.getConcreteClaimCount(state.getStateId()) == 0
                && armies.getLegionsForState(state.getStateId()).isEmpty() && armies.getArmiesForState(state.getStateId()).isEmpty()
                && states.settlementsFor(state).stream().allMatch(SettlementData::isAbstractSettlement)
                && states.enemiesFor(state.getStateId()).stream().allMatch(StateData::isAI);
    }
    private static BlockPos center(long key, int y) {
        ChunkPos p = ChunkPos.unpack(key); return new BlockPos(p.x() * RegionInfo.REGION_BLOCKS + RegionInfo.REGION_BLOCKS / 2, y,
                p.z() * RegionInfo.REGION_BLOCKS + RegionInfo.REGION_BLOCKS / 2);
    }
    private static long region(BlockPos p) { return ClaimManager.regionKey(ChunkPos.containing(p)); }
    private static long distance(BlockPos a, BlockPos b) { return Math.abs((long) a.getX() - b.getX()) + Math.abs((long) a.getZ() - b.getZ()); }

    //this method finds an anchor point within a strategic region for a given state, based on the claims of the territories it owns.
    private static BlockPos anchor(ClaimManager claims, long key, int y) {
        var area = claims.getStrategicRegions().get(key); if (area == null) return null;
        int size = ClaimManager.STRATEGIC_REGION_CHUNKS, count = size * size;
        BitSet land = BitSet.valueOf(area.landMask()); int first = size / 2 * size + size / 2;
        for (int offset = 0; offset < count; offset++) {
            int cell = (first + offset) % count; if (!land.get(cell)) continue;
            ChunkPos chunk = new ChunkPos(area.x() * size + cell % size, area.z() * size + cell / size);
            if (Objects.equals(area.owner(), claims.getStrategicOwner(chunk))) return new BlockPos(chunk.x() * 16 + 8, y, chunk.z() * 16 + 8);
        }
        return null;
    }

    //this method finds the best supply route for a strategic force based on its position and the claims of the territories it passes through.
    private Route supply(ArmyManager.StrategicForce force, ClaimManager claims) {
        SupplyKey key = new SupplyKey(force.owner(), force.position().asLong(), claims.getTopologyRevision(), states.getLogisticsRevision());
        Route cached = supplyCache.get(key); if (cached != null) return cached;
        Route best = new Route(RouteStatus.BLOCKED, List.of());
        for (SettlementData site : states.settlementsFor(states.getState(force.owner()))) {
            if (!site.isSupplyReady() || distance(site.getCenterAltarPos(), force.position()) > StateBalance.SUPPLY_RANGE) continue;
            Route candidate = findRoute(claims, force.owner(), region(site.getCenterAltarPos()), region(force.position()), null);
            if (candidate.status() == RouteStatus.PENDING) best = candidate;
            if (candidate.status() != RouteStatus.FOUND) continue;
            long length = 0; BlockPos previous = site.getCenterAltarPos();
            for (int i = 1; i < candidate.regions().size(); i++) {
                BlockPos next = i == candidate.regions().size() - 1 ? force.position() : center(candidate.regions().get(i), previous.getY());
                length += distance(previous, next); previous = next;
            }
            if (length <= StateBalance.SUPPLY_RANGE) { best = candidate; break; }
        }
        if (best.status() != RouteStatus.PENDING) {
            if (supplyCache.size() >= 256) supplyCache.clear(); supplyCache.put(key, best);
        }
        return best;
    }

    /**
     * Called inside the same four-state finance/decision visit budget, even during decision cooldowns.
     * @param level The server level.
     * @param state The state data.
     */
    public void visit(ServerLevel level, StateData state) {
        ArmyManager armies = ArmyManager.get(level); ClaimManager claims = ClaimManager.get(level); long now = level.getGameTime();
        advanceSearch(claims);
        if (blocks(state.getStateId()) || state.hasEconomicDayDue(now)) return;
        if (!macroOnly(state, armies, claims)) { states.getPlayerConflicts().assessDetailed(level, state); return; }
        List<ArmyManager.StrategicForce> forces = armies.getStrategicForcesForState(state.getStateId());
        if (forces.isEmpty()) { evaluateDefeat(level, state, armies, claims); return; }
        var force = forces.stream().min(Comparator.comparingLong(ArmyManager.StrategicForce::militaryTime).thenComparing(ArmyManager.StrategicForce::id)).orElseThrow();
        UUID ground = claims.getStrategicOwner(ChunkPos.containing(force.position()));
        StateData groundNation = states.getState(ground);
        if (groundNation == null || !groundNation.isAI()) { force.deferMilitaryTime(now); armies.setDirty(); return; } // Unknown/player stakes wait for the detailed adapter without shortage penalties.
        if (force.militaryTime() < 0) { force.initializeMilitaryTime(now); armies.setDirty(); evaluateDefeat(level, state, armies, claims); continuePlanning(level, state, force, armies, claims); return; }
        if (now < force.militaryTime() || now - force.militaryTime() < INTERVAL) { evaluateDefeat(level, state, armies, claims); continuePlanning(level, state, force, armies, claims); return; }
        Route source = supply(force, claims);
        if (source.status() == RouteStatus.PENDING) { force.deferMilitaryTime(now); armies.setDirty(); return; }
        force.advanceStrategicSupply(INTERVAL, source.status() == RouteStatus.FOUND, source.regions()); force.completeMilitaryInterval(INTERVAL); armies.setDirty();
        evaluateDefeat(level, state, armies, claims);
        if (blocks(state.getStateId()) || state.getStrategy() == StateData.Strategy.SURRENDER && !force.retreating()) return;
        if (force.pendingDeclaration()) { continuePlanning(level, state, force, armies, claims); return; }
        if (force.targetSettlement() == null) return;
        SettlementData target = states.getSettlement(force.targetSettlement());
        StateData enemy = target == null ? null : states.getState(target.getOwningStateId());
        if (target == null || !macroOnly(enemy, armies, claims) || blocks(enemy.getStateId())) { force.clearOrder(); return; }
        if (!force.retreating() && (!enemy.getStateId().equals(force.targetState()) || !states.areAtWar(force.owner(), enemy.getStateId()))) { force.clearOrder(); return; }
        if (!force.retreating() && !force.ready()) return;
        Route path = findRoute(claims, force.owner(), region(force.position()), region(target.getCenterAltarPos()), force.retreating() ? null : enemy.getStateId());
        if (path.status() != RouteStatus.FOUND) return;
        if (path.regions().size() > 1) {
            long next = path.regions().get(1);
            if (next != region(target.getCenterAltarPos())) {
                var crossing = claims.getStrategicRegions().get(next);
                if (!force.retreating() && enemy.getStateId().equals(crossing.owner())) {
                    //if the force is not retreating and the enemy owns the next region, we need to consider the implications of this.
                    if (idle() && militaryCurrent(state.getStateId(), armies, now) && militaryCurrent(enemy.getStateId(), armies, now)
                            && !enemy.hasEconomicDayDue(now) && enemy.getLastEconomyTime() >= 0 && capturable(state, enemy, next, now, states, claims)) {
                        SettlementData focus = states.settlementsFor(enemy).stream().filter(s -> region(s.getCenterAltarPos()) == next).sorted(Comparator.comparing(SettlementData::getSettlementId)).findFirst().orElse(null);
                        prepare(level, encounterRegion(nextSequence(), now, force, enemy, next, focus, states, claims, armies));
                    }
                    return;
                }
                BlockPos point = anchor(claims, next, force.position().getY());
                if (point != null) force.moveStrategically(point, path.regions().subList(1, path.regions().size())); return;
            }
        }
        if (force.retreating()) { force.moveStrategically(target.getCenterAltarPos(), List.of()); force.clearOrder(); return; }
        if (target.isLocallyImmune() || enemy.getSiegeImmunityTicks() > 0) return;
        boolean defended = armies.getStrategicForcesForState(enemy.getStateId()).stream().anyMatch(f -> region(f.position()) == region(target.getCenterAltarPos()));
        if (!defended && !capturable(state, enemy, region(target.getCenterAltarPos()), now, states, claims)) return;
        if (idle() && militaryCurrent(state.getStateId(), armies, now) && militaryCurrent(enemy.getStateId(), armies, now)
                && !enemy.hasEconomicDayDue(now) && enemy.getLastEconomyTime() >= 0)
            prepare(level, encounter(nextSequence(), now, force, target, states, claims, armies));
    }

    private static boolean militaryCurrent(UUID owner, ArmyManager armies, long now) {
        return armies.getStrategicForcesForState(owner).stream().allMatch(f -> f.militaryTime() >= 0 && now >= f.militaryTime() && now - f.militaryTime() < INTERVAL);
    }
    /*
     * Continues the planning process for a strategic force.
     * @param level The server level.
     * @param state The state data.
     * @param force The strategic force.
     * @param armies The army manager.
     * @param claims The claim manager.
     */
    private void continuePlanning(ServerLevel level, StateData state, ArmyManager.StrategicForce force, ArmyManager armies, ClaimManager claims) {
        if (!force.pendingDeclaration() || blocks(state.getStateId()) || state.getStrategy() == StateData.Strategy.SURRENDER || !idle()) return;
        SettlementData site = states.getSettlement(force.targetSettlement()); StateData enemy = site == null ? null : states.getState(site.getOwningStateId());
        if (!macroOnly(enemy, armies, claims) || !enemy.getStateId().equals(force.targetState()) || states.hasPassage(state.getStateId(), enemy.getStateId())
                || states.hasPassage(enemy.getStateId(), state.getStateId())) { force.clearOrder(); armies.setDirty(); return; }
        long now = level.getGameTime();
        if (blocks(enemy.getStateId()) || !force.ready() || !militaryCurrent(state.getStateId(), armies, now) || !militaryCurrent(enemy.getStateId(), armies, now)
                || enemy.getLastEconomyTime() < 0 || enemy.hasEconomicDayDue(now)) return;
        Route path = findRoute(claims, force.owner(), region(force.position()), region(site.getCenterAltarPos()), enemy.getStateId());
        if (path.status() == RouteStatus.PENDING) return;
        if (path.status() == RouteStatus.BLOCKED || !AIStateController.canSpend(state.getTreasuryBalance(), EconomyTickHandler.calculateMaintenance(state, states, armies), 0, 0)) {
            force.clearOrder(); armies.setDirty(); return;
        }
        if (states.areAtWar(state.getStateId(), enemy.getStateId())) { force.order(enemy.getStateId(), site.getSettlementId(), path.regions(), false); armies.setDirty(); return; }
        List<ArmyManager.StrategicForce> after = participantForces(armies, Set.of(state.getStateId(), enemy.getStateId()));
        after.stream().filter(f -> f.id().equals(force.id())).findFirst().orElseThrow().order(enemy.getStateId(), site.getSettlementId(), path.regions(), false);
        prepare(level, snapshot(nextSequence(), now, Kind.DECLARE, state, enemy, null, null, states, after, List.of()));
    }

    //this function checks if a state has been defeated and takes appropriate actions based on the assessment of the situation
    private void evaluateDefeat(ServerLevel level, StateData state, ArmyManager armies, ClaimManager claims) {
        if (state.getMajorDefeatTime() < 0) return;
        long now = level.getGameTime(); StateData opponent = states.getState(state.getDefeatOpponent());
        long enemy = opponent == null ? 0 : armies.getTotalSoldiersForState(opponent.getStateId());
        boolean military = false, complete = true;
        for (var survivor : armies.getStrategicForcesForState(state.getStateId())) if (capable(survivor)) {
            Route recovery = supply(survivor, claims); complete &= recovery.status() != RouteStatus.PENDING;
            military |= survivor.supply() > 0 || recovery.status() == RouteStatus.FOUND;
        }
        Defeat verdict = assess(true, (complete || military) && militaryCurrent(state.getStateId(), armies, now), states.settlementsFor(state).size(), military,
                state.getLastCapitalLossTime() >= state.getMajorDefeatTime(), armies.getTotalSoldiersForState(state.getStateId()), enemy, state.getUnpaidDays(), state.getSurrenderDeadline(), now);
        if (verdict == Defeat.SURRENDER && state.getSurrenderDeadline() < 0) { state.offerSurrender(now); states.setDirty(); }
        if (verdict == Defeat.DISSOLVE && idle()) prepare(level, dissolve(nextSequence(), now, state, opponent, states, claims, armies));
        if (verdict == Defeat.RECOVER) states.recordAIRecovery(state, now);
    }

    /**
     * Considers the state of a given state and determines if any actions are needed.
     * @param level The server level.
     * @param state The state data.
     * @return True if the state is in a position to take action, false otherwise.
     * It checks if the state is idle, has no pending operations, and is not in a surrender state.
     *  If the state meets these conditions, it evaluates the military forces and decides whether to recruit new forces or plan an encounter with an enemy state.
     *  It also checks for potential targets and routes for strategic operations.
     */
    public void consider(ServerLevel level, StateData state) {
        ArmyManager armies = ArmyManager.get(level); ClaimManager claims = ClaimManager.get(level); long now = level.getGameTime();
        if (!idle() || !macroOnly(state, armies, claims) || state.getStrategy() == StateData.Strategy.SURRENDER || state.getUnpaidDays() > 0
                || !militaryCurrent(state.getStateId(), armies, now)
                || !AIStateController.canSpend(state.getTreasuryBalance(), EconomyTickHandler.calculateMaintenance(state, states, armies), 0, 0)) return;
        var ownForces = armies.getStrategicForcesForState(state.getStateId());
        if (ownForces.stream().noneMatch(AIWarManager::capable) && ownForces.size() < state.getCurrentTier().getMaxLegions()
                && AIStateController.canSpend(state.getTreasuryBalance(), EconomyTickHandler.calculateMaintenance(state, states, armies), ArmyManager.LEGION_RECRUIT_COST, Legion.DAILY_UPKEEP_EMERALDS)) {
            var home = states.settlementsFor(state).stream().filter(SettlementData::isSupplyReady).sorted(Comparator.comparing(SettlementData::getSettlementId)).findFirst().orElse(null);
            if (home != null) {
                long next = nextSequence(); List<ArmyManager.StrategicForce> after = new ArrayList<>(participantForces(armies, Set.of(state.getStateId())));
                var recruit = new ArmyManager.StrategicForce(UUID.nameUUIDFromBytes(("minecraftempires:recruit:" + state.getStateId() + ":" + next).getBytes(StandardCharsets.UTF_8)), state.getStateId(), home.getSettlementId(), home.getCenterAltarPos());
                recruit.initializeMilitaryTime(now); after.add(recruit);
                Outcome draft = snapshot(next, now, Kind.RECRUIT, state, null, null, null, states, after, List.of());
                StateData paid = StateData.fromNBT(draft.states().getFirst()); paid.deductFunds(ArmyManager.LEGION_RECRUIT_COST);
                prepare(level, new Outcome(draft.sequence(), draft.time(), draft.kind(), draft.actor(), null, draft.actorName(), "", null, "", null,
                        List.of(paid.toNBT()), draft.settlements(), draft.forces(), draft.regions())); return;
            }
        }
        if (armies.getStrategicForces().stream().filter(force -> force.targetSettlement() != null).count() >= MAX_OPERATIONS) return;
        var force = ownForces.stream().filter(ArmyManager.StrategicForce::ready).filter(f -> f.targetSettlement() == null).sorted(Comparator.comparing(ArmyManager.StrategicForce::id)).findFirst().orElse(null);
        if (force == null) return;
        List<ClaimManager.StrategicRegion> regions = claims.regionsFor(state.getStateId());
        int start = state.advanceFrontierCursor(regions.size(), FRONTIER_VISITS); states.setDirty();
        Set<UUID> candidates = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(FRONTIER_VISITS, regions.size()); i++) {
            var local = regions.get((start + i) % regions.size());
            for (int[] d : DIRECTIONS) {
                long key = new ChunkPos(local.x() + d[0], local.z() + d[1]).pack(); var neighbor = claims.getStrategicRegions().get(key);
                if (neighbor != null && neighbor.owner() != null && !neighbor.owner().equals(state.getStateId()) && connected(claims, local.key(), key)) candidates.add(neighbor.owner());
            }
        }
        for (UUID id : candidates) {
            StateData enemy = states.getState(id);
            if (!macroOnly(enemy, armies, claims) || blocks(id) || !militaryCurrent(id, armies, now) || enemy.hasEconomicDayDue(now) || enemy.getLastEconomyTime() < 0
                    || states.hasPassage(state.getStateId(), id) || states.hasPassage(id, state.getStateId())) continue;
            boolean war = states.areAtWar(state.getStateId(), id);
            if (!war && (RegionInfo.mix(state.getGenerationSeed() ^ state.getStateId().getLeastSignificantBits() ^ now / StateBalance.TICKS_PER_DAY) & 7) != 0) continue;
            double confidence = .85 + (RegionInfo.mix(state.getGenerationSeed() ^ state.getStateId().getMostSignificantBits()) & 3) * .1;
            if (!war && ownForces.stream().mapToDouble(AIWarManager::power).sum()
                    < armies.getStrategicForcesForState(id).stream().mapToDouble(AIWarManager::power).sum() * confidence) continue;
            var site = states.settlementsFor(enemy).stream().filter(s -> !s.isLocallyImmune())
                    .sorted(Comparator.comparingLong((SettlementData s) -> distance(force.position(), s.getCenterAltarPos())).thenComparing(SettlementData::getSettlementId)).findFirst().orElse(null);
            if (site == null) continue;
            Route path = findRoute(claims, state.getStateId(), region(force.position()), region(site.getCenterAltarPos()), id);
            if (path.status() == RouteStatus.BLOCKED) continue;
            state.setStrategicTarget(id);
            if (path.status() == RouteStatus.PENDING) { force.planWar(id, site.getSettlementId()); armies.setDirty(); states.setDirty(); return; }
            if (!war) {
                List<ArmyManager.StrategicForce> after = participantForces(armies, Set.of(state.getStateId(), id));
                after.stream().filter(f -> f.id().equals(force.id())).findFirst().orElseThrow().order(id, site.getSettlementId(), path.regions(), false);
                prepare(level, snapshot(nextSequence(), now, Kind.DECLARE, state, enemy, null, null, states, after, List.of()));
            } else { force.order(id, site.getSettlementId(), path.regions(), false); armies.setDirty(); }
            state.setStrategicTarget(id); states.setDirty(); return;
        }
    }

    private long nextSequence() { return Math.incrementExact(sequence); }
    private static List<ArmyManager.StrategicForce> participantForces(ArmyManager armies, Set<UUID> owners) {
        return owners.stream().sorted().flatMap(id -> armies.getStrategicForcesForState(id).stream()).map(ArmyManager.StrategicForce::copy).toList();
    }
    private static Outcome snapshot(long sequence, long now, Kind kind, StateData actor, StateData target, SettlementData focus, UUID retired,
                                    StateManager states, List<ArmyManager.StrategicForce> forces, List<RegionChange> regions) {
        Set<UUID> owners = retired != null || target == null ? Set.of(actor.getStateId()) : Set.of(actor.getStateId(), target.getStateId());
        List<CompoundTag> stateTags = owners.stream().sorted().map(states::getState).map(StateData::toNBT).toList();
        List<CompoundTag> sites = owners.stream().sorted().map(states::getState).flatMap(s -> states.settlementsFor(s).stream())
                .filter(s -> !s.getOwningStateId().equals(retired)).map(SettlementData::toNBT).toList();
        return new Outcome(sequence, now, kind, actor.getStateId(), target == null ? null : target.getStateId(), actor.getStateName(), target == null ? "" : target.getStateName(),
                focus == null ? null : focus.getSettlementId(), focus == null ? "" : focus.getSettlementName(), retired, stateTags, sites,
                forces.stream().map(ArmyManager.StrategicForce::toNBT).toList(), regions);
    }
    public static Outcome dissolve(long sequence, long now, StateData donor, StateData opponent, StateManager states, ClaimManager claims, ArmyManager armies) {
        List<RegionChange> regions = claims.regionsFor(donor.getStateId()).stream().map(r -> new RegionChange(r.key(), donor.getStateId(), r.revision(), null)).toList();
        return snapshot(sequence, now, Kind.DISSOLVE, donor, opponent, null, donor.getStateId(), states, List.of(), regions);
    }

    public static Outcome encounter(long sequence, long now, ArmyManager.StrategicForce attacker, SettlementData site,
                                    StateManager states, ClaimManager claims, ArmyManager armies) {
        return encounterRegion(sequence, now, attacker, states.getState(site.getOwningStateId()), region(site.getCenterAltarPos()), site, states, claims, armies);
    }
    /**
     * Handles an encounter between two strategic forces in a specific region.
     * @param sequence The sequence number of the encounter.
     * @param now The current game time.
     * @param attacker The attacking strategic force.
     * @param defender The defending state data.
     * @param destination The destination region key.
     * @param site The settlement data of the encounter site (if applicable).
     * @param states The state manager.
     * @param claims The claim manager.
     * @param armies The army manager.
     * @return An Outcome object representing the result of the encounter.
     * @throws IllegalArgumentException if the encounter is not valid.
     *the method checks various conditions to ensure that the encounter is valid, such as verifying that both the attacker and defender are AI-controlled states, 
     that they are at war, and that the destination region is appropriate.
      It calculates the power of both forces, determines the outcome of the encounter (win or loss), applies losses to both sides, and handles potential capture of the region.
       The method also updates the state and settlement data accordingly and returns an Outcome object summarizing the results of the encounter.
     */
    private static Outcome encounterRegion(long sequence, long now, ArmyManager.StrategicForce attacker, StateData defender, long destination, SettlementData site,
                                           StateManager states, ClaimManager claims, ArmyManager armies) {
        StateData actor = states.getState(attacker.owner());
        if (actor == null || defender == null || !actor.isAI() || !defender.isAI() || actor.getStateId().equals(defender.getStateId())
                || !states.areAtWar(actor.getStateId(), defender.getStateId()) || site != null && !site.isAbstractSettlement())
            throw new IllegalArgumentException("An aggregate encounter requires two abstract AI belligerents");
        List<ArmyManager.StrategicForce> all = participantForces(armies, Set.of(actor.getStateId(), defender.getStateId()));
        var attack = all.stream().filter(f -> f.id().equals(attacker.id())).findFirst().orElseThrow();
        List<ArmyManager.StrategicForce> defense = all.stream().filter(f -> f.owner().equals(defender.getStateId()) && region(f.position()) == destination).toList();
        double a = power(attack), d = defense.stream().mapToDouble(AIWarManager::power).sum(); boolean won = a > d * 1.1;
        int attackLoss = (int) Math.ceil(attack.soldiers() * (won ? .12 + .28 * d / Math.max(1, a) : .55));
        attack.sufferStrategicLosses(defense.isEmpty() ? 0 : attackLoss, won ? 5 : 30, defense.isEmpty() ? 0 : 50);
        for (var force : defense) force.sufferStrategicLosses((int) Math.ceil(force.soldiers() * (won ? .65 : .15 + .15 * a / Math.max(1, d))), won ? 30 : 5, 50);
        List<RegionChange> changes = new ArrayList<>(); boolean capture = won && capturable(actor, defender, destination, now, states, claims);
        if (capture) {
            var region = claims.getStrategicRegions().get(destination);
            changes.add(new RegionChange(destination, defender.getStateId(), region.revision(), actor.getStateId()));
            BlockPos arrival = site == null ? anchor(claims, destination, attack.position().getY()) : site.getCenterAltarPos();
            if (arrival == null) throw new IllegalStateException("Captured region has no legal abstract arrival");
            attack.moveStrategically(arrival, List.of());
            if (attack.targetSettlement() == null || site != null && site.getSettlementId().equals(attack.targetSettlement())) attack.clearOrder();
        }
        if (!won) withdraw(attack, actor, states, claims, null);
        if (won) for (var force : defense) withdraw(force, defender, states, claims, capture ? destination : null);
        Outcome base = snapshot(sequence, now, capture ? Kind.CAPTURE : Kind.CLASH, actor, defender, capture ? site : null, null, states,
                all.stream().filter(f -> f.soldiers() > 0).toList(), changes);
        List<CompoundTag> sites = new ArrayList<>();
        boolean capitalLost = false;
        for (CompoundTag tag : base.settlements()) {
            SettlementData next = SettlementData.fromNBT(tag);
            if (capture && defender.getStateId().equals(next.getOwningStateId()) && region(next.getCenterAltarPos()) == destination) {
                capitalLost |= next.getSettlementId().equals(defender.getCapitalSettlementId()); next.setOwningStateId(actor.getStateId());
                next.setLocalSiegeImmunityTicks(StateBalance.CAPTURE_IMMUNITY_TICKS); next.setSupplyPreparationTicks(StateBalance.CAPTURED_SUPPLY_PREPARATION_TICKS);
            }
            sites.add(next.toNBT());
        }
        List<CompoundTag> stateTags = new ArrayList<>();
        for (CompoundTag tag : base.states()) {
            StateData next = StateData.fromNBT(tag); UUID id = next.getStateId();
            long before = armies.getTotalSoldiersForState(id), after = all.stream().filter(f -> f.owner().equals(id)).mapToLong(ArmyManager.StrategicForce::soldiers).sum();
            boolean lost = before > 0 && (before - after) * 100 >= before * MAJOR_LOSS_PERCENT;
            lost |= armies.getStrategicForcesForState(id).stream().anyMatch(AIWarManager::capable)
                    && all.stream().filter(f -> f.owner().equals(id)).noneMatch(AIWarManager::capable);
            if (id.equals(defender.getStateId()) && capitalLost) { next.recordCapitalLoss(now); lost = true; }
            if (lost || sites.stream().map(SettlementData::fromNBT).noneMatch(s -> s.getOwningStateId().equals(id))) next.recordMajorDefeat(now, id.equals(actor.getStateId()) ? defender.getStateId() : actor.getStateId());
            stateTags.add(next.toNBT());
        }
        return new Outcome(base.sequence(), base.time(), base.kind(), base.actor(), base.target(), base.actorName(), base.targetName(), base.focus(), base.focusName(), null,
                stateTags, sites, base.forces(), base.regions());
    }
    private static void withdraw(ArmyManager.StrategicForce force, StateData owner, StateManager states, ClaimManager claims, Long excluded) {
        var home = states.settlementsFor(owner).stream().sorted(Comparator.comparingLong(s -> distance(force.position(), s.getCenterAltarPos())))
                .filter(s -> excluded == null || region(s.getCenterAltarPos()) != excluded)
                .filter(s -> route(states, claims, owner.getStateId(), region(force.position()), region(s.getCenterAltarPos()), null).status() == RouteStatus.FOUND).findFirst().orElse(null);
        if (home == null) { force.clearOrder(); return; }
        Route path = route(states, claims, owner.getStateId(), region(force.position()), region(home.getCenterAltarPos()), null);
        force.order(owner.getStateId(), home.getSettlementId(), path.regions(), true);
    }
    private static boolean capturable(StateData actor, StateData defender, long key, long now, StateManager states, ClaimManager claims) {
        var region = claims.getStrategicRegions().get(key);
        if (region == null || !defender.getStateId().equals(region.owner()) || defender.getSiegeImmunityTicks() > 0) return false;
        for (SettlementData site : states.settlementsFor(defender)) if (region(site.getCenterAltarPos()) == key && (!site.isAbstractSettlement() || site.isLocallyImmune()
                || !defender.getStateId().equals(claims.getStrategicOwner(ChunkPos.containing(site.getCenterAltarPos()))))) return false;
        return states.canAcquireChunks(actor, claims.getClaimCountForState(actor.getStateId()), (int) claims.getRegionalContribution(key));
    }

    //this method applies the outcome of a macro operation to the game state, ensuring that the changes are consistent and valid
    public static void apply(Outcome outcome, StateManager states, ClaimManager claims, ArmyManager armies) {
        if ((states.getMacroSequence() > outcome.sequence() || claims.getMacroSequence() > outcome.sequence() || armies.getMacroSequence() > outcome.sequence())
                && (states.getMacroSequence() < outcome.sequence() || claims.getMacroSequence() < outcome.sequence() || armies.getMacroSequence() < outcome.sequence()))
            throw new IllegalStateException("Stale macro outcome cannot repair a newer store");
        states.validateMacroOutcome(outcome);
        if (claims.getMacroSequence() < outcome.sequence()) {
            for (RegionChange change : outcome.regions()) {
                var region = claims.getStrategicRegions().get(change.key());
                if (region == null || !Objects.equals(region.owner(), change.owner()) || region.revision() != change.revision()) throw new IllegalStateException("Macro territory revision conflict");
            }
        }
        if (armies.getMacroSequence() < outcome.sequence()) armies.applyMacroForces(outcome.sequence(), outcome.participants(), outcome.forces().stream().map(ArmyManager.StrategicForce::fromNBT).toList());
        if (claims.getMacroSequence() < outcome.sequence()) {
            for (RegionChange change : outcome.regions()) if (!claims.transferStrategicRegion(change.key(), change.owner(), change.revision(), change.recipient())) throw new IllegalStateException("Macro transfer was not applied");
            claims.recordMacroSequence(outcome.sequence());
        }
        states.applyMacroOutcome(outcome);
    }

    //this method prepares the game state for a macro operation, ensuring that all participants are in a safe financial and representation boundary before proceeding
    public void prepare(ServerLevel level, Outcome outcome) {
        if (!idle() || outcome.sequence() != nextSequence()) throw new IllegalStateException("Macro outcome admission unavailable");
        ArmyManager armies = ArmyManager.get(level); ClaimManager claims = ClaimManager.get(level);
        for (UUID owner : outcome.participants()) {
            StateData state = states.getState(owner);
            if (!macroOnly(state, armies, claims) || state.getLastEconomyTime() < 0 || state.hasEconomicDayDue(level.getGameTime())) throw new IllegalStateException("Macro participant not at a safe financial/representation boundary");
        }
        CompoundTag tag = journal(level.getSeed(), outcome.sequence(), outcome);
        if (tag.sizeInBytes() > JOURNAL_BYTES - 65536) {
            failure = "Outcome exceeds the supported journal budget; no assets were changed"; phase = "BLOCKED"; return;
        }
        applying = outcome; locked.addAll(outcome.participants()); phase = "PREPARING";
        io = CompletableFuture.runAsync(() -> write(file, tag));
    }
    public void tick(ServerLevel level) {
        if (closed || !failure.isEmpty() || lastTick == level.getGameTime()) return;
        lastTick = level.getGameTime();
        try {
            ArmyManager armies = ArmyManager.get(level); ClaimManager claims = ClaimManager.get(level);
            if (!started) {
                started = true; phase = "OPENING"; file = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/minecraftempires_ai/war-outcome.dat");
                long seed = level.getSeed(), s = states.getMacroSequence(), c = claims.getMacroSequence(), a = armies.getMacroSequence();
                io = CompletableFuture.supplyAsync(() -> open(file, seed, s, c, a)); return;
            }
            if (io == null || !io.isDone()) return;
            Object result = io.join(); io = null;
            switch (phase) {
                case "OPENING" -> {
                    Journal journal = (Journal) result; sequence = journal.sequence(); applying = journal.outcome(); phase = "IDLE";
                    if (applying != null) { locked.addAll(applying.participants()); install(level); }
                }
                case "PREPARING" -> install(level);
                case "SAVING" -> {
                    phase = "VERIFYING"; Outcome plan = applying; long seed = level.getSeed();
                    Path data = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data");
                    io = CompletableFuture.runAsync(() -> {
                        verify(StateManager.TYPE.id().withSuffix(".dat").resolveAgainst(data), plan.sequence());
                        verify(ClaimManager.TYPE.id().withSuffix(".dat").resolveAgainst(data), plan.sequence());
                        verify(ArmyManager.TYPE.id().withSuffix(".dat").resolveAgainst(data), plan.sequence());
                        write(file, journal(seed, plan.sequence(), null));
                    });
                }
                case "VERIFYING" -> {
                    sequence = applying.sequence(); Set<UUID> participants = applying.participants(); applying = null; locked.clear(); supplyCache.clear(); searches.clear(); routes.clear(); phase = "IDLE";
                    for (UUID owner : participants) states.refreshProgression(level, states.getState(owner));
                }
                default -> throw new IllegalStateException("Unexpected macro checkpoint phase");
            }
        } catch (RuntimeException error) {
            failure = error.getCause() == null ? error.toString() : error.getCause().toString(); phase = "BLOCKED";
            System.getLogger(AIWarManager.class.getName()).log(System.Logger.Level.ERROR, "AI macro operations stopped safely: " + failure);
        }
    }
    /**
     * Installs the current macro operation into the game world.
     * @param level The server level to install the operation into.
     */
    private void install(ServerLevel level) {
        apply(applying, states, ClaimManager.get(level), ArmyManager.get(level)); phase = "SAVING";
        io = level.getDataStorage().scheduleSave();
    }
    public void close() {
        closed = true;
        if (io != null) try { io.join(); } catch (RuntimeException ignored) { /* Prepared journal remains available for recovery. */ }
        io = null; supplyCache.clear(); searches.clear(); routes.clear();
    }
    private static CompoundTag journal(long seed, long sequence, Outcome outcome) {
        CompoundTag tag = new CompoundTag(); tag.putInt("Version", 1); tag.putLong("Seed", seed); tag.putLong("Sequence", sequence);
        tag.putBoolean("Complete", outcome == null); if (outcome != null) tag.put("Outcome", outcome.toNBT()); return tag;
    }
    private static Journal open(Path file, long seed, long state, long claims, long armies) {
        try {
            if (!Files.exists(file)) {
                if (state != 0 || claims != 0 || armies != 0) throw new IOException("Macro checkpoint is missing beside committed domain receipts");
                return new Journal(0, null);
            }
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.create(JOURNAL_BYTES));
            if (tag.getInt("Version").orElse(0) != 1 || tag.getLong("Seed").orElseThrow() != seed) throw new IOException("Unsupported or foreign macro checkpoint");
            long sequence = tag.getLong("Sequence").orElseThrow();
            if (sequence < 1 || state > sequence || claims > sequence || armies > sequence) throw new IOException("Macro sequence conflict");
            if (tag.getBoolean("Complete").orElse(false)) {
                if (state != sequence || claims != sequence || armies != sequence) throw new IOException("Completed macro checkpoint is missing a domain receipt");
                return new Journal(sequence, null);
            }
            Outcome outcome = Outcome.fromNBT(tag.getCompound("Outcome").orElseThrow());
            if (outcome.sequence() != sequence) throw new IOException("Macro outcome identity mismatch");
            return new Journal(sequence - 1, outcome);
        } catch (IOException | RuntimeException error) { throw new CompletionException(error); }
    }
    private static void write(Path file, CompoundTag tag) {
        try {
            AIJournal.write(file, tag);
        } catch (IOException error) { throw new CompletionException(error); }
    }
    private static void verify(Path file, long sequence) {
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.create(STORE_BYTES)).getCompound("data").orElseThrow();
            if (tag.getLong("MacroSequence").orElse(0L) != sequence) throw new IOException("Macro receipt not durable in " + file.getFileName());
        } catch (IOException | RuntimeException error) { throw new CompletionException(error); }
    }
    private static void putTags(CompoundTag tag, String name, List<CompoundTag> values) {
        ListTag list = new ListTag(); values.forEach(value -> list.add(value.copy())); tag.put(name, list);
    }
    private static List<CompoundTag> tags(CompoundTag tag, String name, int max) {
        ListTag list = tag.getList(name).orElseThrow();
        if (list.size() > max) throw new IllegalArgumentException("Oversized macro snapshot");
        List<CompoundTag> result = new ArrayList<>(); for (var value : list) result.add(((CompoundTag) value).copy()); return List.copyOf(result);
    }
}
