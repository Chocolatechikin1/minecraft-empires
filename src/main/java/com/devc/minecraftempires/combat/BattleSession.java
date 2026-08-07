package com.devc.minecraftempires.combat;

import org.joml.Vector2d;

import java.util.*;

/**
 * Represents a single active tactical battle between two Legions.
 *
 * The battle map is a 2-D coordinate space in "battle units" (≈ 1 block).
 * The origin (0, 0) maps to (mapOriginX, mapOriginZ) in grand-strategy world coordinates.
 *
 * Lifecycle: created by BattleManager.startBattle() → ticked by BattleTickHandler every few server ticks
 *            → resolved (ATTACKER_WINS / DEFENDER_WINS) → cleaned up by BattleManager.
 */
//okay explanation ill keep it
public class BattleSession {
    public enum BattleResult {
        ONGOING,
        ATTACKER_WINS,
        DEFENDER_WINS
    }

    //stat constants (adjust as needed) - this will need rework
    private static final double MELEE_RANGE = 3.0;
    private static final int BASE_MELEE_DAMAGE = 3;
    private static final int MELEE_MORALE_SHOCK = 2;
    private static final int ROUT_CHAIN_SHOCK = 10; // morale shock applied to adjacent cohorts when a cohort routs

    private final UUID battleId;
    private final UUID attackerArmyId;
    private final UUID defenderArmyId;
    private final double mapOriginX;
    private final double mapOriginZ;

    ///combatants
    private final List<CohortData> attackerCohorts;
    private final List<CohortData> defenderCohorts;

    //battle state
    private boolean isActive;
    private BattleResult result = BattleResult.ONGOING;

    //set that tracks number of spectating players
    private final Set<UUID> spectatingPlayerIds = new HashSet<>();

    public BattleSession(UUID attackerArmyId, UUID defenderArmyId, double originX, double originZ) {
        this.battleId        = UUID.randomUUID();
        this.attackerArmyId  = attackerArmyId;
        this.defenderArmyId  = defenderArmyId;
        this.attackerCohorts = new ArrayList<>();
        this.defenderCohorts = new ArrayList<>();
        this.mapOriginX      = originX;
        this.mapOriginZ      = originZ;
        this.isActive        = true;
    }

    //tick simulator for when a player is spectating
    public void tick() {
        if (!isActive) return;

        //move all non-routing cohorts toward their next waypoint
        attackerCohorts.forEach(CohortData::tickMovement);
        defenderCohorts.forEach(CohortData::tickMovement);

        //melee clash: check for overlapping cohorts across sides
        processMeleeClashes();

        //propagate morale chain-panic for newly routed cohorts (ensure this isnt actually being done each tick, pretty much instant rout here)
        processNewlyRouted(attackerCohorts);
        processNewlyRouted(defenderCohorts);

        //check end conditions
        checkBattleEnd();
    }

    //combat damage function
    private void processMeleeClashes() {
        for (CohortData attacker : attackerCohorts) {
            if (!attacker.isAlive() || attacker.isRouting()) continue;
            for (CohortData defender : defenderCohorts) {
                if (!defender.isAlive() || defender.isRouting()) continue;

                if (attacker.getPosition().distance(defender.getPosition()) <= MELEE_RANGE) {
                    // Damage scales with strength stat (0–100 scale → 0–BASE_MELEE_DAMAGE * 2)
                    int aDmg = Math.max(1, (int)(attacker.getStrength() / 50.0 * BASE_MELEE_DAMAGE));
                    int dDmg = Math.max(1, (int)(defender.getStrength() / 50.0 * BASE_MELEE_DAMAGE));
                    attacker.applyDamage(dDmg, MELEE_MORALE_SHOCK);
                    defender.applyDamage(aDmg, MELEE_MORALE_SHOCK);
                }
            }
        }
    }

    //morale at rout processing, if a cohort is routing, it will apply morale shock to its left and right neighbours in the list
    private void processNewlyRouted(List<CohortData> cohorts) {
        for (int i = 0; i < cohorts.size(); i++) {
            CohortData c = cohorts.get(i);
            if (c.getMorale() <= 0 && !c.isRouting()) {
                c.setRouting(true);
                // Left neighbour
                if (i - 1 >= 0) cohorts.get(i - 1).applyMoraleShock(ROUT_CHAIN_SHOCK);
                // Right neighbour
                if (i + 1 < cohorts.size()) cohorts.get(i + 1).applyMoraleShock(ROUT_CHAIN_SHOCK);
            }
        }
    }

    //rout function, sets a retreat waypoint for all cohorts on the given side
    public void signalRetreat(double retreatX, double retreatZ, boolean forSide) {
        List<CohortData> cohorts = forSide ? attackerCohorts : defenderCohorts;
        for (CohortData c : cohorts) {
            if (c.isRouting()) {
                c.setRetreatWaypoint(retreatX, retreatZ);
            }
        }
    }

    //withdraw function, called by BattleManager when a player issues a withdrawal command. This will set the retreat waypoint for all cohorts on the given side, regardless of routing status.
    public void issueWithdrawal(double retreatX, double retreatZ, boolean forSide) {
        List<CohortData> cohorts = forSide ? attackerCohorts : defenderCohorts;
        for (CohortData c : cohorts) {
            c.setRetreatWaypoint(retreatX, retreatZ);
        }
    }

    //end of battle function, checks if either side has no more fighting cohorts
    private void checkBattleEnd() {
        //get attacker and defender cohorts that are alive and not routing
        boolean attackersCanFight = attackerCohorts.stream().anyMatch(c -> c.isAlive() && !c.isRouting());
        boolean defendersCanFight = defenderCohorts.stream().anyMatch(c -> c.isAlive() && !c.isRouting());

        //whichever side has remaining cohorts is the winner, if both sides have no remaining cohorts, the defender wins by default
        if (!attackersCanFight) {
            result   = BattleResult.DEFENDER_WINS;
            isActive = false;
        } else if (!defendersCanFight) {
            result   = BattleResult.ATTACKER_WINS;
            isActive = false;
        }
    }

    //cohort getters
    public void addAttackerCohort(CohortData cohort) { attackerCohorts.add(cohort); }
    public void addDefenderCohort(CohortData cohort) { defenderCohorts.add(cohort); }

    public Optional<CohortData> getCohortById(UUID cohortId) {
        for (CohortData c : attackerCohorts) {
            if (c.getCohortId().equals(cohortId)) return Optional.of(c);
        }
        for (CohortData c : defenderCohorts) {
            if (c.getCohortId().equals(cohortId)) return Optional.of(c);
        }
        return Optional.empty();
    }

    //specrator management getters
    public void addSpectator(UUID playerId)    { spectatingPlayerIds.add(playerId); }
    public void removeSpectator(UUID playerId) { spectatingPlayerIds.remove(playerId); }
    public boolean isSpectated()               { return !spectatingPlayerIds.isEmpty(); }
    public Set<UUID> getSpectatingPlayerIds()  { return Collections.unmodifiableSet(spectatingPlayerIds); }

    //getters
    public UUID             getBattleId()         { return battleId; }
    public UUID             getAttackerArmyId()   { return attackerArmyId; }
    public UUID             getDefenderArmyId()   { return defenderArmyId; }
    public double           getMapOriginX()       { return mapOriginX; }
    public double           getMapOriginZ()       { return mapOriginZ; }
    public List<CohortData> getAttackerCohorts()  { return Collections.unmodifiableList(attackerCohorts); }
    public List<CohortData> getDefenderCohorts()  { return Collections.unmodifiableList(defenderCohorts); }
    public boolean          isActive()            { return isActive; }
    public BattleResult     getResult()           { return result; }
    public void             setInactive()         { isActive = false; }
}