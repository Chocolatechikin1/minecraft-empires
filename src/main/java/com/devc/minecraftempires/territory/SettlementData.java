package com.devc.minecraftempires.territory;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** note to either add or remove this feature later
     * STUB — Martial Law flag set when an Army cohort garrisons this settlement during a campaign.
     * Intended to grant a loyalty buff preventing revolts while loyalty levels stabilise.
     * Loyalty system is not yet implemented; this flag has no game effect until then.
     */
    private boolean isMartialLaw = false;

    //biome tally system
    private final Map<String, Integer> biomeTallies;

    //guard tile node system
    private final List<BlockPos> guardTowerNodes;

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
        this.biomeTallies = new HashMap<>(); //hash map for quick biome tallying
        this.guardTowerNodes = new ArrayList<>(); //arraylist for easy addition/removal of guard tower nodes
    }

    //getters and setters
    public UUID getSettlementId() { return settlementId; }
    public UUID getOwningStateId() { return owningStateId; }
    public String getSettlementName() { return settlementName; }
    public void setSettlementName(String name) { this.settlementName = name; }
    public BlockPos getCenterAltarPos() { return centerAltarPos; }
    
    public int getSettlementTier() { return settlementTier; }
    public int getLocalPopulation() { return localPopulation; } // PHASE 3
    public int getGarrisonCapacity() { return garrisonCapacity; } // PHASE 3
    public int getProtectiveRadius() { return protectiveRadius; }
    public int getLocalSiegeImmunityTicks() { return localSiegeImmunityTicks; }
    public void setLocalSiegeImmunityTicks(int ticks) { this.localSiegeImmunityTicks = ticks; }
    public void tickLocalImmunity() {
        if (this.localSiegeImmunityTicks > 0) {
            this.localSiegeImmunityTicks--;
        }
    }
    public boolean isLocallyImmune() { return this.localSiegeImmunityTicks > 0; }

    // Martial Law stub (loyalty system pending)
    public boolean isMartialLaw()                  { return isMartialLaw; }
    public void setMartialLaw(boolean martialLaw)  { this.isMartialLaw = martialLaw; }

    //progression logic
    public void upgradeSettlement() {
        this.settlementTier++;
        //example scaling math - can be tweaked later
        this.protectiveRadius += 50; 
        this.garrisonCapacity += 100;
    }

    //counts biome tallies for the settlement
    public void incrementBiomeTally(String biomeKey) {
        if (biomeKey == null || biomeKey.isBlank()) return;
        biomeTallies.merge(biomeKey, 1, Integer::sum);
    }

    //gets tally count
    public int getBiomeTally(String biomeKey) {
        return biomeTallies.getOrDefault(biomeKey, 0);
    }

    //returns the biome with the highest tally
    public String getDominantBiome() {
        return biomeTallies.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    //shows a map of all biomes in the settlement
    public Map<String, Integer> getBiomeTallies() {
        return Collections.unmodifiableMap(biomeTallies);
    }

    //registers a guard tower node
    public void addGuardTowerNode(BlockPos pos) {
        if (!guardTowerNodes.contains(pos)) {
            guardTowerNodes.add(pos);
        }
    }

    //removes guard tower nodes
    public void removeGuardTowerNode(BlockPos pos) {
        guardTowerNodes.remove(pos);
    }

    //shows a list of all guard tower nodes
    public List<BlockPos> getGuardTowerNodes() {
        return Collections.unmodifiableList(guardTowerNodes);
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
        tag.putBoolean("MartialLaw", isMartialLaw);

        // Biome tallies
        CompoundTag biomesTag = new CompoundTag();
        biomeTallies.forEach(biomesTag::putInt);
        tag.put("BiomeTallies", biomesTag);

        // Guard tower nodes
        ListTag towerList = new ListTag();
        for (BlockPos node : guardTowerNodes) {
            CompoundTag nodeTag = new CompoundTag();
            nodeTag.putLong("NodePos", node.asLong());
            towerList.add(nodeTag);
        }
        tag.put("GuardTowerNodes", towerList);

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
        settlement.isMartialLaw     = tag.getBoolean("MartialLaw").orElse(false);

        // Load biome tallies
        if (tag.contains("BiomeTallies")) {
            CompoundTag biomesTag = tag.getCompound("BiomeTallies").orElse(new CompoundTag());
            for (String key : biomesTag.keySet()) {
                biomesTag.getInt(key).ifPresent(count -> settlement.biomeTallies.put(key, count));
            }
        }

        // Load guard tower nodes
        if (tag.contains("GuardTowerNodes")) {
            ListTag towerList = tag.getList("GuardTowerNodes").orElse(new ListTag());
            for (int i = 0; i < towerList.size(); i++) {
                towerList.getCompound(i).ifPresent(nodeTag ->
                    nodeTag.getLong("NodePos").ifPresent(pos ->
                        settlement.guardTowerNodes.add(BlockPos.of(pos))));
            }
        }

        return settlement;
    }
}