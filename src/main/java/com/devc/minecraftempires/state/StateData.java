package com.devc.minecraftempires.state;

import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

public class StateData {
    private final UUID stateId;
    private String stateName;
    private UUID leaderId;
    private StateTier currentTier;
    
    //economy variables
    private double treasuryBalance;
    private int totalPopulation;
    private int siegeImmunityTicks; //variable to track siege immunity timer (1 day)

    public StateData(UUID stateId, String stateName, UUID leaderId, StateTier startingTier) {
        this.stateId = stateId;
        this.stateName = stateName;
        this.leaderId = leaderId;
        this.currentTier = startingTier;
        this.treasuryBalance = 0.0;
        this.totalPopulation = 0;
        this.siegeImmunityTicks = 0;
    }

    //getters and setters (yes ai wrote them im too lazy to write them)
    public UUID getStateId() { return stateId; }
    public String getStateName() { return stateName; }
    public void setStateName(String name) { this.stateName = name; }
    
    public UUID getLeaderId() { return leaderId; }
    public void setLeaderId(UUID leaderId) { this.leaderId = leaderId; }

    public StateTier getCurrentTier() { return currentTier; }
    public void setCurrentTier(StateTier tier) { this.currentTier = tier; }

    //economy and tier methods
    public double getTreasuryBalance(){ 
        return treasuryBalance; 
    }
    
    //add emeralds
    public void addFunds(double amount){ 
        this.treasuryBalance += amount; 
    }
    
    //deduct emeralds and ensure it doesn't go below 0
    public void deductFunds(double amount){ 
        this.treasuryBalance = Math.max(0, this.treasuryBalance - amount); 
    }

    //get population
    public int getTotalPopulation(){ 
        return totalPopulation; 
    }

    //set population
    public void setTotalPopulation(int population){
        this.totalPopulation = Math.max(0, population); 
    }

    //get siege immunity duration left
    public int getSiegeImmunityTicks(){ 
        return siegeImmunityTicks; 
    }
    
    //initialize siege immunity duration (in ticks)
    public void setSiegeImmunityTicks(int ticks){ 
        this.siegeImmunityTicks = Math.max(0, ticks); 
    }

    //change siege immmunity ticks
    public void setSiegeImmunity(int ticks){
        setSiegeImmunityTicks(ticks); 
    }
    
    //reduce siege immunty ticks by 1 (called every tick)
    public void tickSiegeImmunity(){
        if (this.siegeImmunityTicks > 0) {
            this.siegeImmunityTicks--;
        }
    }

    //serialization (Saving/Loading to Disk)
    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putString("StateId", stateId.toString());
        tag.putString("StateName", stateName);
        if (leaderId != null) {
            tag.putString("LeaderId", leaderId.toString());
        }
        tag.putString("StateTier", currentTier.name());
        tag.putDouble("Treasury", treasuryBalance);
        tag.putInt("Population", totalPopulation);
        tag.putInt("SiegeImmunity", siegeImmunityTicks);
        return tag;
    }

    public static StateData fromNBT(CompoundTag tag) {
        UUID sId = UUID.fromString(tag.getString("StateId").orElseThrow());
        String sName = tag.getString("StateName").orElse("");

        UUID lId = null;
        String leaderId = tag.getString("LeaderId").orElse("");
        if (!leaderId.isEmpty()) {
            lId = UUID.fromString(leaderId);
        }

        String tierName = tag.getString("StateTier").orElse(StateTier.NOMADIC.name());
        StateTier tier = StateTier.valueOf(tierName);
        
        StateData state = new StateData(sId, sName, lId, tier);
        state.treasuryBalance = tag.getDouble("Treasury").orElse(0.0);
        state.totalPopulation = tag.getInt("Population").orElse(0);
        state.siegeImmunityTicks = tag.getInt("SiegeImmunity").orElse(0);
        
        return state;
    }
}