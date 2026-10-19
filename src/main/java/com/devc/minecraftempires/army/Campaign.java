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
 * A Campaign may be saved as an inactive plan, started by its owner, or
 * created automatically on entry into non-friendly territory or a hostile
 * battle. An active operation persists through multiple battles and sieges until
 * the player manually ends it — subject to an end-condition check.
 *
 * Ending requires the player's explicit action, no participating battle or
 * retreat, and no hostile army or undeployed cohort within THREAT_RANGE_BLOCKS
 * of participating armies, owned settlements, or still-owned conquered chunks.
 *
 * Ending a campaign clears only its status. Army wrappers must then be released separately.
 */
public class Campaign {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    public static final int THREAT_RANGE_BLOCKS = 150;
    public static final int MAX_ARMIES = 128;
    public static final int MAX_NAME = 80;
    public static final int MAX_OBJECTIVE = 1024;

    private final UUID campaignId;
    private final UUID owningStateId;

    private final Set<UUID> armyIds = new HashSet<>(); //hash set of all army IDs in this campaign, used for O(1) membership checks when checking for enemy armies in checkEndConditions()
    private final Set<Long> conqueredChunkPositions = new HashSet<>(); //hash set of all chunks conquered in the campaign, gives O(1) search time when checking for enemy armies in checkEndConditions()
    private boolean isActive = true;
    private boolean planned;
    private String name = "Campaign", objective = "";
    private BlockPos focusPosition;

    public Campaign(UUID campaignId, UUID owningStateId) {
        this.campaignId     = campaignId;
        this.owningStateId  = owningStateId;
    }

    public void addArmy(UUID armyId)    { armyIds.add(armyId); }
    public void removeArmy(UUID armyId) { armyIds.remove(armyId); }
    public Set<UUID> getArmyIds()       { return Collections.unmodifiableSet(armyIds); }
    public boolean isEmpty()            { return armyIds.isEmpty(); }

    /** Plans reference armies without taking their active campaign lock. */
    public static Campaign plan(UUID id, UUID owner) {
        Campaign campaign = new Campaign(id, owner);
        campaign.isActive = false;
        campaign.planned = true;
        return campaign;
    }
    public boolean isPlanned() { return planned; }
    public String getName() { return name; }
    public String getObjective() { return objective; }
    public BlockPos getFocusPosition() { return focusPosition; }
    public void describe(String name, String objective, BlockPos focus) {
        this.name = clean(name, MAX_NAME).replace('\n', ' ').strip();
        if (this.name.isEmpty()) this.name = "Campaign";
        this.objective = clean(objective, MAX_OBJECTIVE);
        focusPosition = focus == null ? null : focus.immutable();
    }
    private static String clean(String value, int maximum) {
        if (value == null) return "";
        String text = value.codePoints().filter(c -> c != 0xA7 && (!Character.isISOControl(c) || c == '\n'))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return text.substring(0, Math.min(maximum, text.length()));
    }
    public void activate() { planned = false; isActive = true; }

    //chunk tracking
    public void trackConqueredChunk(long packedChunkPos) {
        conqueredChunkPositions.add(packedChunkPos);
    }

    //getter
    public Set<Long> getConqueredChunkPositions() {
        return Collections.unmodifiableSet(conqueredChunkPositions);
    }

    public boolean checkEndConditions(ArmyManager manager, ServerLevel level) {
        if (!isActive) return false;
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
        tag.putBoolean("Planned", planned);
        tag.putString("Name", name);
        tag.putString("Objective", objective);
        if (focusPosition != null) tag.putLong("Focus", focusPosition.asLong());

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
        c.planned = tag.getBoolean("Planned").orElse(false);
        if (c.planned) c.isActive = false;
        c.describe(tag.getString("Name").orElse("Campaign"), tag.getString("Objective").orElse(""),
                tag.getLong("Focus").map(BlockPos::of).orElse(null));

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
