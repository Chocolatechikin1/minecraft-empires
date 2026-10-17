package com.devc.minecraftempires.state;

import com.devc.minecraftempires.army.Army;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Cohort;
import com.devc.minecraftempires.army.Legion;
import com.devc.minecraftempires.army.LogisticsManager;
import com.devc.minecraftempires.territory.SettlementData;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Saved, in-game daily economy. Land itself never incurs maintenance. */
public class EconomyTickHandler {
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) return;
        ServerLevel level = event.getServer().overworld();
        StateManager manager = StateManager.get(level);
        // Supply uses its own saved active-state clock; ordinary financial days continue while members are offline.
        LogisticsManager.get(level).tick(level);
        long now = level.getGameTime();
        long last = manager.getLastEconomyGameTime();
        if (last < 0 || last > now) manager.setLastEconomyGameTime(now);
        else if (now - last >= StateBalance.TICKS_PER_DAY) {
            // Saved cadence prevents either restart charges or restart avoidance.
            processDailyEconomics(level);
            manager.setLastEconomyGameTime(last + StateBalance.TICKS_PER_DAY);
        }
        // Bill the interval just completed before expiring its final grace ticks.
        // This keeps the fourteenth introductory day's bill waived at the boundary.
        manager.tickTimers(level);
        // Settlement changes also update progression immediately; this handles old saves.
        if (event.getServer().getTickCount() % 200 == 0) manager.refreshProgression(level);
    }

    public static void processDailyEconomics(ServerLevel level) {
        StateManager manager = StateManager.get(level);
        ArmyManager armies = ArmyManager.get(level);
        for (StateData state : manager.getAllStates()) {
            state.addFunds(calculateTax(state, manager));
            long maintenance = calculateMaintenance(state, manager, armies);
            boolean paid = chargeUpkeep(state, maintenance);
            if (!paid) {
                manager.notifyState(level, state.getStateId(), "Upkeep unpaid for " + state.getUnpaidDays()
                        + " day(s): troop morale is falling" + (state.getUnpaidDays() >= 3 ? " and soldiers are deserting." : "."));
            }
            for (Legion legion : armies.getLegionsForState(state.getStateId())) {
                for (Cohort cohort : legion.allCohorts()) {
                    Army assigned = armies.getArmy(cohort.getAssignedArmyId());
                    if (!cohort.isAlive() || (assigned != null && assigned.isEngaged())) continue;
                    if (!paid) {
                        cohort.tickMorale(-10);
                        if (state.getUnpaidDays() >= 7) cohort.applyAttrition(cohort.getSoldierCount());
                        else if (state.getUnpaidDays() >= 3)
                            cohort.applyAttrition(Math.max(1, (cohort.getSoldierCount() + 9) / 10));
                    } else if (LogisticsManager.get(level).canRecoverMorale(state.getStateId(), cohort, level)) {
                        cohort.tickMorale(2);
                        if (cohort.getMorale() >= 20) cohort.setRouting(false);
                    }
                }
            }
            // Simple growth supplies a testable path from town to city and state milestones.
            if (paid) for (SettlementData settlement : manager.settlementsFor(state)) {
                int pop = settlement.getLocalPopulation();
                int growth = Math.max(StateBalance.DAILY_MINIMUM_GROWTH, pop / 50);
                settlement.setLocalPopulation((int) Math.min(Integer.MAX_VALUE, (long) pop + growth));
            }
        }
        armies.runGarbageCollection();
        manager.refreshProgression(level);
        manager.setDirty();
        armies.setDirty();
    }

    public static long calculateTax(StateData state, StateManager manager) {
        return manager.settlementsFor(state).stream()
                .mapToLong(s -> (long) s.getLocalPopulation() * StateBalance.TAX_PER_RESIDENT).sum();
    }
    public static boolean chargeUpkeep(StateData state, long maintenance) {
        maintenance = Math.max(0, maintenance);
        if (state.getGraceTicksRemaining() > 0) {
            state.setUnpaidDays(0);
            return true;
        }
        if (state.getTreasuryBalance() >= maintenance) {
            state.deductFunds(maintenance);
            state.setUnpaidDays(0);
            return true;
        }
        state.deductFunds(state.getTreasuryBalance());
        state.setUnpaidDays(Math.min(7, state.getUnpaidDays() + 1));
        return false;
    }
    public static long calculateMaintenance(StateData state, StateManager manager, ArmyManager armies) {
        long settlementCost = manager.settlementsFor(state).stream()
                .mapToLong(s -> (long) s.getSettlementTier() * StateBalance.SETTLEMENT_UPKEEP_PER_TIER).sum();
        long soldiers = armies.getLegionsForState(state.getStateId()).stream().mapToLong(Legion::getTotalSoldiers).sum();
        return settlementCost + soldiers;
    }
}
