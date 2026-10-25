package com.devc.minecraftempires.army;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.combat.BattleManager;
import com.devc.minecraftempires.combat.CohortData;
import com.devc.minecraftempires.network.MapDataService;
import com.devc.minecraftempires.network.packet.*;
import com.devc.minecraftempires.network.ModNetworking;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateBalance;
import com.devc.minecraftempires.state.StateManager;
import com.devc.minecraftempires.territory.ClaimManager;
import com.devc.minecraftempires.territory.ChunkData;
import com.devc.minecraftempires.territory.SettlementData;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.*;
import java.util.function.BiConsumer;

/** Persistent organization, physical unit locations, and operational army/campaign wrappers. */
public class ArmyManager extends SavedData {
    public static final int LEGION_RECRUIT_COST = 5_000;
    public static final int LIGHT_INFANTRY_COST = 100;
    public static final int ASSEMBLY_RANGE = 16;
    public static final int ENGAGEMENT_RANGE = 100;
    private static final int MAX_WAYPOINTS = 128;
    private static final Codec<ArmyManager> CODEC = CompoundTag.CODEC.xmap(ArmyManager::fromTag, ArmyManager::toTag);
    public static final SavedDataType<ArmyManager> TYPE = new SavedDataType<>(
        Identifier.withDefaultNamespace("minecraftempires_armies"), ArmyManager::new, CODEC, DataFixTypes.LEVEL);

    private final Map<UUID, Legion> activeLegions = new LinkedHashMap<>();
    private final Map<UUID, List<UUID>> stateToLegionIndex = new HashMap<>();
    // Aggregate legions are economic/military assets, never ordinary moving cohorts or tactical wrappers.
    //essentially, for distant ai army movement, i wanted to not load full details to preserve server
    //compute, hence these additonal methods
    private final Map<UUID, StrategicForce> strategicForces = new LinkedHashMap<>();
    private final Map<UUID, List<UUID>> stateToStrategicIndex = new HashMap<>();
    private long macroSequence;
    private long interactionSequence;
    // Provenance is not a second military authority. Only the detailed legion ticks while present here.
    private final Map<UUID, StrategicForce> detailedOrigins = new LinkedHashMap<>();
    private final Set<UUID> aiInitializations = new HashSet<>();
    private final Map<UUID, Army> activeArmies = new LinkedHashMap<>();
    private final Map<UUID, List<UUID>> stateToArmyIndex = new HashMap<>();
    // Includes inactive plans; only active operations establish an Army.campaignId lock.
    private final Map<UUID, Campaign> activeCampaigns = new LinkedHashMap<>();
    private final Map<UUID, Cohort> cohortRegistry = new LinkedHashMap<>();
    private final Map<UUID, UUID> cohortParents = new HashMap<>();
    private boolean locationsReconciled;
    private long lastEngagementCheck = Long.MIN_VALUE / 2;
    private final Map<UUID, MoveProposal> moveProposals = new LinkedHashMap<>();
    private final Map<UUID, MapDataService.TerrainInspection> moveConfirmations = new HashMap<>();
    private record MoveProposal(UUID id, UUID player, UUID owner, String kind, List<UUID> units, BlockPos target,
                                boolean queue, Map<UUID, BlockPos> starts, Map<UUID, List<BlockPos>> oldOrders,
                                Map<UUID, List<BlockPos>> routes, long expires, boolean shown, Set<UUID> provisional, long rightsRevision) {}

    public static ArmyManager get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Initial compact roster; detailed promotion must preserve it and retire this authority first. */
    //distant ai army stats, saved differently to preserve compute and avoid loading full details
    //when a player gets near, the details from this class struct are pulled to make a full army
    public static final class StrategicForce {
        private final UUID id, owner, home;
        private BlockPos position;
        private int infantry = 500, cavalry = 50, morale = 50, experience;
        private GearTier gear = GearTier.STONE;
        private long supply = 550L * StateBalance.CARRIED_SUPPLY_DAYS * StateBalance.TICKS_PER_DAY;
        private long militaryTime = -1, shortageTicks;
        private int fatigue;
        private UUID targetState, targetSettlement;
        private List<Long> route = List.of(), supplyCorridor = List.of();
        private boolean retreating, pendingDeclaration;
        private boolean movedStrategically;
        private CompoundTag detailedRoster;
        public StrategicForce(UUID id, UUID owner, UUID home, BlockPos position) {
            this.id = Objects.requireNonNull(id); this.owner = Objects.requireNonNull(owner);
            this.home = Objects.requireNonNull(home); this.position = position.immutable();
        }
        public UUID id() { return id; }
        public UUID owner() { return owner; }
        public UUID home() { return home; }
        public BlockPos position() { return position; }
        public int infantry() { return infantry; }
        public int cavalry() { return cavalry; }
        public int soldiers() { return infantry + cavalry; }
        public int morale() { return morale; }
        public GearTier gear() { return gear; }
        public int experience() { return experience; }
        public long supply() { return supply; }
        public long militaryTime() { return militaryTime; }
        public int fatigue() { return fatigue; }
        public UUID targetState() { return targetState; }
        public UUID targetSettlement() { return targetSettlement; }
        public List<Long> route() { return route; }
        public List<Long> supplyCorridor() { return supplyCorridor; }
        public boolean retreating() { return retreating; }
        public boolean pendingDeclaration() { return pendingDeclaration; }
        public boolean ready() { return soldiers() > Legion.AUTO_DISBAND_STRENGTH && morale >= 20 && supply > 0 && !retreating; }
        public StrategicForce copy() { return fromNBT(toNBT()); }
        public void order(UUID nation, UUID settlement, List<Long> path, boolean withdraw) {
            if (path.size() > 16) throw new IllegalArgumentException("Oversized macro corridor");
            targetState = nation; targetSettlement = settlement; route = List.copyOf(path); retreating = withdraw;
            pendingDeclaration = false;
        }
        public void planWar(UUID nation, UUID settlement) { order(nation, settlement, List.of(), false); pendingDeclaration = true; }
        public void clearOrder() { targetState = null; targetSettlement = null; route = List.of(); retreating = false; pendingDeclaration = false; }
        public void moveStrategically(BlockPos next, List<Long> remaining) { position = next.immutable(); route = List.copyOf(remaining); movedStrategically = true; }
        public void initializeMilitaryTime(long now) { if (militaryTime < 0 && now >= 0) militaryTime = now; }
        public void deferMilitaryTime(long now) { if (now >= 0) militaryTime = Math.max(militaryTime, now); supplyCorridor = List.of(); }
        public void completeMilitaryInterval(long interval) { militaryTime = Math.addExact(militaryTime, interval); }
        public void advanceStrategicSupply(long elapsed, boolean connected, List<Long> corridor) {
            supplyCorridor = List.copyOf(corridor);
            long amount = Math.multiplyExact((long) soldiers(), elapsed);
            supply = Math.min(supply, capacity());
            supply = connected ? supply + Math.min(amount, capacity() - supply) : Math.max(0, supply - amount);
            shortageTicks += elapsed;
            if (shortageTicks >= StateBalance.TICKS_PER_DAY) {
                shortageTicks -= StateBalance.TICKS_PER_DAY;
                if (supply == 0) { morale = Math.max(0, morale - StateBalance.SUPPLY_MORALE_PENALTY); fatigue = Math.min(StateBalance.MAX_SUPPLY_FATIGUE, fatigue + StateBalance.SUPPLY_FATIGUE_PER_DAY); }
                else if (connected) fatigue = Math.max(0, fatigue - StateBalance.SUPPLY_FATIGUE_RECOVERY);
            }
        }
        public void sufferStrategicLosses(int losses, int moraleLoss, int earnedExperience) {
            int total = soldiers(); losses = Math.clamp(losses, 0, total);
            int horseLoss = total == 0 ? 0 : (int) ((long) cavalry * losses / total);
            int footLoss = Math.min(infantry, losses - horseLoss);
            infantry -= footLoss; cavalry -= losses - footLoss;
            morale = Math.clamp(morale - moraleLoss, 0, 100);
            experience = (int) Math.min(Integer.MAX_VALUE, (long) experience + Math.max(0, earnedExperience));
            if (GearTier.fromXp(experience).ordinal() > gear.ordinal()) gear = GearTier.fromXp(experience);
            supply = Math.min(supply, capacity());
        }
        private long capacity() { return (long) soldiers() * StateBalance.CARRIED_SUPPLY_DAYS * StateBalance.TICKS_PER_DAY; }
        private void economicDay(boolean paid, int unpaidDays, boolean suppliedHome) {
            if (!paid) {
                morale = Math.max(0, morale - 10);
                if (unpaidDays >= 7) { infantry = 0; cavalry = 0; }
                else if (unpaidDays >= 3) {
                    infantry -= (infantry + 9) / 10; cavalry -= (cavalry + 9) / 10;
                }
            } else if (suppliedHome) morale = Math.min(100, morale + 2);
            supply = Math.min(supply, capacity());
            // Once admitted to macro ticking, its saved military clock alone owns supply consumption.
            if (militaryTime >= 0) return;
            long day = (long) soldiers() * StateBalance.TICKS_PER_DAY;
            if (suppliedHome) supply += Math.min(day, capacity() - supply);
            else supply = Math.max(0, supply - day);
        }
        public CompoundTag toNBT() {
            CompoundTag tag = new CompoundTag(); tag.putInt("Version", 1);
            tag.putString("Id", id.toString()); tag.putString("Owner", owner.toString()); tag.putString("Home", home.toString());
            tag.putLong("Position", position.asLong()); tag.putInt("Infantry", infantry); tag.putInt("Cavalry", cavalry);
            tag.putInt("Morale", morale); tag.putInt("Experience", experience); tag.putString("Gear", gear.name()); tag.putLong("Supply", supply);
            tag.putLong("MilitaryTime", militaryTime); tag.putLong("ShortageTicks", shortageTicks); tag.putInt("Fatigue", fatigue);
            tag.putBoolean("Retreating", retreating);
            tag.putBoolean("PendingDeclaration", pendingDeclaration);
            tag.putBoolean("MovedStrategically", movedStrategically);
            if (detailedRoster != null) tag.put("DetailedRoster", detailedRoster.copy());
            if (targetState != null) tag.putString("TargetState", targetState.toString());
            if (targetSettlement != null) tag.putString("TargetSettlement", targetSettlement.toString());
            tag.putLongArray("Route", route.stream().mapToLong(Long::longValue).toArray());
            tag.putLongArray("SupplyCorridor", supplyCorridor.stream().mapToLong(Long::longValue).toArray());
            return tag;
        }
        public static StrategicForce fromNBT(CompoundTag tag) {
            if (tag.getInt("Version").orElse(0) != 1) throw new IllegalArgumentException("Unsupported aggregate roster");
            StrategicForce force = new StrategicForce(UUID.fromString(tag.getString("Id").orElseThrow()), UUID.fromString(tag.getString("Owner").orElseThrow()),
                    UUID.fromString(tag.getString("Home").orElseThrow()), BlockPos.of(tag.getLong("Position").orElseThrow()));
            force.infantry = Math.clamp(tag.getInt("Infantry").orElse(0), 0, 500); force.cavalry = Math.clamp(tag.getInt("Cavalry").orElse(0), 0, 50);
            force.morale = Math.clamp(tag.getInt("Morale").orElse(0), 0, 100); force.experience = Math.max(0, tag.getInt("Experience").orElse(0));
            force.gear = GearTier.valueOf(tag.getString("Gear").orElse("STONE"));
            force.supply = Math.clamp(tag.getLong("Supply").orElse(0L), 0, force.capacity());
            force.militaryTime = Math.max(-1, tag.getLong("MilitaryTime").orElse(-1L));
            force.shortageTicks = Math.clamp(tag.getLong("ShortageTicks").orElse(0L), 0, StateBalance.TICKS_PER_DAY - 1);
            force.fatigue = Math.clamp(tag.getInt("Fatigue").orElse(0), 0, StateBalance.MAX_SUPPLY_FATIGUE);
            force.targetState = tag.getString("TargetState").map(UUID::fromString).orElse(null);
            force.targetSettlement = tag.getString("TargetSettlement").map(UUID::fromString).orElse(null);
            force.retreating = tag.getBoolean("Retreating").orElse(false);
            force.pendingDeclaration = tag.getBoolean("PendingDeclaration").orElse(false);
            // Legacy mobile rosters conservatively retain their saved position.
            force.movedStrategically = tag.getBoolean("MovedStrategically").orElse(tag.getLong("MilitaryTime").orElse(-1L) >= 0);
            force.detailedRoster = tag.getCompound("DetailedRoster").map(CompoundTag::copy).orElse(null);
            long[] route = tag.getLongArray("Route").orElse(new long[0]), corridor = tag.getLongArray("SupplyCorridor").orElse(new long[0]);
            if (route.length > 16 || corridor.length > 16) throw new IllegalArgumentException("Oversized saved macro corridor");
            force.route = java.util.Arrays.stream(route).boxed().toList(); force.supplyCorridor = java.util.Arrays.stream(corridor).boxed().toList();
            return force;
        }
    }
    //end of abstract npc army, below are npc army details, which are only loaded when a player is near the army

    //registers the distant army
    public boolean registerStrategicForce(StrategicForce force) {
        if (activeLegions.containsKey(force.id()) || strategicForces.putIfAbsent(force.id(), force) != null) return false;
        stateToStrategicIndex.computeIfAbsent(force.owner(), ignored -> new ArrayList<>()).add(force.id());
        setDirty(); return true;
    }

    //"promotes" the distant army to a full army
    /** Both representations live in this SavedData, so one save installs exactly one authority. */
    public boolean promoteForce(ServerLevel level, UUID id) {
        StrategicForce force = strategicForces.get(id);
        if (force == null || StateManager.get(level).getAIWars().blocks(force.owner())) return false;
        StrategicForce candidate = force.copy(); SettlementData home = StateManager.get(level).getSettlement(force.home());
        if (!force.movedStrategically && force.detailedRoster == null && home != null && home.isAbstractSettlement() && home.isCenterVerified()
                && force.owner().equals(home.getOwningStateId()) && near(force.position(), home.getCenterAltarPos(), StateBalance.SETTLEMENT_RADIUS)) candidate.position = home.getCenterAltarPos();
        BlockPos at = candidate.position();
        if (MapDataService.deploymentFormationStatus(level, at.getX() + .5, at.getZ() + .5) != MapDataService.Verification.VERIFIED) return false;
        Legion legion = detailedCandidate(candidate);
        for (Cohort c : legion.allCohorts()) if (c.isAlive() && MapDataService.deploymentFormationStatus(level, c.getPreciseX(), c.getPreciseZ()) != MapDataService.Verification.VERIFIED) return false;
        if (activeLegions.containsKey(id) || legion.allCohorts().stream().anyMatch(c -> cohortRegistry.containsKey(c.getCohortId()))) return false;
        // No recruitment, payment, healing or supply refill occurs at this boundary.
        removeStrategicForce(force); detailedOrigins.put(id, candidate); registerLegion(legion); setDirty(); return true;
    }

    /** Detached candidate only: publishing it still requires real terrain through promoteForce. */
    public static Legion detailedCandidate(StrategicForce force) {
        UUID id = force.id(); BlockPos at = force.position();
        Legion legion;
        if (force.detailedRoster != null) {
            legion = Legion.fromNBT(force.detailedRoster);
            int moraleDelta = force.morale() - legion.getAverageMorale();
            int xpDelta = Math.max(0, force.experience() - legion.allCohorts().stream().mapToInt(Cohort::getXp).max().orElse(0));
            CompoundTag updated = legion.toNBT();
            for (String kind : List.of("InfantryCohorts", "CavalrySquadrons")) {
                ListTag list = updated.getListOrEmpty(kind); int current = 0;
                for (var value : list) current += ((CompoundTag) value).getInt("SoldierCount").orElse(0);
                int remainingLoss = Math.max(0, current - (kind.equals("InfantryCohorts") ? force.infantry() : force.cavalry()));
                for (var value : list) {
                    CompoundTag tag = (CompoundTag) value; int soldiers = tag.getInt("SoldierCount").orElse(0);
                    int loss = current == soldiers ? remainingLoss : current == 0 ? 0 : (int) ((long) remainingLoss * soldiers / current);
                    tag.putInt("SoldierCount", soldiers - loss); remainingLoss -= loss; current -= soldiers;
                    tag.putInt("Morale", Math.clamp(tag.getInt("Morale").orElse(0) + moraleDelta, 0, 100));
                    tag.putInt("XP", (int) Math.min(Integer.MAX_VALUE, (long) tag.getInt("XP").orElse(0) + xpDelta));
                    if (xpDelta > 0) tag.putString("GearTier", force.gear().name());
                }
            }
            legion = Legion.fromNBT(updated);
            long stored = legion.allCohorts().stream().mapToLong(Cohort::getCarriedSupplySoldierTicks).sum();
            if (stored != force.supply()) {
                long stock = force.supply(); int people = legion.getTotalSoldiers();
                for (Cohort c : legion.allCohorts()) { long share = people == c.getSoldierCount() ? stock : people == 0 ? 0 : stock * c.getSoldierCount() / people;
                    c.setCarriedSupplySoldierTicks(share); stock -= share; people -= c.getSoldierCount(); }
            }
            int fatigueDelta = force.fatigue() - legion.allCohorts().stream().mapToInt(Cohort::getLogisticsFatigue).max().orElse(0);
            for (Cohort c : legion.allCohorts()) c.setLogisticsFatigue(c.getLogisticsFatigue() + fatigueDelta);
            if (!legion.getStoredPosition().equals(at)) {
                legion.setStoredPosition(at); legion.allCohorts().forEach(c -> c.setStoredPosition(at));
            }
        }
        else {
            legion = new Legion(force.id(), force.owner(), at);
            int foot = force.infantry(), horse = force.cavalry(), index = 0;
            long stock = force.supply(); int soldiers = force.soldiers();
            while (foot > 0 || horse > 0) {
                boolean cavalry = foot == 0;
                int count = Math.min(cavalry ? 10 : 50, cavalry ? horse : foot);
                Cohort cohort = new Cohort(UUID.nameUUIDFromBytes((id + ":" + index++).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        cavalry ? CohortType.CAVALRY : CohortType.INFANTRY, "plains");
                CompoundTag tag = cohort.toNBT(); tag.putInt("SoldierCount", count); tag.putInt("Morale", force.morale());
                tag.putInt("XP", force.experience()); tag.putString("GearTier", force.gear().name());
                tag.putInt("LogisticsFatigue", force.fatigue()); tag.putInt("LogisticsVersion", 1);
                long share = soldiers == count ? stock : stock * count / soldiers;
                tag.putLong("CarriedSupplySoldierTicks", share); cohort = Cohort.fromNBT(tag); cohort.setStoredPosition(at);
                stock -= share; soldiers -= count;
                if (cavalry) { horse -= count; legion.addCavalrySquadron(cohort); }
                else { foot -= count; legion.addInfantryCohort(cohort); }
            }
        }
        return legion;
    }

    public boolean isPromoted(UUID id) { return detailedOrigins.containsKey(id); }
    /** Server-side AI orders use the same terrain and border rules as player movement. */
    public boolean orderArmy(ServerLevel level, UUID owner, UUID id, BlockPos target) {
        Army army = getArmy(id); StateManager states = StateManager.get(level);
        if (army == null || !owner.equals(army.getOwningStateId()) || army.isEngaged() || army.isRetreating() || states.getAIWars().blocks(owner)) return false;
        UUID destination = ClaimManager.get(level).getStrategicOwner(ChunkPos.containing(target));
        if (destination != null && !destination.equals(owner) && !states.hasPassage(owner, destination)
                && !states.mayInitiateHostility(level, owner, destination)) return false;
        var route = MapDataService.route(level, army.getStoredPosition(), target, 128);
        if (route.status() != MapDataService.Verification.VERIFIED) return false;
        army.clearWaypoints(); routeTurns(route.points()).forEach(army::addWaypoint); setDirty(); return true;
    }
    public boolean demoteForce(UUID id, long now) {
        Legion legion = getLegion(id); StrategicForce origin = detailedOrigins.get(id);
        if (legion == null || origin == null || !canDissolveLegion(legion) || !legion.getWaypoints().isEmpty() || legion.getAuxiliaries().stream().anyMatch(Cohort::isAlive)
                || legion.allCohorts().stream().anyMatch(c -> c.isGarrisoned() || c.isRetreating() || !c.getWaypoints().isEmpty()
                || c.getMarchState().contains("CrossingStart") || c.isDetached() || !near(legion.getStoredPosition(), c.getStoredPosition(), ASSEMBLY_RANGE))) return false;
        StrategicForce force = origin.copy(); force.position = legion.getStoredPosition(); force.clearOrder();
        force.infantry = legion.getInfantryCohorts().stream().mapToInt(Cohort::getSoldierCount).sum();
        force.cavalry = legion.getCavalrySquadrons().stream().mapToInt(Cohort::getSoldierCount).sum();
        force.morale = legion.getAverageMorale(); force.experience = legion.allCohorts().stream().mapToInt(Cohort::getXp).max().orElse(0);
        force.gear = legion.allCohorts().stream().map(Cohort::getGearTier).max(Comparator.naturalOrder()).orElse(GearTier.STONE);
        force.supply = legion.allCohorts().stream().mapToLong(Cohort::getCarriedSupplySoldierTicks).sum();
        force.fatigue = legion.allCohorts().stream().mapToInt(Cohort::getLogisticsFatigue).max().orElse(0);
        force.militaryTime = now; force.detailedRoster = legion.toNBT();
        disbandLegion(id); detailedOrigins.remove(id); if (force.soldiers() > 0) registerStrategicForce(force); setDirty(); return true;
    }
    public void releaseForDemotion(UUID id) {
        Legion legion = getLegion(id); if (legion == null) return;
        for (Cohort c : legion.allCohorts()) if (!c.isDeployed() && !c.isGarrisoned() && !c.isRetreating() && c.getWaypoints().isEmpty()
                && near(legion.getStoredPosition(), c.getStoredPosition(), ASSEMBLY_RANGE)) c.setDetached(false);
    }

    public boolean interactionStable(UUID owner) {
        return getArmiesForState(owner).stream().noneMatch(a -> a.isEngaged() || a.isRetreating())
                && getLegionsForState(owner).stream().flatMap(l -> l.allCohorts().stream()).noneMatch(c -> c.isRetreating() || c.getMarchState().contains("CrossingStart"));
    }
    public void endDonorOrders(UUID owner) {
        activeCampaigns.values().removeIf(c -> owner.equals(c.getOwningStateId()));
        for (Army army : getArmiesForState(owner)) { army.setCampaignId(null); army.clearWaypoints(); }
        for (Legion legion : getLegionsForState(owner)) { legion.clearWaypoints(); legion.allCohorts().forEach(Cohort::clearWaypoints); }
        setDirty();
    }
    public long getInteractionSequence() { return interactionSequence; }
    public CompoundTag interactionSnapshot(Set<UUID> owners) {
        CompoundTag all = toTag(), result = new CompoundTag();
        for (String key : List.of("Legions", "Armies", "Campaigns", "StrategicForces", "DetailedOrigins")) {
            ListTag list = new ListTag(); String field = key.equals("StrategicForces") || key.equals("DetailedOrigins") ? "Owner" : "OwningStateId";
            for (var value : all.getListOrEmpty(key)) { CompoundTag tag = (CompoundTag) value;
                if (owners.contains(UUID.fromString(tag.getString(field).orElseThrow()))) list.add(tag.copy()); }
            result.put(key, list);
        }
        return result;
    }
    /** Replay uses the frozen participant snapshot, preserving losses from before a split save. */
    public void applySurrender(long sequence, UUID donor, UUID recipient, CompoundTag snapshot) {
        installInteractionSnapshot(sequence, donor, recipient, snapshot, true);
    }
    public void applyProvinceSnapshot(long sequence, UUID donor, UUID recipient, CompoundTag snapshot) {
        installInteractionSnapshot(sequence, donor, recipient, snapshot, false);
    }
    private void installInteractionSnapshot(long sequence, UUID donor, UUID recipient, CompoundTag snapshot, boolean surrender) {
        if (interactionSequence >= sequence) return;
        Set<UUID> owners = Set.of(donor, recipient);
        for (UUID owner : owners) {
            for (Army army : getArmiesForState(owner)) { activeArmies.remove(army.getArmyId()); removeIndex(stateToArmyIndex, owner, army.getArmyId()); }
            for (Legion legion : getLegionsForState(owner)) disbandLegion(legion.getLegionId());
            for (StrategicForce force : getStrategicForcesForState(owner)) removeStrategicForce(force);
        }
        activeCampaigns.values().removeIf(c -> owners.contains(c.getOwningStateId()));
        detailedOrigins.values().removeIf(f -> owners.contains(f.owner()));
        CompoundTag restored = snapshot.copy();
        for (String key : List.of("Legions", "Armies", "Campaigns", "StrategicForces", "DetailedOrigins")) {
            String field = key.equals("StrategicForces") || key.equals("DetailedOrigins") ? "Owner" : "OwningStateId";
            ListTag list = new ListTag();
            for (var value : restored.getListOrEmpty(key)) {
                CompoundTag tag = ((CompoundTag) value).copy(); boolean transferred = surrender && donor.toString().equals(tag.getString(field).orElse(""));
                if (transferred && key.equals("Campaigns")) continue;
                if (transferred) {
                    tag.putString(field, recipient.toString());
                    tag.remove("Waypoints"); tag.remove("CampaignId"); tag.remove("TargetState"); tag.remove("TargetSettlement"); tag.remove("Route"); tag.remove("SupplyCorridor");
                    tag.putBoolean("PendingDeclaration", false);
                    if (key.equals("Legions")) for (String cohorts : List.of("InfantryCohorts", "CavalrySquadrons", "Auxiliaries"))
                        for (var c : tag.getListOrEmpty(cohorts)) ((CompoundTag) c).remove("Waypoints");
                    tag.getCompound("DetailedRoster").ifPresent(roster -> roster.putString("OwningStateId", recipient.toString()));
                }
                list.add(tag);
            }
            restored.put(key, list);
        }
        ArmyManager rebuilt = fromTag(restored);
        rebuilt.activeLegions.values().forEach(this::registerLegion); rebuilt.strategicForces.values().forEach(this::registerStrategicForce);
        rebuilt.activeArmies.values().forEach(this::registerArmy); activeCampaigns.putAll(rebuilt.activeCampaigns); detailedOrigins.putAll(rebuilt.detailedOrigins);
        interactionSequence = sequence; setDirty();
    }
    public Collection<StrategicForce> getStrategicForces() { return Collections.unmodifiableCollection(strategicForces.values()); }
    public List<StrategicForce> getStrategicForcesForState(UUID owner) {
        return stateToStrategicIndex.getOrDefault(owner, List.of()).stream().map(strategicForces::get).filter(Objects::nonNull).toList();
    }
    private void removeStrategicForce(StrategicForce force) {
        strategicForces.remove(force.id()); removeIndex(stateToStrategicIndex, force.owner(), force.id()); setDirty();
    }
    public boolean hasAIInitialization(UUID id) { return aiInitializations.contains(id); }
    public long getMacroSequence() { return macroSequence; }
    /** Whole participant snapshots make a prepared outcome recoverable even from a prior financial snapshot. */
    public void applyMacroForces(long sequence, Set<UUID> owners, List<StrategicForce> after) {
        if (macroSequence >= sequence) return;
        for (UUID owner : owners) if (!getLegionsForState(owner).isEmpty() || !getArmiesForState(owner).isEmpty()) throw new IllegalStateException("Detailed force requires handoff");
        for (StrategicForce force : after) {
            StrategicForce existing = strategicForces.get(force.id());
            if (!owners.contains(force.owner()) || activeLegions.containsKey(force.id()) || existing != null && !owners.contains(existing.owner()))
                throw new IllegalStateException("Macro roster identity conflict");
        }
        for (UUID owner : owners) for (StrategicForce force : getStrategicForcesForState(owner)) removeStrategicForce(force);
        after.forEach(this::registerStrategicForce); macroSequence = sequence; setDirty();
    }
    public void recordAIInitialization(UUID id) { if (aiInitializations.add(id)) setDirty(); }
    public long getTotalSoldiersForState(UUID owner) {
        return getLegionsForState(owner).stream().mapToLong(Legion::getTotalSoldiers).sum()
                + getStrategicForcesForState(owner).stream().mapToLong(StrategicForce::soldiers).sum();
    }
    public void applyStrategicEconomy(StateData state, StateManager states, boolean paid) {
        if (state.isInitializationPending()) return;
        for (StrategicForce force : getStrategicForcesForState(state.getStateId())) {
            SettlementData home = states.getSettlement(force.home());
            boolean supplied = home != null && state.getStateId().equals(home.getOwningStateId()) && home.isSupplyReady();
            if (force.militaryTime() >= 0) supplied = !force.supplyCorridor().isEmpty();
            force.economicDay(paid, state.getUnpaidDays(), supplied);
            // Keep the initialization receipt after destruction; retries cannot recreate expended starting troops.
            if (force.soldiers() == 0) removeStrategicForce(force);
        }
        setDirty();
    }

    /*
    
    END OF ALL DISTANT AI/NPC ARMY METHODS
    
    */
    /** Creates a whole legion; the shared recruitment operation charges or grants authorized purchases. */
    public Optional<Legion> raiseLegion(StateData state, BlockPos position) {
        if (state.isInitializationPending()) return Optional.empty();
        if (state.hasSurrenderOverflow()) return Optional.empty();
        if (getLegionCountForState(state.getStateId()) >= state.getCurrentTier().getMaxLegions()) return Optional.empty();
        Legion legion = new Legion(UUID.randomUUID(), state.getStateId(), position);
        for (int i = 0; i < Legion.MAX_INFANTRY_COHORTS; i++) legion.addInfantryCohort(Cohort.createInfantry());
        for (int i = 0; i < Legion.MAX_CAVALRY_SQUADRONS; i++) legion.addCavalrySquadron(Cohort.createCavalrySquadron());
        registerLegion(legion);
        return Optional.of(legion);
    }

    public record RecruitmentResult(UUID legion, boolean free, String error) {
        public boolean succeeded() { return legion != null; }
    }

    /** Shared paid recruitment; the player grant remains restricted to the registered human founder. */
    public RecruitmentResult recruitLegion(ServerLevel level, UUID owner, UUID site, UUID founder) {
        StateManager states = StateManager.get(level);
        StateData state = states.getState(owner);
        SettlementData settlement = states.getSettlement(site);
        boolean free = founder != null && state != null && state == states.getStateByPlayer(founder)
                && states.canClaimFreeLegion(founder);
        String error = recruitmentProblem(state, settlement, getLegionCountForState(owner), free,
                settlement != null && hasHostilePresence(owner, settlement.getCenterAltarPos(), level));
        if (!error.isEmpty()) return new RecruitmentResult(null, false, error);
        Optional<Legion> raised = raiseLegion(state, settlement.getCenterAltarPos());
        if (raised.isEmpty()) return new RecruitmentResult(null, false, "Your state's legion limit has been reached.");
        if (free) states.claimFirstLegionGrant(founder);
        else state.deductFunds(LEGION_RECRUIT_COST);
        states.setDirty();
        return new RecruitmentResult(raised.get().getLegionId(), free, "");
    }

    /** Pure eligibility is also used by the AI scorer; the committing operation repeats these checks. */
    public static String recruitmentProblem(StateData state, SettlementData settlement, int legions, boolean free, boolean threatened) {
        if (state == null || settlement == null || !state.getStateId().equals(settlement.getOwningStateId())) return "Settlement not owned by your state.";
        if (state.isInitializationPending()) return "This nation is still being initialized.";
        if (state.hasSurrenderOverflow()) return "Qualify for your inherited territory and forces before recruiting more.";
        if (!settlement.isCenterVerified()) return "This settlement's operational center has not been verified.";
        if (threatened) return "Cannot recruit while hostile forces threaten this settlement.";
        if (legions >= state.getCurrentTier().getMaxLegions()) return "Your state's legion limit has been reached.";
        if ((!free || state.isAI()) && state.getTreasuryBalance() < LEGION_RECRUIT_COST) return "A legion costs 5,000 emeralds.";
        return "";
    }

    public void registerLegion(Legion legion) {
        if (strategicForces.containsKey(legion.getLegionId())) return;
        if (activeLegions.putIfAbsent(legion.getLegionId(), legion) != null) return;
        stateToLegionIndex.computeIfAbsent(legion.getOwningStateId(), ignored -> new ArrayList<>()).add(legion.getLegionId());
        for (Cohort cohort : legion.allCohorts()) {
            cohortRegistry.put(cohort.getCohortId(), cohort);
            cohortParents.put(cohort.getCohortId(), legion.getLegionId());
        }
        setDirty();
    }

    //register cohort in the army manager
    public void registerCohort(Cohort cohort) {
        cohortRegistry.put(cohort.getCohortId(), cohort);
        for (Legion legion : activeLegions.values()) {
            if (legion.allCohorts().contains(cohort)) cohortParents.put(cohort.getCohortId(), legion.getLegionId());
        }
        setDirty();
    }

    /** Forced removal for destruction/desertion. Player requests must pass canDissolveLegion first. */
    public boolean disbandLegion(UUID legionId) {
        Legion legion = activeLegions.remove(legionId);
        if (legion == null) return false;
        detailedOrigins.remove(legionId);
        for (Cohort cohort : legion.allCohorts()) {
            Army army = getArmy(cohort.getAssignedArmyId());
            if (army != null) army.removeCohortId(cohort.getCohortId());
            cohortRegistry.remove(cohort.getCohortId());
            cohortParents.remove(cohort.getCohortId());
        }
        removeIndex(stateToLegionIndex, legion.getOwningStateId(), legionId);
        setDirty();
        return true;
    }

    //checks if the legion can be dissolved
    public boolean canDissolveLegion(Legion legion) {
        // The wrapper must be removed even after its campaign has ended.
        return legion.allCohorts().stream().noneMatch(Cohort::isDeployed) && activeArmies.values().stream().noneMatch(a -> a.getParticipatingLegionIds().contains(legion.getLegionId()));
    }

    //raise army function
    //grabs the state ID, ignored client position, and list of cohort IDs to raise an army
    //validates the selection and returns an Optional Army if successful
    public Optional<Army> raiseArmy(UUID stateId, BlockPos ignoredClientPosition, List<UUID> cohortIds) {
        if (cohortIds.isEmpty() || cohortIds.size() > 150 || new HashSet<>(cohortIds).size() != cohortIds.size()) return Optional.empty();
        Cohort first = resolveCohort(cohortIds.getFirst());
        if (first == null) return Optional.empty();
        Army army = new Army(UUID.randomUUID(), stateId, first.getStoredPosition());
        army.setPrecisePos(first.getPreciseX(), first.getPreciseZ());
        for (UUID id : cohortIds) {
            Cohort cohort = resolveCohort(id);
            Legion parent = getParentLegion(id);
            if (cohort == null || parent == null || !stateId.equals(parent.getOwningStateId())
                    || !cohort.isAlive() || cohort.isDeployed() || cohort.isGarrisoned() || cohort.isRetreating()
                    || !near(first.getStoredPosition(), cohort.getStoredPosition(), ASSEMBLY_RANGE)
                    || !army.canAddCohort(cohort, this)) return Optional.empty();
            army.addCohortId(id); // Local, unpublished candidate only.
        }
        for (UUID id : cohortIds) {
            Cohort cohort = resolveCohort(id);
            cohort.setAssignedArmyId(army.getArmyId());
            cohort.setDetached(true); cohort.setRegrouping(false);
            cohort.clearWaypoints();
        }
        registerArmy(army);
        activeLegions.values().forEach(Legion::syncMainPosition);
        return Optional.of(army);
    }

    //register the army in the army manager
    public void registerArmy(Army army) {
        if (activeArmies.putIfAbsent(army.getArmyId(), army) != null) return;
        stateToArmyIndex.computeIfAbsent(army.getOwningStateId(), ignored -> new ArrayList<>()).add(army.getArmyId());
        for (UUID id : army.getDeployedCohortIds()) {
            Cohort cohort = resolveCohort(id);
            if (cohort != null) {
                cohort.setAssignedArmyId(army.getArmyId());
                Legion parent = getParentLegion(id);
                if (parent != null) army.rememberLegion(parent.getLegionId());
                if (!cohort.hasStoredPosition()) cohort.setStoredPosition(army.getStoredPosition());
            }
        }
        setDirty();
    }

    //disband army function: removes the army from the manager and releases its cohorts in place
    public boolean disbandArmy(UUID armyId, boolean forceDisband) {
        Army army = getArmy(armyId);
        if (army == null || (!forceDisband && (army.isEngaged() || army.isOnCampaign() || army.isRetreating()))) return false;
        for (UUID id : army.getDeployedCohortIds()) {
            Cohort cohort = resolveCohort(id);
            if (cohort != null && armyId.equals(cohort.getAssignedArmyId())) {
                cohort.setAssignedArmyId(null); cohort.setDetached(true); cohort.setRegrouping(false);
            }
        }
        activeArmies.remove(armyId);
        removeIndex(stateToArmyIndex, army.getOwningStateId(), armyId);
        Campaign campaign = getCampaign(army.getCampaignId());
        if (campaign != null) {
            campaign.removeArmy(armyId);
            if (campaign.isEmpty()) activeCampaigns.remove(campaign.getCampaignId());
        }
        activeCampaigns.values().stream().filter(Campaign::isPlanned).forEach(plan -> plan.removeArmy(armyId));
        activeLegions.values().forEach(Legion::syncMainPosition);
        setDirty();
        return true;
    }

    //cohort detachment function: detaches the given cohorts from their parent legions and releases them in place, if they are not deployed or garrisoned
    public boolean detachCohorts(UUID owner, List<UUID> ids) {
        if (ids.isEmpty() || ids.size() > 150 || new HashSet<>(ids).size() != ids.size()) return false;
        for (UUID id : ids) {
            Cohort c = resolveCohort(id); Legion parent = getParentLegion(id);
            if (c == null || parent == null || !owner.equals(parent.getOwningStateId()) || !c.isAlive() || c.isRetreating() || c.isGarrisoned()) return false;
            Army a = getArmy(c.getAssignedArmyId());
            if (a != null && (a.isEngaged() || a.isRetreating() || a.isOnCampaign())) return false;
        }
        Set<UUID> releasedArmies = new HashSet<>();
        for (UUID id : ids) {
            Cohort c = resolveCohort(id); Army a = getArmy(c.getAssignedArmyId());
            if (a != null) { a.removeCohortId(id); releasedArmies.add(a.getArmyId()); }
            c.setAssignedArmyId(null); c.setDetached(true); c.setRegrouping(false); c.clearWaypoints();
        }
        // A fully detached peacetime wrapper has no remaining formation to display.
        for (UUID id : releasedArmies) {
            Army a = getArmy(id);
            if (a != null && a.getDeployedCohortIds().isEmpty()) disbandArmy(id, false);
        }
        activeLegions.values().forEach(Legion::syncMainPosition);
        setDirty(); return true;
    }

    //cohort waypoint order function: orders the given cohorts to move to the given position, if they are not deployed or garrisoned
    public boolean orderCohorts(UUID owner, List<UUID> ids, BlockPos target, boolean queue) {
        if (ids.isEmpty() || ids.size() > 150 || new HashSet<>(ids).size() != ids.size()) return false;
        // Check queue bounds before detaching; append preserves each cohort's existing orders.
        for (UUID id : ids) {
            Cohort c = resolveCohort(id); Legion parent = getParentLegion(id);
            if (c == null || parent == null || !owner.equals(parent.getOwningStateId()) || !c.isAlive()
                    || c.isDeployed() || c.isGarrisoned() || c.isRetreating() || queue && c.getWaypoints().size() >= MAX_WAYPOINTS) return false;
        }
        for (UUID id : ids) {
            Cohort c = resolveCohort(id);
            c.setDetached(true); c.setRegrouping(false);
            if (!queue) c.clearWaypoints();
            c.addWaypoint(target);
        }
        setDirty(); return true;
    }

    //cohort regroup function: orders the given cohorts to regroup with their parent legions, if they are not deployed or garrisoned
    //parent legion is currently set to the "main" cohort, which is the 1st cohort
    public boolean regroupLegion(UUID owner, UUID legionId) {
        Legion legion = getLegion(legionId);
        if (legion == null || !owner.equals(legion.getOwningStateId())) return false;
        Cohort anchor = legion.getFieldCohort();
        if (anchor == null) anchor = legion.allCohorts().stream().filter(c -> c.isAlive() && !c.isDeployed()
                && !c.isGarrisoned() && !c.isRetreating()).findFirst().orElse(null);
        if (anchor == null) return false;
        anchor.setDetached(false); anchor.setRegrouping(false); anchor.clearWaypoints(); legion.clearWaypoints();
        for (Cohort c : legion.allCohorts()) if (c != anchor && c.isAlive() && !c.isDeployed() && !c.isGarrisoned() && !c.isRetreating()) {
            c.clearWaypoints(); c.addWaypoint(anchor.getStoredPosition()); c.setRegrouping(true);
        }
        legion.syncMainPosition(); setDirty(); return true;
    }

    /** Shorten old saved retreats without moving a unit, extending a shorter order or resetting exclusion. */
    public void shortenRetreats(int maximum) {
        for (Army a : activeArmies.values()) if (a.isRetreating()) {
            BlockPos target = shortenedRetreat(a.getPreciseX(), a.getPreciseZ(), a.getRetreatTarget(), maximum);
            if (!target.equals(a.getRetreatTarget())) a.beginRetreat(target, a.getRetreatOpponent());
        }
        for (Cohort c : cohortRegistry.values()) if (c.isRetreating()) {
            BlockPos target = shortenedRetreat(c.getPreciseX(), c.getPreciseZ(), c.getRetreatTarget(), maximum);
            if (!target.equals(c.getRetreatTarget())) c.beginRetreat(target, c.getRetreatOpponent());
        }
    }
    private static BlockPos shortenedRetreat(double x, double z, BlockPos target, int maximum) {
        double dx = target.getX() + .5 - x, dz = target.getZ() + .5 - z, distance = Math.hypot(dx, dz);
        if (distance <= maximum) return target;
        double span = Math.max(0, maximum - 1); // Leave room for block-center rounding.
        return new BlockPos((int)Math.floor(x + dx / distance * span), target.getY(), (int)Math.floor(z + dz / distance * span));
    }

    //if a legion gets in an engagement "unprovoked," wrap it in an army to participate in the battle and campaign
    public Optional<Army> autoWrapLegionInArmy(Legion legion) {
        Cohort main = legion.getFieldCohort();
        if (main == null || main.isDeployed() || main.isGarrisoned()) return Optional.empty();
        List<UUID> ids = new ArrayList<>();
        ids.add(main.getCohortId()); // Use the same physical anchor for the assembly-range check.
        legion.getFieldCohorts().stream().filter(c -> c != main).map(Cohort::getCohortId).forEach(ids::add);
        Optional<Army> army = raiseArmy(legion.getOwningStateId(), main.getStoredPosition(), ids);
        army.ifPresent(a -> {
            legion.getWaypoints().forEach(a::addWaypoint);
            legion.clearWaypoints();
        });
        return army;
    }

    //campaign creation function: creates a campaign for the given state and army, if one does not already exist
    public Campaign createCampaign(UUID stateId, UUID armyId) {
        Army army = getArmy(armyId);
        if (army == null || !stateId.equals(army.getOwningStateId())) throw new IllegalArgumentException("Army does not belong to state");
        Campaign existing = getCampaign(army.getCampaignId());
        if (existing != null && existing.isActive()) return existing;
        Campaign campaign = new Campaign(UUID.randomUUID(), stateId);
        campaign.describe("Campaign " + campaign.getCampaignId().toString().substring(0, 8), "", null);
        campaign.addArmy(armyId);
        army.setCampaignId(campaign.getCampaignId());
        activeCampaigns.put(campaign.getCampaignId(), campaign);
        setDirty();
        return campaign;
    }

    /** A saved plan never changes orders, diplomacy, territorial claims or the army's active lock. */
    public Optional<Campaign> saveCampaign(UUID owner, UUID id, String name, String objective, BlockPos focus, List<UUID> ids) {
        if (ids.size() > Campaign.MAX_ARMIES || new HashSet<>(ids).size() != ids.size()
                || name == null || name.length() > Campaign.MAX_NAME || objective == null || objective.length() > Campaign.MAX_OBJECTIVE) return Optional.empty();
        Campaign campaign = id == null ? null : getCampaign(id);
        if (id != null && (campaign == null || !owner.equals(campaign.getOwningStateId()))) return Optional.empty();
        if (campaign == null && getCampaignsForState(owner).stream().filter(Campaign::isPlanned).count() >= 128) return Optional.empty();
        for (UUID armyId : ids) {
            Army army = getArmy(armyId);
            if (army == null || !owner.equals(army.getOwningStateId())) return Optional.empty();
            if (campaign != null && campaign.isActive() && !campaign.getArmyIds().contains(armyId)
                    && !armyAssignmentProblem(army, null).isEmpty()) return Optional.empty();
        }
        // Active membership can grow, but removing a force must not bypass campaign ending checks.
        if (campaign != null && campaign.isActive() && !ids.containsAll(campaign.getArmyIds())) return Optional.empty();
        if (campaign == null) campaign = Campaign.plan(UUID.randomUUID(), owner);
        campaign.describe(name, objective, focus);
        if (campaign.isPlanned()) for (UUID previous : new HashSet<>(campaign.getArmyIds())) campaign.removeArmy(previous);
        for (UUID armyId : ids) {
            campaign.addArmy(armyId);
            if (campaign.isActive()) getArmy(armyId).setCampaignId(campaign.getCampaignId());
        }
        activeCampaigns.put(campaign.getCampaignId(), campaign);
        setDirty();
        return Optional.of(campaign);
    }

    //error text list
    public String armyAssignmentProblem(Army army, UUID sameCampaign) {
        if (army == null) return "Army is no longer available";
        if (!army.isViable(this)) return "Army has no surviving troops";
        if (army.isEngaged()) return "Army is in battle";
        if (army.isRetreating()) return "Army is retreating";
        if (army.isOnCampaign() && !Objects.equals(sameCampaign, army.getCampaignId())) return "Army is already on another campaign";
        return "";
    }

    //error list for campaigns
    public String campaignStartProblem(Campaign campaign) {
        if (campaign == null || !campaign.isPlanned()) return "This is not an inactive plan";
        if (campaign.isEmpty()) return "Assign at least one army";
        for (UUID id : campaign.getArmyIds()) {
            Army army = getArmy(id);
            if (army != null && !campaign.getOwningStateId().equals(army.getOwningStateId())) return "Army belongs to another state";
            String problem = armyAssignmentProblem(army, null);
            if (!problem.isEmpty()) return problem;
        }
        return "";
    }

    /** Validate the complete roster before assigning any active links. Armies need not be colocated. */
    public boolean startCampaign(UUID owner, UUID id) {
        Campaign campaign = getCampaign(id);
        if (campaign == null || !owner.equals(campaign.getOwningStateId()) || !campaignStartProblem(campaign).isEmpty()) return false;
        campaign.activate();
        campaign.getArmyIds().forEach(army -> getArmy(army).setCampaignId(id));
        setDirty();
        return true;
    }

    public boolean deleteCampaignPlan(UUID owner, UUID id) {
        Campaign campaign = getCampaign(id);
        if (campaign == null || !campaign.isPlanned() || !owner.equals(campaign.getOwningStateId())) return false;
        activeCampaigns.remove(id);
        setDirty();
        return true;
    }

    public boolean isActiveCampaign(Army army) {
        Campaign campaign = getCampaign(army.getCampaignId());
        return campaign != null && campaign.isActive() && campaign.getOwningStateId().equals(army.getOwningStateId())
                && campaign.getArmyIds().contains(army.getArmyId());
    }

    public String campaignEndProblem(Campaign campaign, ServerLevel level) {
        if (campaign == null || !campaign.isActive()) return "Campaign is not active";
        if (campaign.getArmyIds().stream().map(this::getArmy).filter(Objects::nonNull).anyMatch(Army::isRetreating)) return "An army is retreating";
        return campaign.checkEndConditions(this, level) ? "" : "A battle or hostile threat remains near your armies, conquered land, or settlements";
    }

    //campaign action handler function: handles the given campaign action payload and context, and performs the appropriate action on the campaign
    public static void handleCampaignAction(ArmyActionPayload.CampaignAction payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ServerLevel level = player.level().getServer().overworld();
            StateData state = StateManager.get(level).getStateByPlayer(player.getUUID());
            if (state == null || !controls(player, state.getStateId())) return;
            ArmyManager manager = get(level);
            UUID id = payload.campaignId(), owner = state.getStateId();
            Campaign campaign = id == null ? null : manager.getCampaign(id);
            if (id != null && (campaign == null || !owner.equals(campaign.getOwningStateId()))) return;
            switch (payload.action()) {
                case "SAVE" -> {
                    if (payload.focus() != null && !validTarget(level, payload.focus())) { tell(player, "Choose a map location inside the world border."); break; }
                    tell(player, manager.saveCampaign(owner, id, payload.name(), payload.objective(), payload.focus(), payload.armyIds()).isPresent()
                            ? "Campaign saved." : "Campaign was not saved. Check its armies; active members cannot be removed or taken from another operation.");
                }
                case "START" -> {
                    String problem = manager.campaignStartProblem(campaign);
                    tell(player, manager.startCampaign(owner, id) ? "Campaign started." : problem);
                }
                case "DELETE" -> { if (manager.deleteCampaignPlan(owner, id)) tell(player, "Campaign plan deleted."); }
                case "END" -> manager.endCampaign(id, player);
                default -> { }
            }
            com.devc.minecraftempires.network.ModNetworking.sendSnapshots(player);
        });
    }

    //end campaign function: ends the campaign for the given state and army, if one exists and is eligible to end
    public boolean endCampaign(UUID campaignId, ServerPlayer player) {
        Campaign campaign = getCampaign(campaignId);
        if (campaign == null || !campaign.isActive() || !controls(player, campaign.getOwningStateId())) return false;
        if (campaign.getArmyIds().stream().map(this::getArmy).filter(Objects::nonNull).anyMatch(Army::isRetreating)
                || !campaign.checkEndConditions(this, player.level())) {
            tell(player, "Cannot end campaign while a battle or hostile threat remains near your armies, conquered land, or settlements.");
            return false;
        }
        campaign.disbandCampaign(this);
        activeCampaigns.remove(campaignId);
        setDirty();
        tell(player, "Campaign ended."); //TODO: maybe send in chat a brief summary of what was conquered and what was lost, and any army changes
        return true;
    }

    //sync the positions of all cohorts in the army to the army's position, and update their parent legions
    public void syncCohortPositions(Army army) {
        Set<Legion> parents = new HashSet<>();
        for (UUID id : army.getDeployedCohortIds()) {
            Cohort cohort = resolveCohort(id);
            if (cohort == null) continue;
            if (!cohort.isGarrisoned()) {
                cohort.setStoredPosition(army.getStoredPosition());
                cohort.setPrecisePos(army.getPreciseX(), army.getPreciseZ());
            }
            Legion parent = getParentLegion(id);
            if (parent != null) parents.add(parent);
        }
        parents.forEach(Legion::syncMainPosition);
    }

    public void runGarbageCollection() {
        collectForces(new ArrayList<>(activeLegions.values()), new ArrayList<>(activeArmies.values()));
    }
    public void runGarbageCollectionForState(UUID owner) {
        collectForces(getLegionsForState(owner), getArmiesForState(owner));
    }
    private void collectForces(List<Legion> legions, List<Army> armies) {
        for (Legion legion : legions) {
            boolean inBattle = legion.allCohorts().stream().map(Cohort::getAssignedArmyId).map(this::getArmy)
                    .filter(Objects::nonNull).anyMatch(Army::isEngaged);
            if (!legion.isViable() && !inBattle) disbandLegion(legion.getLegionId());
            else legion.syncMainPosition();
        }
        for (Army army : armies) {
            boolean requiresRelease = army.hasParticipatedInCampaign()
                    && army.getParticipatingLegionIds().stream().anyMatch(activeLegions::containsKey);
            if (!army.isViable(this) && !army.isEngaged() && !requiresRelease) disbandArmy(army.getArmyId(), true);
        }
    }

    //tick armies function: called every five ticks to update the positions of all armies and legions, and check for engagements
    //ngl this function got so complicated idek whats going on anymore
    public void tickArmies(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) return;
        reconcileLocations(level);
        tickMoveProposals(level);
        for (Cohort cohort : new ArrayList<>(cohortRegistry.values())) {
            Army army = getArmy(cohort.getAssignedArmyId());
            Legion parent = getParentLegion(cohort.getCohortId());
            if (parent != null && StateManager.get(level).getAIWars().blocks(parent.getOwningStateId())) continue;
            if (cohort.isAlive() && (army == null || !army.isEngaged())) recoverCohortCrossing(level, cohort);
        }
        boolean changed = false;
        for (Army army : new ArrayList<>(activeArmies.values())) {
            if (StateManager.get(level).getAIWars().blocks(army.getOwningStateId())) continue;
            if (army.isEngaged() || !army.isViable(this)) continue;
            boolean retreating = army.isRetreating();
            if (retreating && army.getWaypoints().isEmpty()) army.addWaypoint(army.getRetreatTarget());
            List<Cohort> body = army.getDeployedCohortIds().stream().map(this::resolveCohort).filter(Objects::nonNull).filter(c -> c.isAlive() && !c.isGarrisoned()).toList();
            if (!army.getWaypoints().isEmpty() || body.stream().anyMatch(c -> !c.getWaypoints().isEmpty())) {
                moveBody(level, army.getOwningStateId(), body, army.getWaypoints(), retreating);
                if (!body.isEmpty()) army.setPrecisePos(body.getFirst().getPreciseX(), body.getFirst().getPreciseZ());
                if (retreating && body.stream().anyMatch(c -> c.getMarchState().getBoolean("BlockedRetreat").orElse(false))) army.clearRetreatOpponent();
                if (retreating) {
                    boolean separated = updateRetreatDisengagement(army);
                    boolean arrived = army.getWaypoints().isEmpty();
                    if (arrived) army.finishRetreat();
                    for (UUID id : army.getDeployedCohortIds()) {
                        Cohort cohort = resolveCohort(id);
                        if (cohort == null) continue;
                        if (separated) cohort.clearRetreatOpponent();
                        if (arrived) cohort.finishRetreat();
                    }
                } else enterTerritory(army, level);
                changed = true;
            }
        }
        for (Legion legion : new ArrayList<>(activeLegions.values())) {
            if (StateManager.get(level).getAIWars().blocks(legion.getOwningStateId())) continue;
            Cohort main = legion.getFieldCohort();
            if (main == null || main.isDeployed() || main.isGarrisoned() || main.isRetreating() || legion.getWaypoints().isEmpty()) continue;
            legion.syncMainPosition(); // Detaching the previous anchor must not move the remaining field body.
            List<Cohort> body = legion.allCohorts().stream().filter(c -> c.isAlive() && !c.isDeployed() && !c.isGarrisoned() && !c.isDetached()).toList();
            moveBody(level, legion.getOwningStateId(), body, legion.getWaypoints(), false);
            legion.syncMainPosition();
            if (needsCampaign(legion.getOwningStateId(), legion.getStoredPosition(), level))
                autoWrapLegionInArmy(legion).ifPresent(a -> enterTerritory(a, level));
            changed = true;
        }
        // Regrouping physically moves each detached cohort; it never teleports to its parent.
        for (Cohort cohort : new ArrayList<>(cohortRegistry.values())) {
            Legion lockedParent = getParentLegion(cohort.getCohortId());
            if (lockedParent != null && StateManager.get(level).getAIWars().blocks(lockedParent.getOwningStateId())) continue;
            if (!cohort.isAlive() || cohort.isDeployed() || cohort.isGarrisoned() || !cohort.isDetached()) continue;
            boolean retreating = cohort.isRetreating();
            if (retreating && cohort.getWaypoints().isEmpty()) cohort.addWaypoint(cohort.getRetreatTarget());
            if (cohort.getWaypoints().isEmpty()) continue;
            Legion parent = getParentLegion(cohort.getCohortId());
            if (parent == null) continue;
            moveCohort(level, parent.getOwningStateId(), cohort, retreating || cohort.isRegrouping());
            if (retreating) {
                Army opponent = getArmy(cohort.getRetreatOpponent());
                if (opponent == null || !near(cohort.getStoredPosition(), opponent.getStoredPosition(), ENGAGEMENT_RANGE)) cohort.clearRetreatOpponent();
                if (cohort.getWaypoints().isEmpty()) cohort.finishRetreat();
            }
            if (cohort.isRegrouping() && cohort.getWaypoints().isEmpty()) {
                Cohort anchor = parent.getFieldCohort();
                cohort.setRegrouping(false);
                if (anchor != null && near(anchor.getStoredPosition(), cohort.getStoredPosition(), ASSEMBLY_RANGE)) cohort.setDetached(false);
            }
            if (!retreating && needsCampaign(parent.getOwningStateId(), cohort.getStoredPosition(), level)) {
                List<BlockPos> remaining = new ArrayList<>(cohort.getWaypoints());
                raiseArmy(parent.getOwningStateId(), cohort.getStoredPosition(), List.of(cohort.getCohortId())).ifPresent(a -> {
                    remaining.forEach(a::addWaypoint);
                    enterTerritory(a, level);
                });
            }
            if (parent != null) parent.syncMainPosition();
            changed = true;
        }
        // Saved world time need not align with the server's five-tick callback after a reload.
        if (level.getGameTime() - lastEngagementCheck >= 20 || level.getGameTime() < lastEngagementCheck) {
            lastEngagementCheck = level.getGameTime();
            for (Army army : new ArrayList<>(activeArmies.values())) {
                if (StateManager.get(level).getAIWars().blocks(army.getOwningStateId())) continue;
                if (army.isEngaged() || !army.isViable(this)) continue;
                if (army.isOnCampaign() && !army.isRetreating()) {
                    Campaign campaign = getCampaign(army.getCampaignId());
                    if (campaign != null && isActiveCampaign(army)) {
                        List<ChunkPos> acquired = ClaimManager.get(level).claimCampaignArea(level, army);
                        acquired.forEach(pos -> campaign.trackConqueredChunk(pos.pack()));
                        changed |= !acquired.isEmpty();
                    }
                }
                changed |= checkCollisionAndEngage(army, level);
            }
            // A stationary legion or detached garrison must also react to an enemy.
            for (Legion legion : new ArrayList<>(activeLegions.values())) {
                for (Cohort cohort : legion.allCohorts()) {
                    if (!cohort.isAlive() || cohort.isDeployed() || cohort.isRetreating()) continue;
                    if (hasEnemyNear(legion.getOwningStateId(), cohort.getStoredPosition(), level)) {
                        Army defender = wrapNearbyDefenders(legion.getOwningStateId(), cohort.getStoredPosition(), null);
                        if (defender != null) changed |= checkCollisionAndEngage(defender, level);
                        break;
                    }
                }
            }
            refreshGarrisons(level);
            runGarbageCollection();
        }
        if (changed) setDirty();
    }

    /** Keep each cohort's saved route progress, allowing a long formation to queue across a crossing. */
    //cohort body movement tracking function: moves the given list of cohorts along their waypoints, if they are not deployed or garrisoned
    private void moveBody(ServerLevel level, UUID owner, List<Cohort> body, Queue<BlockPos> orders, boolean retreating) {
        if (body.isEmpty()) return;
        if (body.stream().allMatch(c -> c.getWaypoints().isEmpty()) && !orders.isEmpty()) for (Cohort cohort : body) cohort.getWaypoints().addAll(orders);
        for (Cohort cohort : body) {
            moveCohort(level, owner, cohort, retreating);
            if (cohort.isRetreating() && cohort.getWaypoints().isEmpty()) cohort.finishRetreat();
        }
        orders.clear();
        body.stream().filter(c -> !c.getWaypoints().isEmpty()).findFirst().ifPresent(c -> orders.addAll(c.getWaypoints()));
    }

    //cohort movement function: moves the given cohort along its waypoints, if it is not deployed or garrisoned
    private void moveCohort(ServerLevel level, UUID owner, Cohort cohort, boolean autonomous) {
        if (cohort.getWaypoints().isEmpty()) return;
        StateManager states = StateManager.get(level); StateData nation = states.getState(owner);
        UUID targetOwner = ClaimManager.get(level).getStrategicOwner(ChunkPos.containing(List.copyOf(cohort.getWaypoints()).getLast()));
        if (nation != null && nation.isAI() && !cohort.isRetreating() && cohort.getMarchState().contains("CrossingStart")
                && targetOwner != null && !owner.equals(targetOwner) && !states.hasPassage(owner, targetOwner) && !states.mayInitiateHostility(level, owner, targetOwner)) {
            BlockPos first = BlockPos.of(cohort.getMarchState().getLong("CrossingStart").orElseThrow());
            BlockPos last = BlockPos.of(cohort.getMarchState().getLong("CrossingEnd").orElseThrow());
            // Complete mandatory safety recovery on actual ground; this is never a follow-on capture order.
            BlockPos bank = java.util.stream.Stream.of(first, last).filter(p -> MapDataService.surface(level, p).usable())
                    .min(Comparator.comparingInt((BlockPos p) -> owner.equals(ClaimManager.get(level).getStrategicOwner(ChunkPos.containing(p))) ? 0 : 1)
                            .thenComparingDouble(p -> p.distSqr(cohort.getStoredPosition()))).orElse(null);
            if (bank != null) cohort.beginRetreat(bank, null);
        }
        if (autonomous || cohort.isRetreating()) rerouteAutonomous(level, cohort);
        CohortData shape = CohortData.fromCohort(cohort, cohort.getPreciseX(), cohort.getPreciseZ());
        CompoundTag originalShape = shape.marchState();
        boolean[] moved = {false};
        int previousWaypoints = cohort.getWaypoints().size();
        move(level, owner, cohort.getPreciseX(), cohort.getPreciseZ(), cohort.getWaypoints(), cohort.isRetreating(), (x, z) -> {
            if (!MapDataService.prepareStrategicFormation(level, shape, x, z)) return false;
            for (Cohort other : cohortRegistry.values()) {
                if (other == cohort || !other.isAlive() || !near(cohort.getStoredPosition(), other.getStoredPosition(), 40)) continue;
                CohortData obstacle = CohortData.fromCohort(other, other.getPreciseX(), other.getPreciseZ());
                if (!shape.isColumn() && !obstacle.isColumn()) continue;
                boolean overlap = CohortData.overlaps(shape, cohort.getPreciseX(), cohort.getPreciseZ(), obstacle, other.getPreciseX(), other.getPreciseZ(), .25);
                if (overlap && Math.hypot(x - other.getPreciseX(), z - other.getPreciseZ()) > Math.hypot(cohort.getPreciseX() - other.getPreciseX(), cohort.getPreciseZ() - other.getPreciseZ()) + 1.0e-8) continue;
                if (CohortData.overlaps(shape, x, z, obstacle, other.getPreciseX(), other.getPreciseZ(), .25)) return false;
            }
            shape.recordMovement(cohort.getPreciseX(), cohort.getPreciseZ()); cohort.setPrecisePos(x, z);
            moved[0] = true;
            return true;
        });
        if (!moved[0]) shape.readMarch(originalShape);
        CompoundTag march = shape.marchState();
        march.putBoolean("BlockedRetreat", cohort.getMarchState().getBoolean("BlockedRetreat").orElse(false)); cohort.setMarchState(march);
        if (!moved[0] && cohort.getWaypoints().size() == previousWaypoints) march.putString("TerrainStatus", cohort.isRetreatBlocked() ? "Retreat blocked" : shape.isColumn() ? "Waiting at crossing" : "Route blocked or unverified");
        cohort.setMarchState(march);
    }

    //cohort crossing recovery function: checks if the given cohort is in a crossing and recovers it if possible
    private void recoverCohortCrossing(ServerLevel level, Cohort cohort) {
        if (!cohort.getMarchState().contains("CrossingStart")) return;
        CohortData shape = CohortData.fromCohort(cohort, cohort.getPreciseX(), cohort.getPreciseZ());
        if (MapDataService.recoverCrossing(level, shape, 0, 0)) {
            cohort.applyTerrainLoss(cohort.getSoldierCount() - shape.getCurrentHealth());
            cohort.setPrecisePos(shape.getPosition().x, shape.getPosition().y); cohort.finishRetreat(); cohort.clearWaypoints();
            Army army = getArmy(cohort.getAssignedArmyId()); if (army != null) army.removeCohortId(cohort.getCohortId());
            cohort.setAssignedArmyId(null); cohort.setDetached(true); cohort.setRegrouping(false);
            CompoundTag march = shape.marchState(); march.putBoolean("Recovered", false); cohort.setMarchState(march); setDirty(); return;
        }
        if (shape.getCrossingStart() == null) { cohort.setMarchState(shape.marchState()); cohort.clearWaypoints(); setDirty(); }
    }

    //cohort autonomous rerouting function: checks if the given cohort is blocked and reroutes it if possible
    private void rerouteAutonomous(ServerLevel level, Cohort cohort) {
        BlockPos destination = cohort.getWaypoints().peek(); if (destination == null) return;
        var direct = MapDataService.verifySegment(level, cohort.getPreciseX(), cohort.getPreciseZ(), destination.getX() + .5, destination.getZ() + .5);
        if (direct == MapDataService.Verification.VERIFIED) {
            CompoundTag march = cohort.getMarchState(); march.putBoolean("BlockedRetreat", false); cohort.setMarchState(march); return;
        }
        var route = MapDataService.route(level, cohort.getStoredPosition(), destination, 128);
        if (route.status() == MapDataService.Verification.VERIFIED) {
            List<BlockPos> remaining = new ArrayList<>(cohort.getWaypoints()); remaining.removeFirst();
            CompoundTag march = cohort.getMarchState(); march.putBoolean("BlockedRetreat", false); cohort.setMarchState(march);
            cohort.clearWaypoints(); cohort.getWaypoints().addAll(routeTurns(route.points())); cohort.getWaypoints().addAll(remaining); return;
        }
        if (route.status() != MapDataService.Verification.BLOCKED || !cohort.isRetreating()) return;
        double dx = destination.getX() - cohort.getPreciseX(), dz = destination.getZ() - cohort.getPreciseZ(), length = Math.hypot(dx, dz);
        for (int distance = (int) Math.min(200, length) - 8; distance >= 8; distance -= 8) for (double angle : new double[]{0, Math.PI / 4, -Math.PI / 4, Math.PI / 2, -Math.PI / 2}) {
            double rx = (dx * Math.cos(angle) - dz * Math.sin(angle)) / Math.max(1, length), rz = (dx * Math.sin(angle) + dz * Math.cos(angle)) / Math.max(1, length);
            BlockPos closer = BlockPos.containing(cohort.getPreciseX() + rx * distance, 64, cohort.getPreciseZ() + rz * distance);
            Army enemy = getArmy(cohort.getRetreatOpponent());
            if (enemy != null && Math.hypot(closer.getX() - enemy.getPreciseX(), closer.getZ() - enemy.getPreciseZ()) <= Math.hypot(cohort.getPreciseX() - enemy.getPreciseX(), cohort.getPreciseZ() - enemy.getPreciseZ())) continue;
            if (MapDataService.verifySegment(level, cohort.getPreciseX(), cohort.getPreciseZ(), closer.getX() + .5, closer.getZ() + .5) == MapDataService.Verification.VERIFIED) {
                CompoundTag march = cohort.getMarchState(); march.putBoolean("BlockedRetreat", false); cohort.setMarchState(march);
                cohort.clearWaypoints(); cohort.addWaypoint(closer); return;
            }
        }
        CompoundTag march = cohort.getMarchState(); march.putBoolean("BlockedRetreat", true); cohort.setMarchState(march);
        cohort.clearRetreatOpponent();
    }

    //campaign needs check: determines if a campaign is needed for the given state and position
    private boolean needsCampaign(UUID stateId, BlockPos position, ServerLevel level) {
        UUID owner = ClaimManager.get(level).getStrategicOwner(ChunkPos.containing(position));
        return owner == null || (!stateId.equals(owner) && !StateManager.get(level).hasPassage(stateId, owner));
    }

    //territory crossing function: checks if the army is entering a foreign territory and declares war if necessary, then creates a campaign for the army
    private void enterTerritory(Army army, ServerLevel level) {
        BlockPos position = army.getStoredPosition();
        UUID owner = ClaimManager.get(level).getStrategicOwner(ChunkPos.containing(position));
        StateManager states = StateManager.get(level);
        if (owner != null && !army.getOwningStateId().equals(owner)) {
            if (states.hasPassage(army.getOwningStateId(), owner) || !states.mayInitiateHostility(level, army.getOwningStateId(), owner)) return;
            if (!states.areAtWar(army.getOwningStateId(), owner)) {
                states.declareWar(level, army.getOwningStateId(), owner);
                states.notifyState(level, army.getOwningStateId(), "Your army crossed a foreign border without passage. War has begun.");
            }
        } else if (owner != null) return;
        createCampaign(army.getOwningStateId(), army.getArmyId());
    }

    //campaign ending check 1: enemy army prescence check, if there is an enemy army within engagement range of the army, the campaign cannot end
    private boolean hasEnemyNear(UUID stateId, BlockPos position, ServerLevel level) {
        StateManager states = StateManager.get(level);
        return activeArmies.values().stream().anyMatch(a -> !a.isEngaged() && a.isViable(this)
                && states.areAtWar(stateId, a.getOwningStateId()) && near(position, a.getStoredPosition(), ENGAGEMENT_RANGE))
                || activeLegions.values().stream().filter(l -> states.areAtWar(stateId, l.getOwningStateId()))
                .flatMap(l -> l.allCohorts().stream()).anyMatch(c -> c.isAlive() && !c.isDeployed() && near(position, c.getStoredPosition(), ENGAGEMENT_RANGE));
    }

    //campaign ending check 2: hostile presence check, if there is a hostile army within engagement range of the army, the campaign cannot end
    public boolean hasHostilePresence(UUID stateId, BlockPos position, ServerLevel level) {
        StateManager states = StateManager.get(level);
        // An ongoing battle is still a threat and must not permit instant recruitment beside it.
        return activeArmies.values().stream().anyMatch(a -> a.isViable(this)
                && states.areAtWar(stateId, a.getOwningStateId()) && near(position, a.getStoredPosition(), ENGAGEMENT_RANGE))
                || activeLegions.values().stream().filter(l -> states.areAtWar(stateId, l.getOwningStateId()))
                .flatMap(l -> l.allCohorts().stream()).anyMatch(c -> c.isAlive() && near(position, c.getStoredPosition(), ENGAGEMENT_RANGE));
    }

    /** Includes a garrison in its defensive battle without losing its settlement assignment. */
    private Army wrapNearbyDefenders(UUID stateId, BlockPos position, UUID aggressor) {
        Army defender = new Army(UUID.randomUUID(), stateId, position);
        for (Legion legion : getLegionsForState(stateId)) {
            for (Cohort cohort : legion.allCohorts()) {
                if (cohort.isAlive() && !cohort.isDeployed() && near(position, cohort.getStoredPosition(), ASSEMBLY_RANGE)
                        && (!cohort.isRetreating() || aggressor != null && !cohort.isDisengagingFrom(aggressor))
                        && defender.canAddCohort(cohort, this)) defender.addCohortId(cohort.getCohortId());
            }
        }
        if (defender.getDeployedCohortIds().isEmpty()) return null;
        registerArmy(defender);
        return defender;
    }

    //engagement check function: checks if the army is within engagement range of any enemy armies or legions, and starts a battle if so
    private boolean checkCollisionAndEngage(Army army, ServerLevel level) {
        if (army.isEngaged() || army.isRetreating() || !army.isViable(this)) return false;
        StateManager states = StateManager.get(level);
        for (Army other : new ArrayList<>(activeArmies.values())) {
            if (other.isEngaged() || !other.isViable(this) || !states.areAtWar(army.getOwningStateId(), other.getOwningStateId())) continue;
            if (!states.mayBeginEncounter(level, army.getOwningStateId(), army.getStoredPosition(), other.getOwningStateId(), other.getStoredPosition())) continue;
            if (!canEngage(army, other)) continue;
            if (near(army.getStoredPosition(), other.getStoredPosition(), ENGAGEMENT_RANGE)
                    && canReachEnemy(level, army.getStoredPosition(), other.getStoredPosition())) {
                createCampaign(army.getOwningStateId(), army.getArmyId());
                createCampaign(other.getOwningStateId(), other.getArmyId());
                BattleManager.get(level).startBattle(army, other, level);
                return true;
            }
        }
        for (Legion legion : new ArrayList<>(activeLegions.values())) {
            if (!states.areAtWar(army.getOwningStateId(), legion.getOwningStateId())) continue;
            for (Cohort cohort : legion.allCohorts()) {
                if (!states.mayBeginEncounter(level, army.getOwningStateId(), army.getStoredPosition(), legion.getOwningStateId(), cohort.getStoredPosition())) continue;
                if (cohort.isAlive() && !cohort.isDeployed() && !cohort.isDisengagingFrom(army.getArmyId())
                        && near(army.getStoredPosition(), cohort.getStoredPosition(), ENGAGEMENT_RANGE)
                        && canReachEnemy(level, army.getStoredPosition(), cohort.getStoredPosition())) {
                    Army defender = wrapNearbyDefenders(legion.getOwningStateId(), cohort.getStoredPosition(), army.getArmyId());
                    if (defender != null) {
                        createCampaign(army.getOwningStateId(), army.getArmyId());
                        createCampaign(defender.getOwningStateId(), defender.getArmyId());
                        BattleManager.get(level).startBattle(army, defender, level);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    //army movement function: moves the army towards its next waypoint, and removes the waypoint if it has been reached
    private static void move(ServerLevel level, UUID owner, double x, double z, Queue<BlockPos> waypoints, boolean retreating, java.util.function.BiPredicate<Double, Double> setter) {
        BlockPos target = waypoints.peek();
        if (target == null) return;
        double dx = target.getX() + 0.5 - x, dz = target.getZ() + 0.5 - z;
        double distance = Math.hypot(dx, dz);
        // Floating-point leftovers at an already reached turn must not require a fresh formation deployment.
        if (distance <= 1.0e-7) { waypoints.poll(); return; }
        double speed = 0.2; // 0.8 blocks/second at five-tick intervals before terrain/road modifiers; tune after playtesting.
        if (retreating) speed *= StateBalance.ROUT_SPEED_MULTIPLIER;
        // Roads spend this movement interval at the appropriate speed on each side of a chunk boundary.
        if (distance > 0) speed = LogisticsManager.get(level).movementDistance(owner, x, z, dx / distance, dz / distance, speed, level);
        if (distance > 0) {
            var surface = com.devc.minecraftempires.network.MapDataService.surface(level, (int) Math.floor(x), (int) Math.floor(z));
            double nextX = x + dx / distance * Math.min(distance, speed), nextZ = z + dz / distance * Math.min(distance, speed);
            var nextSurface = com.devc.minecraftempires.network.MapDataService.surface(level, (int) Math.floor(nextX), (int) Math.floor(nextZ));
            speed /= Math.max(surface.travelCost(), nextSurface.travelCost());
            double slope = nextSurface.height() - surface.height();
            speed /= Math.hypot(1, slope) * (slope > 0 ? 1.25 : 1);
            nextX = x + dx / distance * Math.min(distance, speed); nextZ = z + dz / distance * Math.min(distance, speed);
            if (com.devc.minecraftempires.network.MapDataService.verifySegment(level, x, z, nextX, nextZ)
                    != com.devc.minecraftempires.network.MapDataService.Verification.VERIFIED) return;
        }
        double amount = Math.min(distance, speed);
        if (!retreating) {
            UUID destinationOwner = ClaimManager.get(level).getStrategicOwner(ChunkPos.containing(BlockPos.containing(x + dx / distance * amount, 0, z + dz / distance * amount)));
            StateManager states = StateManager.get(level);
            if (destinationOwner != null && !owner.equals(destinationOwner) && !states.hasPassage(owner, destinationOwner)
                    && !states.mayInitiateHostility(level, owner, destinationOwner)) return;
        }
        if (setter.test(distance == 0 ? x : x + dx / distance * amount, distance == 0 ? z : z + dz / distance * amount) && distance <= speed) waypoints.poll();
    }

    private static boolean canReachEnemy(ServerLevel level, BlockPos from, BlockPos to) {
        return com.devc.minecraftempires.network.MapDataService.route(level, from, to, 64).status()
                == com.devc.minecraftempires.network.MapDataService.Verification.VERIFIED;
    }

    //army and legion order queue function: returns the current order queue for the given army or legion, if it is viable and not engaged or retreating
    private Queue<BlockPos> orderQueue(String kind, UUID id, UUID owner) {
        if (kind.equals("ARMY")) {
            Army army = getArmy(id);
            return army != null && owner.equals(army.getOwningStateId()) && army.isViable(this)
                    && !army.isEngaged() && !army.isRetreating() ? army.getWaypoints() : null;
        }
        if (kind.equals("LEGION")) {
            Legion legion = getLegion(id);
            Cohort main = legion == null ? null : legion.getFieldCohort();
            return main != null && owner.equals(legion.getOwningStateId()) && !main.isDeployed()
                    && !main.isGarrisoned() && !main.isRetreating() ? legion.getWaypoints() : null;
        }
        Cohort cohort = resolveCohort(id);
        Legion parent = getParentLegion(id);
        return cohort != null && parent != null && owner.equals(parent.getOwningStateId()) && cohort.isAlive()
                && !cohort.isDeployed() && !cohort.isGarrisoned() && !cohort.isRetreating() ? cohort.getWaypoints() : null;
    }

    //order position function: returns the current position of the given army, legion, or cohort
    private BlockPos orderPosition(String kind, UUID id) {
        if (kind.equals("ARMY")) return getArmy(id).getStoredPosition();
        if (kind.equals("LEGION")) return getLegion(id).getFieldCohort().getStoredPosition();
        return resolveCohort(id).getStoredPosition();
    }

    /** Prepare against immutable orders. Cancellation, stale previews and failed searches change nothing. */
    private void proposeMove(ServerPlayer player, String kind, List<UUID> units, BlockPos target, boolean queue) {
        StateData state = StateManager.get(player.level()).getStateByPlayer(player.getUUID());
        if (state == null || !controls(player, state.getStateId()) || !validTarget(player.level(), target) || units.isEmpty()) return;
        Map<UUID, BlockPos> starts = new LinkedHashMap<>();
        Map<UUID, List<BlockPos>> orders = new LinkedHashMap<>();
        for (UUID id : new LinkedHashSet<>(units)) {
            Queue<BlockPos> current = orderQueue(kind, id, state.getStateId());
            if (current == null) { tell(player, "No orders changed. Select available units that are not retreating."); return; }
            List<BlockPos> saved = List.copyOf(current);
            orders.put(id, saved);
            starts.put(id, queue && !saved.isEmpty() ? saved.getLast() : orderPosition(kind, id));
        }
        moveProposals.remove(player.getUUID());
        moveConfirmations.remove(player.getUUID());
        MoveProposal proposal = new MoveProposal(UUID.randomUUID(), player.getUUID(), state.getStateId(), kind,
                List.copyOf(starts.keySet()), target, queue, Map.copyOf(starts), Map.copyOf(orders), Map.of(),
                player.level().getGameTime() + 2400, false, Set.of(), ClaimManager.get(player.level()).getTopologyRevision());
        moveProposals.put(player.getUUID(), proposal);
        tell(player, "Checking the route. Your current orders remain active until the new route is ready.");
    }

    private void tickMoveProposals(ServerLevel level) {
        moveConfirmations.keySet().retainAll(moveProposals.keySet());
        for (MoveProposal proposal : new ArrayList<>(moveProposals.values())) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(proposal.player);
            if (player == null || !controls(player, proposal.owner) || level.getGameTime() > proposal.expires) {
                moveProposals.remove(proposal.player); moveConfirmations.remove(proposal.player);
                if (player != null && level.getGameTime() > proposal.expires) tell(player, "The route check expired. Existing orders were kept; request the move again.");
                continue;
            }
            if (proposal.shown) {
                if (moveConfirmations.containsKey(proposal.player)) acceptMove(player, proposal.id);
                continue;
            }
            Map<UUID, List<BlockPos>> routes = new LinkedHashMap<>();
            Set<UUID> provisional = new HashSet<>();
            boolean waiting = false, detour = false, unknown = false;
            for (UUID id : proposal.units) {
                BlockPos start = proposal.starts.get(id);
                var direct = com.devc.minecraftempires.network.MapDataService.verifySegment(level,
                        start.getX() + .5, start.getZ() + .5, proposal.target.getX() + .5, proposal.target.getZ() + .5);
                if (direct == com.devc.minecraftempires.network.MapDataService.Verification.VERIFIED) {
                    routes.put(id, List.of(proposal.target)); continue;
                }
                var route = com.devc.minecraftempires.network.MapDataService.route(level, start, proposal.target, 128);
                if (route.status() == com.devc.minecraftempires.network.MapDataService.Verification.PENDING) { waiting = true; continue; }
                if (route.status() == com.devc.minecraftempires.network.MapDataService.Verification.UNKNOWN) {
                    unknown = true; provisional.add(id); routes.put(id, List.of(proposal.target)); continue;
                }
                if (route.status() != com.devc.minecraftempires.network.MapDataService.Verification.VERIFIED) {
                    tell(player, "No verified route found within the search area. Existing orders were kept.");
                    moveProposals.remove(proposal.player); routes.clear(); break;
                }
                routes.put(id, routeTurns(route.points())); detour = true;
            }
            if (waiting || routes.size() != proposal.units.size()) continue;
            MoveProposal ready = new MoveProposal(proposal.id, proposal.player, proposal.owner, proposal.kind, proposal.units,
                    proposal.target, proposal.queue, proposal.starts, proposal.oldOrders, Map.copyOf(routes), proposal.expires, true, Set.copyOf(provisional), ClaimManager.get(level).getTopologyRevision());
            moveProposals.put(proposal.player, ready);
            if (detour || unknown) net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    routePreview(level, ready, detour, unknown));
            else acceptMove(player, ready.id);
        }
    }

    //route preview screen function: generates a route preview for the given move proposal, including any detours or unknown sections
    private ArmyActionPayload.RoutePreview routePreview(ServerLevel level, MoveProposal proposal, boolean detour, boolean unknown) {
        List<List<BlockPos>> runs = new ArrayList<>(); Set<String> borders = new LinkedHashSet<>(); double extra = 0; int total = 0;
        for (UUID id : proposal.units) {
            BlockPos from = proposal.starts.get(id); double length = 0; List<BlockPos> run = new ArrayList<>();
            for (BlockPos end : proposal.routes.get(id)) {
                double span = Math.hypot(end.getX() - from.getX(), end.getZ() - from.getZ()); length += span;
                int steps = Math.max(1, (int) Math.ceil(span));
                for (int i = 0; i <= steps && total < 4096 && runs.size() < 255; i++) {
                    BlockPos point = BlockPos.containing(from.getX() + .5 + (end.getX() - from.getX()) * (double) i / steps, 0, from.getZ() + .5 + (end.getZ() - from.getZ()) * (double) i / steps);
                    if (MapDataService.canPreviewTerrain(level, proposal.owner, point)) {
                        run.add(point); total++;
                        UUID owner = ClaimManager.get(level).getStrategicOwner(ChunkPos.containing(point));
                        if (owner != null && !proposal.owner.equals(owner)) {
                            StateData state = StateManager.get(level).getState(owner);
                            if (state != null) borders.add(state.getStateName() + (StateManager.get(level).hasPassage(proposal.owner, state.getStateId()) ? " (passage)" : " (entry may start hostilities)"));
                        }
                    } else if (!run.isEmpty()) { runs.add(List.copyOf(run)); run.clear(); }
                }
                from = end;
            }
            if (!run.isEmpty() && runs.size() < 256) runs.add(List.copyOf(run));
            extra = Math.max(extra, length - Math.hypot(proposal.target.getX() - proposal.starts.get(id).getX(), proposal.target.getZ() - proposal.starts.get(id).getZ()));
        }
        String description = unknown ? "Unverified sections: troops stop before unknown or blocked ground." : "Detour adds up to " + Math.round(extra) + " blocks per formation.";
        description += " Only observed route sections are shown.";
        if (!borders.isEmpty()) description += " Crosses " + String.join(", ", borders) + ".";
        if (description.length() > 1000) description = description.substring(0, 997) + "...";
        return new ArmyActionPayload.RoutePreview(proposal.id, description, detour, runs);
    }

    /**
     * Returns the turns in the given route.
     *
     * @param path The route to analyze.
     * @return A list of the turns in the route.
     */
    public static List<BlockPos> routeTurns(List<BlockPos> path) {
        List<BlockPos> result = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) {
            BlockPos before = path.get(i - 1), point = path.get(i);
            if (i == path.size() - 1 || point.getX() - before.getX() != path.get(i + 1).getX() - point.getX()
                    || point.getZ() - before.getZ() != path.get(i + 1).getZ() - point.getZ()) result.add(point);
        }
        return List.copyOf(result);
    }

    ///move acceptance function: accepts the move proposal for the given player and applies the new orders to the units
    private void acceptMove(ServerPlayer player, UUID token) {
        MoveProposal proposal = moveProposals.get(player.getUUID());
        if (proposal == null || !proposal.id.equals(token) || !proposal.shown || player.level().getGameTime() > proposal.expires) return;
        if (!controls(player, proposal.owner)) { discardMove(proposal); return; }
        if (proposal.rightsRevision != ClaimManager.get(player.level()).getTopologyRevision()) { discardMove(proposal); tell(player, "Territorial borders changed. Request a fresh route; existing orders were kept."); return; }
        for (UUID id : proposal.units) {
            Queue<BlockPos> orders = orderQueue(proposal.kind, id, proposal.owner);
            if (orders == null || !List.copyOf(orders).equals(proposal.oldOrders.get(id))
                    || !proposal.queue && !orderPosition(proposal.kind, id).equals(proposal.starts.get(id))
                    || proposal.routes.get(id).size() + (proposal.queue ? orders.size() : 0) > MAX_WAYPOINTS) {
                discardMove(proposal); tell(player, "The units or their orders changed while the route was checked. Issue the order again; existing orders were kept."); return;
            }
        }
        var inspection = moveConfirmations.get(proposal.player);
        if (inspection == null) {
            List<List<BlockPos>> paths = new ArrayList<>();
            for (UUID id : proposal.units) {
                List<BlockPos> path = new ArrayList<>(); path.add(proposal.starts.get(id)); path.addAll(proposal.routes.get(id)); paths.add(path);
            }
            inspection = new MapDataService.TerrainInspection(player.level(), paths);
            moveConfirmations.put(proposal.player, inspection);
            tell(player, "Confirming the selected route. Existing orders remain active until verification finishes.");
        }
        var readiness = inspection.status(player.level());
        if (readiness == MapDataService.Verification.PENDING) return;
        if (readiness == MapDataService.Verification.LIMITED) {
            discardMove(proposal); tell(player, "This route is too large to confirm at once. Try a closer waypoint; existing orders were kept."); return;
        }
        for (UUID id : proposal.units) {
            BlockPos from = proposal.starts.get(id);
            for (BlockPos to : proposal.routes.get(id)) {
                var status = inspection.verifySegment(player.level(), from, to);
                if (status == com.devc.minecraftempires.network.MapDataService.Verification.BLOCKED
                        || !proposal.provisional.contains(id) && status != com.devc.minecraftempires.network.MapDataService.Verification.VERIFIED) {
                    discardMove(proposal); tell(player, status == MapDataService.Verification.BLOCKED
                            ? "The selected route is now obstructed. Request a fresh route; existing orders were kept."
                            : "Part of the selected route could not be verified. Request a fresh route; existing orders were kept."); return;
                }
                from = to;
            }
        }
        discardMove(proposal);
        for (UUID id : proposal.units) {
            Queue<BlockPos> orders = orderQueue(proposal.kind, id, proposal.owner);
            if (!proposal.queue) orders.clear();
            orders.addAll(proposal.routes.get(id));
            if (proposal.kind.equals("ARMY")) for (UUID cohortId : getArmy(id).getDeployedCohortIds()) {
                Cohort cohort = resolveCohort(cohortId); if (cohort == null || cohort.isGarrisoned()) continue;
                if (!proposal.queue) cohort.clearWaypoints(); cohort.getWaypoints().addAll(proposal.routes.get(id));
            }
            if (proposal.kind.equals("LEGION")) for (Cohort cohort : getLegion(id).allCohorts()) {
                if (cohort.isDeployed() || cohort.isGarrisoned() || cohort.isDetached()) continue;
                if (!proposal.queue) cohort.clearWaypoints(); cohort.getWaypoints().addAll(proposal.routes.get(id));
            }
            if (proposal.kind.equals("COHORT")) { resolveCohort(id).setDetached(true); resolveCohort(id).setRegrouping(false); }
        }
        setDirty(); tell(player, "Movement orders accepted."); ModNetworking.sendSnapshots(player);
    }

    //cancel move function: discards the move proposal for the given player and removes it from the move proposals and confirmations
    private void discardMove(MoveProposal proposal) { moveProposals.remove(proposal.player); moveConfirmations.remove(proposal.player); }
    
    //army movement function: moves the army towards its next waypoint, and removes the waypoint if it has been reached
    /** Shared strategic step, including forced retreat, without changing position at order time. */
    public static void advanceMovement(double x, double z, Queue<BlockPos> waypoints, double speed, BiConsumer<Double, Double> setter) {
        BlockPos target = waypoints.peek();
        if (target == null || speed <= 0) return;
        double dx = target.getX() + .5 - x, dz = target.getZ() + .5 - z;
        double distance = Math.hypot(dx, dz);
        if (distance <= speed) {
            setter.accept(target.getX() + 0.5, target.getZ() + 0.5);
            waypoints.poll();
        } else setter.accept(x + dx / distance * speed, z + dz / distance * speed);
    }

    public static boolean canEngage(Army first, Army second) {
        return !first.isDisengagingFrom(second.getArmyId()) && !second.isDisengagingFrom(first.getArmyId());
    }
    //retreat disengagement update function: checks if the army has retreated far enough from its opponent to disengage from battle
    public boolean updateRetreatDisengagement(Army army) {
        Army opponent = getArmy(army.getRetreatOpponent());
        boolean separated = opponent == null || !near(army.getStoredPosition(), opponent.getStoredPosition(), ENGAGEMENT_RANGE);
        if (separated) army.clearRetreatOpponent();
        return separated;
    }

    //checks if two block positions are within a certain range of each other
    public static boolean near(BlockPos a, BlockPos b, int range) {
        double dx = (double) a.getX() - b.getX(), dz = (double) a.getZ() - b.getZ();
        return dx * dx + dz * dz <= (double) range * range;
    }

    //ownership check function: checks if the player controls the state that owns the army
    private static boolean controls(ServerPlayer player, UUID stateId) {
        StateData state = StateManager.get(player.level()).getStateByPlayer(player.getUUID());
        return player.level().dimension() == Level.OVERWORLD && state != null && state.getStateId().equals(stateId)
                && !StateManager.get(player.level()).getAIWars().blocks(stateId)
                && player.getUUID().equals(state.getLeaderId());
    }

    private static void tell(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
        ModNetworking.sendSnapshots(player);
    }
    //valid target check function: checks if the target position is within the valid range of the world
    private static boolean validTarget(ServerLevel level, BlockPos position) {
        return Math.abs((long) position.getX()) <= 29_999_984 && Math.abs((long) position.getZ()) <= 29_999_984
                && level.getWorldBorder().isWithinBounds(position);
    }

    //all dispatchment functions: handles the dispatchment of armies and legions, and the disbandment of armies and legions
    public static void handleDispatchArmy(DispatchArmyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ArmyManager manager = get(player.level());
            Army army = manager.getArmy(payload.armyId());
            if (army == null || !controls(player, army.getOwningStateId()) || army.isEngaged()
                    || !army.isViable(manager) || !validTarget(player.level(), payload.targetPos())) return;
            if (army.isRetreating()) { tell(player, "This army must finish retreating before accepting new orders."); return; }
            manager.proposeMove(player, "ARMY", List.of(army.getArmyId()), payload.targetPos(), payload.isQueueing());
        });
    }

    public static void handleDispatchLegion(DispatchLegionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ArmyManager manager = get(player.level());
            Legion legion = manager.getLegion(payload.legionId());
            if (legion == null || !controls(player, legion.getOwningStateId()) || !validTarget(player.level(), payload.targetPos())) return;
            Cohort main = legion.getFieldCohort();
            if (main == null || main.isDeployed() || main.isGarrisoned() || main.isRetreating()) {
                tell(player, "No free legion body is available. Move its army or detached cohorts, or regroup the legion first.");
                return;
            }
            manager.proposeMove(player, "LEGION", List.of(legion.getLegionId()), payload.targetPos(), payload.isQueueing());
        });
    }

    public static void handleDisbandArmy(DisbandArmyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ArmyManager manager = get(player.level());
            Army army = manager.getArmy(payload.armyId());
            if (army != null) {
                if (!controls(player, army.getOwningStateId())) return;
                tell(player, manager.disbandArmy(army.getArmyId(), false) ? "Army disbanded."
                        : "End the campaign and finish any battle before releasing this army.");
                return;
            }
            Legion legion = manager.getLegion(payload.armyId());
            if (legion == null || !controls(player, legion.getOwningStateId())) return;
            if (!manager.canDissolveLegion(legion)) {
                tell(player, "Error. End the campaign and disolve the army before dissolving this legion.");
                return;
            }
            manager.disbandLegion(legion.getLegionId());
            manager.refreshGarrisons(player.level());
            tell(player, "Legion dissolved permanently.");
        });
    }

    public static void handleComposeArmy(ComposeArmyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            StateData state = StateManager.get(player.level()).getStateByPlayer(player.getUUID());
            if (state == null || !controls(player, state.getStateId())) return;
            ArmyManager manager = get(player.level());
            if (payload.action().equals("DETACH")) {
                tell(player, manager.detachCohorts(state.getStateId(), payload.selectedCohortIds())
                        ? "Selected cohorts detached in place. Move them on the map or garrison them at a nearby settlement."
                        : "No cohorts changed. Finish their battle, retreat or campaign and release any garrison assignment first.");
                return;
            }
            if (payload.action().equals("MOVE")) {
                if (!validTarget(player.level(), payload.initialPosition())) return;
                manager.proposeMove(player, "COHORT", payload.selectedCohortIds(), payload.initialPosition(), payload.queue());
                return;
            }
            if (!payload.action().equals("COMPOSE")) return;
            Optional<Army> army = get(player.level()).raiseArmy(state.getStateId(), payload.initialPosition(), payload.selectedCohortIds());
            // The remaining field body retains its own orders; composing a nearby subset does not move it.
            tell(player, army.isPresent() ? "Army formed." : "Select living, available cohorts within 16 blocks of each other. Army capacity also applies.");
        });
    }

    public static void handleGarrisonCohort(GarrisonCohortPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            StateManager states = StateManager.get(player.level());
            StateData state = states.getStateByPlayer(player.getUUID());
            if (state == null || !controls(player, state.getStateId())) return;
            ArmyManager manager = get(player.level());
            if (!manager.changeGarrison(state.getStateId(), payload.cohortIds(),
                    states.getSettlement(payload.settlementId()), payload.ungarrison())) {
                tell(player, "No cohorts changed. Select your living, unengaged cohorts at this settlement; new garrisons must be within 16 blocks and fit its soldier capacity.");
                return;
            }
            manager.refreshGarrisons(player.level());
            tell(player, payload.ungarrison() ? "Garrison released in place. Regroup or form an army to move it."
                    : "Selected cohorts stationed at the settlement.");
        });
    }

    //garrision change function: changes the garrison of the selected cohorts, either garrisoning them at the settlement or ungarrisoning them and releasing them in place
    public boolean changeGarrison(UUID stateId, List<UUID> ids, SettlementData settlement, boolean ungarrison) {
        if (settlement == null || !stateId.equals(settlement.getOwningStateId()) || ids.isEmpty()
                || ids.size() > 150 || new HashSet<>(ids).size() != ids.size()) return false;
        List<Cohort> selected = new ArrayList<>();
        long newSoldiers = 0;
        for (UUID id : ids) {
            Cohort cohort = resolveCohort(id);
            Legion parent = getParentLegion(id);
            if (cohort == null || parent == null || !cohort.isAlive() || !stateId.equals(parent.getOwningStateId())) return false;
            Army army = getArmy(cohort.getAssignedArmyId());
            if (cohort.isRetreating() || army != null && (army.isEngaged() || army.isRetreating())) return false;
            if (ungarrison) {
                if (!cohort.isGarrisoned() || !settlement.getSettlementId().equals(cohort.getGarrisonedSettlementId())) return false;
            } else {
                if (cohort.isGarrisoned() || !near(cohort.getStoredPosition(), settlement.getCenterAltarPos(), ASSEMBLY_RANGE)) return false;
                newSoldiers += cohort.getSoldierCount();
            }
            selected.add(cohort);
        }
        long currentSoldiers = cohortRegistry.values().stream().filter(c -> c.isAlive() && c.isGarrisoned()
                && settlement.getSettlementId().equals(c.getGarrisonedSettlementId())).mapToLong(Cohort::getSoldierCount).sum();
        if (!ungarrison && currentSoldiers + newSoldiers > settlement.getGarrisonCapacity()) return false;
        for (Cohort cohort : selected) {
            Army army = getArmy(cohort.getAssignedArmyId());
            if (army != null) army.removeCohortId(cohort.getCohortId());
            cohort.setAssignedArmyId(null);
            cohort.setDetached(true); cohort.setRegrouping(false);
            cohort.clearWaypoints();
            cohort.setIsGarrisoned(!ungarrison);
            cohort.setGarrisonedSettlementId(ungarrison ? null : settlement.getSettlementId());
            if (!ungarrison) cohort.setStoredPosition(settlement.getCenterAltarPos());
            getParentLegion(cohort.getCohortId()).syncMainPosition();
        }
        setDirty();
        return true;
    }

    //handle army action function: handles the actions that can be performed on an army, such as recruiting legions and auxiliaries, regrouping, and ending campaigns
    public static void handleArmyAction(ArmyActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            StateManager states = StateManager.get(player.level());
            StateData state = states.getStateByPlayer(player.getUUID());
            if (state == null || !controls(player, state.getStateId())) return;
            ArmyManager manager = get(player.level());
            switch (payload.action()) {
                case "ACCEPT_ROUTE" -> manager.acceptMove(player, payload.targetId());
                case "CANCEL_ROUTE" -> {
                    MoveProposal proposal = manager.moveProposals.get(player.getUUID());
                    if (proposal != null && proposal.id.equals(payload.targetId())) manager.discardMove(proposal);
                }
                case "RECRUIT_LEGION" -> {
                    RecruitmentResult result = manager.recruitLegion(player.level(), state.getStateId(), payload.targetId(), player.getUUID());
                    tell(player, result.succeeded() ? result.free() ? "First Legion Free!" : "Legion recruited." : result.error());
                }
                case "RECRUIT_AUXILIARY" -> {
                    if (state.hasSurrenderOverflow()) { tell(player, "Qualify for your inherited territory and forces before recruiting more."); return; }
                    Legion legion = manager.getLegion(payload.targetId());
                    if (legion == null || !state.getStateId().equals(legion.getOwningStateId())) return;
                    Cohort main = legion.getMainCohort();
                    boolean atSettlement = main != null && !main.isDeployed() && states.getAllSettlements().stream().anyMatch(s ->
                            state.getStateId().equals(s.getOwningStateId()) && near(main.getStoredPosition(), s.getCenterAltarPos(), ASSEMBLY_RANGE));
                    if (!atSettlement || state.getTreasuryBalance() < LIGHT_INFANTRY_COST
                            || manager.hasHostilePresence(state.getStateId(), legion.getStoredPosition(), player.level())) {
                        tell(player, "Recruit light infantry with the legion's main cohort at an owned, unthreatened settlement, for 100 emeralds.");
                        return;
                    }
                    Cohort auxiliary = Cohort.createAuxiliary("light_infantry");
                    if (!legion.addAuxiliary(auxiliary)) { tell(player, "This legion already has ten auxiliary units."); return; }
                    manager.registerCohort(auxiliary);
                    state.deductFunds(LIGHT_INFANTRY_COST);
                    states.setDirty();
                    tell(player, "50 light infantry recruited. Note: their losses are permanent.");
                }
                case "REGROUP" -> {
                    tell(player, manager.regroupLegion(state.getStateId(), payload.targetId())
                            ? "Legion paused at its rendezvous. Available detachments will march there."
                            : "No available field cohorts can form a rendezvous yet.");
                }
                case "END_CAMPAIGN" -> {
                    Army army = manager.getArmy(payload.targetId());
                    if (army != null && controls(player, army.getOwningStateId()) && army.isOnCampaign()) manager.endCampaign(army.getCampaignId(), player);
                }
                default -> { }
            }
        });
    }

    //army disbandment function: disbands selected armies, checking for garrisoned units as well
    public static boolean removeForStateIfAvailable(ServerLevel level, UUID stateId) {
        ArmyManager manager = get(level);
        if (manager.getArmiesForState(stateId).stream().anyMatch(a -> a.isEngaged() || a.isOnCampaign())) return false;
        for (Army army : manager.getArmiesForState(stateId)) manager.disbandArmy(army.getArmyId(), false);
        for (Legion legion : manager.getLegionsForState(stateId)) manager.disbandLegion(legion.getLegionId());
        for (StrategicForce force : manager.getStrategicForcesForState(stateId)) manager.removeStrategicForce(force);
        manager.activeCampaigns.values().removeIf(campaign -> stateId.equals(campaign.getOwningStateId()));
        manager.setDirty();
        manager.refreshGarrisons(level);
        return true;
    }

    public void releaseGarrison(UUID settlementId) {
        for (Cohort cohort : cohortRegistry.values()) {
            if (settlementId.equals(cohort.getGarrisonedSettlementId())) {
                cohort.setIsGarrisoned(false);
                cohort.setGarrisonedSettlementId(null);
            }
        }
        setDirty();
    }

    private void refreshGarrisons(ServerLevel level) {
        StateManager states = StateManager.get(level);
        Set<UUID> occupied = new HashSet<>();
        for (Cohort cohort : cohortRegistry.values()) {
            if (!cohort.isGarrisoned()) continue;
            if (states.getSettlement(cohort.getGarrisonedSettlementId()) == null || !cohort.isAlive()) {
                cohort.setIsGarrisoned(false);
                cohort.setGarrisonedSettlementId(null);
                setDirty();
            } else occupied.add(cohort.getGarrisonedSettlementId());
        }
        for (SettlementData settlement : states.getAllSettlements()) {
            boolean garrisoned = occupied.contains(settlement.getSettlementId());
            if (settlement.isMartialLaw() != garrisoned) { settlement.setMartialLaw(garrisoned); states.setDirty(); }
        }
    }

    private void reconcileLocations(ServerLevel level) {
        if (locationsReconciled) return;
        for (Cohort cohort : cohortRegistry.values()) {
            if (cohort.isGarrisoned()) {
                SettlementData settlement = StateManager.get(level).getSettlement(cohort.getGarrisonedSettlementId());
                if (settlement != null) cohort.setStoredPosition(settlement.getCenterAltarPos());
            }
        }
        // Persisted cohort coordinates are authoritative; membership alone never pulls them back to a parent.
        shortenRetreats(StateBalance.POST_BATTLE_RETREAT_DISTANCE);
        activeLegions.values().forEach(Legion::syncMainPosition);
        locationsReconciled = true;
        setDirty();
    }

    public Cohort resolveCohort(UUID id) { return cohortRegistry.get(id); }
    public Legion getParentLegion(UUID cohortId) { return activeLegions.get(cohortParents.get(cohortId)); }
    public Legion getLegion(UUID id) { return activeLegions.get(id); }
    public Army getArmy(UUID id) { return activeArmies.get(id); }
    public Campaign getCampaign(UUID id) { return activeCampaigns.get(id); }
    public List<Campaign> getCampaignsForState(UUID owner) {
        return activeCampaigns.values().stream().filter(c -> owner.equals(c.getOwningStateId())).toList();
    }
    public Collection<Cohort> getAllCohorts() { return Collections.unmodifiableCollection(cohortRegistry.values()); }
    public Collection<Legion> getAllLegions() { return Collections.unmodifiableCollection(activeLegions.values()); }
    public Collection<Army> getAllArmies() { return Collections.unmodifiableCollection(activeArmies.values()); }
    public List<Legion> getLegionsForState(UUID stateId) {
        return stateToLegionIndex.getOrDefault(stateId, List.of()).stream().map(activeLegions::get).filter(Objects::nonNull).toList();
    }
    public List<Army> getArmiesForState(UUID stateId) {
        return stateToArmyIndex.getOrDefault(stateId, List.of()).stream().map(activeArmies::get).filter(Objects::nonNull).toList();
    }
    public int getLegionCountForState(UUID stateId) { return stateToLegionIndex.getOrDefault(stateId, List.of()).size() + getStrategicForcesForState(stateId).size(); }

    private static void removeIndex(Map<UUID, List<UUID>> index, UUID owner, UUID id) {
        List<UUID> ids = index.get(owner);
        if (ids != null) { ids.remove(id); if (ids.isEmpty()) index.remove(owner); }
    }

    public CompoundTag toTag() {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        ListTag legions = new ListTag(), armies = new ListTag(), campaigns = new ListTag(), standalone = new ListTag();
        activeLegions.values().forEach(l -> legions.add(l.toNBT()));
        activeArmies.values().forEach(a -> armies.add(a.toNBT()));
        activeCampaigns.values().forEach(c -> campaigns.add(c.toNBT()));
        cohortRegistry.values().stream().filter(c -> !cohortParents.containsKey(c.getCohortId())).forEach(c -> standalone.add(c.toNBT()));
        root.put("Legions", legions); root.put("Armies", armies); root.put("Campaigns", campaigns); root.put("StandaloneCohorts", standalone);
        ListTag strategic = new ListTag(), receipts = new ListTag(); strategicForces.values().forEach(force -> strategic.add(force.toNBT()));
        aiInitializations.forEach(id -> receipts.add(net.minecraft.nbt.StringTag.valueOf(id.toString())));
        root.put("StrategicForces", strategic); root.put("AIInitializations", receipts);
        root.putLong("MacroSequence", macroSequence);
        root.putLong("InteractionSequence", interactionSequence);
        ListTag origins = new ListTag(); detailedOrigins.values().forEach(f -> origins.add(f.toNBT())); root.put("DetailedOrigins", origins);
        return root;
    }

    public static ArmyManager fromTag(CompoundTag root) {
        ArmyManager manager = new ArmyManager();
        manager.macroSequence = Math.max(0, root.getLong("MacroSequence").orElse(0L));
        manager.interactionSequence = Math.max(0, root.getLong("InteractionSequence").orElse(0L));
        readTags(root, "DetailedOrigins", tag -> { StrategicForce f = StrategicForce.fromNBT(tag); manager.detailedOrigins.put(f.id(), f); });
        readTags(root, "Legions", tag -> manager.registerLegion(Legion.fromNBT(tag)));
        readTags(root, "StrategicForces", tag -> manager.registerStrategicForce(StrategicForce.fromNBT(tag)));
        for (var value : root.getListOrEmpty("AIInitializations")) try { value.asString().map(UUID::fromString).ifPresent(manager.aiInitializations::add); }
        catch (IllegalArgumentException malformed) { /* Ignore an invalid optional initialization receipt only. */ }
        readTags(root, "StandaloneCohorts", tag -> manager.registerCohort(Cohort.fromNBT(tag)));
        readTags(root, "Armies", tag -> {
            Army army = Army.fromNBT(tag);
            for (UUID id : new ArrayList<>(army.getDeployedCohortIds())) {
                Cohort cohort = manager.resolveCohort(id);
                Legion parent = manager.getParentLegion(id);
                if (cohort == null || (parent != null && !army.getOwningStateId().equals(parent.getOwningStateId()))
                        || (cohort.getAssignedArmyId() != null && manager.getArmy(cohort.getAssignedArmyId()) != null)) army.removeCohortId(id);
            }
            manager.registerArmy(army);
        });
        readTags(root, "Campaigns", tag -> {
            Campaign campaign = Campaign.fromNBT(tag);
            for (UUID id : new HashSet<>(campaign.getArmyIds())) {
                Army army = manager.getArmy(id);
                if (army == null || !army.getOwningStateId().equals(campaign.getOwningStateId())) campaign.removeArmy(id);
                else if (campaign.isActive()) {
                    Campaign already = manager.getCampaign(army.getCampaignId());
                    if (already != null && already.isActive()) campaign.removeArmy(id);
                    else army.setCampaignId(campaign.getCampaignId());
                }
            }
            if (campaign.isPlanned() || campaign.isActive() && !campaign.isEmpty()) manager.activeCampaigns.put(campaign.getCampaignId(), campaign);
        });
        for (Cohort cohort : manager.cohortRegistry.values()) {
            Army army = manager.getArmy(cohort.getAssignedArmyId());
            if (army == null || !army.getDeployedCohortIds().contains(cohort.getCohortId())) cohort.setAssignedArmyId(null);
        }
        for (Army army : manager.activeArmies.values()) {
            if (!manager.isActiveCampaign(army)) army.setCampaignId(null);
        }
        manager.shortenRetreats(StateBalance.POST_BATTLE_RETREAT_DISTANCE);
        manager.activeLegions.values().forEach(Legion::syncMainPosition);
        return manager;
    }

    private static void readTags(CompoundTag root, String key, java.util.function.Consumer<CompoundTag> reader) {
        ListTag list = root.getList(key).orElse(new ListTag());
        for (int i = 0; i < list.size(); i++) {
            Optional<CompoundTag> entry = list.getCompound(i);
            if (entry.isEmpty()) continue;
            try { reader.accept(entry.get()); }
            catch (RuntimeException error) { MinecraftEmpires.LOGGER.error("Could not load {} entry {}: {}", key, i, error.getMessage()); }
        }
    }
}
