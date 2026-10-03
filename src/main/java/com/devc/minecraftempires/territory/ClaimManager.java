package com.devc.minecraftempires.territory;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.army.Army;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Cohort;
import com.devc.minecraftempires.army.Legion;
import com.devc.minecraftempires.state.StateBalance;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateManager;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;

/** Overworld claims. The cached counts make large-state expansion linear in the new area. */
public class ClaimManager extends SavedData {
    private final Map<ChunkPos, ChunkData> claims = new HashMap<>();
    private final Map<String, ChunkPos> settlementCenters = new HashMap<>();
    private final Map<UUID, Integer> claimCounts = new HashMap<>();

    //codec and type for saving/loading
    private static final Codec<ClaimManager> CODEC = CompoundTag.CODEC.xmap(ClaimManager::fromTag, ClaimManager::toTag);
    public static final SavedDataType<ClaimManager> TYPE = new SavedDataType<>(Identifier.withDefaultNamespace("minecraftempires_claims"), ClaimManager::new, CODEC, DataFixTypes.LEVEL);
    
    public static ClaimManager get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }
    public void setClaim(ChunkPos pos, UUID owner, String settlement, boolean garrisoned, int tier) {
        if (owner == null) return;
        ChunkData old = claims.put(pos, new ChunkData(owner, settlement, garrisoned, tier));
        if (old != null && old.getOwnerUUID() != null) claimCounts.merge(old.getOwnerUUID(), -1, Integer::sum);
        claimCounts.merge(owner, 1, Integer::sum);
        setDirty();
    }
    public void removeClaim(ChunkPos pos) {
        ChunkData old = claims.remove(pos);
        if (old != null) {
            if (old.getOwnerUUID() != null) claimCounts.merge(old.getOwnerUUID(), -1, Integer::sum);
            setDirty();
        }
    }
    public void rebuildClaimCounts() {
        claimCounts.clear();
        for (ChunkData data : claims.values()) if (data.getOwnerUUID() != null) claimCounts.merge(data.getOwnerUUID(), 1, Integer::sum);
    }

    //getters and setters
    public ChunkData getClaim(ChunkPos pos) { return claims.get(pos); }
    public boolean isClaimed(ChunkPos pos) { return claims.containsKey(pos); }
    public Map<ChunkPos, ChunkData> getClaimsView() { return Collections.unmodifiableMap(claims); }
    public Map<String, ChunkPos> getSettlementCentersView() { return Collections.unmodifiableMap(settlementCenters); }
    public int getClaimCountForState(UUID state) { return claimCounts.getOrDefault(state, 0); }
    public double getChunkDistance(ChunkPos a, ChunkPos b) { return Math.hypot((double) a.x() - b.x(), (double) a.z() - b.z()); }
    public double getProtectiveRadius(int ignoredTier) { return StateBalance.SETTLEMENT_RADIUS / 16.0; }
    
    //settlement center management
    public void registerSettlementCenter(String settlement, ChunkPos pos) { settlementCenters.put(settlement, pos); setDirty(); }
    public void removeSettlementCenter(String settlement) { if (settlementCenters.remove(settlement) != null) setDirty(); }
    public void clearAllClaimsForState(UUID state) {
        if (claims.values().removeIf(data -> state.equals(data.getOwnerUUID()))) { rebuildClaimCounts(); setDirty(); }
    }
    public void clearAllClaimsForSettlement(String settlement) {
        if (claims.values().removeIf(data -> settlement.equals(data.getSettlementID()))) { rebuildClaimCounts(); setDirty(); }
    }

    /** Do not force world generation merely to sample water on the map's distant edge. */
    //land claim checker
    public static boolean isClaimableLand(ServerLevel level, ChunkPos chunk) {
        BlockPos sample = new BlockPos(chunk.x() * 16 + 8, 64, chunk.z() * 16 + 8);
        // If the chunk is not generated, we cannot determine if it is claimable. This prevents players from claiming ungenerated chunks.
        if (!level.getWorldBorder().isWithinBounds(sample)) return false;
        return !level.hasChunkAt(sample) || (!level.getBiome(sample).is(BiomeTags.IS_OCEAN) && !level.getBiome(sample).is(BiomeTags.IS_RIVER));
    }

    /** Legacy overload is intentionally unable to conquer without a server/diplomacy context. */
    public boolean tryFlipBorder(ChunkPos pos, UUID attacker, String settlement) { return false; }
    //main claim function
    public boolean tryFlipBorder(ServerLevel level, ChunkPos pos, UUID attacker, String settlement) {
        if (level != level.getServer().overworld()) return false;
        StateManager states = StateManager.get(level);
        StateData state = states.getState(attacker);
        ChunkData old = claims.get(pos);
        if (state == null || (old != null && attacker.equals(old.getOwnerUUID()))) return false;
        if (!states.canAcquireChunks(state, getClaimCountForState(attacker), 1)) return false;
        if (old != null && (!states.areAtWar(attacker, old.getOwnerUUID()) || old.isGarrisoned())) return false;
        if (protectsSettlement(states, pos, attacker)) return false;
        if (!isClaimableLand(level, pos)) return false;
        UUID defender = old == null ? null : old.getOwnerUUID();
        setClaim(pos, attacker, settlement, false, 1);
        if (defender != null) BreachAlertService.recordBreach(level, pos, defender, attacker);
        return true;
    }

    private boolean protectsSettlement(StateManager states, ChunkPos pos, UUID attacker) {
        // Older saves had a 3x3 footprint. Protection still uses the agreed radius even
        // when a nearby chunk lacks the settlement id; it never silently grants land.
        for (SettlementData settlement : states.getAllSettlements()) {
            if (!attacker.equals(settlement.getOwningStateId()) && StateManager.isInSettlementFootprint(settlement.getCenterAltarPos(), pos)) return true;
        }
        return false;
    }

    //campaign area claimer, with additional checks for nearby enemies and settlement capture.
    public List<ChunkPos> claimCampaignArea(ServerLevel level, Army army) {
        List<ChunkPos> acquired = new ArrayList<>();
        if (level != level.getServer().overworld() || !army.isOnCampaign() || army.isEngaged()) return acquired;
        UUID attacker = army.getOwningStateId();
        StateManager states = StateManager.get(level);
        if (states.getState(attacker) == null) return acquired;
        List<BlockPos> defenders = new ArrayList<>();
        ArmyManager armies = ArmyManager.get(level);
        for (Legion legion : armies.getAllLegions()) {
            if (!states.areAtWar(attacker, legion.getOwningStateId())) continue;
            for (Cohort cohort : legion.allCohorts()) if (cohort.isAlive()) defenders.add(cohort.getStoredPosition());
        }
        for (Army other : armies.getAllArmies()) {
            if (states.areAtWar(attacker, other.getOwningStateId()) && other.isViable(armies)) defenders.add(other.getStoredPosition());
        }
        BlockPos position = army.getStoredPosition();
        // Capture the protected core only after reaching its altar and removing its defenders.
        for (SettlementData settlement : new ArrayList<>(states.getAllSettlements())) {
            if (!states.areAtWar(attacker, settlement.getOwningStateId())
                    || horizontalDistanceSquared(position, settlement.getCenterAltarPos()) > 16L * 16
                    || threatened(settlement.getCenterAltarPos(), defenders)) continue;
            List<ChunkPos> captured = claims.entrySet().stream()
                    .filter(e -> settlement.getSettlementId().toString().equals(e.getValue().getSettlementID()) && !attacker.equals(e.getValue().getOwnerUUID()))
                    .map(Map.Entry::getKey).toList();
            if (states.captureSettlement(level, settlement.getSettlementId(), attacker)) acquired.addAll(captured);
        }
        List<ChunkPos> candidates = StateManager.squareChunks(position, StateBalance.CAMPAIGN_RADIUS);
        candidates.sort(Comparator.comparingLong(c -> horizontalDistanceSquared(position, new BlockPos(c.x() * 16 + 8, position.getY(), c.z() * 16 + 8))));
        for (ChunkPos candidate : candidates) {
            BlockPos center = new BlockPos(candidate.x() * 16 + 8, position.getY(), candidate.z() * 16 + 8);
            if (threatened(center, defenders)) continue;
            // Passing an ally or peaceful neighbor never transfers its land or declares war.
            if (tryFlipBorder(level, candidate, attacker, "")) acquired.add(candidate);
        }
        if (!acquired.isEmpty()) states.refreshProgression(level);
        return acquired;
    }
    //returns true if the position is within 100 blocks of any enemy position.
    private static boolean threatened(BlockPos position, List<BlockPos> enemies) {
        for (BlockPos enemy : enemies) if (horizontalDistanceSquared(position, enemy) <= 100L * 100) return true;
        return false;
    }
    //gets the squared horizontal distance between two positions, ignoring Y.
    private static long horizontalDistanceSquared(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX(), dz = (long) a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        claims.forEach((pos, data) -> { CompoundTag item = data.toNBT(); item.putLong("ChunkPosLong", pos.pack()); list.add(item); });
        tag.put("ClaimsList", list);
        CompoundTag centers = new CompoundTag();
        settlementCenters.forEach((id, pos) -> centers.putLong(id, pos.pack()));
        tag.put("SettlementCenters", centers);
        return tag;
    }
    private static ClaimManager fromTag(CompoundTag tag) {
        ClaimManager manager = new ClaimManager();
        ListTag list = tag.getList("ClaimsList").orElse(new ListTag());
        for (int i = 0; i < list.size(); i++) {
            try {
                CompoundTag item = list.getCompound(i).orElseThrow();
                if (!item.contains("ChunkPosLong")) continue;
                ChunkData data = ChunkData.fromNBT(item);
                if (data.getOwnerUUID() != null) manager.claims.put(ChunkPos.unpack(item.getLong("ChunkPosLong").orElseThrow()), data);
            } catch (RuntimeException malformed) { MinecraftEmpires.LOGGER.warn("Skipped invalid saved claim", malformed); }
        }
        CompoundTag centers = tag.getCompound("SettlementCenters").orElse(new CompoundTag());
        for (String id : centers.keySet()) centers.getLong(id).ifPresent(p -> manager.settlementCenters.put(id, ChunkPos.unpack(p)));
        manager.rebuildClaimCounts();
        return manager;
    }
}
