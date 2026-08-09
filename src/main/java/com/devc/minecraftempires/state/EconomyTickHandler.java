package com.devc.minecraftempires.state;

import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Legion;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.devc.minecraftempires.territory.ClaimManager;

public class EconomyTickHandler {
    private static final int TICKS_PER_DAY = 24000; //1 in game day
    private static int tickCounter = 0;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;

        //trigger the economy loop once per day
        if (tickCounter >= TICKS_PER_DAY) {
            tickCounter = 0; 
            
            //gets overworld only to process the global economy
            ServerLevel overworld = event.getServer().getLevel(ServerLevel.OVERWORLD);
            if (overworld != null) {
                processDailyEconomics(overworld);
            }
        }
    }

    private static void processDailyEconomics(ServerLevel level) {
        StateManager manager     = StateManager.get(level);
        ClaimManager claimManager = ClaimManager.get(level);
        ArmyManager armyManager  = ArmyManager.get(level);
        
        for (StateData state : manager.getAllStates()) {
            
            //calculates tax revenue (whole-number emeralds)
            long taxRevenue = calculateTax(state);
            //calculates maintenance costs — whole-number emeralds, no decimals
            long maintenanceCost = calculateMaintenance(state, claimManager, armyManager);
            //applies net to treasury
            long netProfit = taxRevenue - maintenanceCost;
            
            if (netProfit > 0) {
                state.addFunds(netProfit);
            } else { //costs exceed revenue, deduct from treasury
                state.deductFunds(Math.abs(netProfit));
                // TODO (Phase 4): Trigger bankruptcy warnings if treasury drops to 0
            }

            //tick down immunity (decrements by 1 day)
            state.tickSiegeImmunity();
        }
        
        // Run daily garbage collection: disband Legions that have fallen below viability.
        // This runs AFTER treasury deductions so a newly bankrupt state isn't immediately
        // stripped of armies — that's a separate Phase 4 mechanic.
        armyManager.runGarbageCollection();

        // IMPORTANT: Tell the server this data changed so it writes the new bank balances to disk!
        manager.setDirty();
        armyManager.setDirty();
    }

    //all math formulas for economy are here, can be adjusted later for balance
    private static long calculateTax(StateData state) {
        //base income per tier (e.g. Empire yields more base tax than a County)
        long baseIncome = (long)(state.getCurrentTier().ordinal() * 50);

        //income scaled by population: 1 emerald per 2 citizens (integer math, no decimals)
        long popIncome = state.getTotalPopulation() / 2;

        return baseIncome + popIncome;
    }

    private static long calculateMaintenance(StateData state, ClaimManager claimManager, ArmyManager armyManager) {
        //higher tiers cost more inherently to maintain
        long baseMaintenance = (long)(state.getCurrentTier().ordinal() * 75);

        //get land upkeep costs
        int ownedChunks = claimManager.getClaimCountForState(state.getStateId());
        long chunkCost = ownedChunks * 2L; //2 emeralds per chunk per day

        //legion upkeep: flat 1 emerald per soldier per day (no decimal math)
        int totalSoldiers = armyManager.getLegionsForState(state.getStateId()).stream().mapToInt(Legion::getTotalSoldiers).sum();
        long legionCost = totalSoldiers * 1L;

        return baseMaintenance + chunkCost + legionCost;
    }
}