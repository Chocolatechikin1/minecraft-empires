package com.devc.minecraftempires.army;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.combat.BattleManager;
import com.devc.minecraftempires.network.packet.*;
import com.devc.minecraftempires.network.ModNetworking;
import com.devc.minecraftempires.state.StateData;
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
    private final Map<UUID, Army> activeArmies = new LinkedHashMap<>();
    private final Map<UUID, List<UUID>> stateToArmyIndex = new HashMap<>();
    private final Map<UUID, Campaign> activeCampaigns = new LinkedHashMap<>();
    private final Map<UUID, Cohort> cohortRegistry = new LinkedHashMap<>();
    private final Map<UUID, UUID> cohortParents = new HashMap<>();
    private boolean locationsReconciled;

    public static ArmyManager get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Creates a whole legion; only the authorized recruitment handler charges or grants it. */
    public Optional<Legion> raiseLegion(StateData state, BlockPos position) {
        if (getLegionCountForState(state.getStateId()) >= state.getCurrentTier().getMaxLegions()) return Optional.empty();
        Legion legion = new Legion(UUID.randomUUID(), state.getStateId(), position);
        for (int i = 0; i < Legion.MAX_INFANTRY_COHORTS; i++) legion.addInfantryCohort(Cohort.createInfantry());
        for (int i = 0; i < Legion.MAX_CAVALRY_SQUADRONS; i++) legion.addCavalrySquadron(Cohort.createCavalrySquadron());
        registerLegion(legion);
        return Optional.of(legion);
    }

    public void registerLegion(Legion legion) {
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
        for (UUID id : cohortIds) {
            Cohort cohort = resolveCohort(id);
            Legion parent = getParentLegion(id);
            if (cohort == null || parent == null || !stateId.equals(parent.getOwningStateId())
                    || !cohort.isAlive() || cohort.isDeployed() || cohort.isGarrisoned()
                    || !near(first.getStoredPosition(), cohort.getStoredPosition(), ASSEMBLY_RANGE)
                    || !army.canAddCohort(cohort, this)) return Optional.empty();
            army.addCohortId(id); // Local, unpublished candidate only.
        }
        for (UUID id : cohortIds) {
            Cohort cohort = resolveCohort(id);
            cohort.setAssignedArmyId(army.getArmyId());
            cohort.clearWaypoints();
        }
        registerArmy(army);
        syncCohortPositions(army);
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
        if (army == null || (!forceDisband && (army.isEngaged() || army.isOnCampaign()))) return false;
        syncCohortPositions(army);
        for (UUID id : army.getDeployedCohortIds()) {
            Cohort cohort = resolveCohort(id);
            if (cohort != null && armyId.equals(cohort.getAssignedArmyId())) cohort.setAssignedArmyId(null);
        }
        activeArmies.remove(armyId);
        removeIndex(stateToArmyIndex, army.getOwningStateId(), armyId);
        Campaign campaign = getCampaign(army.getCampaignId());
        if (campaign != null) {
            campaign.removeArmy(armyId);
            if (campaign.isEmpty()) activeCampaigns.remove(campaign.getCampaignId());
        }
        setDirty();
        return true;
    }

    //if a legion gets in an engagement "unprovoked," wrap it in an army to participate in the battle and campaign
    public Optional<Army> autoWrapLegionInArmy(Legion legion) {
        Cohort main = legion.getMainCohort();
        if (main == null || main.isDeployed() || main.isGarrisoned()) return Optional.empty();
        List<UUID> ids = legion.allCohorts().stream().filter(c -> c.isAlive() && !c.isDeployed() && !c.isGarrisoned()
                && near(main.getStoredPosition(), c.getStoredPosition(), ASSEMBLY_RANGE)).map(Cohort::getCohortId).toList();
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
        if (existing != null) return existing;
        Campaign campaign = new Campaign(UUID.randomUUID(), stateId);
        campaign.addArmy(armyId);
        army.setCampaignId(campaign.getCampaignId());
        activeCampaigns.put(campaign.getCampaignId(), campaign);
        setDirty();
        return campaign;
    }

    //end campaign function: ends the campaign for the given state and army, if one exists and is eligible to end
    public boolean endCampaign(UUID campaignId, ServerPlayer player) {
        Campaign campaign = getCampaign(campaignId);
        if (campaign == null || !controls(player, campaign.getOwningStateId())) return false;
        if (!campaign.checkEndConditions(this, player.level())) {
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
        for (Legion legion : new ArrayList<>(activeLegions.values())) {
            boolean inBattle = legion.allCohorts().stream().map(Cohort::getAssignedArmyId).map(this::getArmy)
                    .filter(Objects::nonNull).anyMatch(Army::isEngaged);
            if (!legion.isViable() && !inBattle) disbandLegion(legion.getLegionId());
            else legion.syncMainPosition();
        }
        for (Army army : new ArrayList<>(activeArmies.values())) {
            boolean requiresRelease = army.hasParticipatedInCampaign()
                    && army.getParticipatingLegionIds().stream().anyMatch(activeLegions::containsKey);
            if (!army.isViable(this) && !army.isEngaged() && !requiresRelease) disbandArmy(army.getArmyId(), true);
        }
    }

    //tick armies function: called every tick to update the positions of all armies and legions, and check for engagements
    //ngl this function got so complicated idek whats going on anymore
    public void tickArmies(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) return;
        reconcileLocations(level);
        boolean changed = false;
        for (Army army : new ArrayList<>(activeArmies.values())) {
            if (army.isEngaged() || !army.isViable(this)) continue;
            if (!army.getWaypoints().isEmpty()) {
                move(army.getPreciseX(), army.getPreciseZ(), army.getWaypoints(), army::setPrecisePos);
                syncCohortPositions(army);
                enterTerritory(army, level);
                changed = true;
            }
        }
        for (Legion legion : new ArrayList<>(activeLegions.values())) {
            Cohort main = legion.getMainCohort();
            if (main == null || main.isDeployed() || main.isGarrisoned() || legion.getWaypoints().isEmpty()) continue;
            BlockPos oldPosition = main.getStoredPosition();
            List<Cohort> travelling = legion.allCohorts().stream().filter(c -> c.isAlive() && !c.isDeployed()
                    && !c.isGarrisoned() && c.getWaypoints().isEmpty() && near(oldPosition, c.getStoredPosition(), ASSEMBLY_RANGE)).toList();
            move(legion.getPreciseX(), legion.getPreciseZ(), legion.getWaypoints(), legion::setPrecisePos);
            for (Cohort cohort : travelling) {
                cohort.setStoredPosition(legion.getStoredPosition());
                cohort.setPrecisePos(legion.getPreciseX(), legion.getPreciseZ());
            }
            if (needsCampaign(legion.getOwningStateId(), legion.getStoredPosition(), level))
                autoWrapLegionInArmy(legion).ifPresent(a -> enterTerritory(a, level));
            changed = true;
        }
        // Regrouping physically moves each detached cohort; it never teleports to its parent.
        for (Cohort cohort : new ArrayList<>(cohortRegistry.values())) {
            if (!cohort.isAlive() || cohort.isDeployed() || cohort.isGarrisoned() || cohort.getWaypoints().isEmpty()) continue;
            move(cohort.getPreciseX(), cohort.getPreciseZ(), cohort.getWaypoints(), cohort::setPrecisePos);
            Legion parent = getParentLegion(cohort.getCohortId());
            if (parent != null && needsCampaign(parent.getOwningStateId(), cohort.getStoredPosition(), level)) {
                List<BlockPos> remaining = new ArrayList<>(cohort.getWaypoints());
                raiseArmy(parent.getOwningStateId(), cohort.getStoredPosition(), List.of(cohort.getCohortId())).ifPresent(a -> {
                    remaining.forEach(a::addWaypoint);
                    enterTerritory(a, level);
                });
            }
            if (parent != null) parent.syncMainPosition();
            changed = true;
        }
        if (level.getGameTime() % 20 == 0) {
            for (Army army : new ArrayList<>(activeArmies.values())) {
                if (army.isEngaged() || !army.isViable(this)) continue;
                if (army.isOnCampaign()) {
                    Campaign campaign = getCampaign(army.getCampaignId());
                    if (campaign != null) {
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
                    if (!cohort.isAlive() || cohort.isDeployed()) continue;
                    if (hasEnemyNear(legion.getOwningStateId(), cohort.getStoredPosition(), level)) {
                        Army defender = wrapNearbyDefenders(legion.getOwningStateId(), cohort.getStoredPosition());
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

    private boolean needsCampaign(UUID stateId, BlockPos position, ServerLevel level) {
        ChunkData claim = ClaimManager.get(level).getClaim(new ChunkPos(position.getX() >> 4, position.getZ() >> 4));
        return claim == null || (!stateId.equals(claim.getOwnerUUID()) && !StateManager.get(level).hasPassage(stateId, claim.getOwnerUUID()));
    }

    //territory crossing function: checks if the army is entering a foreign territory and declares war if necessary, then creates a campaign for the army
    private void enterTerritory(Army army, ServerLevel level) {
        BlockPos position = army.getStoredPosition();
        ChunkData claim = ClaimManager.get(level).getClaim(new ChunkPos(position.getX() >> 4, position.getZ() >> 4));
        StateManager states = StateManager.get(level);
        if (claim != null && !army.getOwningStateId().equals(claim.getOwnerUUID())) {
            if (states.hasPassage(army.getOwningStateId(), claim.getOwnerUUID())) return;
            if (!states.areAtWar(army.getOwningStateId(), claim.getOwnerUUID())) {
                states.declareWar(army.getOwningStateId(), claim.getOwnerUUID());
                states.notifyState(level, army.getOwningStateId(), "Your army crossed a foreign border without passage. War has begun.");
                states.notifyState(level, claim.getOwnerUUID(), "A foreign army crossed your border without passage. War has begun.");
            }
        } else if (claim != null) return;
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
    private boolean hasHostilePresence(UUID stateId, BlockPos position, ServerLevel level) {
        StateManager states = StateManager.get(level);
        // An ongoing battle is still a threat and must not permit instant recruitment beside it.
        return activeArmies.values().stream().anyMatch(a -> a.isViable(this)
                && states.areAtWar(stateId, a.getOwningStateId()) && near(position, a.getStoredPosition(), ENGAGEMENT_RANGE))
                || activeLegions.values().stream().filter(l -> states.areAtWar(stateId, l.getOwningStateId()))
                .flatMap(l -> l.allCohorts().stream()).anyMatch(c -> c.isAlive() && near(position, c.getStoredPosition(), ENGAGEMENT_RANGE));
    }

    /** Includes a garrison in its defensive battle without losing its settlement assignment. */
    private Army wrapNearbyDefenders(UUID stateId, BlockPos position) {
        Army defender = new Army(UUID.randomUUID(), stateId, position);
        for (Legion legion : getLegionsForState(stateId)) {
            for (Cohort cohort : legion.allCohorts()) {
                if (cohort.isAlive() && !cohort.isDeployed() && near(position, cohort.getStoredPosition(), ASSEMBLY_RANGE)
                        && defender.canAddCohort(cohort, this)) defender.addCohortId(cohort.getCohortId());
            }
        }
        if (defender.getDeployedCohortIds().isEmpty()) return null;
        registerArmy(defender);
        return defender;
    }

    //engagement check function: checks if the army is within engagement range of any enemy armies or legions, and starts a battle if so
    private boolean checkCollisionAndEngage(Army army, ServerLevel level) {
        if (army.isEngaged() || !army.isViable(this)) return false;
        StateManager states = StateManager.get(level);
        for (Army other : new ArrayList<>(activeArmies.values())) {
            if (other.isEngaged() || !other.isViable(this) || !states.areAtWar(army.getOwningStateId(), other.getOwningStateId())) continue;
            if (near(army.getStoredPosition(), other.getStoredPosition(), ENGAGEMENT_RANGE)) {
                createCampaign(army.getOwningStateId(), army.getArmyId());
                createCampaign(other.getOwningStateId(), other.getArmyId());
                BattleManager.get(level).startBattle(army, other, level);
                return true;
            }
        }
        for (Legion legion : new ArrayList<>(activeLegions.values())) {
            if (!states.areAtWar(army.getOwningStateId(), legion.getOwningStateId())) continue;
            for (Cohort cohort : legion.allCohorts()) {
                if (cohort.isAlive() && !cohort.isDeployed() && near(army.getStoredPosition(), cohort.getStoredPosition(), ENGAGEMENT_RANGE)) {
                    Army defender = wrapNearbyDefenders(legion.getOwningStateId(), cohort.getStoredPosition());
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
    private static void move(double x, double z, Queue<BlockPos> waypoints, BiConsumer<Double, Double> setter) {
        BlockPos target = waypoints.peek();
        if (target == null) return;
        double dx = target.getX() + 0.5 - x, dz = target.getZ() + 0.5 - z;
        double distance = Math.hypot(dx, dz);
        double speed = 0.2; // 0.8 blocks/second at five-tick intervals; terrain modifiers are deferred. and also check if this is too fast or slow
        if (distance <= speed) {
            setter.accept(target.getX() + 0.5, target.getZ() + 0.5);
            waypoints.poll();
        } else setter.accept(x + dx / distance * speed, z + dz / distance * speed);
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
            if (!payload.isQueueing()) army.clearWaypoints();
            if (army.getWaypoints().size() < MAX_WAYPOINTS) army.addWaypoint(payload.targetPos());
            manager.setDirty();
            ModNetworking.sendSnapshots(player);
        });
    }

    public static void handleDispatchLegion(DispatchLegionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ArmyManager manager = get(player.level());
            Legion legion = manager.getLegion(payload.legionId());
            if (legion == null || !controls(player, legion.getOwningStateId()) || !validTarget(player.level(), payload.targetPos())) return;
            Cohort main = legion.getMainCohort();
            if (main == null || main.isDeployed() || main.isGarrisoned()) {
                tell(player, "Move the army containing the main cohort, or release its assignment first.");
                return;
            }
            if (!payload.isQueueing()) legion.clearWaypoints();
            if (legion.getWaypoints().size() < MAX_WAYPOINTS) legion.addWaypoint(payload.targetPos());
            manager.setDirty();
            ModNetworking.sendSnapshots(player);
        });
    }

    public static void handleDisbandArmy(DisbandArmyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ArmyManager manager = get(player.level());
            Army army = manager.getArmy(payload.armyId());
            if (army != null) {
                if (!controls(player, army.getOwningStateId())) return;
                tell(player, manager.disbandArmy(army.getArmyId(), false) ? "Army wrapper released. Cohorts remain at their locations."
                        : "End the campaign and finish any battle before releasing this army.");
                return;
            }
            Legion legion = manager.getLegion(payload.armyId());
            if (legion == null || !controls(player, legion.getOwningStateId())) return;
            if (!manager.canDissolveLegion(legion)) {
                tell(player, "End its campaign and release every army wrapper before dissolving this legion.");
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
            Optional<Army> army = get(player.level()).raiseArmy(state.getStateId(), payload.initialPosition(), payload.selectedCohortIds());
            if (army.isPresent()) {
                ArmyManager manager = get(player.level());
                for (UUID id : army.get().getDeployedCohortIds()) {
                    Legion parent = manager.getParentLegion(id);
                    if (parent != null && parent.getMainCohort() != null && id.equals(parent.getMainCohort().getCohortId())) parent.clearWaypoints();
                }
            }
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
            if (army != null && army.isEngaged()) return false;
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
                case "RECRUIT_LEGION" -> {
                    SettlementData settlement = states.getSettlement(payload.targetId());
                    if (settlement == null || !state.getStateId().equals(settlement.getOwningStateId())) return;
                    if (manager.hasHostilePresence(state.getStateId(), settlement.getCenterAltarPos(), player.level())) {
                        tell(player, "Cannot recruit while hostile forces threaten this settlement.");
                        return;
                    }
                    boolean free = states.canClaimFreeLegion(player.getUUID());
                    if (!free && state.getTreasuryBalance() < LEGION_RECRUIT_COST) { tell(player, "A legion costs 5,000 emeralds."); return; }
                    Optional<Legion> legion = manager.raiseLegion(state, settlement.getCenterAltarPos());
                    if (legion.isEmpty()) { tell(player, "Your state's legion limit has been reached."); return; }
                    if (free) states.claimFirstLegionGrant(player.getUUID());
                    else state.deductFunds(LEGION_RECRUIT_COST);
                    states.setDirty();
                    tell(player, free ? "First Legion Free!" : "Legion recruited.");
                }
                case "RECRUIT_AUXILIARY" -> {
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
                    if (!legion.addAuxiliary(auxiliary)) { tell(player, "This legion already has ten living auxiliary formations."); return; }
                    manager.registerCohort(auxiliary);
                    state.deductFunds(LIGHT_INFANTRY_COST);
                    states.setDirty();
                    tell(player, "50 light infantry recruited. Their losses are permanent.");
                }
                case "REGROUP" -> {
                    Legion legion = manager.getLegion(payload.targetId());
                    if (legion == null || !state.getStateId().equals(legion.getOwningStateId())) return;
                    Cohort main = legion.getMainCohort();
                    if (main == null) return;
                    for (Cohort cohort : legion.allCohorts()) {
                        if (cohort != main && cohort.isAlive() && !cohort.isDeployed() && !cohort.isGarrisoned()) {
                            cohort.clearWaypoints();
                            cohort.addWaypoint(main.getStoredPosition());
                        }
                    }
                    manager.setDirty();
                    tell(player, "Available detachments ordered to the main cohort's current location.");
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
        activeArmies.values().forEach(this::syncCohortPositions);
        activeLegions.values().forEach(Legion::syncMainPosition);
        locationsReconciled = true;
        setDirty();
    }

    public Cohort resolveCohort(UUID id) { return cohortRegistry.get(id); }
    public Legion getParentLegion(UUID cohortId) { return activeLegions.get(cohortParents.get(cohortId)); }
    public Legion getLegion(UUID id) { return activeLegions.get(id); }
    public Army getArmy(UUID id) { return activeArmies.get(id); }
    public Campaign getCampaign(UUID id) { return activeCampaigns.get(id); }
    public Collection<Cohort> getAllCohorts() { return Collections.unmodifiableCollection(cohortRegistry.values()); }
    public Collection<Legion> getAllLegions() { return Collections.unmodifiableCollection(activeLegions.values()); }
    public Collection<Army> getAllArmies() { return Collections.unmodifiableCollection(activeArmies.values()); }
    public List<Legion> getLegionsForState(UUID stateId) {
        return stateToLegionIndex.getOrDefault(stateId, List.of()).stream().map(activeLegions::get).filter(Objects::nonNull).toList();
    }
    public List<Army> getArmiesForState(UUID stateId) {
        return stateToArmyIndex.getOrDefault(stateId, List.of()).stream().map(activeArmies::get).filter(Objects::nonNull).toList();
    }
    public int getLegionCountForState(UUID stateId) { return stateToLegionIndex.getOrDefault(stateId, List.of()).size(); }

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
        return root;
    }

    public static ArmyManager fromTag(CompoundTag root) {
        ArmyManager manager = new ArmyManager();
        readTags(root, "Legions", tag -> manager.registerLegion(Legion.fromNBT(tag)));
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
                else army.setCampaignId(campaign.getCampaignId());
            }
            if (campaign.isActive() && !campaign.isEmpty()) manager.activeCampaigns.put(campaign.getCampaignId(), campaign);
        });
        for (Cohort cohort : manager.cohortRegistry.values()) {
            Army army = manager.getArmy(cohort.getAssignedArmyId());
            if (army == null || !army.getDeployedCohortIds().contains(cohort.getCohortId())) cohort.setAssignedArmyId(null);
        }
        for (Army army : manager.activeArmies.values()) {
            if (manager.getCampaign(army.getCampaignId()) == null) army.setCampaignId(null);
            manager.syncCohortPositions(army);
        }
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
