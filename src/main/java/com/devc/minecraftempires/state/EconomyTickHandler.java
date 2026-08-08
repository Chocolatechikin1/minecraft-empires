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
            
            //calculates tax revenue
            double taxRevenue = calculateTax(state);
            //calculates maintenance costs (now includes Legion upkeep)
            double maintenanceCost = calculateMaintenance(state, claimManager, armyManager);
            //applies net to treasury
            double netProfit = taxRevenue - maintenanceCost;
            
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
    private static double calculateTax(StateData state) {
        //base income per tier (e.g. Empire yields more base tax than a County)
        double baseIncome = state.getCurrentTier().ordinal() * 50.0; 
        
        //income scaled strictly by population (e.g., 0.5 emeralds/coins per citizen)
        double popIncome = state.getTotalPopulation() * 0.5; 
        
        return baseIncome + popIncome;
    }

    private static double calculateMaintenance(StateData state, ClaimManager claimManager, ArmyManager armyManager) {
        //higher tiers cost more inherently to maintain
        double baseMaintenance = state.getCurrentTier().ordinal() * 75.0;

        //get land upkeep costs
        int ownedChunks = claimManager.getClaimCountForState(state.getStateId());
        double chunkCost = ownedChunks * 2.0; //2 emeralds per chunk per day

        //legion upkeep: 1.5 emeralds per soldier per day (750 per full 500-soldier legion) (fix this, shouldnt have decimals for emeralds)
        int totalSoldiers = armyManager.getLegionsForState(state.getStateId()).stream().mapToInt(Legion::getTotalSoldiers).sum();
        double legionCost = totalSoldiers * 1.5;

        return baseMaintenance + chunkCost + legionCost;
    }
}