package com.devc.minecraftempires.combat;

import com.devc.minecraftempires.army.Army;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Cohort;
import com.devc.minecraftempires.army.CohortType;
import com.devc.minecraftempires.army.GearTier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

//if there is no player present, this class will be used to resolve the battle automatically, without player input
//computes the power of each army, determines a winner, applies casualties, and returns a BattleOutcome object
public final class AutoResolveEngine {
    private AutoResolveEngine() {}

    private static int gearTierBonus(GearTier tier) {
        return switch (tier) {
            case STONE   -> 0;
            case IRON    -> 10;
            case DIAMOND -> 25;
        };
    }

    //compute function, calculates the power of an army based on the number of soldiers, their strength, gear tier, and morale
    private static double computePower(Army army, ArmyManager manager) {
        double total = 0;
        for (UUID cohortId : army.getDeployedCohortIds()) {
            Cohort c = manager.resolveCohort(cohortId);
            if (c == null || !c.isAlive() || c.isGarrisoned()) continue;

            double cavalryMult = c.getType() == CohortType.CAVALRY ? 1.5 : 1.0; //1.5x multiplier for cavalry units
            total += c.getSoldierCount() * cavalryMult * (c.getStrength() + gearTierBonus(c.getGearTier()) + (c.getMorale() / 10.0));
        }
        return Math.max(1, total); //to avoid division by zero
    }

    //casualty function, applies casualties to an army, distributing them proportionally across all alive, non-garrisoned cohorts, and applying morale shock based on the number of casualties taken
    private static void applyCasualties(Army army, int totalCasualties, ArmyManager manager) {
        List<Cohort> combatants = new ArrayList<>();
        int totalSoldiers = 0;
        for (UUID cohortId : army.getDeployedCohortIds()) {
            Cohort c = manager.resolveCohort(cohortId);
            if (c != null && c.isAlive() && !c.isGarrisoned()) {
                combatants.add(c);
                totalSoldiers += c.getSoldierCount();
            }
        }
        if (totalSoldiers <= 0) return;

        for (Cohort cohort : combatants) {
            double share     = (double) cohort.getSoldierCount() / totalSoldiers;
            int    casualties = (int) Math.round(totalCasualties * share);
            cohort.applyAttrition(casualties);
            int moraleShock  = (int)((double) casualties / Math.max(1, cohort.getSoldierCount()) * 30);
            cohort.tickMorale(-moraleShock);
        }
    }

    //strength function, calculates the total number of soldiers in an army, excluding garrisoned cohorts
    private static int combatStrength(Army army, ArmyManager manager) {
        int total = 0;
        for (UUID cohortId : army.getDeployedCohortIds()) {
            Cohort c = manager.resolveCohort(cohortId);
            if (c != null && c.isAlive() && !c.isGarrisoned()) total += c.getSoldierCount();
        }
        return total;
    }

    //resolver function, takes in a battle session, an attacker army, a defender army, and the army manager, computes the power of each army, determines a winner, applies casualties, increments battle count for XP purposes, and returns a BattleOutcome object
    public static BattleOutcome resolve(BattleSession session, Army attacker, Army defender, ArmyManager manager) {
        double attackerPower = computePower(attacker, manager);
        double defenderPower = computePower(defender, manager);

        double totalPower = attackerPower + defenderPower;
        double attackerRatio = attackerPower / totalPower;
        double defenderRatio = defenderPower / totalPower;

        int totalSoldiers = combatStrength(attacker, manager) + combatStrength(defender, manager);
        double balance = Math.abs(attackerRatio - defenderRatio);

        //casualty calculations: loser takes 40-60% casualties, winner takes 10-25% casualties, scaled by the balance of power
        int loserCasualties = (int)(totalSoldiers * (0.40 + balance * 0.20));
        int winnerCasualties = (int)(totalSoldiers * (0.10 + (1 - balance) * 0.15));

        BattleSession.BattleResult result;
        int attackerLosses;
        int defenderLosses;

        if (attackerPower >= defenderPower) {
            result = BattleSession.BattleResult.ATTACKER_WINS;
            attackerLosses = Math.min(combatStrength(attacker, manager), winnerCasualties);
            defenderLosses = Math.min(combatStrength(defender, manager), loserCasualties);
        } 
        else {
            result = BattleSession.BattleResult.DEFENDER_WINS;
            attackerLosses = Math.min(combatStrength(attacker, manager), loserCasualties);
            defenderLosses = Math.min(combatStrength(defender, manager), winnerCasualties);
        }

        applyCasualties(attacker, attackerLosses, manager);
        applyCasualties(defender, defenderLosses, manager);

        // Increment battle count for XP purposes
        for (UUID cohortId : attacker.getDeployedCohortIds()) {
            Cohort c = manager.resolveCohort(cohortId);
            if (c != null && !c.isGarrisoned()) c.incrementBattleCount();
        }
        for (UUID cohortId : defender.getDeployedCohortIds()) {
            Cohort c = manager.resolveCohort(cohortId);
            if (c != null && !c.isGarrisoned()) c.incrementBattleCount();
        }

        session.setInactive();
        return new BattleOutcome(result, attackerLosses, defenderLosses);
    }

    //outcome class, holds the result of a battle, the number of casualties for the attacker, and the number of casualties for the defender
    public record BattleOutcome(
            BattleSession.BattleResult result,
            int attackerCasualties,
            int defenderCasualties
    ) {}
}
