package com.devc.minecraftempires.state;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

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
        StateManager manager = StateManager.get(level);
        
        for (StateData state : manager.getAllStates()) {
            
            //calculates tax revenue
            double taxRevenue = calculateTax(state);
            //calculates maintenance costs
            double maintenanceCost = calculateMaintenance(state);
            //applies extra to treasury
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
        
        // IMPORTANT: Tell the server this data changed so it writes the new bank balances to disk!
        manager.setDirty();
    }

    //all math formulas for economy are here, can be adjusted later for balance
    private static double calculateTax(StateData state) {
        //base income per tier (e.g. Empire yields more base tax than a County)
        double baseIncome = state.getCurrentTier().ordinal() * 50.0; 
        
        //income scaled strictly by population (e.g., 0.5 emeralds/coins per citizen)
        double popIncome = state.getTotalPopulation() * 0.5; 
        
        return baseIncome + popIncome;
    }

    private static double calculateMaintenance(StateData state) {
        //higher tiers cost more inherently to maintain
        double baseMaintenance = state.getCurrentTier().ordinal() * 75.0;
        
        // TODO (Phase 3): Add cost of active legions here later
        return baseMaintenance;
    }
}