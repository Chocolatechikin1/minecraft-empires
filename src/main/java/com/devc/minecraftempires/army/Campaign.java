package com.devc.minecraftempires.army;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.territory.ClaimManager;
import com.devc.minecraftempires.state.StateManager;
import net.minecraft.server.level.ServerLevel;
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
 * A Campaign is created automatically when an Army physically enters
 * non-friendly territory, or when a battle is triggered against an
 * invading enemy. It persists through multiple battles and sieges until
 * the player manually ends it — subject to an end-condition check.
 *
 * End conditions (both must pass):
 *  1. The player clicks "End Campaign" in the Army management screen.
 *  2. No enemy Army is within THREAT_RANGE_BLOCKS of any chunk conquered
 *     during this campaign.
 *
 * Ending a campaign clears only its status. Army wrappers must then be released separately.
 */
public class Campaign {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
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

    public boolean checkEndConditions(ArmyManager manager, ServerLevel level) {
        StateManager states = StateManager.get(level);
        List<BlockPos> protectedPositions = new ArrayList<>();
        for (UUID id : armyIds) {
            Army army = manager.getArmy(id);
            if (army != null) {
                if (army.isEngaged()) return false;
                if (army.isViable(manager)) protectedPositions.add(army.getStoredPosition());
            }
        }
        states.getAllSettlements().stream().filter(s -> owningStateId.equals(s.getOwningStateId()))
                .forEach(s -> protectedPositions.add(s.getCenterAltarPos()));
        List<BlockPos> threats = new ArrayList<>();
        manager.getAllArmies().stream().filter(a -> a.isViable(manager) && states.areAtWar(owningStateId, a.getOwningStateId()))
                .forEach(a -> threats.add(a.getStoredPosition()));
        manager.getAllLegions().stream().filter(l -> states.areAtWar(owningStateId, l.getOwningStateId()))
                .flatMap(l -> l.allCohorts().stream()).filter(c -> c.isAlive() && !c.isDeployed())
                .forEach(c -> threats.add(c.getStoredPosition()));
        ClaimManager claims = ClaimManager.get(level);
        for (BlockPos enemy : threats) {
            if (protectedPositions.stream().anyMatch(p -> ArmyManager.near(p, enemy, THREAT_RANGE_BLOCKS))) return false;
            // Search the small neighborhood around a threat instead of every historical conquest.
            int cx = enemy.getX() >> 4, cz = enemy.getZ() >> 4, radius = (THREAT_RANGE_BLOCKS + 15) / 16;
            for (int x = cx - radius; x <= cx + radius; x++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    ChunkPos chunk = new ChunkPos(x, z);
                    if (!conqueredChunkPositions.contains(chunk.pack())) continue;
                    var claim = claims.getClaim(chunk);
                    if (claim != null && owningStateId.equals(claim.getOwnerUUID())
                            && ArmyManager.near(new BlockPos(x * 16 + 8, 0, z * 16 + 8), enemy, THREAT_RANGE_BLOCKS)) return false;
                }
            }
        }
        return true;
    }

    // End the campaign only. The player's next action releases the army wrapper.
    public void disbandCampaign(ArmyManager armyManager) {
        for (UUID armyId : new HashSet<>(armyIds)) {
            Army army = armyManager.getArmy(armyId);
            if (army != null && campaignId.equals(army.getCampaignId())) {
                army.setCampaignId(null);
                army.clearWaypoints(); // Ending an operation must not immediately march into another one.
            }
        }
        armyIds.clear();
        isActive = false;
        LOGGER.info("Campaign {} ended.", campaignId);
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
