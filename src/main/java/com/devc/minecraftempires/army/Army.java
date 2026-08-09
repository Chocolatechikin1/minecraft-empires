package com.devc.minecraftempires.army;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

import java.util.*;

//army class, the primary military force in game, required to create one to campaign
public class Army {
    public static final int MAX_INFANTRY_COHORTS  = 60; //max legions: 6
    public static final int MAX_CAVALRY_SQUADRONS = 30; //max calvary wings: 6
    public static final int MIN_COHORTS = 1; //min cohorts: 1
    //min squadrons: 1

    private final UUID armyId;
    private final UUID owningStateId;
    private final List<UUID> deployedCohortIds = new ArrayList<>(); //cohort IDs of all deployed cohorts (including garrisoned ones)

    private UUID campaignId = null; //UUID of the Campaign this Army belongs to; null if not on a campaign
    private BlockPos storedPosition;
    private BlockPos campPosition = null;
    private UUID currentBattleId = null;
    private final Queue<BlockPos> waypoints = new LinkedList<>();

    public Army(UUID armyId, UUID owningStateId, BlockPos storedPosition) {
        this.armyId         = armyId;
        this.owningStateId  = owningStateId;
        this.storedPosition = storedPosition;
    }

   //add cohort to army function
    public void addCohortId(UUID cohortId) {
        if (!deployedCohortIds.contains(cohortId)) {
            deployedCohortIds.add(cohortId);
        }
    }

    //remove cohort from army function
    public void removeCohortId(UUID cohortId) {
        deployedCohortIds.remove(cohortId);
    }

    //getter
    public List<UUID> getDeployedCohortIds() {
        return Collections.unmodifiableList(deployedCohortIds);
    }

    //check if the cohort can be added
    public boolean canAddCohort(Cohort cohort, ArmyManager manager) {
        if (cohort.getType() == CohortType.CAVALRY) { //if it's cavalry, check cavalry cap
            long current = deployedCohortIds.stream().map(manager::resolveCohort).filter(Objects::nonNull).filter(c -> c.getType() == CohortType.CAVALRY).count();
            return current < MAX_CAVALRY_SQUADRONS;
        } 
        else{ //if it's infantry or auxiliary, check infantry cap
            // INFANTRY and AUXILIARY share the infantry cap
            long current = deployedCohortIds.stream().map(manager::resolveCohort).filter(Objects::nonNull).filter(c -> c.getType() != CohortType.CAVALRY).count();
            return current < MAX_INFANTRY_COHORTS;
        }
    }

    //get total strength of the army 
    public int getTotalStrength(ArmyManager manager){
        int total = 0;
        for (UUID id : deployedCohortIds){
            Cohort c = manager.resolveCohort(id);
            if (c != null && c.isAlive()) total += c.getSoldierCount();
        }
        return total;
    }

    //get morale 
    public int getBattleMorale(ArmyManager manager) {
        List<Integer> morales = new ArrayList<>();
        for (UUID id : deployedCohortIds) { //only counts cohorts that are alive and not garrisoned
            Cohort c = manager.resolveCohort(id);
            if (c != null && c.isAlive() && !c.isGarrisoned()) morales.add(c.getMorale());
        }
        return morales.isEmpty() ? 0 : (int) morales.stream().mapToInt(Integer::intValue).average().orElse(0);
    }

    //army visibility check, if it has at least one alive and non-garrisoned cohort, it is viable
    public boolean isViable(ArmyManager manager) {
        for (UUID id : deployedCohortIds) {
            Cohort c = manager.resolveCohort(id);
            if (c != null && c.isAlive() && !c.isGarrisoned()) return true;
        }
        return false;
    }

    //getters and setters
    public boolean isEngaged()                      { return currentBattleId != null; }
    public boolean isOnCampaign()                   { return campaignId != null; }

    public UUID getArmyId()                         { return armyId; }
    public UUID getOwningStateId()                  { return owningStateId; }

    public BlockPos getStoredPosition()             { return storedPosition; }
    public void setStoredPosition(BlockPos pos)     { this.storedPosition = pos; }

    public BlockPos getCampPosition()               { return campPosition; }
    public void setCampPosition(BlockPos pos)       { this.campPosition = pos; }

    public UUID getCurrentBattleId()                { return currentBattleId; }
    public void setCurrentBattleId(UUID id)         { this.currentBattleId = id; }

    public UUID getCampaignId()                     { return campaignId; }
    public void setCampaignId(UUID id)              { this.campaignId = id; }

    public Queue<BlockPos> getWaypoints()           { return waypoints; }
    public void addWaypoint(BlockPos pos)           { waypoints.offer(pos); }
    public void clearWaypoints()                    { waypoints.clear(); }

    //serializer
    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putString("ArmyId",        armyId.toString());
        tag.putString("OwningStateId", owningStateId.toString());
        tag.putLong("StoredPos",       storedPosition.asLong());

        if (campPosition != null)   tag.putLong("CampPos",     campPosition.asLong());
        if (currentBattleId != null) tag.putString("BattleId", currentBattleId.toString());
        if (campaignId != null)      tag.putString("CampaignId", campaignId.toString());

        // Deployed cohort ID list
        ListTag cohortList = new ListTag();
        for (UUID id : deployedCohortIds) {
            cohortList.add(StringTag.valueOf(id.toString()));
        }
        tag.put("DeployedCohorts", cohortList);

        // Waypoints
        ListTag wpList = new ListTag();
        for (BlockPos wp : waypoints) {
            CompoundTag wpTag = new CompoundTag();
            wpTag.putLong("Pos", wp.asLong());
            wpList.add(wpTag);
        }
        tag.put("Waypoints", wpList);

        return tag;
    }

    public static Army fromNBT(CompoundTag tag) {
        UUID     armyId    = UUID.fromString(tag.getString("ArmyId").orElseThrow());
        UUID     stateId   = UUID.fromString(tag.getString("OwningStateId").orElseThrow());
        BlockPos pos       = BlockPos.of(tag.getLong("StoredPos").orElse(BlockPos.ZERO.asLong()));

        Army army = new Army(armyId, stateId, pos);

        tag.getLong("CampPos").ifPresent(l -> army.campPosition = BlockPos.of(l));
        tag.getString("BattleId").ifPresent(s -> { if (!s.isEmpty()) army.currentBattleId = UUID.fromString(s); });
        tag.getString("CampaignId").ifPresent(s -> { if (!s.isEmpty()) army.campaignId = UUID.fromString(s); });

        tag.getList("DeployedCohorts").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) {
                list.getString(i).ifPresent(s -> {
                    if (!s.isEmpty()) army.deployedCohortIds.add(UUID.fromString(s));
                });
            }
        });

        tag.getList("Waypoints").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) {
                list.getCompound(i).ifPresent(wpTag ->
                        wpTag.getLong("Pos").ifPresent(l -> army.waypoints.offer(BlockPos.of(l))));
            }
        });

        return army;
    }
}
