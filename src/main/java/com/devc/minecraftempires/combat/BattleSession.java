package com.devc.minecraftempires.combat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import java.util.*;
import org.joml.Vector2d;
import com.devc.minecraftempires.network.MapDataService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Server-owned battle, simulated every five server ticks (four steps per second). */
//this class handles only user or spectated battles, auto resolve ones are in AutoResolveEngine.java
//yo ts i cant keep up anymore ngl
public class BattleSession {
    public enum BattleResult { ONGOING, ATTACKER_WINS, DEFENDER_WINS, INTERRUPTED }
    public enum BattlePhase { PREPARATION, DEPLOYMENT, ENGAGEMENT }
    public enum Formation { LINE, TRIPLEX, WEDGE }
    public static final double COHORT_SPACING = 10.0;
    private static final double MELEE_RANGE = COHORT_SPACING + .5;
    private static final int DEPLOYMENT_STEPS = 120;
    private static final int PREPARATION_LIMIT = 240;
    private static final int INITIAL_GRACE_STEPS = 240;
    private static final int ABANDONMENT_GRACE_STEPS = 40;
    private static final int MELEE_INTERVAL = 8;
    public static final double MELEE_TURN_DEGREES_PER_SECOND = 45;
    // Initial balance: 90-degree front/rear sectors; flanks occupy the remaining two quarters.
    private static final double FACING_SECTOR_COSINE = Math.sqrt(.5);
    public static final double FIELD_BOUND = 160;
    private final UUID battleId;
    private final UUID attackerArmyId;
    private final UUID defenderArmyId;
    private final double mapOriginX;
    private final double mapOriginZ;
    private final List<CohortData> attackerCohorts = new ArrayList<>();
    private final List<CohortData> defenderCohorts = new ArrayList<>();
    private final Set<UUID> spectatingPlayerIds = new HashSet<>();
    private boolean active = true;
    private BattleResult result = BattleResult.ONGOING;
    private BattlePhase phase = BattlePhase.DEPLOYMENT;
    private int deploymentSteps;
    private int idleSteps;
    private int combatSteps;
    private boolean everSpectated;
    private boolean autoResolveRequested;
    private final Formation[] formations = {Formation.LINE, Formation.LINE};
    private final Layout[] layouts = new Layout[2];
    private final boolean[] layoutRejected = new boolean[2];
    private String deploymentMessage = "";
    private record PreviewKey(BlockPos start, BlockPos end, int attackers, int defenders) {}
    private static final Map<ServerLevel, LinkedHashMap<PreviewKey, BattleSession>> deploymentPreviews = new WeakHashMap<>();
    static void clearDeploymentPreviews() { deploymentPreviews.clear(); }
    private long previewRequested;
    private long previewCompletedRevision = Long.MIN_VALUE;
    private static final class Layout {
        final List<CohortData> units;
        final Vector2d origin, forward;
        final Formation formation;
        int cursor;
        boolean pending, waiting;
        final ArrayDeque<Integer> deferred = new ArrayDeque<>();
        Layout(List<CohortData> units, Vector2d origin, Vector2d forward, Formation formation) {
            this.units = units; this.origin = origin; this.forward = forward; this.formation = formation;
        }
    }
    private int preparationSteps, layoutVersion = 1;
    private boolean resumeEngagement, deploymentFailed;
    private boolean deploymentSurveyPending;
    private ServerLevel terrainLevel;
    private record ConnectionKey(BlockPos first, BlockPos second) {}
    private final Map<ConnectionKey, MapDataService.Verification> connections = new LinkedHashMap<>(16, .75f, true);
    private long connectionRevision = Long.MIN_VALUE;
    private record PendingOrder(double x, double z, boolean clear) {}
    private final Map<UUID, Queue<PendingOrder>> pendingOrders = new HashMap<>();
    private final Map<UUID, Long> automaticRouteChecks = new HashMap<>();
    private record PursuitRoute(Vector2d from, Vector2d goal) {}
    private final Map<UUID, PursuitRoute> pursuitRoutes = new HashMap<>();
    private final Map<UUID, Vector2d> pursuitGoals = new HashMap<>();
    private final Map<UUID, Long> pursuitChecks = new HashMap<>();
    private record RejectedApproaches(Vector2d target, long revision, Set<Integer> slots) {}
    private final Map<UUID, RejectedApproaches> rejectedApproaches = new HashMap<>();
    private long pursuitSteps;
    private long avoidanceDeadline;
    private final Set<UUID> legacyOrderIntents = new HashSet<>();
    public void attachTerrain(ServerLevel level) {
        terrainLevel = level;
        // Old saves can contain the coincident coastal stack; repair its placement before resuming combat.
        if (layoutVersion == 0) {
            layoutVersion = 1;
            if (active && phase == BattlePhase.ENGAGEMENT && hasOverlappingAllies()) { resumeEngagement = true; phase = BattlePhase.PREPARATION; }
        }
    }
    public boolean hasTerrain() { return terrainLevel != null; }
    private BlockPos world(Vector2d point) { return BlockPos.containing(mapOriginX + point.x, 64, mapOriginZ + point.y); }

    //opposing unit contact function: checks if two units are in contact with each other, taking into account their positions and the terrain
    public MapDataService.Verification connection(CohortData first, CohortData second) {
        if (terrainLevel == null) return MapDataService.Verification.VERIFIED;
        Vector2d a = first.getPosition(), b = second.getPosition();
        if (Math.abs(a.x) > FIELD_BOUND || Math.abs(a.y) > FIELD_BOUND || Math.abs(b.x) > FIELD_BOUND || Math.abs(b.y) > FIELD_BOUND) return MapDataService.Verification.BLOCKED;
        long revision = MapDataService.terrainRevision(terrainLevel);
        if (revision != connectionRevision) { connections.clear(); connectionRevision = revision; }
        ConnectionKey key = new ConnectionKey(world(a), world(b));
        var cached = connections.get(key); if (cached != null) return cached;
        var direct = MapDataService.verifySegment(terrainLevel, mapOriginX + a.x, mapOriginZ + a.y, mapOriginX + b.x, mapOriginZ + b.y);
        var status = direct == MapDataService.Verification.VERIFIED ? direct : MapDataService.battleRoute(terrainLevel, world(a), world(b), mapOriginX, mapOriginZ).status();
        if (status == MapDataService.Verification.VERIFIED || status == MapDataService.Verification.BLOCKED) {
            if (connections.size() >= 4096) connections.remove(connections.keySet().iterator().next()); connections.put(key, status);
        }
        return status;
    }
    private boolean contact(CohortData first, CohortData second) {
        if (first.isColumn() || second.isColumn()) {
            if (!CohortData.overlaps(first, first.getPosition().x, first.getPosition().y, second, second.getPosition().x, second.getPosition().y, .5)) return false;
        } else if (first.getPosition().distance(second.getPosition()) > MELEE_RANGE) return false;
        return terrainLevel == null || MapDataService.verifySegment(terrainLevel, mapOriginX + first.getPosition().x, mapOriginZ + first.getPosition().y,
                mapOriginX + second.getPosition().x, mapOriginZ + second.getPosition().y) == MapDataService.Verification.VERIFIED;
    }

    /** Recovery runs before connectivity so survivors can continue fighting on their actual bank. */
    public void checkTerrain() {
        if (terrainLevel == null || !active) return;
        for (CohortData cohort : allBodies()) if (cohort.isAlive()) MapDataService.recoverCrossing(terrainLevel, cohort, mapOriginX, mapOriginZ);
        boolean uncertain = false;
        for (CohortData a : attackerCohorts) if (a.canFight()) for (CohortData b : defenderCohorts) if (b.canFight()) {
            var status = connection(a, b);
            if (status == MapDataService.Verification.VERIFIED) return;
            uncertain |= status != MapDataService.Verification.BLOCKED;
        }
        if (!uncertain && attackerCohorts.stream().anyMatch(CohortData::canFight) && defenderCohorts.stream().anyMatch(CohortData::canFight)) finish(BattleResult.INTERRUPTED);
    }
    private List<CohortData> allBodies() { return java.util.stream.Stream.concat(attackerCohorts.stream(), defenderCohorts.stream()).toList(); }

    public BattleSession(UUID attacker, UUID defender, double x, double z) {
        this(UUID.randomUUID(), attacker, defender, x, z);
    }
    private BattleSession(UUID id, UUID attacker, UUID defender, double x, double z) {
        battleId = id;
        attackerArmyId = attacker;
        defenderArmyId = defender;
        mapOriginX = x;
        mapOriginZ = z;
    }
    public boolean isAutoResolveAllowed() {
        return phase == BattlePhase.ENGAGEMENT && (!everSpectated || autoResolveRequested)
                && idleSteps >= (everSpectated ? ABANDONMENT_GRACE_STEPS : INITIAL_GRACE_STEPS);
    }
    public boolean continuesWithoutViewers() { return everSpectated && !autoResolveRequested; }
    public void requestAutoResolve() { autoResolveRequested = true; idleSteps = 0; }
    public String getFormation(boolean attacker) { return formations[attacker ? 0 : 1].name(); }
    public String getDeploymentMessage() { return deploymentMessage; }
    public List<Vector2d> getPendingObjectives(UUID cohort) {
        PursuitRoute pursuit = pursuitRoutes.get(cohort);
        return pursuit == null ? pendingOrders.getOrDefault(cohort, new ArrayDeque<>()).stream().map(order -> new Vector2d(order.x, order.z)).toList()
                : List.of(new Vector2d(pursuit.goal));
    }
    public void resetIdleTicks() { idleSteps = 0; }
    public void tickIdle() { idleSteps++; }

    public void tick() { tick(true, true); }
    public void tickDeployment() {
        if (!active || phase == BattlePhase.ENGAGEMENT) return;
        if (terrainLevel != null) {
            deployStackedCohorts();
            if (hasOverlappingAllies() || layouts[0] != null || layouts[1] != null) {
                phase = BattlePhase.PREPARATION;
                if (++preparationSteps >= PREPARATION_LIMIT && !deploymentSurveyPending) { deploymentFailed = true; finish(BattleResult.INTERRUPTED); }
                return;
            }
            if (phase == BattlePhase.PREPARATION) phase = resumeEngagement ? BattlePhase.ENGAGEMENT : BattlePhase.DEPLOYMENT;
        }
        if (active && phase == BattlePhase.DEPLOYMENT && ++deploymentSteps >= DEPLOYMENT_STEPS) phase = BattlePhase.ENGAGEMENT;
    }

    //unit overlap check: makes sure 2 or more units are not stacking on top of each other
    private boolean hasOverlappingAllies() {
        for (List<CohortData> side : List.of(attackerCohorts, defenderCohorts)) for (int i = 0; i < side.size(); i++) for (int j = i + 1; j < side.size(); j++) {
            CohortData first = side.get(i), second = side.get(j);
            if (first.isAlive() && !first.hasEscaped() && second.isAlive() && !second.hasEscaped()
                    && CohortData.overlaps(first, first.getPosition().x, first.getPosition().y, second, second.getPosition().x, second.getPosition().y, .25)) return true;
        }
        return false;
    }
    public String getResultLabel() { return deploymentFailed ? "INTERRUPTED_NO_SPACE" : result.name(); }
    public static MapDataService.Verification verifyDeployment(ServerLevel level, BlockPos start, BlockPos end, int count) {
        return verifyDeployment(level, start, end, count, count);
    }

    //deployment checker to ensure that the deployment units are valid and not overlapping with each other or with the terrain
    public static MapDataService.Verification verifyDeployment(ServerLevel level, BlockPos start, BlockPos end, int attackers, int defenders) {
        var previews = deploymentPreviews.computeIfAbsent(level, ignored -> new LinkedHashMap<>());
        previews.entrySet().removeIf(entry -> level.getGameTime() - entry.getValue().previewRequested > 2400);
        PreviewKey key = new PreviewKey(start.immutable(), end.immutable(), attackers, defenders);
        BattleSession preview = previews.get(key);
        if (preview != null && preview.previewCompletedRevision != Long.MIN_VALUE && preview.previewCompletedRevision != MapDataService.terrainRevision(level)) preview = null;
        if (preview == null) {
            if (previews.size() >= 32) previews.remove(previews.keySet().iterator().next());
            preview = new BattleSession(UUID.randomUUID(), UUID.randomUUID(), start.getX(), start.getZ()); preview.attachTerrain(level);
            for (int i = 0; i < attackers; i++) {
                preview.addAttackerCohort(new CohortData(UUID.randomUUID(), "INFANTRY", 50, 50, 1, 50, 50, .5, .5));
            }
            for (int i = 0; i < defenders; i++) {
                preview.addDefenderCohort(new CohortData(UUID.randomUUID(), "INFANTRY", 50, 50, 1, 50, 50, end.getX() - start.getX() + .5, end.getZ() - start.getZ() + .5));
            }
            previews.put(key, preview);
        }
        preview.previewRequested = level.getGameTime();
        preview.deployStackedCohorts();
        var status = !preview.hasOverlappingAllies() && preview.layouts[0] == null && preview.layouts[1] == null ? MapDataService.Verification.VERIFIED
                : preview.deploymentSurveyPending ? MapDataService.Verification.PENDING : MapDataService.Verification.BLOCKED;
        if (status != MapDataService.Verification.PENDING) preview.previewCompletedRevision = MapDataService.terrainRevision(level);
        return status;
    }
    /** Strategic stacks deploy as a whole side on verified ground; existing crossing positions are never rearranged. */
    public void deployStackedCohorts() {
        if (terrainLevel == null || phase == BattlePhase.ENGAGEMENT) return;
        deploymentSurveyPending = false;
        for (int side = 0; side < 2; side++) if (layouts[side] == null && !layoutRejected[side]) {
            List<CohortData> units = side == 0 ? attackerCohorts : defenderCohorts;
            if (units.stream().anyMatch(unit -> unit.isAlive() && !unit.isColumn() && !unit.hasEscaped() && units.stream().anyMatch(other -> other != unit && other.isAlive() && !other.hasEscaped()
                    && CohortData.overlaps(unit, unit.getPosition().x, unit.getPosition().y, other, other.getPosition().x, other.getPosition().y, .25)))) queueLayout(side, formations[side], true);
        }
        long deadline = System.nanoTime() + 3_000_000;
        for (int side = 0; side < 2; side++) if (layouts[side] != null) {
            Layout layout = layouts[side];
            int revisits = Math.min(2, layout.deferred.size());
            for (int work = 0; work < 8 && System.nanoTime() < deadline; work++) {
                int candidate;
                if (revisits > 0 || layout.cursor >= 1620 && !layout.deferred.isEmpty()) { candidate = layout.deferred.removeFirst(); revisits--; }
                else if (layout.cursor < 1620) candidate = layout.cursor++;
                else break;
                layout.waiting = false;
                if (tryLayout(side, layout, candidate)) { formations[side] = layout.formation; layouts[side] = null; deploymentMessage = candidate % 5 == 4 ? "Deployment adapted to available ground." : ""; break; }
                // Retain unfinished candidates, but inspect alternatives while a disconnected island's flood completes.
                if (layout.waiting) layout.deferred.addLast(candidate);
            }
            if (layouts[side] != null && layout.cursor >= 1620 && layout.deferred.isEmpty()) {
                layouts[side] = null; layoutRejected[side] = true; deploymentMessage = "No room for that formation on reachable ground.";
            }
            deploymentSurveyPending |= layouts[side] != null;
        }
        if (layouts[0] != null || layouts[1] != null) {
            int total = (int) allBodies().stream().filter(c -> c.isAlive() && !c.hasEscaped()).count();
            int pending = java.util.Arrays.stream(layouts).filter(Objects::nonNull).mapToInt(layout -> layout.units.size()).sum();
            deploymentMessage = "Preparing deployment: " + Math.max(0, total - pending) + "/" + total + " formations placed.";
        }
        if (hasOverlappingAllies() || layouts[0] != null || layouts[1] != null) phase = BattlePhase.PREPARATION;
    }
    private Vector2d sideCenter(List<CohortData> side) {
        return new Vector2d(side.stream().filter(CohortData::isAlive).mapToDouble(c -> c.getPosition().x).average().orElse(0),
                side.stream().filter(CohortData::isAlive).mapToDouble(c -> c.getPosition().y).average().orElse(0));
    }
    private void queueLayout(int side, Formation formation) {
        queueLayout(side, formation, false);
    }
    private void queueLayout(int side, Formation formation, boolean stackedOnly) {
        layoutRejected[side] = false;
        List<CohortData> sideUnits = side == 0 ? attackerCohorts : defenderCohorts;
        List<CohortData> units = sideUnits.stream().filter(c -> c.isAlive() && !c.isColumn() && !c.hasEscaped()).toList();
        if (stackedOnly) {
            // Separate only an actual allied stack. Genuine positions on another bank or already in contact stay put.
            CohortData seed = units.stream().filter(unit -> sideUnits.stream().anyMatch(other -> other != unit && other.isAlive() && !other.hasEscaped()
                    && CohortData.overlaps(unit, unit.getPosition().x, unit.getPosition().y, other, other.getPosition().x, other.getPosition().y, .25))).findFirst().orElse(null);
            Map<BlockPos, List<CohortData>> stacks = new LinkedHashMap<>();
            for (CohortData unit : units) stacks.computeIfAbsent(world(unit.getPosition()), ignored -> new ArrayList<>()).add(unit);
            List<CohortData> largest = stacks.values().stream().max(Comparator.comparingInt(List::size)).orElse(List.of());
            // Nearby bodies may already be on different banks. Pack the coincident stack together;
            // repair a remaining partial overlap one body at a time within that body's reachable ground.
            units = largest.size() > 1 ? List.copyOf(largest) : seed == null ? List.of() : List.of(seed);
        }
        Vector2d origin = sideCenter(units), forward = sideCenter(side == 0 ? defenderCohorts : attackerCohorts).sub(origin);
        // Align the deployment rows to the dominant battle axis; rotating ordinary markers remains separate work.
        forward = Math.abs(forward.x) > Math.abs(forward.y) ? new Vector2d(Math.signum(forward.x), 0) : new Vector2d(0, forward.y == 0 ? (side == 0 ? 1 : -1) : Math.signum(forward.y));
        layouts[side] = new Layout(units, origin, forward, formation);
    }
    private boolean tryLayout(int side, Layout layout, int candidate) {
        int[] offsets = {0, -1, 1, -2, 2, -3, 3, -4, 4};
        int variant = candidate % 5, rotation = candidate / 5 % 4, anchorIndex = candidate / 20;
        boolean adaptive = variant == 4;
        Vector2d gridForward = switch (rotation) {
            case 1 -> new Vector2d(-layout.forward.y, layout.forward.x);
            case 2 -> new Vector2d(layout.forward.y, -layout.forward.x);
            case 3 -> new Vector2d(layout.forward).negate();
            default -> layout.forward;
        };
        int columns = Math.max(1, Math.min(layout.units.size(), layout.formation == Formation.TRIPLEX ? (layout.units.size() + 2) / 3 : 8));
        if (!adaptive && variant > 0) columns = Math.min(columns, variant == 1 ? 5 : variant == 2 ? 3 : 1);
        if (adaptive) columns = columns * 2 - 1;
        Vector2d anchor = new Vector2d(layout.origin).add(layout.forward.x * offsets[anchorIndex / 9] * 12,
                layout.forward.y * offsets[anchorIndex / 9] * 12);
        anchor.add(-layout.forward.y * offsets[anchorIndex % 9] * 12, layout.forward.x * offsets[anchorIndex % 9] * 12);
        Vector2d opposing = sideCenter(side == 0 ? defenderCohorts : attackerCohorts);
        double frontLimit = Math.max(0, new Vector2d(opposing).sub(layout.origin).dot(layout.forward) / 2);
        List<Vector2d> sites = new ArrayList<>();
        int slots = adaptive ? Math.min(512, columns * ((layout.units.size() + columns - 1) / columns + 16)) : layout.units.size();
        for (int slot = 0; slot < slots && sites.size() < layout.units.size(); slot++) {
            CohortData unit = layout.units.get(sites.size()); int row = slot / columns, col = slot % columns;
            double spacing = adaptive ? 6 : 12;
            double lateral = (col - ((adaptive ? columns : Math.min(columns, layout.units.size() - row * columns)) - 1) / 2.0) * spacing;
            double depth = row * spacing + (layout.formation == Formation.WEDGE ? Math.abs(lateral) * .5 : 0);
            Vector2d site = new Vector2d(anchor).add(-gridForward.x * depth - gridForward.y * lateral, -gridForward.y * depth + gridForward.x * lateral);
            if (Math.abs(site.x) > FIELD_BOUND - 7 || Math.abs(site.y) > FIELD_BOUND - 7 || new Vector2d(site).sub(layout.origin).dot(layout.forward) > frontLimit) { if (adaptive) continue; return false; }
            if (sites.stream().anyMatch(reserved -> reserved.distanceSquared(site) < (COHORT_SPACING + .5) * (COHORT_SPACING + .5))) continue;
            boolean occupied = false;
            for (CohortData other : allBodies()) if (!layout.units.contains(other) && other.isAlive() && !other.hasEscaped()
                    && CohortData.overlaps(unit, site.x, site.y, other, other.getPosition().x, other.getPosition().y, .5)) { occupied = true; break; }
            if (occupied) { if (adaptive) continue; return false; }
            var ground = MapDataService.deploymentFormationStatus(terrainLevel, mapOriginX + site.x, mapOriginZ + site.y);
            if (ground != MapDataService.Verification.VERIFIED) { layout.waiting |= ground == MapDataService.Verification.UNKNOWN; layout.pending |= layout.waiting; if (adaptive) continue; return false; }
            var path = MapDataService.deploymentSite(terrainLevel, world(unit.getPosition()), world(site));
            if (path != MapDataService.Verification.VERIFIED) { layout.waiting |= path == MapDataService.Verification.PENDING; layout.pending |= layout.waiting; if (adaptive) continue; return false; }
            sites.add(site);
        }
        if (sites.size() != layout.units.size()) return false;
        // Commit the whole side only after every slot is valid; a failed formation never partially moves it.
        for (int i = 0; i < sites.size(); i++) layout.units.get(i).place(sites.get(i).x, sites.get(i).y);
        return true;
    }
    public void tick(boolean advanceAttacker, boolean advanceDefender) {
        if (!active) return;
        checkTerrain();
        if (!active) return;
        initializeCombatFacing();
        if (phase != BattlePhase.ENGAGEMENT) {
            tickDeployment();
            return;
        }
        retryPlayerOrders();
        retryObstructedMovement();
        // Old snapshots did not save AI intent. Adopt only the side currently controlled by the AI.
        for (CohortData cohort : allBodies()) if (legacyOrderIntents.remove(cohort.getCohortId()))
            cohort.setAutomaticAdvance(isAttackerCohort(cohort.getCohortId()) ? advanceAttacker : advanceDefender);
        updateEngagements();
        pursuitSteps++;
        updateAttackOrders();
        if (advanceAttacker) advanceIdleUnits(attackerCohorts, defenderCohorts);
        if (advanceDefender) advanceIdleUnits(defenderCohorts, attackerCohorts);
        processNewRouts(attackerCohorts, true);
        processNewRouts(defenderCohorts, false);
        List<CohortData> bodies = new ArrayList<>(attackerCohorts);
        bodies.addAll(defenderCohorts);
        for (CohortData cohort : bodies) cohort.tickMovement(next -> resolveMovement(cohort, next, bodies));
        updateEngagements();
        updateCombatFacing(attackerCohorts, defenderCohorts);
        updateCombatFacing(defenderCohorts, attackerCohorts);
        if (++combatSteps >= MELEE_INTERVAL) {
            combatSteps = 0;
            processMelee();
            processMoraleAura(attackerCohorts);
            processMoraleAura(defenderCohorts);
        }
        processNewRouts(attackerCohorts, true);
        processNewRouts(defenderCohorts, false);
        checkBattleEnd();
    }
    // Treat each formation as a body, not a point. Swept tests also prevent fast units crossing one another.
    //just prevents from overlapping with other cohorts, if it does overlap it will try to move in a different direction
    private Vector2d resolveMovement(CohortData cohort, Vector2d next, List<CohortData> bodies) {
        Vector2d start = cohort.getPosition();
        Vector2d delta = new Vector2d(next).sub(start);
        CompoundTag originalShape = cohort.marchState();
        int turn = (cohort.getCohortId().hashCode() & 1) == 0 ? 1 : -1;
        String blocked = "Waiting for another formation";
        // Stable detours handle congestion; short steering never reverses away from the waypoint.
        for (double angle : cohort.getStalledMovementSteps() >= 8 ? new double[]{0} : new double[]{0, turn * Math.PI / 4, -turn * Math.PI / 4}) {
            cohort.readMarch(originalShape);
            double cos = Math.cos(angle), sin = Math.sin(angle);
            Vector2d candidate = new Vector2d(start.x + delta.x * cos - delta.y * sin,
                    start.y + delta.x * sin + delta.y * cos);
            if (Math.abs(candidate.x) > FIELD_BOUND || Math.abs(candidate.y) > FIELD_BOUND) continue;
            if (terrainLevel != null) {
                var ground = MapDataService.surface(terrainLevel, world(candidate));
                var previous = MapDataService.surface(terrainLevel, world(start));
                double slope = ground.height() - previous.height();
                double distance = Math.max(.001, start.distance(candidate));
                double cost = Math.max(previous.travelCost(), ground.travelCost()) * Math.hypot(distance, slope) / distance * (slope > 0 ? 1.25 : 1);
                candidate = new Vector2d(candidate).sub(start).div(Math.max(1, cost)).add(start);
                if (!MapDataService.prepareFormation(terrainLevel, cohort, mapOriginX, mapOriginZ, candidate.x, candidate.y)) { blocked = "Ground or formation clearance pending"; continue; }
            }
            if (clearMovement(cohort, candidate, bodies)) { cohort.setOrderStatus("Moving"); return candidate; }
        }
        cohort.readMarch(originalShape);
        cohort.setOrderStatus(blocked);
        return new Vector2d(start);
    }

    //movement update function, checks if the movement is clear of other cohorts
    private boolean clearMovement(CohortData cohort, Vector2d end, List<CohortData> bodies) {
        return clearMovement(cohort, cohort.getPosition(), end, bodies);
    }
    private boolean clearMovement(CohortData cohort, Vector2d start, Vector2d end, List<CohortData> bodies) {
        double dx = end.x - start.x, dz = end.y - start.y, length = dx * dx + dz * dz;
        for (CohortData other : bodies) {
            if (other == cohort || !other.isAlive() || other.hasEscaped()) continue;
            Vector2d obstacle = other.getPosition();
            double before = start.distanceSquared(obstacle), after = end.distanceSquared(obstacle);
            // Legacy/manual positions may already overlap: allow only movement that separates them.
            boolean column = cohort.isColumn() || other.isColumn();
            if (column ? CohortData.overlaps(cohort, start.x, start.y, other, obstacle.x, obstacle.y, 0) : before < COHORT_SPACING * COHORT_SPACING - 1.0e-8) {
                if (after > before + 1.0e-8 && dx * (start.x - obstacle.x) + dz * (start.y - obstacle.y) >= 0) continue;
                return false;
            }
            if (column) {
                int steps = Math.max(1, (int) Math.ceil(Math.sqrt(length) * 2));
                for (int i = 1; i <= steps; i++) if (CohortData.overlaps(cohort, start.x + dx * i / steps, start.y + dz * i / steps, other, obstacle.x, obstacle.y, 0)) return false;
            } else {
                double t = length == 0 ? 0 : Math.clamp(((obstacle.x - start.x) * dx + (obstacle.y - start.y) * dz) / length, 0, 1);
                double nearX = start.x + t * dx - obstacle.x, nearZ = start.y + t * dz - obstacle.y;
                if (nearX * nearX + nearZ * nearZ < COHORT_SPACING * COHORT_SPACING - 1.0e-8) return false;
            }
        }
        return true;
    }
    /** Replan stalled manual and automatic routes alike, without skipping explicit queued objectives. */
    //for obstructed units, retry function
    private void retryObstructedMovement() {
        List<CohortData> bodies = allBodies();
        avoidanceDeadline = System.nanoTime() + 2_000_000;
        int work = 0;
        for (CohortData cohort : bodies) {
            if (!cohort.isAlive() || cohort.hasEscaped() || cohort.isEngaged() || cohort.getMovementWaypoints().isEmpty() || cohort.getStalledMovementSteps() < 8) continue;
            long now = terrainLevel == null ? combatSteps : terrainLevel.getGameTime();
            if (terrainLevel != null && now - automaticRouteChecks.getOrDefault(cohort.getCohortId(), Long.MIN_VALUE / 2) < 20) continue;
            if (++work > 2) break; // Bound local avoidance work per simulation step.
            automaticRouteChecks.put(cohort.getCohortId(), now);
            if (!cohort.isColumn() && trafficDetour(cohort, bodies)) { cohort.setOrderStatus("Moving around another formation"); continue; }
            if (terrainLevel == null) continue;
            if (cohort.isColumn()) { cohort.setOrderStatus("Waiting for crossing clearance"); continue; }
            Vector2d goal = cohort.getOrderObjectives().getFirst();
            var route = MapDataService.formationRoute(terrainLevel, world(cohort.getPosition()), world(goal), mapOriginX, mapOriginZ);
            if (route.status() != MapDataService.Verification.VERIFIED) {
                cohort.setOrderStatus(route.status() == MapDataService.Verification.BLOCKED || route.status() == MapDataService.Verification.LIMITED ? "No usable formation route" : "Checking a new route"); continue;
            }
            List<Vector2d> old = new ArrayList<>(cohort.getMovementWaypoints()); int through = old.indexOf(goal);
            if (through < 0) through = old.size() - 1;
            List<Vector2d> turns = com.devc.minecraftempires.army.ArmyManager.routeTurns(route.points()).stream()
                    .map(point -> new Vector2d(point.getX() + .5 - mapOriginX, point.getZ() + .5 - mapOriginZ)).toList();
            if (cohort.replaceRoutePrefix(turns, through + 1)) cohort.setOrderStatus("Following a new route");
        }
    }

    //cohort space management method: for 2 blocked cohorts, it will try to find a detour around the other cohort
    private boolean trafficDetour(CohortData cohort, List<CohortData> bodies) {
        Vector2d start = cohort.getPosition(), first = cohort.getMovementWaypoints().peek();
        if (start.distanceSquared(first) < .000001) return false;
        Vector2d direction = new Vector2d(first).sub(start).normalize();
        CohortData obstruction = bodies.stream().filter(other -> other != cohort && other.isAlive() && !other.hasEscaped())
                .filter(other -> other.getPosition().distanceSquared(start) < 24 * 24)
                .filter(other -> new Vector2d(other.getPosition()).sub(start).dot(direction) > -1)
                .min(Comparator.comparingDouble(other -> other.getPosition().distanceSquared(start))).orElse(null);
        if (obstruction == null || cohort.isColumn()) return false;
        List<Vector2d> orders = new ArrayList<>(cohort.getMovementWaypoints());
        Vector2d objective = cohort.getOrderObjectives().getFirst();
        int sign = 1; // The same passing side relative to travel sends opposing traffic to opposite world sides.
        // Reserve a stable pair of corners around the obstruction instead of changing steering every frame.
        for (int side : new int[]{sign, -sign}) {
            Vector2d lateral = new Vector2d(-direction.y, direction.x).mul(side * 12);
            Vector2d corner = new Vector2d(start).add(lateral);
            for (int i = 0; i < orders.size(); i++) {
                Vector2d exit = orders.get(i);
                boolean intermediate = start.distance(exit) > 40;
                if (intermediate) exit = new Vector2d(exit).sub(start).normalize().mul(40).add(start);
                Vector2d far = new Vector2d(obstruction.getPosition()).add(new Vector2d(direction).mul(12)).add(lateral);
                if (Math.abs(corner.x) > FIELD_BOUND - 6 || Math.abs(corner.y) > FIELD_BOUND - 6 || Math.abs(far.x) > FIELD_BOUND - 6 || Math.abs(far.y) > FIELD_BOUND - 6) break;
                if (new Vector2d(exit).sub(obstruction.getPosition()).dot(direction) >= 10
                        && localLegClear(cohort, start, corner, bodies) && localLegClear(cohort, corner, far, bodies) && localLegClear(cohort, far, exit, bodies))
                    return cohort.replaceRoutePrefix(List.of(corner, far, new Vector2d(exit)), intermediate ? i : i + 1);
                if (intermediate || exit.equals(objective)) break;
            }
        }
        return false;
    }
    private boolean localLegClear(CohortData cohort, Vector2d from, Vector2d to, List<CohortData> bodies) {
        return clearMovement(cohort, from, to, bodies) && (terrainLevel == null || System.nanoTime() < avoidanceDeadline && MapDataService.formationSegmentClear(terrainLevel,
                mapOriginX + from.x, mapOriginZ + from.y, mapOriginX + to.x, mapOriginZ + to.y, avoidanceDeadline));
    }
    private void updateEngagements() {
        // Compute the whole set first so continuing contacts do not repeatedly clear orders.
        Set<UUID> contacts = new HashSet<>();
        for (CohortData attacker : attackerCohorts) {
            if (!attacker.isAlive() || attacker.hasEscaped()) continue;
            for (CohortData defender : defenderCohorts) {
                if (defender.isAlive() && !defender.hasEscaped() &&
                        contact(attacker, defender)) {
                    contacts.add(attacker.getCohortId());
                    contacts.add(defender.getCohortId());
                }
            }
        }
        attackerCohorts.forEach(c -> c.setEngaged(contacts.contains(c.getCohortId())));
        defenderCohorts.forEach(c -> c.setEngaged(contacts.contains(c.getCohortId())));
    }
    private void advanceIdleUnits(List<CohortData> allies, List<CohortData> enemies) {
        for (CohortData cohort : allies) {
            if (!cohort.canTakeOrders() || cohort.isEngaged() || cohort.getAttackTarget() != null
                    || pendingOrders.containsKey(cohort.getCohortId())
                    || !cohort.getMovementWaypoints().isEmpty() && !cohort.isAutomaticallyAdvancing()) continue;
            if (terrainLevel != null && !cohort.getMovementWaypoints().isEmpty() && cohort.getStalledMovementSteps() < 8) continue;
            if (terrainLevel != null && terrainLevel.getGameTime() - automaticRouteChecks.getOrDefault(cohort.getCohortId(), Long.MIN_VALUE / 2) < 20) continue;
            if (terrainLevel != null) automaticRouteChecks.put(cohort.getCohortId(), terrainLevel.getGameTime());
            enemies.stream().filter(CohortData::canFight)
                    .min(Comparator.comparingDouble(c -> c.getPosition().distanceSquared(cohort.getPosition())))
                    .ifPresent(target -> {
                        if (terrainLevel == null) cohort.advanceToward(target.getPosition().x, target.getPosition().y);
                        else routeOrder(cohort, target.getPosition().x, target.getPosition().y, true, true);
                    });
        }
    }
    private void processMelee() {
        // One target per cohort avoids multiplying a unit's damage by every nearby enemy.
        Map<CohortData, Integer> hits = new HashMap<>();
        Map<CohortData, Integer> shocks = new HashMap<>();
        collectHits(attackerCohorts, defenderCohorts, hits, shocks);
        collectHits(defenderCohorts, attackerCohorts, hits, shocks);
        // Preserve one morale pulse per damaged target, using its strongest incoming directional shock.
        hits.forEach((target, damage) -> target.applyDamage(damage, shocks.getOrDefault(target, 2)));
    }
    private void collectHits(List<CohortData> side, List<CohortData> opponents, Map<CohortData, Integer> hits, Map<CohortData, Integer> shocks) {
        for (CohortData cohort : side) {
            if (!cohort.canFight() || cohort.isWithdrawing()) continue;
            opponents.stream().filter(c -> c.isAlive() && !c.hasEscaped())
                    .filter(c -> contact(cohort, c))
                    .min(Comparator.<CohortData>comparingInt(c -> targetPriority(cohort, c))
                            .thenComparingDouble(c -> c.getPosition().distanceSquared(cohort.getPosition())).thenComparing(CohortData::getCohortId))
                    .ifPresent(target -> {
                        double strengthFraction = (double) cohort.getCurrentHealth() / cohort.getMaxHealth();
                        double resistance = 1.25 - Math.clamp(target.getEndurance(), 0, 100) / 200.0;
                        int damage = Math.max(1, (int) Math.round(cohort.getStrength() / 50.0 * 3 * strengthFraction * resistance));
                        hits.merge(target, damage, Integer::sum);
                        shocks.merge(target, directionalMoraleShock(target, cohort), Math::max);
                    });
        }
    }

    //start of combat facing methods, sets the facing of the cohorts to face each other
    // If a cohort is already facing an enemy, it keeps that facing until the enemy is gone.
    private void initializeCombatFacing() {
        for (List<CohortData> side : List.of(attackerCohorts, defenderCohorts)) {
            Vector2d opposing = sideCenter(side == attackerCohorts ? defenderCohorts : attackerCohorts);
            for (CohortData cohort : side) if (!cohort.hasCombatFacing()) {
                double dx = opposing.x - cohort.getPosition().x, dz = opposing.y - cohort.getPosition().y;
                if (Math.hypot(dx, dz) < 1.0e-8) cohort.faceCombat(0, side == attackerCohorts ? 1 : -1);
                else cohort.faceCombat(dx, dz);
            }
        }
    }

    //cohort facing update method, updates the facing of the cohorts to face each other
    private void updateCombatFacing(List<CohortData> side, List<CohortData> opponents) {
        for (CohortData cohort : side) {
            if (!cohort.canFight() || cohort.isWithdrawing() || !cohort.isEngaged()) { cohort.setPrimaryOpponent(null); continue; }
            CohortData target = opponents.stream().filter(c -> c.isAlive() && !c.hasEscaped() && contact(cohort, c))
                    .min(Comparator.<CohortData>comparingInt(c -> targetPriority(cohort, c))
                            .thenComparingDouble(c -> c.getPosition().distanceSquared(cohort.getPosition())).thenComparing(CohortData::getCohortId)).orElse(null);
            cohort.setPrimaryOpponent(target == null ? null : target.getCohortId());
            if (target != null && !cohort.isColumn()) cohort.turnCombatToward(target.getPosition().x - cohort.getPosition().x,
                    target.getPosition().y - cohort.getPosition().y, Math.toRadians(MELEE_TURN_DEGREES_PER_SECOND) * .25);
        }
    }

    //combat facing shock method, returns the morale shock based on the facing of the cohorts
    public static int directionalMoraleShock(CohortData defender, CohortData attacker) {
        double dx = attacker.getPosition().x - defender.getPosition().x, dz = attacker.getPosition().y - defender.getPosition().y;
        double distance = Math.hypot(dx, dz);
        if (distance < 1.0e-8) return 2;
        double dot = (dx * defender.getCombatFacingX() + dz * defender.getCombatFacingZ()) / distance;
        return dot >= FACING_SECTOR_COSINE - 1.0e-8 ? 2 : dot <= -FACING_SECTOR_COSINE + 1.0e-8 ? 4 : 3;
    }

    //for enemy cohort targeting, returns the priority of the target based on the cohort's attack target and primary opponent
    private static int targetPriority(CohortData cohort, CohortData candidate) {
        return candidate.getCohortId().equals(cohort.getAttackTarget()) ? 0 : candidate.getCohortId().equals(cohort.getPrimaryOpponent()) ? 1 : 2;
    }

    /** An enemy identity is intent, never permission to cross friendly bodies or blocked terrain. */
    public boolean issueAttackOrder(UUID cohortId, UUID targetId) {
        CohortData cohort = getCohortById(cohortId).orElse(null), target = getCohortById(targetId).orElse(null);
        if (!active || phase != BattlePhase.ENGAGEMENT || cohort == null || !cohort.canTakeOrders()
                || target == null || !target.isAlive() || target.hasEscaped() || isAttackerCohort(cohortId) == isAttackerCohort(targetId)) return false;
        pendingOrders.remove(cohortId); forgetPursuit(cohortId);
        cohort.clearOrders();
        cohort.setAttackTarget(targetId, freeAttackSlot(cohort, target));
        cohort.setOrderStatus("Pursuing selected enemy");
        return true;
    }
    private void forgetPursuit(UUID id) { pursuitRoutes.remove(id); pursuitGoals.remove(id); pursuitChecks.remove(id); rejectedApproaches.remove(id); }
    private int freeAttackSlot(CohortData cohort, CohortData target) {
        Set<Integer> used = new HashSet<>();
        for (CohortData ally : isAttackerCohort(cohort.getCohortId()) ? attackerCohorts : defenderCohorts)
            if (ally != cohort && ally.canTakeOrders() && target.getCohortId().equals(ally.getAttackTarget())) used.add(ally.getAttackSlot());
        int first = 0, count = target.isColumn() ? 3 : 6;
        while (first + count < 1024) {
            int best = -1; double distance = Double.POSITIVE_INFINITY;
            Set<Integer> rejected = rejectedApproaches.containsKey(cohort.getCohortId()) ? rejectedApproaches.get(cohort.getCohortId()).slots : Set.of();
            for (int slot = first; slot < first + count; slot++) if (!used.contains(slot) && !rejected.contains(slot)) {
                Vector2d point = attackApproach(cohort, target, slot);
                if (Math.abs(point.x) > FIELD_BOUND || Math.abs(point.y) > FIELD_BOUND) continue;
                double candidate = point.distanceSquared(cohort.getPosition());
                if (candidate < distance) { best = slot; distance = candidate; }
            }
            if (best >= 0) return best;
            first += count; count += target.isColumn() ? 3 : 6;
        }
        return cohort.getAttackSlot();
    }
    private static int attackRing(int slot, int base) {
        int ring = 1;
        while (slot >= base * ring) { slot -= base * ring; ring++; }
        return ring;
    }

    //attack approach method, returns the position of the cohort based on the target and the slot
    private static Vector2d attackApproach(CohortData cohort, CohortData target, int slot) {
        int base = target.isColumn() ? 3 : 6, ring = attackRing(slot, base);
        int first = base * ring * (ring - 1) / 2;
        double angle = 2 * Math.PI * (slot - first) / (base * ring);
        double radius = (target.isColumn() ? 6.75 : COHORT_SPACING + .25) + (COHORT_SPACING + .25) * (ring - 1);
        Vector2d anchor = target.getPosition();
        if (target.isColumn()) anchor = target.footprintAt(anchor.x, anchor.y).stream()
                .min(Comparator.comparingDouble(p -> p.distanceSquared(cohort.getPosition()))).orElse(anchor);
        return new Vector2d(anchor).add(Math.cos(angle) * radius, Math.sin(angle) * radius);
    }
    //update attack orders method, updates the attack orders of the cohorts
    private void updateAttackOrders() {
        for (CohortData cohort : allBodies()) {
            UUID id = cohort.getCohortId(), targetId = cohort.getAttackTarget();
            if (targetId == null) { forgetPursuit(id); continue; }
            CohortData target = getCohortById(targetId).orElse(null);
            if (!cohort.canTakeOrders() || target == null || !target.isAlive() || target.hasEscaped()
                    || isAttackerCohort(id) == isAttackerCohort(targetId)) {
                // Clear only pursuit orders; a withdrawal/rout may already have installed its own exit route.
                if (cohort.canTakeOrders()) { cohort.clearOrders(); cohort.setOrderStatus("Target no longer available"); }
                else cohort.setAttackTarget(null, 0);
                forgetPursuit(id); continue;
            }
            if (cohort.isEngaged()) { pursuitRoutes.remove(id); cohort.setOrderStatus("Engaged; target retained"); continue; }
            PursuitRoute request = pursuitRoutes.get(id);
            if (request == null) { //while the request isn't null, it will keep the same request until the target moves or the route is blocked
                if (pursuitSteps - pursuitChecks.getOrDefault(id, -4L) < 4) continue;
                pursuitChecks.put(id, pursuitSteps);
                var rejected = rejectedApproaches.get(id);
                long revision = terrainLevel == null ? 0 : MapDataService.terrainRevision(terrainLevel);
                if (rejected != null && (rejected.revision != revision || rejected.target.distanceSquared(target.getPosition()) >= 4)) {
                    rejectedApproaches.remove(id); rejected = null;
                }
                int available = freeAttackSlot(cohort, target), base = target.isColumn() ? 3 : 6;
                Vector2d current = attackApproach(cohort, target, cohort.getAttackSlot());
                if (attackRing(available, base) < attackRing(cohort.getAttackSlot(), base) || Math.abs(current.x) > FIELD_BOUND || Math.abs(current.y) > FIELD_BOUND
                        || rejected != null && rejected.slots.contains(cohort.getAttackSlot())) cohort.setAttackTarget(targetId, available);
                Vector2d goal = attackApproach(cohort, target, cohort.getAttackSlot()), previous = pursuitGoals.get(id);
                if (Math.abs(goal.x) > FIELD_BOUND || Math.abs(goal.y) > FIELD_BOUND) { cohort.setOrderStatus("Waiting for target approach space"); continue; }
                if (previous != null && goal.distanceSquared(previous) < 4 && !cohort.getMovementWaypoints().isEmpty()) continue;
                if (previous != null && goal.distanceSquared(previous) < .01 && cohort.getPosition().distanceSquared(goal) < .01) continue;
                request = new PursuitRoute(new Vector2d(cohort.getPosition()), goal);
                pursuitRoutes.put(id, request);
            }
            if (terrainLevel == null) {
                acceptPursuitRoute(cohort, request, List.of(request.goal)); continue;
            }
            // Finish the same bounded search while the target moves; never restart it on every frame.
            var route = cohort.isColumn() ? MapDataService.battleRoute(terrainLevel, world(request.from), world(request.goal), mapOriginX, mapOriginZ)
                    : MapDataService.formationRoute(terrainLevel, world(request.from), world(request.goal), mapOriginX, mapOriginZ);
            if (route.status() == MapDataService.Verification.VERIFIED) {
                var turns = com.devc.minecraftempires.army.ArmyManager.routeTurns(route.points()).stream()
                        .map(p -> new Vector2d(p.getX() + .5 - mapOriginX, p.getZ() + .5 - mapOriginZ)).toList();
                acceptPursuitRoute(cohort, request, turns);
            } else if (route.status() == MapDataService.Verification.BLOCKED || route.status() == MapDataService.Verification.LIMITED) {
                // Try another unreserved approach instead of repeatedly aiming at an unusable shore/slot.
                var rejected = rejectedApproaches.computeIfAbsent(id, ignored -> new RejectedApproaches(new Vector2d(target.getPosition()), MapDataService.terrainRevision(terrainLevel), new HashSet<>()));
                rejected.slots.add(cohort.getAttackSlot());
                pursuitRoutes.remove(id); pursuitChecks.put(id, pursuitSteps); cohort.setOrderStatus("No usable route to target");
            } else cohort.setOrderStatus("Checking route to target");
        }
    }
    private void acceptPursuitRoute(CohortData cohort, PursuitRoute request, List<Vector2d> turns) {
        UUID id = cohort.getCohortId(); pursuitRoutes.remove(id); pursuitChecks.put(id, pursuitSteps);
        if (turns.size() > 64) { cohort.setOrderStatus("Target route is too long"); return; }
        cohort.clearRoute();
        turns.forEach(p -> cohort.queueWaypoint(p.x, p.y));
        if (!turns.isEmpty()) cohort.queueObjective(turns.getLast().x, turns.getLast().y);
        pursuitGoals.put(id, new Vector2d(request.goal)); cohort.setOrderStatus("Pursuing selected enemy");
    }
    private void processMoraleAura(List<CohortData> side) {
        for (CohortData cohort : side) {
            if (!cohort.canFight()) continue;
            boolean nearbyRout = side.stream().anyMatch(c -> c != cohort && c.isRouting() &&
                    c.isAlive() && !c.hasEscaped() && c.getPosition().distance(cohort.getPosition()) <= 10);
            if (nearbyRout) cohort.applyMoraleShock(1);
        }
    }
    private void processNewRouts(List<CohortData> side, boolean attacker) {
        for (CohortData cohort : side) {
            if (!cohort.isAlive()) continue;
            if (terrainLevel != null && (cohort.isRouting() || cohort.isWithdrawing()) && !cohort.hasEscaped()) retryRetreat(cohort, attacker);
            if (!cohort.announceRout()) continue;
            routeOrder(cohort, cohort.getPosition().x, attacker ? -FIELD_BOUND : FIELD_BOUND, false);
            for (CohortData neighbor : side) {
                if (neighbor != cohort && neighbor.canFight() &&
                        neighbor.getPosition().distance(cohort.getPosition()) <= 10) neighbor.applyMoraleShock(10);
            }
        }
    }
    public boolean issueOrder(UUID cohortId, double x, double z, boolean clear) {
        if (!active || !Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x) > FIELD_BOUND || Math.abs(z) > FIELD_BOUND) return false;
        CohortData cohort = getCohortById(cohortId).orElse(null);
        if (cohort == null || !cohort.canTakeOrders()) return false;
        if (phase == BattlePhase.PREPARATION) return false;
        if (phase == BattlePhase.DEPLOYMENT) {
            boolean attacker = isAttackerCohort(cohortId);
            // Legacy deployment stays behind the starting line; live deployment stays near the actual reachable position.
            if (terrainLevel == null && ((attacker && (z > -8 || z < -100)) || (!attacker && (z < 8 || z > 100)))) return false;
            if (terrainLevel != null && (cohort.getPosition().distance(x, z) > 100 || !clearMovement(cohort, new Vector2d(x, z), allBodies()))) return false;
            if (terrainLevel != null && (cohort.isColumn() || MapDataService.verifySegment(terrainLevel, mapOriginX + cohort.getPosition().x, mapOriginZ + cohort.getPosition().y, mapOriginX + x, mapOriginZ + z) != MapDataService.Verification.VERIFIED
                    || !MapDataService.formationClear(terrainLevel, mapOriginX + x, mapOriginZ + z, cohort.getHeadingX(), cohort.getHeadingZ(), cohort.getWidth(), cohort.getLength()))) return false;
            if (java.util.stream.Stream.concat(attackerCohorts.stream(), defenderCohorts.stream())
                    .anyMatch(other -> other != cohort && other.isAlive() && !other.hasEscaped()
                            && other.getPosition().distanceSquared(x, z) < COHORT_SPACING * COHORT_SPACING - 1.0e-8)) return false;
            cohort.place(x, z);
        } else {
            if (cohort.isEngaged()) return false;
            if (cohort.getAttackTarget() != null) { cohort.clearOrders(); forgetPursuit(cohortId); }
            if (terrainLevel != null) {
                if (clear) { pendingOrders.remove(cohortId); cohort.clearOrders(); }
                if (!pendingOrders.containsKey(cohortId) && routeOrder(cohort, x, z, clear)) return true;
                Queue<PendingOrder> pending = pendingOrders.computeIfAbsent(cohortId, ignored -> new ArrayDeque<>());
                if (pending.size() >= 64) return false;
                pending.add(new PendingOrder(x, z, clear)); cohort.setOrderStatus("Checking route"); return true;
            }
            if (clear) cohort.clearOrders(); cohort.queueWaypoint(x, z); cohort.queueObjective(x, z); cohort.setOrderStatus("Moving");
        }
        return true;
    }
    private boolean routeOrder(CohortData cohort, double x, double z, boolean clear) {
        return routeOrder(cohort, x, z, clear, false);
    }
    private boolean routeOrder(CohortData cohort, double x, double z, boolean clear, boolean automatic) {
        if (terrainLevel == null) { if (clear) cohort.clearOrders(); cohort.queueWaypoint(x, z); cohort.queueObjective(x, z); return true; }
        Vector2d start = !clear && !cohort.getMovementWaypoints().isEmpty() ? new ArrayList<>(cohort.getMovementWaypoints()).getLast() : cohort.getPosition();
        var route = cohort.isColumn() ? MapDataService.battleRoute(terrainLevel, world(start), world(new Vector2d(x, z)), mapOriginX, mapOriginZ)
                : MapDataService.formationRoute(terrainLevel, world(start), world(new Vector2d(x, z)), mapOriginX, mapOriginZ);
        if (route.status() != MapDataService.Verification.VERIFIED) return false;
        var turns = com.devc.minecraftempires.army.ArmyManager.routeTurns(route.points());
        if (turns.size() + (clear ? 0 : cohort.getMovementWaypoints().size()) > 64) return false;
        if (clear) cohort.clearOrders();
        for (BlockPos point : turns) cohort.queueWaypoint(point.getX() + .5 - mapOriginX, point.getZ() + .5 - mapOriginZ);
        if (!turns.isEmpty()) { BlockPos end = turns.getLast(); cohort.queueObjective(end.getX() + .5 - mapOriginX, end.getZ() + .5 - mapOriginZ); }
        cohort.setOrderStatus("Moving");
        cohort.setAutomaticAdvance(automatic);
        return true;
    }
    /** A right-click is retained while its bounded route search runs instead of silently dropping the command. */
    private void retryPlayerOrders() {
        for (var entry : new ArrayList<>(pendingOrders.entrySet())) {
            CohortData cohort = getCohortById(entry.getKey()).orElse(null); PendingOrder order = entry.getValue().peek();
            if (cohort == null || !cohort.canTakeOrders() || cohort.isEngaged()) { pendingOrders.remove(entry.getKey()); continue; }
            Vector2d from = !order.clear && !cohort.getMovementWaypoints().isEmpty() ? new ArrayList<>(cohort.getMovementWaypoints()).getLast() : cohort.getPosition();
            var route = cohort.isColumn() ? MapDataService.battleRoute(terrainLevel, world(from), world(new Vector2d(order.x, order.z)), mapOriginX, mapOriginZ)
                    : MapDataService.formationRoute(terrainLevel, world(from), world(new Vector2d(order.x, order.z)), mapOriginX, mapOriginZ);
            if (route.status() == MapDataService.Verification.BLOCKED || route.status() == MapDataService.Verification.LIMITED) { pendingOrders.remove(entry.getKey()); cohort.setOrderStatus("Order rejected: no usable formation route"); }
            else if (route.status() == MapDataService.Verification.VERIFIED) {
                if (!routeOrder(cohort, order.x, order.z, order.clear)) pendingOrders.remove(entry.getKey());
                else { entry.getValue().poll(); if (entry.getValue().isEmpty()) pendingOrders.remove(entry.getKey()); }
            }
        }
    }
    /** Pending retreat searches retain their intent and retry; a blocked exit never becomes a teleport. */
    private void retryRetreat(CohortData cohort, boolean attacker) {
        var orders = cohort.getMovementWaypoints();
        if (!orders.isEmpty()) {
            Vector2d first = orders.peek();
            if (MapDataService.verifySegment(terrainLevel, mapOriginX + cohort.getPosition().x, mapOriginZ + cohort.getPosition().y,
                    mapOriginX + first.x, mapOriginZ + first.y) == MapDataService.Verification.VERIFIED) return;
        }
        Vector2d goal = orders.isEmpty() ? new Vector2d(cohort.getPosition().x, attacker ? -FIELD_BOUND + 1 : FIELD_BOUND - 1)
                : new Vector2d(new ArrayList<>(orders).getLast());
        if (routeOrder(cohort, goal.x, goal.y, true)) return;
        if (orders.isEmpty()) cohort.queueWaypoint(goal.x, goal.y);
    }
    public void withdrawCohort(UUID cohortId) {
        if (phase != BattlePhase.ENGAGEMENT) return;
        getCohortById(cohortId).ifPresent(c -> c.beginWithdrawal(c.getPosition().x,
                isAttackerCohort(cohortId) ? -FIELD_BOUND : FIELD_BOUND));
    }
    public void applyFormation(boolean attacker, Formation formation) {
        if (phase != BattlePhase.DEPLOYMENT || !active) return;
        if (terrainLevel != null) { queueLayout(attacker ? 0 : 1, formation); deploymentMessage = "Checking formation placement"; return; }
        List<CohortData> side = attacker ? attackerCohorts : defenderCohorts;
        int count = side.size();
        int columns = Math.min(30, formation == Formation.TRIPLEX ? Math.max(1, (count + 2) / 3) : Math.max(1, count));
        int rows = Math.max(1, (count + columns - 1) / columns);
        double maximumDepth = (rows - 1) * 12.0 + (formation == Formation.WEDGE ? (columns - 1) * COHORT_SPACING / 8 : 0);
        double depthScale = Math.min(1.0, 80.0 / Math.max(1.0, maximumDepth));
        for (int i = 0; i < count; i++) {
            int row = i / columns;
            int column = i % columns;
            double x = (column - (Math.min(columns, count - row * columns) - 1) / 2.0) * COHORT_SPACING;
            double depth = formation == Formation.WEDGE ? Math.abs(x) * .25 + row * 12 : row * 12;
            double z = (attacker ? -1 : 1) * (20 + depth * depthScale);
            if (terrainLevel == null) side.get(i).place(x, z); else issueOrder(side.get(i).getCohortId(), x, z, true);
        }
    }
    public void signalRetreat(double x, double z, boolean attacker) {
        (attacker ? attackerCohorts : defenderCohorts).stream().filter(CohortData::isRouting)
                .forEach(c -> c.setRetreatWaypoint(x, z));
    }
    public void issueWithdrawal(double x, double z, boolean attacker) {
        (attacker ? attackerCohorts : defenderCohorts).forEach(c -> c.beginWithdrawal(x, z));
    }
    private void checkBattleEnd() {
        if (attackerCohorts.stream().noneMatch(CohortData::canFight)) finish(BattleResult.DEFENDER_WINS);
        else if (defenderCohorts.stream().noneMatch(CohortData::canFight)) finish(BattleResult.ATTACKER_WINS);
    }
    public void finish(BattleResult result) { this.result = result; active = false; }
    public void setInactive() { checkBattleEnd(); if (active) finish(BattleResult.DEFENDER_WINS); }
    public void addAttackerCohort(CohortData cohort) { attackerCohorts.add(cohort); }
    public void addDefenderCohort(CohortData cohort) { defenderCohorts.add(cohort); }
    public boolean isAttackerCohort(UUID id) { return attackerCohorts.stream().anyMatch(c -> c.getCohortId().equals(id)); }
    public Optional<CohortData> getCohortById(UUID id) {
        return java.util.stream.Stream.concat(attackerCohorts.stream(), defenderCohorts.stream())
                .filter(c -> c.getCohortId().equals(id)).findFirst();
    }
    public void addSpectator(UUID id) { spectatingPlayerIds.add(id); everSpectated = true; autoResolveRequested = false; idleSteps = 0; }
    public void removeSpectator(UUID id) { spectatingPlayerIds.remove(id); }
    public boolean isSpectated() { return !spectatingPlayerIds.isEmpty(); }
    public Set<UUID> getSpectatingPlayerIds() { return Collections.unmodifiableSet(spectatingPlayerIds); }
    public UUID getBattleId() { return battleId; }
    public UUID getAttackerArmyId() { return attackerArmyId; }
    public UUID getDefenderArmyId() { return defenderArmyId; }
    public double getMapOriginX() { return mapOriginX; }
    public double getMapOriginZ() { return mapOriginZ; }
    public List<CohortData> getAttackerCohorts() { return Collections.unmodifiableList(attackerCohorts); }
    public List<CohortData> getDefenderCohorts() { return Collections.unmodifiableList(defenderCohorts); }
    public boolean isActive() { return active; }
    public BattleResult getResult() { return result; }
    public BattlePhase getPhase() { return phase; }
    /** Protocol uses actual game ticks, not simulation steps. */
    public int getDeploymentTicksRemaining() { return Math.max(0, DEPLOYMENT_STEPS - deploymentSteps) * 5; }
    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", battleId.toString());
        tag.putString("Attacker", attackerArmyId.toString());
        tag.putString("Defender", defenderArmyId.toString());
        tag.putDouble("X", mapOriginX);
        tag.putDouble("Z", mapOriginZ);
        tag.putString("Result", result.name());
        tag.putString("Phase", phase.name());
        tag.putInt("DeploymentSteps", deploymentSteps);
        tag.putBoolean("EverSpectated", everSpectated); tag.putBoolean("AutoResolveRequested", autoResolveRequested); tag.putInt("IdleSteps", idleSteps);
        tag.putString("AttackerFormation", formations[0].name()); tag.putString("DefenderFormation", formations[1].name());
        ListTag pendingLayouts = new ListTag();
        for (int side = 0; side < 2; side++) {
            tag.putBoolean(side == 0 ? "AttackerLayoutRejected" : "DefenderLayoutRejected", layoutRejected[side]);
            Layout layout = layouts[side]; if (layout == null) continue;
            CompoundTag value = new CompoundTag(); value.putInt("Side", side); value.putString("Formation", layout.formation.name()); value.putInt("Cursor", layout.cursor);
            value.putIntArray("Deferred", layout.deferred.stream().mapToInt(Integer::intValue).toArray());
            value.putDouble("X", layout.origin.x); value.putDouble("Z", layout.origin.y); value.putDouble("ForwardX", layout.forward.x); value.putDouble("ForwardZ", layout.forward.y);
            ListTag units = new ListTag(); layout.units.forEach(unit -> units.add(net.minecraft.nbt.StringTag.valueOf(unit.getCohortId().toString()))); value.put("Units", units); pendingLayouts.add(value);
        }
        tag.put("PendingLayouts", pendingLayouts);
        tag.putInt("PreparationSteps", preparationSteps); tag.putInt("LayoutVersion", layoutVersion);
        tag.putBoolean("ResumeEngagement", resumeEngagement); tag.putBoolean("DeploymentFailed", deploymentFailed);
        ListTag legacyIntents = new ListTag(); legacyOrderIntents.forEach(id -> legacyIntents.add(net.minecraft.nbt.StringTag.valueOf(id.toString()))); tag.put("LegacyOrderIntents", legacyIntents);
        tag.putInt("CombatSteps", combatSteps);
        ListTag pending = new ListTag();
        pendingOrders.forEach((id, orders) -> orders.forEach(order -> { CompoundTag value = new CompoundTag(); value.putString("Cohort", id.toString()); value.putDouble("X", order.x); value.putDouble("Z", order.z); value.putBoolean("Clear", order.clear); pending.add(value); }));
        tag.put("PendingOrders", pending);
        ListTag attackers = new ListTag();
        attackerCohorts.forEach(c -> attackers.add(c.toNBT()));
        tag.put("AttackingCohorts", attackers);
        ListTag defenders = new ListTag();
        defenderCohorts.forEach(c -> defenders.add(c.toNBT()));
        tag.put("DefendingCohorts", defenders);
        return tag;
    }
    public static BattleSession fromNBT(CompoundTag tag) {
        BattleSession session = new BattleSession(UUID.fromString(tag.getString("Id").orElseThrow()),
                UUID.fromString(tag.getString("Attacker").orElseThrow()), UUID.fromString(tag.getString("Defender").orElseThrow()),
                tag.getDouble("X").orElse(0.0), tag.getDouble("Z").orElse(0.0));
        tag.getList("AttackingCohorts").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) list.getCompound(i).ifPresent(c -> { CohortData cohort = CohortData.fromNBT(c); session.addAttackerCohort(cohort); if (!c.contains("AutomaticAdvance")) session.legacyOrderIntents.add(cohort.getCohortId()); });
        });
        tag.getList("DefendingCohorts").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) list.getCompound(i).ifPresent(c -> { CohortData cohort = CohortData.fromNBT(c); session.addDefenderCohort(cohort); if (!c.contains("AutomaticAdvance")) session.legacyOrderIntents.add(cohort.getCohortId()); });
        });
        BattleResult result = BattleResult.valueOf(tag.getString("Result").orElse("ONGOING"));
        session.phase = BattlePhase.valueOf(tag.getString("Phase").orElse("DEPLOYMENT"));
        session.deploymentSteps = Math.clamp(tag.getInt("DeploymentSteps").orElse(0), 0, DEPLOYMENT_STEPS);
        session.everSpectated = tag.getBoolean("EverSpectated").orElse(false); session.autoResolveRequested = tag.getBoolean("AutoResolveRequested").orElse(false);
        session.idleSteps = Math.clamp(tag.getInt("IdleSteps").orElse(0), 0, INITIAL_GRACE_STEPS);
        for (int side = 0; side < 2; side++) try { session.formations[side] = Formation.valueOf(tag.getString(side == 0 ? "AttackerFormation" : "DefenderFormation").orElse("LINE")); } catch (IllegalArgumentException ignored) { }
        session.layoutRejected[0] = tag.getBoolean("AttackerLayoutRejected").orElse(false); session.layoutRejected[1] = tag.getBoolean("DefenderLayoutRejected").orElse(false);
        tag.getList("PendingLayouts").ifPresent(values -> { for (int i = 0; i < Math.min(2, values.size()); i++) values.getCompound(i).ifPresent(value -> {
            try {
                int side = value.getInt("Side").orElse(-1); if (side < 0 || side > 1) return;
                List<CohortData> units = new ArrayList<>(); value.getList("Units").ifPresent(ids -> { for (var id : ids) session.getCohortById(UUID.fromString(id.asString().orElseThrow())).filter(c -> c.isAlive() && !c.isColumn() && !c.hasEscaped() && session.isAttackerCohort(c.getCohortId()) == (side == 0)).ifPresent(units::add); });
                if (units.isEmpty()) return;
                Vector2d origin = new Vector2d(value.getDouble("X").orElse(0.0), value.getDouble("Z").orElse(0.0));
                Vector2d forward = new Vector2d(value.getDouble("ForwardX").orElse(0.0), value.getDouble("ForwardZ").orElse(1.0));
                if (!Double.isFinite(origin.x) || !Double.isFinite(origin.y) || !Double.isFinite(forward.x) || !Double.isFinite(forward.y) || forward.lengthSquared() < .001) return;
                Layout layout = new Layout(List.copyOf(units), origin, forward.normalize(), Formation.valueOf(value.getString("Formation").orElse("LINE"))); layout.cursor = Math.clamp(value.getInt("Cursor").orElse(0), 0, 1620);
                value.getIntArray("Deferred").ifPresent(indices -> java.util.Arrays.stream(indices).filter(index -> index >= 0 && index < 1620).distinct().limit(1620).forEach(layout.deferred::addLast)); session.layouts[side] = layout;
            } catch (RuntimeException ignored) { }
        }); });
        session.preparationSteps = Math.clamp(tag.getInt("PreparationSteps").orElse(0), 0, PREPARATION_LIMIT);
        session.layoutVersion = tag.getInt("LayoutVersion").orElse(0);
        session.resumeEngagement = tag.getBoolean("ResumeEngagement").orElse(false); session.deploymentFailed = tag.getBoolean("DeploymentFailed").orElse(false);
        tag.getList("LegacyOrderIntents").ifPresent(values -> { for (var value : values) try { session.legacyOrderIntents.add(UUID.fromString(value.asString().orElse(""))); } catch (IllegalArgumentException ignored) { } });
        session.combatSteps = Math.clamp(tag.getInt("CombatSteps").orElse(0), 0, MELEE_INTERVAL);
        tag.getList("PendingOrders").ifPresent(values -> { for (int i = 0; i < Math.min(values.size(), 512); i++) values.getCompound(i).ifPresent(value -> {
            try {
                UUID id = UUID.fromString(value.getString("Cohort").orElseThrow()); double x = value.getDouble("X").orElse(0.0), z = value.getDouble("Z").orElse(0.0);
                if (session.getCohortById(id).isPresent() && Double.isFinite(x) && Double.isFinite(z) && Math.abs(x) <= FIELD_BOUND && Math.abs(z) <= FIELD_BOUND)
                    session.pendingOrders.computeIfAbsent(id, ignored -> new ArrayDeque<>()).add(new PendingOrder(x, z, value.getBoolean("Clear").orElse(true)));
            } catch (RuntimeException ignored) { }
        }); });
        if (result != BattleResult.ONGOING) session.finish(result);
        return session;
    }
}
