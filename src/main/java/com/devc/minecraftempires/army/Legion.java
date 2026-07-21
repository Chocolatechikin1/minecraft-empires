package com.devc.minecraftempires.army;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.LinkedList;
import java.util.Queue;

//legion class
public class Legion {
    public static final int MAX_INFANTRY_COHORTS  = 10;
    public static final int MAX_CAVALRY_SQUADRONS = 5;
    public static final int DAILY_UPKEEP_EMERALDS = 750;

    private final UUID legionId;
    private final UUID owningStateId;

    //variable to store the legion position
    private BlockPos storedPosition;

    //waypoint tracking
    private final Queue<BlockPos> waypoints = new LinkedList<>();

    private final List<Cohort> infantryCohorts;    // max MAX_INFANTRY_COHORTS
    private final List<Cohort> cavalrySquadrons;   // max MAX_CAVALRY_SQUADRONS
    private final List<Cohort> auxiliaries;        // unlimited (change later)

    //constructor
    public Legion(UUID legionId, UUID owningStateId, BlockPos storedPosition) {
        this.legionId       = legionId;
        this.owningStateId  = owningStateId;
        this.storedPosition = storedPosition;
        this.infantryCohorts  = new ArrayList<>();
        this.cavalrySquadrons = new ArrayList<>();
        this.auxiliaries      = new ArrayList<>();
    }

    //add cohort method
    public boolean addInfantryCohort(Cohort cohort) {
        if (cohort.getType() != CohortType.INFANTRY) return false;
        if (infantryCohorts.size() >= MAX_INFANTRY_COHORTS) return false;
        infantryCohorts.add(cohort);
        return true;
    }

    //add cavalry squadron method
    public boolean addCavalrySquadron(Cohort squadron) {
        if (squadron.getType() != CohortType.CAVALRY) return false;
        if (cavalrySquadrons.size() >= MAX_CAVALRY_SQUADRONS) return false;
        cavalrySquadrons.add(squadron);
        return true;
    }

    //method to add auxiliary cohort (controls auxillary count cap, change later)
    public boolean addAuxiliary(Cohort cohort) {
        if (cohort.getType() != CohortType.AUXILIARY) return false;
        auxiliaries.add(cohort);
        return true;
    }

    //get total count of all soldiers 
    public int getTotalStrength() {
        int total = 0;
        for (Cohort c : infantryCohorts)  total += c.getSoldierCount();
        for (Cohort c : cavalrySquadrons) total += c.getSoldierCount();
        for (Cohort c : auxiliaries)      total += c.getSoldierCount();
        return total;
    }

    //checks if legion meets requirement minimums
    public boolean isViable() {
        boolean hasInfantry = infantryCohorts.stream().anyMatch(Cohort::isAlive);
        boolean hasCavalry  = cavalrySquadrons.stream().anyMatch(Cohort::isAlive);
        return hasInfantry && hasCavalry;
    }

    /**
     * Called when an INFANTRY cohort's morale hits 0 and it begins routing.
     *
     * Finds the routing cohort's index in infantryCohorts, then applies a
     * morale panic debuff to the immediately adjacent cohorts (i-1, i+1).
     * The debuff amount is sourced from the routing cohort itself, allowing
     * future balancing without touching this method.
     *
     * CAVALRY and AUXILIARY cohorts are not part of the panic chain.
     *
     * @param routingCohort the cohort that just reached morale == 0
     */
    public void applyMoraleChainPanic(Cohort routingCohort) {
        int idx = infantryCohorts.indexOf(routingCohort);
        if (idx == -1) return; // routing cohort isn't in the infantry list

        int debuff = routingCohort.getMoralePanicDebuff();

        // Left neighbour
        if (idx - 1 >= 0) {
            infantryCohorts.get(idx - 1).tickMorale(-debuff);
        }

        // Right neighbour
        if (idx + 1 < infantryCohorts.size()) {
            infantryCohorts.get(idx + 1).tickMorale(-debuff);
        }
    }

    //getters
    public UUID getLegionId()                      { return legionId; }
    public UUID getOwningStateId()                 { return owningStateId; }
    public BlockPos getStoredPosition()            { return storedPosition; }
    public void setStoredPosition(BlockPos pos)    { this.storedPosition = pos; }

    public List<Cohort> getInfantryCohorts()       { return Collections.unmodifiableList(infantryCohorts); }
    public List<Cohort> getCavalrySquadrons()      { return Collections.unmodifiableList(cavalrySquadrons); }
    public List<Cohort> getAuxiliaries()           { return Collections.unmodifiableList(auxiliaries); }

    public int getInfantryCount()                  { return infantryCohorts.size(); }
    public int getCavalrySquadronCount()           { return cavalrySquadrons.size(); }

    //serialization methods
    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putString("LegionId",      legionId.toString());
        tag.putString("OwningStateId", owningStateId.toString());
        tag.putLong("StoredPos",       storedPosition.asLong());

        tag.put("InfantryCohorts",  serializeCohortList(infantryCohorts));
        tag.put("CavalrySquadrons", serializeCohortList(cavalrySquadrons));
        tag.put("Auxiliaries",      serializeCohortList(auxiliaries));

        return tag;
    }

    public static Legion fromNBT(CompoundTag tag) {
        UUID lid   = UUID.fromString(tag.getString("LegionId").orElseThrow());
        UUID sid   = UUID.fromString(tag.getString("OwningStateId").orElseThrow());
        long posL  = tag.getLong("StoredPos").orElse(BlockPos.ZERO.asLong());
        BlockPos pos = BlockPos.of(posL);

        Legion legion = new Legion(lid, sid, pos);

        tag.getList("InfantryCohorts").ifPresent(list ->
                deserializeCohortList(list, legion.infantryCohorts));
        tag.getList("CavalrySquadrons").ifPresent(list ->
                deserializeCohortList(list, legion.cavalrySquadrons));
        tag.getList("Auxiliaries").ifPresent(list ->
                deserializeCohortList(list, legion.auxiliaries));

        return legion;
    }

    private static ListTag serializeCohortList(List<Cohort> cohorts) {
        ListTag list = new ListTag();
        for (Cohort c : cohorts) {
            list.add(c.toNBT());
        }
        return list;
    }

    private static void deserializeCohortList(ListTag list, List<Cohort> target) {
        for (int i = 0; i < list.size(); i++) {
            list.getCompound(i).ifPresent(ct -> target.add(Cohort.fromNBT(ct)));
        }
    }

    //rts Waypoint Logic
    public Queue<BlockPos> getWaypoints() { 
        return this.waypoints; 
    } 

    public void addWaypoint(BlockPos pos) { 
        this.waypoints.offer(pos); 
    } 

    public void clearWaypoints() { 
        this.waypoints.clear(); 
    } 
}
