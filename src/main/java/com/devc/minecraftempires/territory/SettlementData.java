package com.devc.minecraftempires.territory;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

public class SettlementData {
    private final UUID settlementId; //settlement unique identifier
    private final UUID owningStateId; //links settlements to a state
    private String settlementName; //settlement name
    private BlockPos centerAltarPos; //center altar
    
    //settlement data
    private int settlementTier; //tier values
    private int localPopulation; //population
    private int garrisonCapacity; //garrison count
    private int protectiveRadius; //base radius for claim protection
    //siege protection variable
    private int localSiegeImmunityTicks;    

    public SettlementData(UUID settlementId, UUID owningStateId, String settlementName, BlockPos centerAltarPos) {
        this.settlementId = settlementId;
        this.owningStateId = owningStateId;
        this.settlementName = settlementName;
        this.centerAltarPos = centerAltarPos;
        
        //settlement initialization defaults
        this.settlementTier = 1;
        this.localPopulation = 10;
        this.garrisonCapacity = 50; 
        this.protectiveRadius = 100; //gives a protective area of 156 chunks
        this.localSiegeImmunityTicks = 0; //no immunity by default
    }

    // --- Getters & Setters ---
    public UUID getSettlementId() { return settlementId; }
    public UUID getOwningStateId() { return owningStateId; }
    public String getSettlementName() { return settlementName; }
    public void setSettlementName(String name) { this.settlementName = name; }
    public BlockPos getCenterAltarPos() { return centerAltarPos; }
    
    public int getSettlementTier() { return settlementTier; }
    public int getProtectiveRadius() { return protectiveRadius; }
    public int getLocalSiegeImmunityTicks() { return localSiegeImmunityTicks; }
    public void setLocalSiegeImmunityTicks(int ticks) { this.localSiegeImmunityTicks = ticks; }
    public void tickLocalImmunity() {
        if (this.localSiegeImmunityTicks > 0) {
            this.localSiegeImmunityTicks--;
        }
    }
    public boolean isLocallyImmune() { return this.localSiegeImmunityTicks > 0; }

    //progression logic
    public void upgradeSettlement() {
        this.settlementTier++;
        //example scaling math - can be tweaked later
        this.protectiveRadius += 50; 
        this.garrisonCapacity += 100;
    }

    // --- NBT Serialization ---
    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putString("SettlementId", settlementId.toString());
        tag.putString("OwningStateId", owningStateId.toString());
        tag.putString("SettlementName", settlementName);
        tag.putLong("AltarPos", centerAltarPos.asLong());
        tag.putInt("Tier", settlementTier);
        tag.putInt("LocalPopulation", localPopulation);
        tag.putInt("GarrisonCap", garrisonCapacity);
        tag.putInt("Radius", protectiveRadius);
        tag.putInt("LocalImmunity", localSiegeImmunityTicks);
        return tag;
    }

    public static SettlementData fromNBT(CompoundTag tag) {
        UUID sId = UUID.fromString(tag.getString("SettlementId").orElseThrow());
        UUID stateId = UUID.fromString(tag.getString("OwningStateId").orElseThrow());
        String name = tag.getString("SettlementName").orElse("");
        
        BlockPos altarPos = BlockPos.of(tag.getLong("AltarPos").orElse(BlockPos.ZERO.asLong()));
        
        SettlementData settlement = new SettlementData(sId, stateId, name, altarPos);
        settlement.settlementTier = tag.getInt("Tier").orElse(1);
        settlement.localPopulation = tag.getInt("LocalPopulation").orElse(10);
        settlement.garrisonCapacity = tag.getInt("GarrisonCap").orElse(50);
        settlement.localSiegeImmunityTicks = tag.getInt("LocalImmunity").orElse(0);
        settlement.protectiveRadius = tag.getInt("Radius").orElse(100);
        
        return settlement;
    }
}