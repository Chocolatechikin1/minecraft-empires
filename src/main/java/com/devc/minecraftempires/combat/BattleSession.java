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
 *
 * Pacing notes:
 *  - DEPLOYMENT phase lasts 30 s (600 ticks). Cohorts accept waypoints but don't march or fight.
 *  - Idle grace period: 60 s (1200 ticks) on first creation; drops to 10 s (200 ticks) if a player
 *    spectates and then leaves. BattleManager auto-resolves only after this threshold is exceeded.
 *  - Melee damage fires every 40 ticks (2 s) to slow down combat. Movement still ticks every 5 ticks.
 *  - Routing cohorts passively drain -1 morale per combat tick from nearby friendly units (morale aura).
 */
//okay explanation ill keep it
public class BattleSession {
    public enum BattleResult {
        ONGOING,
        ATTACKER_WINS,
        DEFENDER_WINS
    }

    /** Two-phase battle flow: troops deploy first, then engage. */
    public enum BattlePhase {
        DEPLOYMENT,
        ENGAGEMENT
    }

    //stat constants (adjust as needed) - this will need rework
    private static final double MELEE_RANGE          = 3.0;
    private static final int    BASE_MELEE_DAMAGE    = 3;
    private static final int    MELEE_MORALE_SHOCK   = 2;
    private static final int    ROUT_CHAIN_SHOCK     = 10; // morale shock applied to adjacent cohorts when a cohort routs

    /** Radius (in battle units) within which a routing cohort's aura drains friendly morale. */
    private static final double MORALE_AURA_RADIUS   = 10.0;
    private static final int    MORALE_AURA_DRAIN    = 1;  // morale drained per combat tick per nearby routing unit

    /** Deployment phase length — 30 seconds at 20 ticks/s. */
    private static final int DEPLOYMENT_TICKS_MAX    = 600;

    /** After creation, auto-resolve is blocked for 60 s (players can open the map). */
    private static final int INITIAL_GRACE_TICKS     = 1200;

    /** After a spectating player leaves, auto-resolve is blocked for only 10 s. */
    private static final int ABANDONMENT_GRACE_TICKS = 200;

    /** Melee clashes fire once every 40 ticks (2 s) to slow down combat. */
    private static final int MELEE_TICK_INTERVAL     = 40;

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

    //deployment phase tracking
    private BattlePhase phase = BattlePhase.DEPLOYMENT;
    private int deploymentTicks  = 0;
    private int  idleTicks = 0;
    private boolean wasEverSpectated = false;
    private int combatTickCounter = 0;

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

    //grace period checker function, returns true if the battle is still in the grace period and cannot be auto-resolved
    public boolean isAutoResolveAllowed() {
        int threshold = wasEverSpectated ? ABANDONMENT_GRACE_TICKS : INITIAL_GRACE_TICKS;
        return idleTicks >= threshold;
    }

    //resets if a player returns to spectate
    public void resetIdleTicks() {
        idleTicks = 0;
    }

    //tick simulator for when a player is spectating
    public void tick() {
        if (!isActive) return;

        //deplyoment phase: cohorts can be moved but do not march or fight, after 30 seconds the battle enters engagement phase
        if (phase == BattlePhase.DEPLOYMENT) {
            deploymentTicks++;
            if (deploymentTicks >= DEPLOYMENT_TICKS_MAX) {
                phase = BattlePhase.ENGAGEMENT;
            }
            return; // skip movement and combat until ENGAGEMENT
        }

        //start of engagement phase
        attackerCohorts.forEach(CohortData::tickMovement);
        defenderCohorts.forEach(CohortData::tickMovement);

        //combat tick counter, melee clashes and morale auras only happen every 40 ticks (2 seconds) to slow down combat
        combatTickCounter++;
        if (combatTickCounter >= MELEE_TICK_INTERVAL) {
            combatTickCounter = 0;

            //melee clash: check for overlapping cohorts across sides
            processMeleeClashes();

            //morale aura: routing cohorts drain morale from nearby friendly units
            //TODO: make sure that morale loss aura isnt applied every time for some reason
            processMoraleAuras(attackerCohorts);
            processMoraleAuras(defenderCohorts);
        }

        //propagate morale chain-panic for newly routed cohorts (ensure this isn't actually being done each tick, pretty much instant rout here)
        processNewlyRouted(attackerCohorts);
        processNewlyRouted(defenderCohorts);

        //check end conditions
        checkBattleEnd();
    }

    //idle tick counter function, called when no players are spectating the battle, increments idleTicks by 1
    public void tickIdle() {
        idleTicks++;
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

    /** keep for now, remove this comment later
     * Morale aura: any routing cohort continuously drains -1 morale per combat tick
     * from all friendly units within MORALE_AURA_RADIUS. This implements the
     * "chain-panic" mechanic as a spatial Aura effect rather than a one-time neighbour shock.
     *
     * @param cohorts The list of cohorts on one side (attackers or defenders).
     */
    private void processMoraleAuras(List<CohortData> cohorts) {
        for (CohortData routing : cohorts) {
            if (!routing.isRouting() || !routing.isAlive()) continue;

            for (CohortData friendly : cohorts) {
                if (friendly == routing || !friendly.isAlive() || friendly.isRouting()) continue;

                if (routing.getPosition().distance(friendly.getPosition()) <= MORALE_AURA_RADIUS) {
                    friendly.applyMoraleShock(MORALE_AURA_DRAIN);
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

    //spectator management getters
    public void addSpectator(UUID playerId) {
        spectatingPlayerIds.add(playerId);
        wasEverSpectated = true; // once a player has joined, abandonment threshold applies on leave
        idleTicks = 0;           // reset idle counter while someone is watching
    }
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
    public BattlePhase      getPhase()            { return phase; }
    public int              getDeploymentTicksRemaining() { return Math.max(0, DEPLOYMENT_TICKS_MAX - deploymentTicks); }
    public void             setInactive()         { isActive = false; }
}