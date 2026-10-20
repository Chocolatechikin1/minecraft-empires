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

    /** Explicit control is saved; a missing/offline leader never turns an old player state into AI. */
    //variables for AI decision making
    public enum Control { HUMAN, AI }
    public enum Strategy { DEVELOP, DEFEND, PREPARE, OPERATE, RECOVER, SURRENDER }
    private Control control = Control.HUMAN;
    private Strategy strategy = Strategy.DEVELOP;
    private int generationVersion;
    private long generationSeed;
    private long nextDecisionTime;
    private long lastDecisionTime = -1;
    private long lastEconomyTime = -1;
    private long lastCapitalLossTime = -1;
    private UUID strategicTarget;
    private boolean surrenderOverflow;
    private boolean initializationPending;
    private long majorDefeatTime = -1, surrenderDeadline = -1;
    private UUID defeatOpponent;
    private int frontierCursor;

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

    //beginning of npc state methods
    /** Generated states share this model, but do not receive human introductory benefits. */
    public static StateData createAI(UUID id, String name, StateTier tier, long seed, int version) {
        if (version < 1) throw new IllegalArgumentException("AI generation version must be positive");
        StateData state = new StateData(id, name, null, tier);
        state.control = Control.AI;
        state.generationSeed = seed;
        state.generationVersion = version;
        state.graceTicksRemaining = 0;
        return state;
    }

    public boolean isAI() { return control == Control.AI; }
    public Control getControl() { return control; }
    public Strategy getStrategy() { return strategy; }
    public void setStrategy(Strategy value) { strategy = java.util.Objects.requireNonNull(value); }
    public int getGenerationVersion() { return generationVersion; }
    public long getGenerationSeed() { return generationSeed; }
    public long getNextDecisionTime() { return nextDecisionTime; }
    public long getLastDecisionTime() { return lastDecisionTime; }
    public long getLastEconomyTime() { return lastEconomyTime; }
    /** New nations start at activation; an existing saved date is never reset by a clock rewind. */
    public void initializeEconomyTime(long now) {
        if (now >= 0 && lastEconomyTime < 0) lastEconomyTime = now;
    }
    public boolean hasEconomicDayDue(long now) {
        return lastEconomyTime >= 0 && now >= lastEconomyTime && now - lastEconomyTime >= StateBalance.TICKS_PER_DAY;
    }
    public void recordEconomicDay(long now) {
        if (!hasEconomicDayDue(now)) throw new IllegalStateException("No completed financial day");
        lastEconomyTime += StateBalance.TICKS_PER_DAY;
    }
    public UUID getStrategicTarget() { return strategicTarget; }
    public void setStrategicTarget(UUID id) { strategicTarget = id; }
    /** Saved decision receipt and cooldown prevent replaying a strategic purchase after reload. */
    public void recordDecision(long now, long delay) {
        if (now < 0 || delay < 0) throw new IllegalArgumentException("Negative decision clock");
        lastDecisionTime = now;
        nextDecisionTime = delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay;
    }
    public long getLastCapitalLossTime() { return lastCapitalLossTime; }
    /** Records pressure for national surrender; provincial loyalty/defection remains future work. */
    public void recordCapitalLoss(long now) { lastCapitalLossTime = Math.max(lastCapitalLossTime, Math.max(0, now)); }
    public boolean hasSurrenderOverflow() { return surrenderOverflow; }
    public void setSurrenderOverflow(boolean value) { surrenderOverflow = value; }
    public boolean isInitializationPending() { return initializationPending; }
    public void setInitializationPending(boolean value) { initializationPending = isAI() && value; }
    public long getMajorDefeatTime() { return majorDefeatTime; }
    public UUID getDefeatOpponent() { return defeatOpponent; }
    public long getSurrenderDeadline() { return surrenderDeadline; }
    public int advanceFrontierCursor(int size, int budget) {
        int start = Math.floorMod(frontierCursor, Math.max(1, size));
        frontierCursor = (start + Math.min(Math.max(0, budget), size)) % Math.max(1, size);
        return start;
    }
    /** Only completed major outcomes trigger collapse assessment; peacetime army shortages do not. */
    public void recordMajorDefeat(long now, UUID opponent) {
        if (!isAI() || now < 0) return;
        majorDefeatTime = Math.max(majorDefeatTime, now); defeatOpponent = opponent; surrenderDeadline = -1;
    }
    public void offerSurrender(long now) {
        strategy = Strategy.SURRENDER;
        surrenderDeadline = now > Long.MAX_VALUE - StateBalance.TICKS_PER_DAY ? Long.MAX_VALUE : now + StateBalance.TICKS_PER_DAY;
    }
    public void clearDefeatAssessment() { majorDefeatTime = -1; defeatOpponent = null; surrenderDeadline = -1; }

    //END OF AI/NPC METHODS
    
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
    public void setGraceTicksRemaining(long ticks) { graceTicksRemaining = isAI() ? 0 : Math.clamp(ticks, 0L, StateBalance.INTRODUCTORY_TICKS); }
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
        tag.putString("Control", control.name());
        tag.putString("Strategy", strategy.name());
        tag.putInt("GenerationVersion", generationVersion);
        tag.putLong("GenerationSeed", generationSeed);
        tag.putLong("NextDecisionTime", nextDecisionTime);
        tag.putLong("LastDecisionTime", lastDecisionTime);
        tag.putLong("LastEconomyTime", lastEconomyTime);
        tag.putLong("LastCapitalLossTime", lastCapitalLossTime);
        tag.putBoolean("SurrenderOverflow", surrenderOverflow);
        tag.putBoolean("InitializationPending", initializationPending);
        tag.putLong("MajorDefeatTime", majorDefeatTime); tag.putLong("SurrenderDeadline", surrenderDeadline);
        tag.putInt("FrontierCursor", frontierCursor);
        if (defeatOpponent != null) tag.putString("DefeatOpponent", defeatOpponent.toString());
        if (strategicTarget != null) tag.putString("StrategicTarget", strategicTarget.toString());
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
        // Only an explicit known tag opts into AI. Damaged/legacy control values stay human.
        if (tag.getString("Control").orElse("").equals(Control.AI.name())) state.control = Control.AI;
        try { state.strategy = Strategy.valueOf(tag.getString("Strategy").orElse("DEVELOP")); }
        catch (IllegalArgumentException ignored) { state.strategy = Strategy.DEVELOP; }
        state.generationVersion = Math.max(0, tag.getInt("GenerationVersion").orElse(0));
        state.generationSeed = tag.getLong("GenerationSeed").orElse(0L);
        state.nextDecisionTime = Math.max(0, tag.getLong("NextDecisionTime").orElse(0L));
        state.lastDecisionTime = Math.max(-1, tag.getLong("LastDecisionTime").orElse(-1L));
        state.lastEconomyTime = Math.max(-1, tag.getLong("LastEconomyTime").orElse(-1L));
        state.lastCapitalLossTime = Math.max(-1, tag.getLong("LastCapitalLossTime").orElse(-1L));
        state.surrenderOverflow = tag.getBoolean("SurrenderOverflow").orElse(false);
        state.initializationPending = state.isAI() && tag.getBoolean("InitializationPending").orElse(false);
        state.majorDefeatTime = Math.max(-1, tag.getLong("MajorDefeatTime").orElse(-1L));
        state.surrenderDeadline = Math.max(-1, tag.getLong("SurrenderDeadline").orElse(-1L));
        state.frontierCursor = Math.max(0, tag.getInt("FrontierCursor").orElse(0));
        tag.getString("DefeatOpponent").ifPresent(id -> {
            try { state.defeatOpponent = UUID.fromString(id); } catch (IllegalArgumentException ignored) { /* Defeat still needs an assessment without a recipient. */ }
        });
        tag.getString("StrategicTarget").ifPresent(id -> {
            try { state.strategicTarget = UUID.fromString(id); } catch (IllegalArgumentException ignored) { /* Missing target can be replanned. */ }
        });
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
