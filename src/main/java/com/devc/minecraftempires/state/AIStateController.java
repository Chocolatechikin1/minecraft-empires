package com.devc.minecraftempires.state;

import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Legion;
import com.devc.minecraftempires.territory.SettlementData;
import net.minecraft.server.level.ServerLevel;

import java.util.*;
import java.util.function.Consumer;

/*
NPC state management class
Manages the behavior and actions of npc states on players
It is responsible for making decisions for npc states based on their current situation and the game state
*/
public final class AIStateController {
    public static final int MAX_DECISIONS_PER_PASS = 4;
    public static final int RESERVE_DAYS = 2;
    public enum Action { WAIT, UPGRADE, RECRUIT, GOVERNMENT, OFFER_PEACE }
    public record Decision(StateData.Strategy strategy, Action action) {}
    /** Facts contain no player/session object and do not mutate state while candidates are scored. */
    public record Situation(long treasury, long maintenance, int unpaidDays, int settlements, boolean atWar,
                            boolean threatened, boolean readyForce, boolean capitalLost,
                            boolean recruitEligible, boolean upgradeEligible, boolean governmentEligible) {}
    private record Candidate(int score, Decision decision) {}
    private final Deque<UUID> pending = new ArrayDeque<>();
    private final Set<UUID> registered = new HashSet<>();

    public void register(UUID id) { if (registered.add(Objects.requireNonNull(id))) pending.addLast(id); }
    public int queuedStates() { return pending.size(); }

    /** A shared rotating budget covers finance and decisions, including states still on decision cooldown. */
    private int visit(StateManager states, long now, int budget, Consumer<StateData> update) {
        if (now < 0 || budget < 1) return 0;
        int visits = Math.min(Math.min(budget, MAX_DECISIONS_PER_PASS), pending.size()), updated = 0;
        for (int i = 0; i < visits; i++) {
            UUID id = pending.removeFirst(); StateData state = states.getState(id);
            if (state == null || !state.isAI()) { registered.remove(id); continue; }
            pending.addLast(id);
            if (state.isInitializationPending() || states.getAIWars().blocks(id)) continue;
            update.accept(state); updated++;
        }
        return updated;
    }

    /** Saved per-state dates survive restart; queue order is disposable and never catches up missed purchases. */
    public int advance(StateManager states, long now, int budget, Consumer<StateData> decide) {
        int[] decisions = {0};
        visit(states, now, budget, state -> {
            if (now < state.getNextDecisionTime()) return;
            state.recordDecision(now, StateBalance.TICKS_PER_DAY);
            states.setDirty(); decisions[0]++; decide.accept(state);
        });
        return decisions[0];
    }

    //npc state economy method, handles the economic decisions of npc states
    /** At most one owed day per visit; drain bills before allowing a discretionary decision. */
    public int advanceEconomy(StateManager states, long now, int budget, Consumer<StateData> bill, Consumer<StateData> decide) {
        return advanceEconomy(states, now, budget, bill, state -> { }, decide);
    }
    public int advanceEconomy(StateManager states, long now, int budget, Consumer<StateData> bill, Consumer<StateData> military, Consumer<StateData> decide) {
        return visit(states, now, budget, state -> {
            long before = state.getLastEconomyTime();
            state.initializeEconomyTime(now);
            if (state.hasEconomicDayDue(now)) {
                bill.accept(state);
                state.recordEconomicDay(now);
            }
            if (before != state.getLastEconomyTime()) states.setDirty();
            if (state.hasEconomicDayDue(now) || now < state.getLastEconomyTime()) return;
            military.accept(state);
            if (states.getState(state.getStateId()) != state || states.getAIWars().blocks(state.getStateId())
                    || state.getStrategy() == StateData.Strategy.SURRENDER || now < state.getNextDecisionTime()) return;
            state.recordDecision(now, StateBalance.TICKS_PER_DAY);
            states.setDirty();
            decide.accept(state);
        });
    }

    /** Two days of the resulting maintenance must remain after a discretionary purchase. */
    public static boolean canSpend(long treasury, long maintenance, long price, long addedMaintenance) {
        if (treasury < 0 || maintenance < 0 || price < 0 || addedMaintenance < 0 || treasury < price) return false;
        long available = (treasury - price) / RESERVE_DAYS;
        return maintenance <= available && addedMaintenance <= available - maintenance;
    }

    //decision tree function: the AI will choose a decision based on the current situation of the state
    public static Decision choose(Situation facts) {
        List<Candidate> choices = new ArrayList<>();
        choices.add(new Candidate(0, new Decision(StateData.Strategy.DEVELOP, Action.WAIT)));
        if (facts.atWar()) choices.add(new Candidate(20, new Decision(facts.readyForce() ? StateData.Strategy.OPERATE : StateData.Strategy.RECOVER, Action.WAIT)));
        if (facts.unpaidDays() > 0 || !canSpend(facts.treasury(), facts.maintenance(), 0, 0))
            choices.add(new Candidate(90, new Decision(StateData.Strategy.RECOVER, Action.WAIT)));
        if (facts.threatened()) choices.add(new Candidate(100, new Decision(StateData.Strategy.DEFEND, Action.WAIT)));
        if (facts.unpaidDays() == 0) {
            if (facts.upgradeEligible() && !facts.threatened() && canSpend(facts.treasury(), facts.maintenance(), StateBalance.CITY_UPGRADE_COST, StateBalance.SETTLEMENT_UPKEEP_PER_TIER))
                choices.add(new Candidate(50, new Decision(StateData.Strategy.DEVELOP, Action.UPGRADE)));
            if (facts.recruitEligible() && !facts.readyForce() && canSpend(facts.treasury(), facts.maintenance(), ArmyManager.LEGION_RECRUIT_COST, Legion.DAILY_UPKEEP_EMERALDS))
                choices.add(new Candidate(facts.threatened() ? 105 : 60, new Decision(facts.atWar() ? StateData.Strategy.DEFEND : StateData.Strategy.PREPARE, Action.RECRUIT)));
            if (facts.governmentEligible()) choices.add(new Candidate(70, new Decision(StateData.Strategy.DEVELOP, Action.GOVERNMENT)));
        }
        if (facts.atWar() && !facts.threatened() && (facts.unpaidDays() >= 3 || facts.capitalLost() && facts.settlements() <= 1))
            choices.add(new Candidate(110, new Decision(StateData.Strategy.RECOVER, Action.OFFER_PEACE)));
        return choices.stream().max(Comparator.comparingInt(Candidate::score)).orElseThrow().decision();
    }

    /** Player progression requires two towns for city-state tier; preserve the base while preparing and retaining it. */
    public static boolean canDevelop(StateData state, SettlementData site, int towns) {
        return StateManager.settlementUpgradeProblem(state, site).isEmpty()
                && !((state.getCurrentTier() == StateTier.DUCHY || state.getCurrentTier() == StateTier.CITY_STATE) && site.getSettlementTier() == 1 && towns <= 2);
    }

    /** Run after the completed economic interval. Purchases go through the same validators as human actions. */
    public void tick(ServerLevel level) {
        StateManager states = StateManager.get(level); ArmyManager armies = ArmyManager.get(level);
        advanceEconomy(states, level.getGameTime(), MAX_DECISIONS_PER_PASS,
                state -> EconomyTickHandler.processStateDay(level, state), state -> states.getAIWars().visit(level, state), state -> {
            states.refreshProgression(level, state);
            List<SettlementData> settlements = states.settlementsFor(state).stream().sorted(Comparator.comparing(SettlementData::getSettlementId)).toList();
            int legions = armies.getLegionCountForState(state.getStateId());
            List<StateData> enemies = states.enemiesFor(state.getStateId()).stream().sorted(Comparator.comparing(StateData::getStateId)).toList();
            boolean threatened = settlements.stream().anyMatch(site -> armies.hasHostilePresence(state.getStateId(), site.getCenterAltarPos(), level));
            SettlementData recruit = settlements.stream().filter(site -> ArmyManager.recruitmentProblem(state, site, legions, false,
                    armies.hasHostilePresence(state.getStateId(), site.getCenterAltarPos(), level)).isEmpty()).findFirst().orElse(null);
            int towns = states.getTownCount(state);
            SettlementData upgrade = settlements.stream().filter(site -> canDevelop(state, site, towns)).findFirst().orElse(null);
            boolean ready = armies.getLegionsForState(state.getStateId()).stream().filter(Legion::isViable).flatMap(legion -> legion.allCohorts().stream())
                    .anyMatch(cohort -> cohort.isAlive() && !cohort.isRouting() && cohort.getMorale() >= 20 && !cohort.isSupplyExhausted())
                    || armies.getStrategicForcesForState(state.getStateId()).stream().anyMatch(ArmyManager.StrategicForce::ready);
            boolean capitalLost = state.getLastCapitalLossTime() >= 0 && level.getGameTime() >= state.getLastCapitalLossTime()
                    && level.getGameTime() - state.getLastCapitalLossTime() < StateBalance.TICKS_PER_DAY * 3;
            Decision decision = choose(new Situation(state.getTreasuryBalance(), EconomyTickHandler.calculateMaintenance(state, states, armies),
                    state.getUnpaidDays(), settlements.size(), !enemies.isEmpty(), threatened, ready, capitalLost,
                    recruit != null, upgrade != null, state.hasEndgameChoice()));
            state.setStrategy(decision.strategy());
            switch (decision.action()) {
                case UPGRADE -> states.upgradeSettlement(level, state, upgrade.getSettlementId());
                case RECRUIT -> armies.recruitLegion(level, state.getStateId(), recruit.getSettlementId(), null);
                case GOVERNMENT -> states.chooseGovernment(level, state, (state.getStateId().getLeastSignificantBits() & 1) == 0 ? StateTier.EMPIRE : StateTier.REPUBLIC);
                case OFFER_PEACE -> enemies.forEach(enemy -> {
                    if (states.hasPeaceOffer(enemy.getStateId(), state.getStateId())) states.acceptPeace(level, state.getStateId(), enemy.getStateId());
                    else states.offerPeace(state.getStateId(), enemy.getStateId());
                });
                // OPERATE is strategic intent; the macro coordinator owns AI-only routes and outcomes.
                case WAIT -> { }
            }
            states.getPlayerConflicts().considerHumanOpponent(level, state);
            states.getAIWars().consider(level, state);
        });
    }
}
