package com.devc.minecraftempires.combat;

import com.devc.minecraftempires.army.Army;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Cohort;
import java.util.List;

//if there is no player present, this class will be used to resolve the battle automatically, without player input
//computes the power of each army, determines a winner, applies casualties, and returns a BattleOutcome object
public final class AutoResolveEngine {
    private AutoResolveEngine() {}
    private static double computePower(List<CohortData> cohorts, ArmyManager manager) {
        double total = 0;
        for (CohortData data : cohorts) {
            if (!data.canFight()) continue;
            Cohort source = manager.resolveCohort(data.getCohortId());
            int gear = source == null ? 0 : switch (source.getGearTier()) {
                case STONE -> 0;
                case IRON -> 10;
                case DIAMOND -> 25;
            };
            double cavalry = "CAVALRY".equals(data.getType()) ? 1.5 : 1.0;
            total += data.getCurrentHealth() * cavalry *
                    Math.max(1, data.getStrength() + gear) * (0.25 + data.getMorale() / 100.0) *
                    (0.75 + Math.clamp(data.getEndurance(), 0, 100) / 200.0) * (data.isWithdrawing() ? 0.25 : 1.0);
        }
        return total;
    }
    private static int applyCasualties(List<CohortData> cohorts, double fraction, boolean defeated) {
        int eligible = cohorts.stream().filter(c -> c.isAlive() && !c.hasEscaped())
                .mapToInt(CohortData::getCurrentHealth).sum();
        int losses = Math.min(eligible, (int) Math.round(eligible * fraction));
        int remainingLosses = losses;
        int remainingSoldiers = eligible;
        for (CohortData cohort : cohorts) {
            if (!cohort.isAlive() || cohort.hasEscaped()) continue;
            int soldiers = cohort.getCurrentHealth();
            int count = remainingSoldiers == soldiers ? remainingLosses :
                    (int) ((long) remainingLosses * soldiers / Math.max(1, remainingSoldiers));
            cohort.applyDamage(count, defeated ? 30 : 10);
            if (defeated) cohort.setRouting(true);
            remainingLosses -= count;
            remainingSoldiers -= soldiers;
        }
        return losses;
    }
    public static BattleOutcome resolve(BattleSession session, Army attacker, Army defender, ArmyManager manager) {
        if (!session.isActive()) return new BattleOutcome(session.getResult(), 0, 0);
        double attack = attacker == null ? 0 : computePower(session.getAttackerCohorts(), manager);
        double defense = defender == null ? 0 : computePower(session.getDefenderCohorts(), manager);
        boolean attackerWins = attack > 0 && attack >= defense;
        double balance = Math.abs(attack - defense) / Math.max(1.0, attack + defense);
        double winnerLossRate = 0.10 + (1 - balance) * 0.15;
        double loserLossRate = 0.40 + balance * 0.20;
        if (attack == 0 || defense == 0) winnerLossRate = 0;
        int attackerLosses = applyCasualties(session.getAttackerCohorts(),
                attackerWins ? winnerLossRate : loserLossRate, !attackerWins);
        int defenderLosses = applyCasualties(session.getDefenderCohorts(),
                attackerWins ? loserLossRate : winnerLossRate, attackerWins);
        BattleSession.BattleResult result = attackerWins ? BattleSession.BattleResult.ATTACKER_WINS :
                BattleSession.BattleResult.DEFENDER_WINS;
        session.finish(result);
        return new BattleOutcome(result, attackerLosses, defenderLosses);
    }
    public record BattleOutcome(BattleSession.BattleResult result, int attackerCasualties, int defenderCasualties) {}
}
