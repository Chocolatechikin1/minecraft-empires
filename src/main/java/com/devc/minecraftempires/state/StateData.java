package com.devc.minecraftempires.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class StateData {
    private final UUID stateId;
    private String stateName;
    private UUID leaderId;
    private StateTier currentTier;

    //settlement tracking
    private final List<UUID> ownedSettlements;
    
    //economy variables
    private long treasuryBalance;
    private int totalPopulation;
    private long graceTicksRemaining = StateBalance.INTRODUCTORY_TICKS;
    private int unpaidDays;
    private boolean endgameChoice;
    private UUID capitalSettlementId;

    //state war data
    private int siegeImmunityTicks; //variable to track siege immunity timer (1 day)
    private int campaignMomentumTicks;
    private int settlementsCapturedThisCampaign;

    public StateData(UUID stateId, String stateName, UUID leaderId, StateTier startingTier) {
        this.stateId = stateId;
        this.stateName = StateManager.cleanName(stateName);
        this.leaderId = leaderId;
        this.currentTier = startingTier;
        this.ownedSettlements = new ArrayList<>();
        this.treasuryBalance = 0L;
        this.totalPopulation = 0;
        this.siegeImmunityTicks = 0;
        this.campaignMomentumTicks = 0;
        this.settlementsCapturedThisCampaign = 0;
    }

    //getters and setters (yes ai wrote them im too lazy to write them)
    public UUID getStateId() { return stateId; }
    public String getStateName() { return stateName; }
    public void setStateName(String name) { this.stateName = StateManager.cleanName(name); }
    
    public UUID getLeaderId() { return leaderId; }
    public void setLeaderId(UUID leaderId) { this.leaderId = leaderId; }

    public StateTier getCurrentTier() { return currentTier; }
    public void setCurrentTier(StateTier tier) { this.currentTier = tier; }

    public List<UUID> getOwnedSettlements() { return ownedSettlements; }
    public void addSettlement(UUID settlementId) { 
        if (!ownedSettlements.contains(settlementId)) {
            ownedSettlements.add(settlementId); 
        }
    }
    public void removeSettlement(UUID settlementId) { ownedSettlements.remove(settlementId); }

    //economy and tier methods
    public long getTreasuryBalance(){ 
        return treasuryBalance; 
    }
    
    //add emeralds
    public void addFunds(long amount){ 
        if (amount > 0) this.treasuryBalance = amount > Long.MAX_VALUE - treasuryBalance ? Long.MAX_VALUE : treasuryBalance + amount;
    }
    
    //deduct emeralds and ensure it doesn't go below 0
    public void deductFunds(long amount){ 
        if (amount > 0) this.treasuryBalance = Math.max(0L, this.treasuryBalance - amount);
    }

    //get population
    public int getTotalPopulation(){ 
        return totalPopulation; 
    }

    //set population
    public void setTotalPopulation(int population){
        this.totalPopulation = Math.max(0, population);
    }

    public long getGraceTicksRemaining() { return graceTicksRemaining; }
    public void setGraceTicksRemaining(long ticks) { graceTicksRemaining = Math.clamp(ticks, 0L, StateBalance.INTRODUCTORY_TICKS); }
    public int getUnpaidDays() { return unpaidDays; }
    public void setUnpaidDays(int days) { unpaidDays = Math.max(0, days); }
    public boolean hasEndgameChoice() { return endgameChoice; }
    public void setEndgameChoice(boolean available) { endgameChoice = available; }
    public UUID getCapitalSettlementId() { return capitalSettlementId; }
    public void setCapitalSettlementId(UUID id) { capitalSettlementId = id; }

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

    //campaign momentum methods
    public int getCampaignMomentumTicks() { return campaignMomentumTicks; }
    public void addCampaignMomentum(int ticks) { this.campaignMomentumTicks += ticks; }
    public void tickCampaignMomentum() {
        if (this.campaignMomentumTicks > 0) {
            this.campaignMomentumTicks--;
            if (this.campaignMomentumTicks == 0) {
                // Momentum ran out, reset capture count
                this.settlementsCapturedThisCampaign = 0;
            }
        }
    }

    //settlement capture tracking
    public int getSettlementsCaptured() { return settlementsCapturedThisCampaign; }
    public void incrementSettlementsCaptured() { this.settlementsCapturedThisCampaign++; }
    public void resetCampaignData() {
        this.campaignMomentumTicks = 0;
        this.settlementsCapturedThisCampaign = 0;
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
        tag.putLong("Treasury", treasuryBalance);
        tag.putInt("Population", totalPopulation);
        tag.putLong("GraceTicks", graceTicksRemaining);
        tag.putInt("UnpaidDays", unpaidDays);
        tag.putBoolean("EndgameChoice", endgameChoice);
        if (capitalSettlementId != null) tag.putString("CapitalSettlement", capitalSettlementId.toString());
        tag.putInt("SiegeImmunity", siegeImmunityTicks);
        tag.putInt("CampaignMomentum", campaignMomentumTicks);
        tag.putInt("CampaignCaptures", settlementsCapturedThisCampaign);
        ListTag settlementList = new ListTag();
        for (UUID id : ownedSettlements) {
            settlementList.add(StringTag.valueOf(id.toString()));
        }
        tag.put("OwnedSettlements", settlementList);
        return tag;
    }

    public static StateData fromNBT(CompoundTag tag) {
        UUID sId = UUID.fromString(tag.getString("StateId").orElseThrow());
        String sName = tag.getString("StateName").orElse("");

        UUID lId = null;
        String leaderId = tag.getString("LeaderId").orElse("");
        if (!leaderId.isEmpty()) {
            try { lId = UUID.fromString(leaderId); } catch (IllegalArgumentException ignored) { /* Keep the state even if its optional leader reference is damaged. */ }
        }

        String tierName = tag.getString("StateTier").orElse(StateTier.COUNTY.name());
        StateTier tier;
        try { tier = StateTier.valueOf(tierName); } catch (IllegalArgumentException ignored) { tier = StateTier.COUNTY; }
        
        StateData state = new StateData(sId, sName, lId, tier);
        state.treasuryBalance = Math.max(0, tag.getLong("Treasury").orElse(0L));
        state.setGraceTicksRemaining(tag.getLong("GraceTicks").orElse(StateBalance.INTRODUCTORY_TICKS));
        state.unpaidDays = Math.max(0, tag.getInt("UnpaidDays").orElse(0));
        state.endgameChoice = tag.getBoolean("EndgameChoice").orElse(false);
        tag.getString("CapitalSettlement").ifPresent(id -> { try { state.capitalSettlementId = UUID.fromString(id); } catch (IllegalArgumentException ignored) {} });
        state.totalPopulation = Math.max(0, tag.getInt("Population").orElse(0));
        state.siegeImmunityTicks = Math.max(0, tag.getInt("SiegeImmunity").orElse(0));
        state.campaignMomentumTicks = Math.max(0, tag.getInt("CampaignMomentum").orElse(0));
        state.settlementsCapturedThisCampaign = Math.max(0, tag.getInt("CampaignCaptures").orElse(0));
        
        ListTag settlementList = tag.getList("OwnedSettlements").orElse(new ListTag());
        for (int i = 0; i < settlementList.size(); i++) {
            try { state.ownedSettlements.add(UUID.fromString(settlementList.getString(i).orElseThrow())); }
            catch (RuntimeException malformedReference) { /* Rebuilt from the settlement registry after loading. */ }
        }
        
        return state;
    }
}
