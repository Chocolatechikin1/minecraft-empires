package com.devc.minecraftempires.army;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.territory.ClaimManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.ChunkPos;

import java.util.*;

//good description ill keep
/**
 * Tracks a multi-battle military operation for one or more Armies.
 *
 * A Campaign is created automatically when an Army is dispatched to
 * non-friendly territory, or when a battle is triggered against an
 * invading enemy. It persists through multiple battles and sieges until
 * the player manually ends it — subject to an end-condition check.
 *
 * End conditions (both must pass):
 *  1. The player clicks "End Campaign" in the Army management screen.
 *  2. No enemy Army is within THREAT_RANGE_BLOCKS of any chunk conquered
 *     during this campaign.
 *
 * On disband:
 *  - Non-garrisoned cohorts: assignedArmyId cleared (returned to Legion pool).
 *  - Garrisoned cohorts: isGarrisoned stays true, garrisonedSettlementId kept.
 *  - Army objects are removed from ArmyManager.
 */
public class Campaign {
    public static final int THREAT_RANGE_BLOCKS = 150;

    private final UUID campaignId;
    private final UUID owningStateId;

    private final Set<UUID> armyIds = new HashSet<>(); //hash set of all army IDs in this campaign, used for O(1) membership checks when checking for enemy armies in checkEndConditions()
    private final Set<Long> conqueredChunkPositions = new HashSet<>(); //hash set of all chunks conquered in the campaign, gives O(1) search time when checking for enemy armies in checkEndConditions()
    private boolean isActive = true;

    public Campaign(UUID campaignId, UUID owningStateId) {
        this.campaignId     = campaignId;
        this.owningStateId  = owningStateId;
    }

    public void addArmy(UUID armyId)    { armyIds.add(armyId); }
    public void removeArmy(UUID armyId) { armyIds.remove(armyId); }
    public Set<UUID> getArmyIds()       { return Collections.unmodifiableSet(armyIds); }
    public boolean isEmpty()            { return armyIds.isEmpty(); }

    //chunk tracking
    public void trackConqueredChunk(long packedChunkPos) {
        conqueredChunkPositions.add(packedChunkPos);
    }

    //getter
    public Set<Long> getConqueredChunkPositions() {
        return Collections.unmodifiableSet(conqueredChunkPositions);
    }

    //campaign end conditions check, checks if there are any enemy armies within 150 blocks of any conquered chunk
    public boolean checkEndConditions(ArmyManager armyManager) {
        if (conqueredChunkPositions.isEmpty()) return true;
        Collection<Army> allArmies = armyManager.getAllArmies();
        for (Long packedPos : conqueredChunkPositions) {
            ChunkPos chunkPos = ChunkPos.unpack(packedPos);
            //use block center of the chunk for distance math
            BlockPos chunkCentre = new BlockPos(chunkPos.x() * 16 + 8, 64, chunkPos.z() * 16 + 8);
            for (Army enemy : allArmies) {
                //skip friendly armies
                if (enemy.getOwningStateId().equals(owningStateId)) continue;
                //skip armies on the same campaign
                if (armyIds.contains(enemy.getArmyId())) continue;
                if (chunkCentre.distSqr(enemy.getStoredPosition()) <= (long) THREAT_RANGE_BLOCKS * THREAT_RANGE_BLOCKS) {
                    MinecraftEmpires.LOGGER.debug("Cannot end campaign, enemy Army {} is {} blocks from conquered chunk ({}, {}).",enemy.getArmyId(), chunkCentre.distManhattan(enemy.getStoredPosition()),chunkPos.x(), chunkPos.z());
                    return false;
                }
            }
        }
        return true;
    }

    //disbands all armies, releases non-garrisoned cohorts back to their Legion pools, and sets isActive to false
    //garrisoned cohorts keep garrisoning their settlements
    public void disbandCampaign(ArmyManager armyManager) {
        for (UUID armyId : new HashSet<>(armyIds)) {
            armyManager.disbandArmy(armyId, /*forceDisband=*/true);
        }
        isActive = false;
        MinecraftEmpires.LOGGER.info("Campaign {} ended.", campaignId);
    }

    //getters
    public UUID getCampaignId()    { return campaignId; }
    public UUID getOwningStateId() { return owningStateId; }
    public boolean isActive()      { return isActive; }
    public void setInactive()      { isActive = false; }

    //serializers
    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putString("CampaignId",    campaignId.toString());
        tag.putString("OwningStateId", owningStateId.toString());
        tag.putBoolean("IsActive",     isActive);

        ListTag armyList = new ListTag();
        for (UUID id : armyIds) armyList.add(StringTag.valueOf(id.toString()));
        tag.put("Armies", armyList);

        ListTag chunkList = new ListTag();
        for (Long pos : conqueredChunkPositions) {
            CompoundTag ct = new CompoundTag();
            ct.putLong("Pos", pos);
            chunkList.add(ct);
        }
        tag.put("ConqueredChunks", chunkList);

        return tag;
    }

    public static Campaign fromNBT(CompoundTag tag) {
        UUID cid    = UUID.fromString(tag.getString("CampaignId").orElseThrow());
        UUID sid    = UUID.fromString(tag.getString("OwningStateId").orElseThrow());
        Campaign c  = new Campaign(cid, sid);
        c.isActive  = tag.getBoolean("IsActive").orElse(true);

        tag.getList("Armies").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) {
                list.getString(i).ifPresent(s -> { if (!s.isEmpty()) c.armyIds.add(UUID.fromString(s)); });
            }
        });

        tag.getList("ConqueredChunks").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) {
                list.getCompound(i).ifPresent(ct -> ct.getLong("Pos").ifPresent(c.conqueredChunkPositions::add));
            }
        });

        return c;
    }
}
