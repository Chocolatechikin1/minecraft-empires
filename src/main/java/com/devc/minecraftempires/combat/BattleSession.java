package com.devc.minecraftempires.combat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import java.util.*;
import org.joml.Vector2d;

/** Server-owned battle, simulated every five server ticks (four steps per second). */
//this class handles only user or spectated battles, auto resolve ones are in AutoResolveEngine.java
public class BattleSession {
    public enum BattleResult { ONGOING, ATTACKER_WINS, DEFENDER_WINS }
    public enum BattlePhase { DEPLOYMENT, ENGAGEMENT }
    public enum Formation { LINE, TRIPLEX, WEDGE }
    public static final double COHORT_SPACING = 10.0;
    private static final double MELEE_RANGE = COHORT_SPACING + .5;
    private static final int DEPLOYMENT_STEPS = 120;
    private static final int INITIAL_GRACE_STEPS = 240;
    private static final int ABANDONMENT_GRACE_STEPS = 40;
    private static final int MELEE_INTERVAL = 8;
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
        return idleSteps >= (everSpectated ? ABANDONMENT_GRACE_STEPS : INITIAL_GRACE_STEPS);
    }
    public void resetIdleTicks() { idleSteps = 0; }
    public void tickIdle() { idleSteps++; }

    public void tick() { tick(true, true); }
    public void tickDeployment() {
        if (active && phase == BattlePhase.DEPLOYMENT && ++deploymentSteps >= DEPLOYMENT_STEPS) phase = BattlePhase.ENGAGEMENT;
    }
    public void tick(boolean advanceAttacker, boolean advanceDefender) {
        if (!active) return;
        if (phase == BattlePhase.DEPLOYMENT) {
            tickDeployment();
            return;
        }
        updateEngagements();
        if (advanceAttacker) advanceIdleUnits(attackerCohorts, defenderCohorts);
        if (advanceDefender) advanceIdleUnits(defenderCohorts, attackerCohorts);
        processNewRouts(attackerCohorts, true);
        processNewRouts(defenderCohorts, false);
        List<CohortData> bodies = new ArrayList<>(attackerCohorts);
        bodies.addAll(defenderCohorts);
        for (CohortData cohort : bodies) cohort.tickMovement(next -> resolveMovement(cohort, next, bodies));
        updateEngagements();
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
        int turn = (cohort.getCohortId().hashCode() & 1) == 0 ? 1 : -1;
        for (double angle : new double[]{0, turn * Math.PI / 4, -turn * Math.PI / 4, turn * Math.PI / 2, -turn * Math.PI / 2}) {
            double cos = Math.cos(angle), sin = Math.sin(angle);
            Vector2d candidate = new Vector2d(start.x + delta.x * cos - delta.y * sin,
                    start.y + delta.x * sin + delta.y * cos);
            if (Math.abs(candidate.x) > FIELD_BOUND || Math.abs(candidate.y) > FIELD_BOUND) continue;
            if (clearMovement(cohort, candidate, bodies)) return candidate;
        }
        return new Vector2d(start);
    }

    //movement update function, checks if the movement is clear of other cohorts
    private boolean clearMovement(CohortData cohort, Vector2d end, List<CohortData> bodies) {
        Vector2d start = cohort.getPosition();
        double dx = end.x - start.x, dz = end.y - start.y, length = dx * dx + dz * dz;
        for (CohortData other : bodies) {
            if (other == cohort || !other.isAlive() || other.hasEscaped()) continue;
            Vector2d obstacle = other.getPosition();
            double before = start.distanceSquared(obstacle), after = end.distanceSquared(obstacle);
            // Legacy/manual positions may already overlap: allow only movement that separates them.
            if (before < COHORT_SPACING * COHORT_SPACING - 1.0e-8) {
                if (after > before + 1.0e-8 && dx * (start.x - obstacle.x) + dz * (start.y - obstacle.y) >= 0) continue;
                return false;
            }
            double t = length == 0 ? 0 : Math.clamp(((obstacle.x - start.x) * dx + (obstacle.y - start.y) * dz) / length, 0, 1);
            double nearX = start.x + t * dx - obstacle.x, nearZ = start.y + t * dz - obstacle.y;
            if (nearX * nearX + nearZ * nearZ < COHORT_SPACING * COHORT_SPACING - 1.0e-8) return false;
        }
        return true;
    }
    private void updateEngagements() {
        // Compute the whole set first so continuing contacts do not repeatedly clear orders.
        Set<UUID> contacts = new HashSet<>();
        for (CohortData attacker : attackerCohorts) {
            if (!attacker.isAlive() || attacker.hasEscaped()) continue;
            for (CohortData defender : defenderCohorts) {
                if (defender.isAlive() && !defender.hasEscaped() &&
                        attacker.getPosition().distance(defender.getPosition()) <= MELEE_RANGE) {
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
            if (!cohort.canTakeOrders() || cohort.isEngaged()
                    || !cohort.getMovementWaypoints().isEmpty() && !cohort.isAutomaticallyAdvancing()) continue;
            enemies.stream().filter(CohortData::canFight)
                    .min(Comparator.comparingDouble(c -> c.getPosition().distanceSquared(cohort.getPosition())))
                    .ifPresent(target -> cohort.advanceToward(target.getPosition().x, target.getPosition().y));
        }
    }
    private void processMelee() {
        // One target per cohort avoids multiplying a unit's damage by every nearby enemy.
        Map<CohortData, Integer> hits = new HashMap<>();
        collectHits(attackerCohorts, defenderCohorts, hits);
        collectHits(defenderCohorts, attackerCohorts, hits);
        hits.forEach((target, damage) -> target.applyDamage(damage, 2));
    }
    private void collectHits(List<CohortData> side, List<CohortData> opponents, Map<CohortData, Integer> hits) {
        for (CohortData cohort : side) {
            if (!cohort.canFight() || cohort.isWithdrawing()) continue;
            opponents.stream().filter(c -> c.isAlive() && !c.hasEscaped())
                    .filter(c -> c.getPosition().distance(cohort.getPosition()) <= MELEE_RANGE)
                    .min(Comparator.comparingDouble(c -> c.getPosition().distanceSquared(cohort.getPosition())))
                    .ifPresent(target -> {
                        double strengthFraction = (double) cohort.getCurrentHealth() / cohort.getMaxHealth();
                        double resistance = 1.25 - Math.clamp(target.getEndurance(), 0, 100) / 200.0;
                        int damage = Math.max(1, (int) Math.round(cohort.getStrength() / 50.0 * 3 * strengthFraction * resistance));
                        hits.merge(target, damage, Integer::sum);
                    });
        }
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
            if (!cohort.isAlive() || !cohort.announceRout()) continue;
            cohort.setRetreatWaypoint(cohort.getPosition().x, attacker ? -FIELD_BOUND : FIELD_BOUND);
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
        if (phase == BattlePhase.DEPLOYMENT) {
            boolean attacker = isAttackerCohort(cohortId);
            // Deployment stays on the player's side and away from the opposing starting line.
            if ((attacker && (z > -8 || z < -100)) || (!attacker && (z < 8 || z > 100))) return false;
            if (java.util.stream.Stream.concat(attackerCohorts.stream(), defenderCohorts.stream())
                    .anyMatch(other -> other != cohort && other.isAlive() && !other.hasEscaped()
                            && other.getPosition().distanceSquared(x, z) < COHORT_SPACING * COHORT_SPACING - 1.0e-8)) return false;
            cohort.place(x, z);
        } else {
            if (cohort.isEngaged()) return false;
            if (clear) cohort.clearOrders();
            cohort.queueWaypoint(x, z);
        }
        return true;
    }
    public void withdrawCohort(UUID cohortId) {
        if (phase != BattlePhase.ENGAGEMENT) return;
        getCohortById(cohortId).ifPresent(c -> c.beginWithdrawal(c.getPosition().x,
                isAttackerCohort(cohortId) ? -FIELD_BOUND : FIELD_BOUND));
    }
    public void applyFormation(boolean attacker, Formation formation) {
        if (phase != BattlePhase.DEPLOYMENT || !active) return;
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
            side.get(i).place(x, (attacker ? -1 : 1) * (20 + depth * depthScale));
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
    public void addSpectator(UUID id) { spectatingPlayerIds.add(id); everSpectated = true; idleSteps = 0; }
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
        tag.putInt("CombatSteps", combatSteps);
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
            for (int i = 0; i < list.size(); i++) list.getCompound(i).ifPresent(c -> session.addAttackerCohort(CohortData.fromNBT(c)));
        });
        tag.getList("DefendingCohorts").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) list.getCompound(i).ifPresent(c -> session.addDefenderCohort(CohortData.fromNBT(c)));
        });
        BattleResult result = BattleResult.valueOf(tag.getString("Result").orElse("ONGOING"));
        session.phase = BattlePhase.valueOf(tag.getString("Phase").orElse("DEPLOYMENT"));
        session.deploymentSteps = Math.clamp(tag.getInt("DeploymentSteps").orElse(0), 0, DEPLOYMENT_STEPS);
        session.combatSteps = Math.clamp(tag.getInt("CombatSteps").orElse(0), 0, MELEE_INTERVAL);
        if (result != BattleResult.ONGOING) session.finish(result);
        return session;
    }
}
