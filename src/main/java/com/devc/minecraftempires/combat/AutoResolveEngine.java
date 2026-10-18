package com.devc.minecraftempires.combat;

import com.devc.minecraftempires.army.Army;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Cohort;
import java.util.*;
import com.devc.minecraftempires.network.MapDataService;

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
        if (session.hasTerrain() && session.getPhase() != BattleSession.BattlePhase.ENGAGEMENT) return new BattleOutcome(BattleSession.BattleResult.ONGOING, 0, 0);
        if (session.hasTerrain()) return resolveConnected(session, attacker, defender, manager);
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
    /** Resolve only complete connected groups; an isolated bank contributes neither power nor losses. */
    //detached units battle handling function: resolves battles for units that are not connected to the main battle, treating them as isolated and not contributing to the overall outcome
    private static BattleOutcome resolveConnected(BattleSession session, Army attacker, Army defender, ArmyManager manager) {
        session.checkTerrain();
        if (!session.isActive()) return new BattleOutcome(session.getResult(), 0, 0);
        List<CohortData> all = new ArrayList<>(session.getAttackerCohorts()); all.addAll(session.getDefenderCohorts());
        Map<CohortData, Set<CohortData>> edges = new HashMap<>();
        for (CohortData a : session.getAttackerCohorts()) if (a.canFight()) for (CohortData b : session.getDefenderCohorts()) if (b.canFight()) {
            var status = session.connection(a, b);
            if (status != MapDataService.Verification.VERIFIED && status != MapDataService.Verification.BLOCKED) return new BattleOutcome(BattleSession.BattleResult.ONGOING, 0, 0);
            if (status == MapDataService.Verification.VERIFIED) { edges.computeIfAbsent(a, ignored -> new HashSet<>()).add(b); edges.computeIfAbsent(b, ignored -> new HashSet<>()).add(a); }
        }
        Set<CohortData> seen = new HashSet<>(); int attackLoss = 0, defenseLoss = 0;
        for (CohortData seed : all) {
            if (!edges.containsKey(seed) || !seen.add(seed)) continue;
            List<CohortData> group = new ArrayList<>(); ArrayDeque<CohortData> queue = new ArrayDeque<>(); queue.add(seed);
            while (!queue.isEmpty()) { CohortData current = queue.removeFirst(); group.add(current); for (CohortData next : edges.getOrDefault(current, Set.of())) if (seen.add(next)) queue.add(next); }
            List<CohortData> attackers = group.stream().filter(session.getAttackerCohorts()::contains).toList();
            List<CohortData> defenders = group.stream().filter(session.getDefenderCohorts()::contains).toList();
            double attack = computePower(attackers, manager), defense = computePower(defenders, manager);
            boolean win = attack > 0 && attack >= defense;
            double balance = Math.abs(attack - defense) / Math.max(1, attack + defense);
            double winner = .10 + (1 - balance) * .15, loser = .40 + balance * .20;
            attackLoss += applyCasualties(attackers, win ? winner : loser, !win);
            defenseLoss += applyCasualties(defenders, win ? loser : winner, win);
        }
        boolean a = session.getAttackerCohorts().stream().anyMatch(CohortData::canFight), b = session.getDefenderCohorts().stream().anyMatch(CohortData::canFight);
        session.finish(a && b || !a && !b ? BattleSession.BattleResult.INTERRUPTED : a ? BattleSession.BattleResult.ATTACKER_WINS : BattleSession.BattleResult.DEFENDER_WINS);
        return new BattleOutcome(session.getResult(), attackLoss, defenseLoss);
    }
    public record BattleOutcome(BattleSession.BattleResult result, int attackerCasualties, int defenderCasualties) {}
}
