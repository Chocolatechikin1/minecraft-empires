package com.devc.minecraftempires.combat;

import com.devc.minecraftempires.army.Cohort;
import net.minecraft.nbt.CompoundTag;
import org.joml.Vector2d;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.function.UnaryOperator;

//data for a single cohort in a battle
/** A battle snapshot; health is surviving soldiers / original formation strength. */
public class CohortData {
    private final UUID cohortId;
    private final String type;
    private final int maxHealth;
    private int currentHealth;
    private int morale;
    private final double speed;
    private final int strength;
    private final int endurance;
    private boolean routing;
    private boolean routAnnounced;
    private boolean engaged;
    private boolean withdrawing;
    private boolean escaped;
    private int withdrawalSteps;
    private boolean automaticAdvance;
    private int stalledMovementSteps;
    private double bestWaypointDistance = Double.POSITIVE_INFINITY;
    private String orderStatus = "";
    private final Queue<Vector2d> orderObjectives = new ArrayDeque<>();
    private double headingX, headingZ = 1;
    // Tactical facing is independent of terrain trial directions and the bridge column's route tangent.
    private double combatFacingX, combatFacingZ = 1;
    private boolean combatFacingInitialized;
    private UUID primaryOpponent;
    // Pursuit intent survives contact; primaryOpponent is only the current melee contact.
    private UUID attackTarget;
    private int attackSlot;
    private boolean column;
    private net.minecraft.core.BlockPos crossingStart, crossingEnd;
    private boolean recoveredFromCollapse;
    private final List<Vector2d> trail = new ArrayList<>();
    private List<net.minecraft.core.BlockPos> crossingPath = List.of();
    private final Vector2d position;
    private final Vector2d previousPosition;
    private final Queue<Vector2d> movementWaypoints = new ArrayDeque<>();

    public CohortData(UUID id, String type, int maxHealth, int morale, double speed,
                      int strength, int endurance, double x, double z) {
        this.cohortId = id;
        this.type = type;
        this.maxHealth = Math.max(1, maxHealth);
        this.currentHealth = this.maxHealth;
        this.morale = Math.clamp(morale, 0, 100);
        this.speed = Math.max(0.1, speed);
        this.strength = strength;
        this.endurance = endurance;
        position = new Vector2d(x, z);
        previousPosition = new Vector2d(x, z);
    }

    public static CohortData fromCohort(Cohort cohort, double x, double z) {
        CohortData data = new CohortData(cohort.getCohortId(), cohort.getType().name(),
                cohort.getMaxSoldiers(), cohort.getMorale(), cohort.getSpeed() / 50.0,
                cohort.getStrength(), cohort.getEffectiveEndurance(), x, z);
        data.currentHealth = cohort.getSoldierCount();
        data.routing = cohort.isRouting() || cohort.getMorale() == 0;
        data.readMarch(cohort.getMarchState());
        if (data.isColumn()) data.faceCombat(data.headingX, data.headingZ);
        double originX = cohort.getPreciseX() - x, originZ = cohort.getPreciseZ() - z;
        data.trail.forEach(point -> point.sub(originX, originZ));
        cohort.getWaypoints().forEach(point -> data.queueWaypoint(point.getX() + .5 - originX, point.getZ() + .5 - originZ));
        return data;
    }
    //following methods are for bridge interaction and logic handling
    public double getHeadingX() { return headingX; }
    public double getHeadingZ() { return headingZ; }
    public double getWidth() { return column ? 3 : 10; }
    public double getLength() { return column ? 24 : 10; }
    public boolean isColumn() { return column; }
    public boolean hasRecoveredFromCollapse() { return recoveredFromCollapse; }
    public net.minecraft.core.BlockPos getCrossingStart() { return crossingStart; }
    public net.minecraft.core.BlockPos getCrossingEnd() { return crossingEnd; }
    public void face(double dx, double dz) {
        double length = Math.hypot(dx, dz);
        if (length > 1.0e-8) { headingX = dx / length; headingZ = dz / length; }
    }
    public void beginCrossing(net.minecraft.core.BlockPos start, net.minecraft.core.BlockPos end) {
        column = true; crossingStart = start; crossingEnd = end; recoveredFromCollapse = false;
        face(end.getX() - start.getX(), end.getZ() - start.getZ());
        crossingPath = List.of(start, end);
    }
    public void beginCrossing(List<net.minecraft.core.BlockPos> path) { beginCrossing(path.getFirst(), path.getLast()); crossingPath = List.copyOf(path); }
    public List<net.minecraft.core.BlockPos> getCrossingPath() { return crossingPath; }
    public void reform() { column = false; crossingStart = null; crossingEnd = null; crossingPath = List.of(); }
    public void markRecovered() { recoveredFromCollapse = true; reform(); trail.clear(); clearOrders(); }
    public CompoundTag marchState() {
        return marchState(0, 0);
    }
    public CompoundTag marchState(double originX, double originZ) {
        CompoundTag tag = new CompoundTag(); tag.putBoolean("Column", column);
        tag.putDouble("HeadingX", headingX); tag.putDouble("HeadingZ", headingZ);
        if (crossingStart != null) tag.putLong("CrossingStart", crossingStart.asLong());
        if (crossingEnd != null) tag.putLong("CrossingEnd", crossingEnd.asLong());
        tag.putBoolean("Recovered", recoveredFromCollapse);
        net.minecraft.nbt.ListTag history = new net.minecraft.nbt.ListTag();
        for (Vector2d point : trail) { CompoundTag value = new CompoundTag(); value.putDouble("X", point.x + originX); value.putDouble("Z", point.y + originZ); history.add(value); }
        tag.put("Trail", history);
        tag.putLongArray("CrossingPath", crossingPath.stream().mapToLong(net.minecraft.core.BlockPos::asLong).toArray()); return tag;
    }
    public void readMarch(CompoundTag tag) {
        column = tag.getBoolean("Column").orElse(false);
        face(tag.getDouble("HeadingX").orElse(0.0), tag.getDouble("HeadingZ").orElse(1.0));
        crossingStart = tag.getLong("CrossingStart").map(net.minecraft.core.BlockPos::of).orElse(null);
        crossingEnd = tag.getLong("CrossingEnd").map(net.minecraft.core.BlockPos::of).orElse(null);
        recoveredFromCollapse = tag.getBoolean("Recovered").orElse(false);
        trail.clear(); tag.getList("Trail").ifPresent(values -> { for (int i = 0; i < Math.min(64, values.size()); i++) values.getCompound(i).ifPresent(value -> trail.add(new Vector2d(value.getDouble("X").orElse(0.0), value.getDouble("Z").orElse(0.0)))); });
        crossingPath = tag.getLongArray("CrossingPath").map(values -> java.util.Arrays.stream(values).limit(256).mapToObj(net.minecraft.core.BlockPos::of).toList()).orElseGet(() -> crossingStart == null || crossingEnd == null ? List.of() : List.of(crossingStart, crossingEnd));
    }

    /** A column follows its route around bends, with twelve blocks of troops on either side of its marker. */
    public List<Vector2d> footprintAt(double x, double z) {
        List<Vector2d> before = new ArrayList<>(); before.add(new Vector2d(x, z));
        for (int i = trail.size() - 1; i >= 0; i--) before.add(new Vector2d(trail.get(i)));
        List<Vector2d> after = new ArrayList<>(); after.add(new Vector2d(x, z)); movementWaypoints.forEach(point -> after.add(new Vector2d(point)));
        List<Vector2d> rear = sampleLength(before, -headingX, -headingZ, (getLength() - getWidth()) / 2);
        java.util.Collections.reverse(rear); List<Vector2d> result = new ArrayList<>(rear);
        result.addAll(sampleLength(after, headingX, headingZ, (getLength() - getWidth()) / 2)); return result;
    }
    private static List<Vector2d> sampleLength(List<Vector2d> path, double fallbackX, double fallbackZ, double length) {
        List<Vector2d> result = new ArrayList<>(); Vector2d current = new Vector2d(path.getFirst()); result.add(new Vector2d(current));
        int index = 1;
        while (length > .000001) {
            Vector2d target = index < path.size() ? path.get(index) : new Vector2d(current.x + fallbackX * length, current.y + fallbackZ * length);
            double distance = current.distance(target); if (distance < .000001) { index++; continue; }
            double step = Math.min(.5, Math.min(length, distance)); current.lerp(target, step / distance); result.add(new Vector2d(current)); length -= step;
            if (step >= distance - .000001) index++;
        }
        return result;
    }
    public void recordMovement(double x, double z) {
        if (trail.isEmpty() || trail.getLast().distanceSquared(x, z) >= .25) trail.add(new Vector2d(x, z));
        while (trail.size() > 32) trail.removeFirst();
    }
    private static double segmentDistance(Vector2d point, Vector2d a, Vector2d b) {
        double dx = b.x - a.x, dz = b.y - a.y, length = dx * dx + dz * dz;
        double t = length == 0 ? 0 : Math.clamp(((point.x - a.x) * dx + (point.y - a.y) * dz) / length, 0, 1);
        return Math.hypot(point.x - a.x - t * dx, point.y - a.y - t * dz);
    }

    /** Ordinary bodies retain ten-block center spacing; bent columns use their actual heading, width and route. */
    public static boolean overlaps(CohortData a, double ax, double az, CohortData b, double bx, double bz, double padding) {
        double bound = Math.hypot(a.getLength(), a.getWidth()) / 2 + Math.hypot(b.getLength(), b.getWidth()) / 2 + padding;
        if (Math.hypot(ax - bx, az - bz) > bound) return false;
        if (a.column || b.column) {
            List<Vector2d> ap = a.column ? a.footprintAt(ax, az) : List.of(new Vector2d(ax, az));
            List<Vector2d> bp = b.column ? b.footprintAt(bx, bz) : List.of(new Vector2d(bx, bz));
            double radius = a.getWidth() / 2 + b.getWidth() / 2 + padding;
            for (Vector2d point : ap) for (int i = 0; i < bp.size(); i++) if (segmentDistance(point, bp.get(Math.max(0, i - 1)), bp.get(i)) < radius - 1.0e-8) return true;
            for (Vector2d point : bp) for (int i = 0; i < ap.size(); i++) if (segmentDistance(point, ap.get(Math.max(0, i - 1)), ap.get(i)) < radius - 1.0e-8) return true;
            return false;
        }
        return Math.hypot(ax - bx, az - bz) < BattleSession.COHORT_SPACING + padding - 1.0e-8;
    }

    //end of bridge logic methods

    //start of combat facing methods
    public double getCombatFacingX() { return column ? headingX : combatFacingX; }
    public double getCombatFacingZ() { return column ? headingZ : combatFacingZ; }
    public boolean hasCombatFacing() { return combatFacingInitialized; }
    public UUID getPrimaryOpponent() { return primaryOpponent; }
    public void setPrimaryOpponent(UUID id) { primaryOpponent = id; }
    public UUID getAttackTarget() { return attackTarget; }
    public int getAttackSlot() { return attackSlot; }
    public void setAttackTarget(UUID id, int slot) { attackTarget = id; attackSlot = Math.clamp(slot, 0, 1023); }
    public void faceCombat(double dx, double dz) {
        double magnitude = Math.hypot(dx, dz);
        if (Double.isFinite(magnitude) && magnitude > 1.0e-8) {
            combatFacingX = dx / magnitude; combatFacingZ = dz / magnitude; combatFacingInitialized = true;
        }
    }
    /** Turn by the shortest arc using simulated elapsed time, never packet or render frequency. */
    public void turnCombatToward(double dx, double dz, double maximumRadians) {
        if (!Double.isFinite(dx) || !Double.isFinite(dz) || Math.hypot(dx, dz) < 1.0e-8) return;
        double current = Math.atan2(combatFacingZ, combatFacingX), target = Math.atan2(dz, dx);
        double delta = Math.atan2(Math.sin(target - current), Math.cos(target - current));
        double next = current + Math.clamp(delta, -Math.max(0, maximumRadians), Math.max(0, maximumRadians));
        faceCombat(Math.cos(next), Math.sin(next));
    }

    //end of combat facing methods
    public void tickMovement() {
        tickMovement(UnaryOperator.identity());
    }
    /** The battle resolves swept movement against other formation bodies before committing a step. */
    public void tickMovement(UnaryOperator<Vector2d> resolveMovement) {
        previousPosition.set(position);
        if (!isAlive() || escaped) return;
        if (withdrawalSteps > 0) { withdrawalSteps--; return; }
        if (movementWaypoints.isEmpty() || (engaged && !routing && !withdrawing)) return;
        Vector2d target = movementWaypoints.peek();
        bestWaypointDistance = Math.min(bestWaypointDistance, position.distance(target));
        double step = speed * (routing ? 1.25 : 1.0);
        Vector2d next = position.distance(target) <= step ? new Vector2d(target)
                : new Vector2d(target).sub(position).normalize().mul(step).add(position);
        Vector2d resolved = resolveMovement.apply(next);
        // Sideways jitter is not progress. Measure improvement toward this waypoint, not just motion.
        double remaining = resolved.distance(target);
        if (remaining < bestWaypointDistance - .05) { stalledMovementSteps = 0; bestWaypointDistance = remaining; }
        else stalledMovementSteps = Math.min(1000, stalledMovementSteps + 1);
        if (position.distanceSquared(resolved) > 1.0e-8) {
            recordMovement(position.x, position.y);
            if (remaining < position.distance(target) - 1.0e-8) faceCombat(resolved.x - position.x, resolved.y - position.y);
        }
        position.set(resolved);
        if (position.distanceSquared(target) < 1.0e-8) {
            movementWaypoints.poll();
            bestWaypointDistance = Double.POSITIVE_INFINITY; stalledMovementSteps = 0;
            if (!orderObjectives.isEmpty() && position.distanceSquared(orderObjectives.peek()) < .01) orderObjectives.poll();
            if (movementWaypoints.isEmpty()) orderStatus = "";
            if ((routing || withdrawing) && movementWaypoints.isEmpty()) escaped = true;
        }
    }

    public void applyDamage(int damage, int moraleShock) {
        currentHealth = Math.max(0, currentHealth - Math.max(0, damage));
        applyMoraleShock(moraleShock);
    }

    public void applyMoraleShock(int shock) {
        morale = Math.clamp(morale - Math.max(0, shock), 0, 100);
        if (morale == 0) routing = true;
        if (routing) setAttackTarget(null, 0);
    }

    public boolean announceRout() {
        if (!routing || routAnnounced) return false;
        routAnnounced = true;
        return true;
    }

    public boolean isAlive() { return currentHealth > 0; }
    public boolean canFight() { return isAlive() && !routing && !escaped; }
    public boolean canTakeOrders() { return canFight() && !withdrawing; }
    public void setEngaged(boolean value) {
        if (value && !engaged && !routing && !withdrawing) clearRoute();
        engaged = value;
    }
    public void queueWaypoint(double x, double z) {
        automaticAdvance = false;
        if (movementWaypoints.size() < 64) movementWaypoints.add(new Vector2d(x, z));
    }
    public boolean isAutomaticallyAdvancing() { return automaticAdvance; }
    public void setAutomaticAdvance(boolean automatic) { automaticAdvance = automatic; }
    public int getStalledMovementSteps() { return stalledMovementSteps; }
    public String getOrderStatus() { return orderStatus; }
    public void setOrderStatus(String value) { orderStatus = value; }
    public List<Vector2d> getOrderObjectives() {
        return orderObjectives.isEmpty() && !movementWaypoints.isEmpty()
                ? List.of(new Vector2d(new ArrayList<>(movementWaypoints).getLast())) : List.copyOf(orderObjectives);
    }
    public void queueObjective(double x, double z) { if (orderObjectives.size() < 64) orderObjectives.add(new Vector2d(x, z)); }
    /** Replace a computed route prefix while preserving explicit player objectives and later queued legs. */
    public boolean replaceRoutePrefix(List<Vector2d> prefix, int consumed) {
        List<Vector2d> remaining = new ArrayList<>(movementWaypoints);
        if (consumed < 0 || consumed > remaining.size() || prefix.size() + remaining.size() - consumed > 64) return false;
        movementWaypoints.clear(); movementWaypoints.addAll(prefix); movementWaypoints.addAll(remaining.subList(consumed, remaining.size()));
        bestWaypointDistance = Double.POSITIVE_INFINITY; stalledMovementSteps = 0; return true;
    }
    public void advanceToward(double x, double z) {
        clearOrders(); queueWaypoint(x, z); automaticAdvance = true;
    }
    public void setRetreatWaypoint(double x, double z) {
        if (escaped) return;
        movementWaypoints.clear();
        queueWaypoint(x, z);
    }
    public void beginWithdrawal(double x, double z) {
        if (!canTakeOrders()) return;
        withdrawing = true;
        withdrawalSteps = engaged ? 12 : 0; // three seconds at four simulation steps/second
        clearOrders();
        setRetreatWaypoint(x, z);
    }
    public void place(double x, double z) {
        position.set(x, z);
        previousPosition.set(position);
        clearOrders();
    }
    public void clearOrders() { clearRoute(); attackTarget = null; attackSlot = 0; }
    /** Route replacement/contact must not discard an explicit enemy target. */
    public void clearRoute() { movementWaypoints.clear(); orderObjectives.clear(); automaticAdvance = false; stalledMovementSteps = 0; bestWaypointDistance = Double.POSITIVE_INFINITY; orderStatus = ""; }
    public UUID getCohortId() { return cohortId; }
    public String getType() { return type; }
    public int getCurrentHealth() { return currentHealth; }
    public int getMaxHealth() { return maxHealth; }
    public int getMorale() { return morale; }
    public double getSpeed() { return speed; }
    public int getStrength() { return strength; }
    public int getEndurance() { return endurance; }
    public Vector2d getPosition() { return position; }
    public Vector2d getPreviousPosition() { return previousPosition; }
    public boolean isRouting() { return routing; }
    public void setRouting(boolean value) { routing = value; }
    public boolean isEngaged() { return engaged; }
    public boolean isWithdrawing() { return withdrawing; }
    public boolean hasEscaped() { return escaped; }
    public Queue<Vector2d> getMovementWaypoints() { return movementWaypoints; }

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", cohortId.toString());
        tag.putString("Type", type);
        tag.putInt("Max", maxHealth);
        tag.putInt("Current", currentHealth);
        tag.putInt("Morale", morale);
        tag.putDouble("Speed", speed);
        tag.putInt("Strength", strength);
        tag.putInt("Endurance", endurance);
        tag.putDouble("X", position.x);
        tag.putDouble("Z", position.y);
        tag.putBoolean("Routing", routing);
        tag.putBoolean("Escaped", escaped);
        tag.putBoolean("Withdrawing", withdrawing);
        tag.putBoolean("AutomaticAdvance", automaticAdvance); tag.putInt("StalledMovementSteps", stalledMovementSteps);
        tag.putDouble("BestWaypointDistance", bestWaypointDistance); tag.putString("OrderStatus", orderStatus);
        net.minecraft.nbt.ListTag objectives = new net.minecraft.nbt.ListTag();
        orderObjectives.forEach(point -> { CompoundTag value = new CompoundTag(); value.putDouble("X", point.x); value.putDouble("Z", point.y); objectives.add(value); }); tag.put("OrderObjectives", objectives);
        tag.put("March", marchState());
        tag.putBoolean("CombatFacingInitialized", combatFacingInitialized);
        tag.putDouble("CombatFacingX", combatFacingX); tag.putDouble("CombatFacingZ", combatFacingZ);
        if (primaryOpponent != null) tag.putString("PrimaryOpponent", primaryOpponent.toString());
        if (attackTarget != null) { tag.putString("AttackTarget", attackTarget.toString()); tag.putInt("AttackSlot", attackSlot); }
        net.minecraft.nbt.ListTag orders = new net.minecraft.nbt.ListTag();
        for (Vector2d point : movementWaypoints) { CompoundTag order = new CompoundTag(); order.putDouble("X", point.x); order.putDouble("Z", point.y); orders.add(order); }
        tag.put("Orders", orders); tag.putInt("WithdrawalSteps", withdrawalSteps); tag.putBoolean("RoutAnnounced", routAnnounced);
        return tag;
    }
    public static CohortData fromNBT(CompoundTag tag) {
        CohortData data = new CohortData(UUID.fromString(tag.getString("Id").orElseThrow()),
                tag.getString("Type").orElse("INFANTRY"), tag.getInt("Max").orElse(50),
                tag.getInt("Morale").orElse(50), tag.getDouble("Speed").orElse(1.0),
                tag.getInt("Strength").orElse(50), tag.getInt("Endurance").orElse(50),
                tag.getDouble("X").orElse(0.0), tag.getDouble("Z").orElse(0.0));
        data.currentHealth = Math.clamp(tag.getInt("Current").orElse(data.maxHealth), 0, data.maxHealth);
        data.routing = tag.getBoolean("Routing").orElse(false);
        data.escaped = tag.getBoolean("Escaped").orElse(false);
        data.withdrawing = tag.getBoolean("Withdrawing").orElse(false);
        tag.getCompound("March").ifPresent(data::readMarch);
        if (tag.getBoolean("CombatFacingInitialized").orElse(false))
            data.faceCombat(tag.getDouble("CombatFacingX").orElse(0.0), tag.getDouble("CombatFacingZ").orElse(1.0));
        tag.getString("PrimaryOpponent").ifPresent(id -> { try { data.primaryOpponent = UUID.fromString(id); } catch (IllegalArgumentException ignored) { } });
        tag.getString("AttackTarget").ifPresent(id -> { try { data.setAttackTarget(UUID.fromString(id), tag.getInt("AttackSlot").orElse(0)); } catch (IllegalArgumentException ignored) { } });
        tag.getList("Orders").ifPresent(values -> { for (int i = 0; i < Math.min(64, values.size()); i++) values.getCompound(i).ifPresent(value -> data.queueWaypoint(value.getDouble("X").orElse(0.0), value.getDouble("Z").orElse(0.0))); });
        data.withdrawalSteps = Math.clamp(tag.getInt("WithdrawalSteps").orElse(0), 0, 12); data.routAnnounced = tag.getBoolean("RoutAnnounced").orElse(false);
        data.automaticAdvance = tag.getBoolean("AutomaticAdvance").orElse(false);
        data.stalledMovementSteps = Math.clamp(tag.getInt("StalledMovementSteps").orElse(0), 0, 1000);
        data.bestWaypointDistance = tag.getDouble("BestWaypointDistance").orElse(Double.POSITIVE_INFINITY);
        data.orderStatus = tag.getString("OrderStatus").orElse("");
        tag.getList("OrderObjectives").ifPresent(values -> { for (int i = 0; i < Math.min(64, values.size()); i++) values.getCompound(i).ifPresent(value -> data.queueObjective(value.getDouble("X").orElse(0.0), value.getDouble("Z").orElse(0.0))); });
        return data;
    }
}
