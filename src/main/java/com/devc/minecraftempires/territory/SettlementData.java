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
    /** Reserved affiliation for independent abstract sites; this is never a registered nation. */
    public static final UUID INDEPENDENT_OWNER = new UUID(0, 0);
    public enum Kind {
        VILLAGE("Village"), TOWN("Town"), CITY("City"), OUTPOST("Outpost / castle");
        private final String label;
        Kind(String label) { this.label = label; }
        public String label() { return label; }
    }
    private Kind kind = Kind.TOWN;
    private final UUID settlementId; //settlement unique identifier
    private UUID owningStateId; //changes when the settlement is captured
    private String settlementName; //settlement name
    private BlockPos centerAltarPos; //center altar
    
    //settlement data
    private int settlementTier; //tier values
    private int localPopulation; //population
    private int garrisonCapacity; //garrison count
    private int protectiveRadius; //base radius for claim protection
    //siege protection variable
    private int localSiegeImmunityTicks;
    // Preparation pauses with the owning state's supply clock, unlike capture immunity.
    private long supplyPreparationTicks;
    // An abstract AI settlement can exist before its strategic center is physically surveyed.
    private boolean abstractSettlement;
    private boolean centerVerified = true; // Existing player settlements retain their established centers.
    private long strategicRegion;
    private UUID subdivisionId;

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
        this.settlementName = com.devc.minecraftempires.state.StateManager.cleanName(settlementName);
        this.centerAltarPos = centerAltarPos;
        
        //settlement initialization defaults
        this.settlementTier = 1;
        this.localPopulation = com.devc.minecraftempires.state.StateBalance.STARTING_POPULATION;
        this.garrisonCapacity = 50; 
        this.protectiveRadius = com.devc.minecraftempires.state.StateBalance.SETTLEMENT_RADIUS; // New settlements use 64 blocks; older saves retain their former protection.
        this.localSiegeImmunityTicks = 0; //no immunity by default
        this.biomeTallies = new HashMap<>(); //hash map for quick biome tallying
        this.guardTowerNodes = new ArrayList<>(); //arraylist for easy addition/removal of guard tower nodes
    }

    //getters and setters
    public UUID getSettlementId() { return settlementId; }
    public UUID getOwningStateId() { return owningStateId; }
    public boolean isIndependent() { return INDEPENDENT_OWNER.equals(owningStateId); }
    public Kind getKind() { return kind; }
    /** Generation metadata shares the ordinary settlement record and city progression rules. */
    public void setGeneratedKind(Kind value) {
        if (!abstractSettlement) throw new IllegalStateException("Generated kinds require an abstract site");
        kind = java.util.Objects.requireNonNull(value);
        settlementTier = value == Kind.CITY ? 2 : 1;
        garrisonCapacity = value == Kind.CITY ? 150 : 50;
    }
    public void setOwningStateId(UUID id) { owningStateId = id; }
    public String getSettlementName() { return settlementName; }
    public void setSettlementName(String name) { this.settlementName = com.devc.minecraftempires.state.StateManager.cleanName(name); }
    public BlockPos getCenterAltarPos() { return centerAltarPos; }
    public boolean isAbstractSettlement() { return abstractSettlement; }
    public boolean isCenterVerified() { return centerVerified; }
    public long getStrategicRegion() { return strategicRegion; }
    public UUID getSubdivisionId() { return subdivisionId; }
    /** This is affiliation metadata, not a governor/loyalty simulation or a verified terrain result. */
    public void initializeAbstract(long region, UUID subdivision) {
        abstractSettlement = true; centerVerified = false; strategicRegion = region; subdivisionId = subdivision;
    }
    /** Only the server's terrain validation path may commit a verified operational center. */
    public void acceptVerifiedCenter(BlockPos position) {
        if (!abstractSettlement) throw new IllegalStateException("Player settlement centers cannot be relocated by AI");
        centerAltarPos = position.immutable(); centerVerified = true;
    }
    
    public int getSettlementTier() { return settlementTier; }
    public int getLocalPopulation() { return localPopulation; } // PHASE 3
    public void setLocalPopulation(int population) { localPopulation = Math.max(0, population); }
    public int getGarrisonCapacity() { return garrisonCapacity; } // PHASE 3
    public int getProtectiveRadius() { return protectiveRadius; }
    public int getLocalSiegeImmunityTicks() { return localSiegeImmunityTicks; }
    public void setLocalSiegeImmunityTicks(int ticks) { this.localSiegeImmunityTicks = Math.max(0, ticks); }
    public void tickLocalImmunity() {
        if (this.localSiegeImmunityTicks > 0) {
            this.localSiegeImmunityTicks--;
        }
    }
    public boolean isLocallyImmune() { return this.localSiegeImmunityTicks > 0; }

    public long getSupplyPreparationTicks() { return supplyPreparationTicks; }
    public void setSupplyPreparationTicks(long ticks) { supplyPreparationTicks = Math.max(0L, ticks); }
    public void advanceSupplyPreparationTicks(long elapsedTicks) {
        supplyPreparationTicks = Math.max(0L, supplyPreparationTicks - Math.max(0L, elapsedTicks));
    }
    public boolean isSupplyReady() { return supplyPreparationTicks == 0; }

    // Martial Law stub (loyalty system pending)
    public boolean isMartialLaw()                  { return isMartialLaw; }
    public void setMartialLaw(boolean martialLaw)  { this.isMartialLaw = martialLaw; }

    //progression logic
    public void upgradeSettlement() {
        if (settlementTier >= com.devc.minecraftempires.state.StateBalance.MAXIMUM_SETTLEMENT_TIER) return;
        this.settlementTier++;
        this.kind = Kind.CITY;
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
        tag.putString("Kind", kind.name());
        tag.putInt("LocalPopulation", localPopulation);
        tag.putInt("GarrisonCap", garrisonCapacity);
        tag.putInt("Radius", protectiveRadius);
        tag.putInt("FootprintVersion", 1);
        tag.putInt("LocalImmunity", localSiegeImmunityTicks);
        tag.putLong("SupplyPreparationTicks", supplyPreparationTicks);
        tag.putBoolean("MartialLaw", isMartialLaw);
        tag.putBoolean("AbstractSettlement", abstractSettlement);
        tag.putBoolean("CenterVerified", centerVerified);
        tag.putLong("StrategicRegion", strategicRegion);
        if (subdivisionId != null) tag.putString("Subdivision", subdivisionId.toString());

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
        
        //create a new SettlementData instance with the loaded values
        SettlementData settlement = new SettlementData(sId, stateId, name, altarPos);
        settlement.settlementTier = Math.max(1, tag.getInt("Tier").orElse(1));
        try { settlement.kind = Kind.valueOf(tag.getString("Kind").orElse(settlement.settlementTier >= 2 ? "CITY" : "TOWN")); }
        catch (IllegalArgumentException ignored) { settlement.kind = settlement.settlementTier >= 2 ? Kind.CITY : Kind.TOWN; }
        if (settlement.settlementTier >= 2) settlement.kind = Kind.CITY;
        settlement.localPopulation = Math.max(0, tag.getInt("LocalPopulation").orElse(com.devc.minecraftempires.state.StateBalance.STARTING_POPULATION));
        settlement.garrisonCapacity = Math.max(0, tag.getInt("GarrisonCap").orElse(50));
        settlement.localSiegeImmunityTicks = Math.max(0, tag.getInt("LocalImmunity").orElse(0));
        // Earlier loaders ignored Radius, so an old small value did not describe its
        // actual protection. Preserve the effective 100 blocks until this version saves it.
        settlement.protectiveRadius = tag.getInt("FootprintVersion").orElse(0) >= 1
                ? Math.max(1, tag.getInt("Radius").orElse(com.devc.minecraftempires.state.StateBalance.LEGACY_SETTLEMENT_RADIUS))
                : com.devc.minecraftempires.state.StateBalance.LEGACY_SETTLEMENT_RADIUS;
        settlement.supplyPreparationTicks = Math.max(0L, tag.getLong("SupplyPreparationTicks").orElse(0L));
        settlement.isMartialLaw = tag.getBoolean("MartialLaw").orElse(false);
        settlement.abstractSettlement = tag.getBoolean("AbstractSettlement").orElse(false);
        settlement.centerVerified = !settlement.abstractSettlement || tag.getBoolean("CenterVerified").orElse(false);
        settlement.strategicRegion = tag.getLong("StrategicRegion").orElse(0L);
        tag.getString("Subdivision").ifPresent(id -> {
            try { settlement.subdivisionId = UUID.fromString(id); } catch (IllegalArgumentException ignored) { /* Optional future administrative affiliation. */ }
        });

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
