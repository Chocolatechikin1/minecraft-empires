package com.devc.minecraftempires.territory;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.army.Army;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Cohort;
import com.devc.minecraftempires.army.Legion;
import com.devc.minecraftempires.network.MapDataService;
import com.devc.minecraftempires.state.StateBalance;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateManager;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
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
    private long topologyRevision;
    private final Map<Long, UUID> pendingInfill = new LinkedHashMap<>();
    private final Set<Long> rejectedInfill = new HashSet<>();
    private boolean infillMigrationComplete;
    private Iterator<ChunkPos> migrationClaims;
    private PocketJob pocketJob;
    private long infillRetryAt;
    private final Map<Long, Long> infillDeferred = new HashMap<>();
    private final Map<UUID, int[]> ownerBounds = new HashMap<>();
    private final Map<Long, PocketJob> pausedPockets = new LinkedHashMap<>();
    private boolean terrainInfillPending;
    private long terrainInfillAt;

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
        includeBounds(owner, pos);
        queueInfillAround(pos, owner);
        markTopologyChanged();
    }
    public void removeClaim(ChunkPos pos) {
        ChunkData old = claims.remove(pos);
        if (old != null) {
            if (old.getOwnerUUID() != null) claimCounts.merge(old.getOwnerUUID(), -1, Integer::sum);
            queueInfillAround(pos, old.getOwnerUUID());
            markTopologyChanged();
        }
    }
    public void rebuildClaimCounts() {
        claimCounts.clear();
        ownerBounds.clear();
        for (ChunkData data : claims.values()) if (data.getOwnerUUID() != null) claimCounts.merge(data.getOwnerUUID(), 1, Integer::sum);
        claims.forEach((pos, data) -> includeBounds(data.getOwnerUUID(), pos));
    }

    private void includeBounds(UUID owner, ChunkPos pos) {
        if (owner == null) return;
        int[] bounds = ownerBounds.computeIfAbsent(owner, ignored -> new int[]{pos.x(), pos.z(), pos.x(), pos.z()});
        bounds[0] = Math.min(bounds[0], pos.x()); bounds[1] = Math.min(bounds[1], pos.z());
        bounds[2] = Math.max(bounds[2], pos.x()); bounds[3] = Math.max(bounds[3], pos.z());
    }

    //getters and setters
    public ChunkData getClaim(ChunkPos pos) { return claims.get(pos); }
    public boolean isClaimed(ChunkPos pos) { return claims.containsKey(pos); }
    public Map<ChunkPos, ChunkData> getClaimsView() { return Collections.unmodifiableMap(claims); }
    public Map<String, ChunkPos> getSettlementCentersView() { return Collections.unmodifiableMap(settlementCenters); }
    public int getClaimCountForState(UUID state) { return claimCounts.getOrDefault(state, 0); }
    public long getTopologyRevision() { return topologyRevision; }
    /** Also called after direct claim-owner updates such as settlement capture. */
    public void markTopologyChanged() { topologyRevision++; setDirty(); }

    private void queueInfillAround(ChunkPos position, UUID owner) {
        if (owner == null) return;
        rejectedInfill.clear();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            ChunkPos next = new ChunkPos(position.x() + dx, position.z() + dz);
            if (!claims.containsKey(next)) pendingInfill.put(next.pack(), owner);
        }
    }
    public void reconsiderInfill() { rejectedInfill.clear(); infillDeferred.clear(); pausedPockets.clear(); pocketJob = null; infillMigrationComplete = false; migrationClaims = null; setDirty(); }
    public void terrainChanged() { terrainInfillPending = true; }

    /** Ownership enclosure includes water and diagonal escape gaps; traversability is checked separately. */
    //technically a class, but struct for resumable pocket infill jobs, which track the state of a claim expansion process
    private static final class PocketJob {
        final UUID owner;
        final long seed;
        final long revision;
        final ArrayDeque<ChunkPos> frontier = new ArrayDeque<>();
        final Set<ChunkPos> cells = new LinkedHashSet<>();
        final Set<ChunkPos> boundary = new LinkedHashSet<>();
        final Set<ChunkPos> land = new HashSet<>();
        Iterator<ChunkPos> inspect;
        ChunkPos inspecting;
        int column;
        BlockPos origin;
        int radius;
        boolean rejected;
        PocketJob(ChunkPos start, UUID owner, long revision) { this.owner = owner; this.seed = start.pack(); this.revision = revision; cells.add(start); frontier.add(start); }
    }

    /** One resumable pocket at a time; persisted seeds also resume the initial existing-save reconciliation. */
    //claim infill tick function: processes pending infill claims, checking for valid pockets of land to claim and updating the claim manager state accordingly
    public void tickInfill(ServerLevel level, long deadline) {
        if (level.getGameTime() < infillRetryAt || System.nanoTime() >= deadline) return;
        // Coalesce construction/fluid events; a new physical crossing can make a previously isolated pocket eligible.
        if (terrainInfillPending && level.getGameTime() >= terrainInfillAt && infillMigrationComplete) {
            reconsiderInfill(); terrainInfillPending = false; terrainInfillAt = level.getGameTime() + 200;
        }
        if (!infillMigrationComplete) {
            // The snapshot protects the gradual scan from ordinary ownership changes between ticks.
            if (migrationClaims == null) migrationClaims = new ArrayList<>(claims.keySet()).iterator();
            for (int i = 0; i < 128 && migrationClaims.hasNext() && System.nanoTime() < deadline; i++) {
                ChunkPos position = migrationClaims.next(); ChunkData claim = claims.get(position);
                if (claim != null) queueInfillAround(position, claim.getOwnerUUID());
            }
            if (migrationClaims.hasNext()) return;
            infillMigrationComplete = true; migrationClaims = null; setDirty();
        }
        if (pocketJob != null && pocketJob.revision != topologyRevision) pocketJob = null;
        if (pocketJob == null) {
            Iterator<Map.Entry<Long, UUID>> seeds = pendingInfill.entrySet().iterator();
            while (seeds.hasNext()) {
                var seed = seeds.next(); ChunkPos start = ChunkPos.unpack(seed.getKey());
                if (claims.containsKey(start) || rejectedInfill.contains(seed.getKey())) { seeds.remove(); continue; }
                if (level.getGameTime() < infillDeferred.getOrDefault(seed.getKey(), 0L)) continue;
                PocketJob paused = pausedPockets.remove(seed.getKey());
                pocketJob = paused != null && paused.revision == topologyRevision ? paused : new PocketJob(start, seed.getValue(), topologyRevision); break;
            }
            if (pocketJob == null) return;
        }
        PocketJob job = pocketJob;
        int[] bounds = ownerBounds.get(job.owner);
        if (bounds == null) { rejectPocket(job); return; }
        for (int work = 0; work < 128 && !job.frontier.isEmpty() && System.nanoTime() < deadline; work++) {
            ChunkPos current = job.frontier.removeFirst();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                ChunkPos next = new ChunkPos(current.x() + dx, current.z() + dz);
                if (next.x() < bounds[0] || next.z() < bounds[1] || next.x() > bounds[2] || next.z() > bounds[3]) job.rejected = true;
                ChunkData claim = claims.get(next);
                if (claim != null) {
                    if (!job.owner.equals(claim.getOwnerUUID())) job.rejected = true;
                    else job.boundary.add(next);
                } else if (job.cells.add(next)) job.frontier.addLast(next);
            }
            // An unbounded exterior is never guessed to be an enclosed pocket.
            if (job.rejected) { rejectPocket(job); return; }
            if (job.cells.size() > 2048) { deferPocket(job, level, 1200); return; }
        }
        if (!job.frontier.isEmpty()) return;
        if (job.inspect == null) job.inspect = job.cells.iterator();
        while (System.nanoTime() < deadline) {
            if (job.inspecting == null) {
                if (!job.inspect.hasNext()) break;
                job.inspecting = job.inspect.next(); job.column = 0;
            }
            int x = job.inspecting.x() * 16 + (job.column & 15), z = job.inspecting.z() * 16 + (job.column >> 4);
            var ground = MapDataService.baseSurface(level, x, z);
            if (ground.ground() == MapDataService.Ground.UNKNOWN) { deferPocket(job, level, 5); return; }
            if (ground.ocean() && (ground.ground() == MapDataService.Ground.DEEP || ground.ground() == MapDataService.Ground.SHALLOW)) { rejectPocket(job); return; }
            if (ground.dry()) job.land.add(job.inspecting);
            if (++job.column == 256) job.inspecting = null;
        }
        if (job.inspecting != null || job.inspect.hasNext()) return;
        if (!job.land.isEmpty()) {
            if (job.origin == null) {
                for (ChunkPos edge : job.boundary) {
                    for (int i = 0; i < 256; i++) {
                        int x = edge.x() * 16 + (i & 15), z = edge.z() * 16 + (i >> 4);
                        var ground = MapDataService.surface(level, x, z);
                        if (ground.dry()) { job.origin = new BlockPos(x, (int) ground.height(), z); break; }
                    }
                    if (job.origin != null || System.nanoTime() >= deadline) break;
                }
                if (job.origin == null) { deferPocket(job, level, 5); return; }
                for (ChunkPos cell : job.cells) job.radius = Math.max(job.radius, Math.max(Math.abs(cell.x() * 16 + 8 - job.origin.getX()), Math.abs(cell.z() * 16 + 8 - job.origin.getZ())) + 16);
                if (job.radius > 512) { deferPocket(job, level, 1200); return; }
            }
            var reachable = MapDataService.reachableArea(level, job.origin, job.radius, false);
            if (!reachable.dryChunks().containsAll(job.land)) {
                if (reachable.status() == MapDataService.Verification.PENDING || reachable.status() == MapDataService.Verification.UNKNOWN
                        || reachable.status() == MapDataService.Verification.LIMITED) { deferPocket(job, level, 5); return; }
                rejectPocket(job); return;
            }
        }
        StateManager states = StateManager.get(level); StateData state = states.getState(job.owner);
        if (state == null) { rejectPocket(job); return; }
        if (!states.canAcquireChunks(state, getClaimCountForState(job.owner), job.cells.size())) {
            deferPocket(job, level, 200); return;
        }
        if (job.revision != topologyRevision || job.cells.stream().anyMatch(claims::containsKey)) { pocketJob = null; return; }
        for (ChunkPos cell : job.cells) setClaim(cell, job.owner, "", false, 0);
        job.cells.forEach(cell -> pendingInfill.remove(cell.pack())); pocketJob = null;
        states.refreshProgression(level); setDirty();
    }

    //claim infill helper functions: deferPocket and rejectPocket manage the state of a pocket job, either deferring it for later processing or rejecting it entirely
    private void deferPocket(PocketJob job, ServerLevel level, int delay) {
        for (ChunkPos cell : job.cells) if (pendingInfill.containsKey(cell.pack())) infillDeferred.put(cell.pack(), level.getGameTime() + delay);
        // Retain the survey cursor across pending samples instead of restarting at columns that age before the next retry.
        if (delay < 1200) {
            if (pausedPockets.size() >= 16) pausedPockets.remove(pausedPockets.keySet().iterator().next());
            pausedPockets.put(job.seed, job);
        }
        pocketJob = null;
    }

    private void rejectPocket(PocketJob job) {
        for (ChunkPos cell : job.cells) { pendingInfill.remove(cell.pack()); rejectedInfill.add(cell.pack()); }
        if (rejectedInfill.size() > 131072) rejectedInfill.clear();
        pocketJob = null; setDirty();
    }
    public double getChunkDistance(ChunkPos a, ChunkPos b) { return Math.hypot((double) a.x() - b.x(), (double) a.z() - b.z()); }
    public double getProtectiveRadius(int ignoredTier) { return StateBalance.SETTLEMENT_RADIUS / 16.0; }
    public double getProtectiveRadius(SettlementData settlement) { return settlement.getProtectiveRadius() / 16.0; }
    
    //settlement center management
    public void registerSettlementCenter(String settlement, ChunkPos pos) { settlementCenters.put(settlement, pos); markTopologyChanged(); }
    public void removeSettlementCenter(String settlement) { if (settlementCenters.remove(settlement) != null) markTopologyChanged(); }
    public void clearAllClaimsForState(UUID state) {
        if (claims.values().removeIf(data -> state.equals(data.getOwnerUUID()))) { rebuildClaimCounts(); markTopologyChanged(); }
    }
    /** Disbanding a settlement releases its administration, not its state's ownership of the land. */
    public void releaseSettlementClaims(String settlement) {
        if (settlement == null || settlement.isBlank()) return;
        boolean changed = settlementCenters.remove(settlement) != null;
        for (ChunkData data : claims.values()) {
            if (!settlement.equals(data.getSettlementID())) continue;
            // Retain each current owner, including any chunks since captured by another state.
            data.setSettlementID("");
            data.setIsGarrisoned(false);
            data.setSettlementTier(0);
            changed = true;
        }
        // Ownership/counts are unchanged, but settlement-dependent map/supply caches must refresh.
        if (changed) markTopologyChanged();
    }

    /** Do not force world generation merely to sample water on the map's distant edge. */
    //land claim checker
    public static boolean isClaimableLand(ServerLevel level, ChunkPos chunk) {
        // Unknown columns wait for inspection; water in a forest or custom biome is still water.
        // This eligibility helper does not establish reachability from an expansion origin.
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            if (MapDataService.surface(level, chunk.x() * 16 + x, chunk.z() * 16 + z).usable()) return true;
        return false;
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
            if (!attacker.equals(settlement.getOwningStateId()) && StateManager.isInSettlementFootprint(settlement, pos)) return true;
        }
        return false;
    }

    //campaign area claimer, with additional checks for nearby enemies and settlement capture.
    public List<ChunkPos> claimCampaignArea(ServerLevel level, Army army) {
        List<ChunkPos> acquired = new ArrayList<>();
        if (level != level.getServer().overworld() || !army.isOnCampaign() || army.isEngaged() || army.isRetreating()) return acquired;
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
        var reachable = MapDataService.reachableArea(level, position, StateBalance.CAMPAIGN_RADIUS, false);
        if (reachable.status() == MapDataService.Verification.PENDING || reachable.status() == MapDataService.Verification.LIMITED) return acquired;
        // Capture the protected core only after reaching its altar and removing its defenders.
        for (SettlementData settlement : new ArrayList<>(states.getAllSettlements())) {
            if (!states.areAtWar(attacker, settlement.getOwningStateId())
                    || horizontalDistanceSquared(position, settlement.getCenterAltarPos()) > 16L * 16
                    || MapDataService.verifySegment(level, position.getX() + .5, position.getZ() + .5,
                            settlement.getCenterAltarPos().getX() + .5, settlement.getCenterAltarPos().getZ() + .5) != MapDataService.Verification.VERIFIED
                    || threatened(settlement.getCenterAltarPos(), defenders)) continue;
            List<ChunkPos> captured = claims.entrySet().stream()
                    .filter(e -> settlement.getSettlementId().toString().equals(e.getValue().getSettlementID()) && !attacker.equals(e.getValue().getOwnerUUID()))
                    .map(Map.Entry::getKey).toList();
            if (states.captureSettlement(level, settlement.getSettlementId(), attacker)) acquired.addAll(captured);
        }
        List<ChunkPos> candidates = new ArrayList<>(reachable.chunks());
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
        tag.putBoolean("InfillMigrationComplete", infillMigrationComplete);
        ListTag pending = new ListTag();
        pendingInfill.forEach((position, owner) -> {
            CompoundTag item = new CompoundTag(); item.putLong("Position", position); item.putString("Owner", owner.toString()); pending.add(item);
        });
        tag.put("PendingInfill", pending);
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
        manager.infillMigrationComplete = tag.getBoolean("InfillMigrationComplete").orElse(false);
        for (var value : tag.getListOrEmpty("PendingInfill")) if (value instanceof CompoundTag item) {
            try { manager.pendingInfill.put(item.getLong("Position").orElseThrow(), UUID.fromString(item.getString("Owner").orElseThrow())); }
            catch (RuntimeException ignored) { /* An invalid seed must not affect saved ownership. */ }
        }
        return manager;
    }
}
