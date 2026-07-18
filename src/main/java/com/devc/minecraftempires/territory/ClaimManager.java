package com.devc.minecraftempires.territory;

//import dependencies
import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.util.datafix.DataFixTypes;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ClaimManager extends SavedData {
    //central data storage, HashMap for O(1) lookups, key is ChunkPos, value is ChunkData
    private final Map<ChunkPos, ChunkData> claims = new HashMap<>();

    // Tracks the core "City Altar" chunk for a given settlement ID
    private final Map<String, ChunkPos> settlementCenters = new HashMap<>();

    //standard identifier
    private static final String DATA_NAME = "minecraftempires_claims";
    private static final String CLAIMS_LIST_KEY = "ClaimsList";
    private static final String CHUNK_POS_KEY = "ChunkPosLong";

    private static final Codec<ClaimManager> CODEC = CompoundTag.CODEC.xmap(ClaimManager::fromTag, ClaimManager::toTag);

    public static final SavedDataType<ClaimManager> TYPE = new SavedDataType<>(
        Identifier.withDefaultNamespace(DATA_NAME),
        ClaimManager::new,
        CODEC,
        DataFixTypes.LEVEL
    );

    //constructor
    public ClaimManager() {
        //initialize the claims map
    }

    //access method to fetch or create new instance
    public static ClaimManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    //claim management methods
    //claim a chunk
    public void setClaim(ChunkPos pos, UUID ownerUUID, String settlementID, boolean isGarrisoned, int tier) {
        ChunkData data = new ChunkData(ownerUUID, settlementID, isGarrisoned, tier);
        claims.put(pos, data);
        setDirty(); //flag for  NeoForge that this data changed and MUST be saved to disk
    }

    //unclaim a chunk
    public void removeClaim(ChunkPos pos) {
        if (claims.remove(pos) != null) {
            setDirty();
        }
    }

    //retreive chunk data at a coordinate point, returns null if unclaimed
    public ChunkData getClaim(ChunkPos pos) {
        return claims.get(pos);
    }

    //method to check if chunk is claimed
    public boolean isClaimed(ChunkPos pos) {
        return claims.containsKey(pos);
    }

    public Map<ChunkPos, ChunkData> getClaimsView() { // PHASE 3
        return Collections.unmodifiableMap(claims);
    }

    public Map<String, ChunkPos> getSettlementCentersView() { // PHASE 3
        return Collections.unmodifiableMap(settlementCenters);
    }

    //settlement management methods
    //calculates Euclidean distance between two chunks
    public double getChunkDistance(ChunkPos pos1, ChunkPos pos2) {
        int dx = pos1.x() - pos2.x();
        int dz = pos1.z() - pos2.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    //protective settlement radius (increases by tier)
    public double getProtectiveRadius(int tier){
        return 6.0 + (tier * 4.0); // Base radius of 6 chunks, increasing by 4 chunks per tier
    }

    //center altar chunk
    public void registerSettlementCenter(String settlementID, ChunkPos centerPos) {
        settlementCenters.put(settlementID, centerPos);
        setDirty();
    }

    //main border flipping logic: called when a hostile unit enters a chunk
    public boolean tryFlipBorder(ChunkPos targetPos, UUID attackerUUID, String attackerSettlementID) {
        return tryFlipBorderInternal(null, targetPos, attackerUUID, attackerSettlementID);
    }

    /**
     * Phase 3-aware overload. Future army movement code should use this version so successful
     * hostile border crossings generate both chat warnings and map breach markers.
     */
    public boolean tryFlipBorder(
            ServerLevel level,
            ChunkPos targetPos,
            UUID attackerUUID,
            String attackerSettlementID
    ) {
        return tryFlipBorderInternal(level, targetPos, attackerUUID, attackerSettlementID);
    }

    private boolean tryFlipBorderInternal(
            ServerLevel level,
            ChunkPos targetPos,
            UUID attackerUUID,
            String attackerSettlementID
    ) {
        ChunkData targetData = claims.get(targetPos);
        UUID defenderStateId = targetData == null ? null : targetData.getOwnerUUID();

        //if land is unclaimed, take instantly
        if (targetData == null) {
            setClaim(targetPos, attackerUUID, attackerSettlementID, false, 1);
            return true;
        }

        //if chunk is garrisoned, cannot flip passively
        if (targetData.isGarrisoned()) {
            return false; //triggers a siege or battle
        }

        //defending settlement core locator
        String defenderSettlement = targetData.getSettlementID();
        ChunkPos defenderCore = settlementCenters.get(defenderSettlement);

        //if core missing, land is considered abandoned, attacker takes it
        if (defenderCore == null) {
            setClaim(targetPos, attackerUUID, attackerSettlementID, false, 1);
            notifyBreachIfNeeded(level, targetPos, defenderStateId, attackerUUID);
            return true;
        }

        //calculate if attackers have reached the radius of the settlement
        double distanceToCore = getChunkDistance(targetPos, defenderCore);
        double protectiveRadius = getProtectiveRadius(targetData.getSettlementTier());

        if (distanceToCore > protectiveRadius) {
            // Attacker is outside the core protection. Flip the un-garrisoned land!
            setClaim(targetPos, attackerUUID, attackerSettlementID, false, 1);
            notifyBreachIfNeeded(level, targetPos, defenderStateId, attackerUUID);
            return true;
        }

        // Attacker hit the protective radius. Passive flipping stops here.
        return false;
    }

    private static void notifyBreachIfNeeded(
            ServerLevel level,
            ChunkPos targetPos,
            UUID defenderStateId,
            UUID attackerStateId
    ) {
        if (level != null) {
            BreachAlertService.recordBreach(level, targetPos, defenderStateId, attackerStateId);
        }
    }

    //serialization
    private CompoundTag toTag() {
        ListTag list = new ListTag();
        CompoundTag tag = new CompoundTag();

        for (Map.Entry<ChunkPos, ChunkData> entry : claims.entrySet()) {
            list.add(toEntryTag(entry.getKey(), entry.getValue()));
        }

        //save settlement centers as well
        CompoundTag centersTag = new CompoundTag();
        for (Map.Entry<String, ChunkPos> entry : settlementCenters.entrySet()) {
            centersTag.putLong(entry.getKey(), entry.getValue().pack());
        }
        tag.put(CLAIMS_LIST_KEY, list);
        tag.put("SettlementCenters", centersTag);
        return tag;
    }

    private static ClaimManager fromTag(CompoundTag tag) {
        ClaimManager manager = new ClaimManager();

        if (tag.contains(CLAIMS_LIST_KEY)) {
            ListTag list = tag.getList(CLAIMS_LIST_KEY).orElse(new ListTag());
            
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entryTag = list.getCompound(i).orElse(new CompoundTag());
                decodeEntryTag(entryTag).ifPresent(entry -> manager.claims.put(entry.pos(), entry.data()));
            }
        }

        //load settlement centers
        if (tag.contains("SettlementCenters")) {
            CompoundTag centersTag = tag.getCompound("SettlementCenters").orElse(new CompoundTag());
            for (String settlementID : centersTag.keySet()) {
                long packedPos = centersTag.getLong(settlementID).orElse(0L);
                ChunkPos centerPos = ChunkPos.unpack(packedPos);
                manager.settlementCenters.put(settlementID, centerPos);
            }
        }

        return manager;
    }

    private static CompoundTag toEntryTag(ChunkPos pos, ChunkData data) {
        CompoundTag entryTag = data.toNBT();
        entryTag.putLong(CHUNK_POS_KEY, pos.pack());
        return entryTag;
    }

    private static java.util.Optional<ClaimEntry> decodeEntryTag(CompoundTag entryTag) {
        if (!entryTag.contains(CHUNK_POS_KEY)) {
            return java.util.Optional.empty();
        }

        long posLong = entryTag.getLong(CHUNK_POS_KEY).orElse(0L);
        ChunkPos pos = ChunkPos.unpack(posLong);
        ChunkData data = ChunkData.fromNBT(entryTag);
        return java.util.Optional.of(new ClaimEntry(pos, data));
    }

    private record ClaimEntry(ChunkPos pos, ChunkData data) {}

    //get the total terrutory (claims) owned by a specific state for tax purposes
    public int getClaimCountForState(UUID stateId) {
        int count = 0;
        for (ChunkData data : this.claims.values()) {
            if (data.getOwnerUUID().equals(stateId)) {
                count++;
            }
        }
        return count;
    }

    //clear chunk claims when disbanding a state
    public void clearAllClaimsForState(UUID stateId) {
        boolean chunksRemoved = this.claims.entrySet().removeIf(entry -> entry.getValue().getOwnerUUID().equals(stateId));
        if (chunksRemoved) {
            this.setDirty(); // CRITICAL: Tells the server to save the cleared map to disk!
        }
    }
}
