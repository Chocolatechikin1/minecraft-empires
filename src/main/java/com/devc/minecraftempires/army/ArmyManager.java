package com.devc.minecraftempires.army;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.combat.BattleManager;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateTier;
import com.devc.minecraftempires.territory.ClaimManager;
import com.devc.minecraftempires.territory.SettlementData;
import com.devc.minecraftempires.territory.ChunkData;
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
import com.devc.minecraftempires.network.packet.DispatchArmyPayload;
import com.devc.minecraftempires.network.packet.DispatchLegionPayload;
import com.devc.minecraftempires.network.packet.DisbandArmyPayload;
import com.devc.minecraftempires.network.packet.ComposeArmyPayload;
import com.devc.minecraftempires.network.packet.GarrisonCohortPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.*;

/**
 * Primary server-side data store for all Legions, Armies, and Campaigns.
 *
 * Two-layer grand-strategy system:
 *  - Legions: permanent organisational units. Visible on the map. Hold Cohorts.
 *             Can move in friendly territory without an Army wrapper.
 *  - Armies:  operational groupings of Cohorts. Enter BattleSessions.
 *             Auto-created when a Legion/Army triggers combat.
 *  - Campaigns: wrap Armies through multi-battle operations.
 *
 * The cohortRegistry is a flat Map<UUID, Cohort> that allows O(1) lookup of any
 * Cohort by ID without scanning all Legions. It is populated at load time and
 * kept in sync whenever Cohorts are added/removed.
 */
public class ArmyManager extends SavedData {
    private static final String DATA_NAME = "minecraftempires_armies";
    private static final Codec<ArmyManager> CODEC = CompoundTag.CODEC.xmap(ArmyManager::fromTag, ArmyManager::toTag);
    public static final SavedDataType<ArmyManager> TYPE = new SavedDataType<>(
            Identifier.withDefaultNamespace(DATA_NAME),
            ArmyManager::new,
            CODEC,
            DataFixTypes.LEVEL
    );

    private final Map<UUID, Legion> activeLegions = new HashMap<>(); //all active Legions, keyed by legionId
    private final Map<UUID, List<UUID>> stateToLegionIndex = new HashMap<>(); //all legions owned by the state
    private final Map<UUID, Army> activeArmies = new HashMap<>(); //active armies
    private final Map<UUID, List<UUID>> stateToArmyIndex = new HashMap<>(); //all armies owned by the state
    private final Map<UUID, Campaign> activeCampaigns = new HashMap<>(); //all campaigns
    private final Map<UUID, Cohort> cohortRegistry = new HashMap<>(); //all cohorts in all legions

    public ArmyManager() {}

    public static ArmyManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    //raise legion function, checks if the state can raise a legion, if so creates one and registers it
    public Optional<Legion> raiseLegion(StateData state, BlockPos pos) {
        StateTier tier = state.getCurrentTier();
        int cap = tier.getMaxLegions();
        UUID stateId = state.getStateId();

        if (cap <= 0) { //check how many legions the state has
            MinecraftEmpires.LOGGER.info("State '{}' cannot field any Legions.", state.getStateName());
            return Optional.empty();
        }
        int currentCount = getLegionCountForState(stateId);
        if (currentCount >= cap) { //check if the state has reached its legion cap
            MinecraftEmpires.LOGGER.info(" State '{}' at Legion cap ({}/{}).",
                    state.getStateName(), currentCount, cap);
            return Optional.empty();
        }
        //success, register legion
        Legion legion = new Legion(UUID.randomUUID(), stateId, pos);
        registerLegion(legion);
        MinecraftEmpires.LOGGER.info("Raised Legion {} for state '{}' ({}/{}).", legion.getLegionId(), state.getStateName(), currentCount + 1, cap);
        return Optional.of(legion);
    }

    //disbands legion,clears cohorts in the army and then removes it
    public boolean disbandLegion(UUID legionId) {
        Legion removed = activeLegions.remove(legionId);
        if (removed == null) return false;

        // Un-register all cohorts from the flat registry and un-deploy them
        for (Cohort c : allCohortsOf(removed)) {
            cohortRegistry.remove(c.getCohortId());
            if (c.getAssignedArmyId() != null) {
                Army a = activeArmies.get(c.getAssignedArmyId());
                if (a != null) a.removeCohortId(c.getCohortId());
            }
        }

        List<UUID> stateList = stateToLegionIndex.get(removed.getOwningStateId());
        if (stateList != null) {
            stateList.remove(legionId);
            if (stateList.isEmpty()) stateToLegionIndex.remove(removed.getOwningStateId());
        }
        setDirty();
        MinecraftEmpires.LOGGER.info("Disbanded Legion {} (owner: {}).",
                legionId, removed.getOwningStateId());
        return true;
    }

    private void registerLegion(Legion legion) {
        activeLegions.put(legion.getLegionId(), legion);
        stateToLegionIndex.computeIfAbsent(legion.getOwningStateId(), k -> new ArrayList<>()).add(legion.getLegionId());
        // Register all existing cohorts into the flat registry
        for (Cohort c : allCohortsOf(legion)) {
            cohortRegistry.put(c.getCohortId(), c);
        }
        setDirty();
    }

    //register a single cohort
    public void registerCohort(Cohort cohort) {
        cohortRegistry.put(cohort.getCohortId(), cohort);
    }

    //raise an army at current position and load cohorts into it
    public Optional<Army> raiseArmy(UUID owningStateId, BlockPos pos, List<UUID> cohortIds) {
        if (cohortIds.isEmpty()) return Optional.empty();

        Army army = new Army(UUID.randomUUID(), owningStateId, pos);
        for (UUID cohortId : cohortIds) {
            Cohort c = cohortRegistry.get(cohortId);
            if (c == null) {
                MinecraftEmpires.LOGGER.warn("raiseArmy: unknown cohort {}, aborting.", cohortId);
                return Optional.empty();
            }
            if (c.isDeployed()) {
                MinecraftEmpires.LOGGER.warn("raiseArmy: cohort {} already deployed, aborting.", cohortId);
                return Optional.empty();
            }
            if (!army.canAddCohort(c, this)) {
                MinecraftEmpires.LOGGER.warn("raiseArmy: cap exceeded for cohort type {}, aborting.", c.getType());
                return Optional.empty();
            }
            army.addCohortId(cohortId);
            c.setAssignedArmyId(army.getArmyId());
        }

        registerArmy(army);
        MinecraftEmpires.LOGGER.info("Raised Army {} for state {} with {} cohorts.", army.getArmyId(), owningStateId, cohortIds.size());
        return Optional.of(army);
    }

    //disbands an army, releases non-garrisoned cohorts back to their Legion pools, and sets isActive to false
    public boolean disbandArmy(UUID armyId, boolean forceDisband) {
        Army army = activeArmies.get(armyId);
        if (army == null) return false;

        for (UUID cohortId : new ArrayList<>(army.getDeployedCohortIds())) {
            Cohort c = cohortRegistry.get(cohortId);
            if (c != null) {
                if (!c.isGarrisoned()) {
                    // Non-garrisoned: return to Legion pool
                    c.setAssignedArmyId(null);
                } else {
                    // Garrisoned: stays garrisoned at settlement, just un-links from Army
                    c.setAssignedArmyId(null);
                    // isGarrisoned and garrisonedSettlementId intentionally preserved
                }
            }
        }

        activeArmies.remove(armyId);
        List<UUID> stateList = stateToArmyIndex.get(army.getOwningStateId());
        if (stateList != null) {
            stateList.remove(armyId);
            if (stateList.isEmpty()) stateToArmyIndex.remove(army.getOwningStateId());
        }

        // Unlink from campaign if applicable
        if (army.getCampaignId() != null) {
            Campaign campaign = activeCampaigns.get(army.getCampaignId());
            if (campaign != null) campaign.removeArmy(armyId);
        }

        setDirty();
        MinecraftEmpires.LOGGER.info("Disbanded Army {} (owner: {}).", armyId, army.getOwningStateId());
        return true;
    }

    private void registerArmy(Army army) {
        activeArmies.put(army.getArmyId(), army);
        stateToArmyIndex
                .computeIfAbsent(army.getOwningStateId(), k -> new ArrayList<>())
                .add(army.getArmyId());
        setDirty();
    }

    //legion to army, for if the player just wants one legion to become the army for a campaign
    public Optional<Army> autoWrapLegionInArmy(Legion legion) {
        List<UUID> availableIds = new ArrayList<>();
        for (Cohort c : allCohortsOf(legion)) {
            if (!c.isDeployed()) availableIds.add(c.getCohortId());
        }
        if (availableIds.isEmpty()) return Optional.empty();

        Optional<Army> result = raiseArmy(legion.getOwningStateId(), legion.getStoredPosition(), availableIds);
        if (result.isPresent()) {
            legion.clearWaypoints(); // Army takes over movement
        }
        return result;
    }

    //army campaign launcher function
    public Campaign createCampaign(UUID owningStateId, UUID armyId) {
        Campaign campaign = new Campaign(UUID.randomUUID(), owningStateId);
        campaign.addArmy(armyId);
        Army army = activeArmies.get(armyId); //load the desired army
        if (army != null) army.setCampaignId(campaign.getCampaignId()); //link the army to the campaign
        activeCampaigns.put(campaign.getCampaignId(), campaign); //register the campaign
        setDirty(); //mark to be saved to disk
        MinecraftEmpires.LOGGER.info("Campaign {} created for Army {}.", campaign.getCampaignId(), armyId);
        return campaign;
    }

    //campaign end checker
    public boolean endCampaign(UUID campaignId, ServerPlayer player) {
        Campaign campaign = activeCampaigns.get(campaignId); //get the desired campaign
        if (campaign == null) return false;

        if (!campaign.checkEndConditions(this)) { //checks with helper function, if failed, inform player
            player.sendSystemMessage(Component.literal("§cCannot end campaign, enemy forces are still threatening your territories."));
            return false;
        }

        //if successful, disband the campaign and remove it from the active campaigns list
        campaign.disbandCampaign(this); //call helper function
        activeCampaigns.remove(campaignId); //remove from active campaigns
        setDirty(); //mark to be saved to disk
        player.sendSystemMessage(Component.literal("§aCampaign ended successfully."));
        return true;
    }

    public Cohort resolveCohort(UUID cohortId) {
        return cohortRegistry.get(cohortId);
    }

    //class garbage collector, disbands armies and legions that are no longer viable 
    public void runGarbageCollection() {
        // Armies: disband if no alive, non-garrisoned cohorts remain
        List<UUID> armiesToDisband = new ArrayList<>();
        for (Army army : activeArmies.values()) {
            if (!army.isViable(this)) armiesToDisband.add(army.getArmyId()); //mark for disbanding
        }
        for (UUID id : armiesToDisband) { //disband marked army
            MinecraftEmpires.LOGGER.info("GC: Army {} is no longer viable — disbanding.", id);
            disbandArmy(id, true);
        }

        // Legions: disband if not viable (no alive cohorts of required types)
        List<UUID> legionsToDisband = new ArrayList<>();
        for (Legion legion : activeLegions.values()) {
            if (!legion.isViable()) legionsToDisband.add(legion.getLegionId());
        }
        for (UUID id : legionsToDisband) { //disband marked legion
            MinecraftEmpires.LOGGER.info("GC: Legion {} is no longer viable — disbanding.", id);
            disbandLegion(id);
        }

        if (!armiesToDisband.isEmpty() || !legionsToDisband.isEmpty()) {
            MinecraftEmpires.LOGGER.info("GC: {} Army(s) and {} Legion(s) disbanded.", armiesToDisband.size(), legionsToDisband.size());
        }
    }

    //tick class, moves armies and legions along their waypoints, checks for collisions and starts battles if necessary
    public void tickArmies(ServerLevel level) {
        boolean requiresSave = false;

        // Move Armies
        for (Army army : activeArmies.values()) {
            if (army.isEngaged()) continue;
            if (!army.getWaypoints().isEmpty()) {
                moveEntity(army.getStoredPosition(), army.getWaypoints(),
                        army::setStoredPosition);
                if (checkCollisionAndEngage(army, level)) requiresSave = true;
                requiresSave = true;
            }
        }

        // Move standalone Legions (those with available cohorts and their own waypoints)
        for (Legion legion : activeLegions.values()) {
            if (!legion.getWaypoints().isEmpty() && legion.hasAvailableCohorts()) {
                moveEntity(legion.getStoredPosition(), legion.getWaypoints(),
                        legion::setStoredPosition);
                if (checkLegionCollision(legion, level)) requiresSave = true;
                requiresSave = true;
            }
        }

        if (requiresSave) setDirty();
    }

    //army movement function, moves the army along its waypoints at a fixed speed, and removes the waypoint if reached
    private void moveEntity(BlockPos current, Queue<BlockPos> waypoints, java.util.function.Consumer<BlockPos> setPos) {
        BlockPos target = waypoints.peek();
        double dx = target.getX() - current.getX();
        double dz = target.getZ() - current.getZ();
        double distance = Math.hypot(dx, dz);
        // 0.2 blocks/tick = 4 blocks/second at 20 ticks/s (modify if needed later)
        double marchSpeed = 0.2;

        if (distance <= marchSpeed) {
            setPos.accept(target);
            waypoints.poll();
        } 
        else{
            double ratio = marchSpeed / distance;
            int stepX = current.getX() + (int) Math.round(dx * ratio);
            int stepZ = current.getZ() + (int) Math.round(dz * ratio);
            setPos.accept(new BlockPos(stepX, current.getY(), stepZ));
        }
    }

    private static final int ENGAGEMENT_DIST_SQ = 2500; // 50 blocks squared

    //army collision checker, also wraps a legion or military unit that isn't in an army in one and pushes it into battle
    private boolean checkCollisionAndEngage(Army movingArmy, ServerLevel level) {
        //get the moving army's position and check if it's engaged, if so return false
        if (movingArmy.isEngaged()) return false;
        BlockPos pos = movingArmy.getStoredPosition();

        // Check against other Armies
        for (Army other : activeArmies.values()) {
            if (other.getArmyId().equals(movingArmy.getArmyId())) continue;
            if (other.getOwningStateId().equals(movingArmy.getOwningStateId())) continue;
            if (other.isEngaged()) continue;
            if (pos.distSqr(other.getStoredPosition()) < ENGAGEMENT_DIST_SQ) {
                // Ensure campaign exists for moving army
                ensureCampaign(movingArmy);
                BattleManager.get(level).startBattle(movingArmy, other, level);
                MinecraftEmpires.LOGGER.info("Engagement: Army {} vs Army {} at {}.", movingArmy.getArmyId(), other.getArmyId(), pos);
                return true;
            }
        }

        // Check against standalone enemy Legions (note: it only checks legions, but not inidividual cohorts or military units, adjust later)
        for (Legion legion : activeLegions.values()) {
            if (legion.getOwningStateId().equals(movingArmy.getOwningStateId())) continue;
            if (!legion.hasAvailableCohorts()) continue;
            if (pos.distSqr(legion.getStoredPosition()) < ENGAGEMENT_DIST_SQ) {
                Optional<Army> wrappedOpt = autoWrapLegionInArmy(legion);
                if (wrappedOpt.isPresent()) {
                    ensureCampaign(movingArmy);
                    BattleManager.get(level).startBattle(movingArmy, wrappedOpt.get(), level);
                    MinecraftEmpires.LOGGER.info("Engagement: Army {} vs auto-wrapped Legion {} at {}.", movingArmy.getArmyId(), legion.getLegionId(), pos);
                    return true;
                }
            }
        }

        // TODO: Once Detachments / Garrison Cohorts can leave a settlement as their own map entity,
        //       this method must also check for Detachment-vs-Army and Detachment-vs-Legion collisions.
        //add the other military unit checkers here
        return false;
    }

    //checks individual legions for collisions with other legions or armies, and wraps them in an army if necessary
    private boolean checkLegionCollision(Legion movingLegion, ServerLevel level) {
        BlockPos pos = movingLegion.getStoredPosition();

        // Legion vs Army
        for (Army enemy : activeArmies.values()) { 
            if (enemy.getOwningStateId().equals(movingLegion.getOwningStateId())) continue;
            if (enemy.isEngaged()) continue;
            if (pos.distSqr(enemy.getStoredPosition()) < ENGAGEMENT_DIST_SQ) {
                Optional<Army> wrapped = autoWrapLegionInArmy(movingLegion);
                if (wrapped.isPresent()) {
                    ensureCampaign(wrapped.get());
                    BattleManager.get(level).startBattle(wrapped.get(), enemy, level);
                    MinecraftEmpires.LOGGER.info("Engagement: Legion {} (wrapped) vs Army {} at {}.",
                            movingLegion.getLegionId(), enemy.getArmyId(), pos);
                    return true;
                }
            }
        }

        // Legion vs Legion
        for (Legion other : activeLegions.values()) { //note: checks for armies, but not individual cohorts or military units, adjust later
            if (other.getLegionId().equals(movingLegion.getLegionId())) continue;
            if (other.getOwningStateId().equals(movingLegion.getOwningStateId())) continue;
            if (!other.hasAvailableCohorts()) continue;
            if (pos.distSqr(other.getStoredPosition()) < ENGAGEMENT_DIST_SQ) {
                Optional<Army> wrappedA = autoWrapLegionInArmy(movingLegion);
                Optional<Army> wrappedB = autoWrapLegionInArmy(other);
                if (wrappedA.isPresent() && wrappedB.isPresent()) {
                    ensureCampaign(wrappedA.get());
                    BattleManager.get(level).startBattle(wrappedA.get(), wrappedB.get(), level);
                    MinecraftEmpires.LOGGER.info("[ArmyManager] Engagement: Legion {} vs Legion {} (both wrapped) at {}.",
                            movingLegion.getLegionId(), other.getLegionId(), pos);
                    return true;
                }
            }
        }

        //add the other military unit checkers here
        return false;
    }

    //campaign auto creater function
    private void ensureCampaign(Army army) {
        if (!army.isOnCampaign()) {
            createCampaign(army.getOwningStateId(), army.getArmyId());
        }
    }

    //network handlers for army dispatch, legion dispatch, army disband, army compose, and cohort garrisoning
    public static void handleDispatchArmy(final DispatchArmyPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> { //queues the work to be done on the server thread
            if (!(context.player() instanceof ServerPlayer player)) return;
            ArmyManager manager = ArmyManager.get(player.level());
            Army army = manager.getArmy(payload.armyId());
            if (army == null) return;

            if (!payload.isQueueing()) army.clearWaypoints();
            army.addWaypoint(payload.targetPos());

            // Auto-create campaign if the target is in non-friendly territory
            if (!army.isOnCampaign()) {
                ChunkPos targetChunk = new ChunkPos(payload.targetPos().getX() >> 4, payload.targetPos().getZ() >> 4); // Convert BlockPos to ChunkPos
                ClaimManager claimManager = ClaimManager.get(player.level());
                ChunkData chunkData = claimManager.getClaim(targetChunk);
                boolean isFriendly = chunkData != null && army.getOwningStateId().equals(chunkData.getOwnerUUID());
                if (!isFriendly) {
                    manager.createCampaign(army.getOwningStateId(), army.getArmyId());
                }
            }

            manager.setDirty();
            MinecraftEmpires.LOGGER.info("Army {} dispatched to {} (queuing={}).", payload.armyId(), payload.targetPos(), payload.isQueueing());
        });
    }

    //single legion mover function, moves the legion to the target position, and clears waypoints if not queueing
    public static void handleDispatchLegion(final DispatchLegionPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> { //queues the work to be done on the server thread
            if (!(context.player() instanceof ServerPlayer player)) return;
            ArmyManager manager = ArmyManager.get(player.level());
            Legion legion = manager.getLegion(payload.legionId());
            if (legion == null) return;

            if (!payload.isQueueing()) legion.clearWaypoints();
            legion.addWaypoint(payload.targetPos());

            manager.setDirty();
            MinecraftEmpires.LOGGER.info("Legion {} dispatched to {} (queuing={}).", payload.legionId(), payload.targetPos(), payload.isQueueing());
        });
    }

    /** hmm double check this function it should not disband legions that are in campaigns, but it should disband armies that are in campaigns and end the campaign if so 
     * Disband handler.
     * If the target UUID is an Army that belongs to an active Campaign, routes to endCampaign.
     * If it's a standalone Army, disbands directly.
     * If it's a Legion UUID, disbands the Legion.
     */
    //army disbander function, checks if the army is in a campaign, if so ends the campaign, otherwise disbands the army or legion
    public static void handleDisbandArmy(final DisbandArmyPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ArmyManager manager = ArmyManager.get(player.level());

            Army army = manager.getArmy(payload.armyId());
            if (army != null) {
                if (army.isOnCampaign()) {
                    manager.endCampaign(army.getCampaignId(), player);
                } 
                else{
                    manager.disbandArmy(army.getArmyId(), false);
                    player.sendSystemMessage(Component.literal("§aArmy disbanded."));
                }
                return;
            }

            // Try Legion
            Legion legion = manager.activeLegions.get(payload.armyId());
            if (legion != null) {
                // Block disbanding a Legion whose cohorts are actively deployed in a Campaign.
                // Players must end the Campaign first before the Legion can be dissolved.
                boolean inActiveCampaign = legion.getInfantryCohorts().stream().anyMatch(c -> {
                    if (c.getAssignedArmyId() == null) return false;
                    Army a = manager.activeArmies.get(c.getAssignedArmyId());
                    return a != null && a.isOnCampaign();
                }) || legion.getCavalrySquadrons().stream().anyMatch(c -> {
                    if (c.getAssignedArmyId() == null) return false;
                    Army a = manager.activeArmies.get(c.getAssignedArmyId());
                    return a != null && a.isOnCampaign();
                }) || legion.getAuxiliaries().stream().anyMatch(c -> {
                    if (c.getAssignedArmyId() == null) return false;
                    Army a = manager.activeArmies.get(c.getAssignedArmyId());
                    return a != null && a.isOnCampaign();
                });

                if (inActiveCampaign) {
                    player.sendSystemMessage(Component.literal("§cCannot disband a Legion that is actively deployed in a Campaign. End the Campaign first."));
                    return;
                }

                manager.disbandLegion(payload.armyId());
                manager.setDirty();
                player.sendSystemMessage(Component.literal("§aLegion disbanded."));
            }
        });
    }

    //army creation helper function, checks if the player can create an army with the selected cohorts, and creates it if possible
    public static void handleComposeArmy(final ComposeArmyPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ArmyManager manager = ArmyManager.get(player.level()); //get player server level (?)
            StateManager stateManager = StateManager.get(player.level()); //get statte level
            StateData state = stateManager.getStateByPlayer(player.getUUID()); //get state data
            if (state == null) return;

            Optional<Army> result = manager.raiseArmy(state.getStateId(), payload.initialPosition(), payload.selectedCohortIds());
            if (result.isPresent()) {
                manager.setDirty();
                player.sendSystemMessage(Component.literal("§aArmy formed."));
            } 
            else {
                player.sendSystemMessage(Component.literal("§cFailed to form Army."));
            }
        });
    }

    //garrison function, checks if the cohort is garrisoned or not, and sets the garrison status accordingly, also sets martial law if garrisoned
    public static void handleGarrisonCohort(final GarrisonCohortPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ArmyManager manager = ArmyManager.get(player.level());
            Cohort cohort = manager.resolveCohort(payload.cohortId());
            if (cohort == null) return;

            StateManager stateManager = StateManager.get(player.level());
            SettlementData settlement = stateManager.getSettlement(payload.settlementId());

            if (payload.ungarrison()) {
                // Remove garrison
                cohort.setIsGarrisoned(false);
                UUID prevSettlement = cohort.getGarrisonedSettlementId();
                cohort.setGarrisonedSettlementId(null);
                if (prevSettlement != null && settlement != null) {
                    // Check if any other cohort is still garrisoning this settlement
                    boolean othersGarrisoning = manager.cohortRegistry.values().stream().anyMatch(c -> c.isGarrisoned() && prevSettlement.equals(c.getGarrisonedSettlementId()));
                    if (!othersGarrisoning && settlement != null) {
                        settlement.setMartialLaw(false);
                        MinecraftEmpires.LOGGER.info("Martial Law lifted at {}.", settlement.getSettlementName());
                    }
                }
                player.sendSystemMessage(Component.literal("§aCohort returned to legion."));
            } 
            else{
                // Garrison the cohort
                cohort.setIsGarrisoned(true);
                cohort.setGarrisonedSettlementId(payload.settlementId());
                if (settlement != null && !settlement.isMartialLaw()) {
                    settlement.setMartialLaw(true);
                    MinecraftEmpires.LOGGER.info("Martial Law established at {} (STUB — loyalty pending).", settlement.getSettlementName());
                }
                player.sendSystemMessage(Component.literal("§aCohort garrisoning settlement (Martial Law active)."));
            }

            manager.setDirty();
        });
    }

    //getters
    public Legion getLegion(UUID legionId)                    { return activeLegions.get(legionId); }
    public Army   getArmy(UUID armyId)                        { return activeArmies.get(armyId); }
    public Campaign getCampaign(UUID campaignId)              { return activeCampaigns.get(campaignId); }

    public List<Legion> getLegionsForState(UUID stateId) { //utilizes lists, gets O(1) lookup of legions for a state, and returns an unmodifiable list of legions
        List<UUID> ids = stateToLegionIndex.getOrDefault(stateId, Collections.emptyList());
        List<Legion> result = new ArrayList<>(ids.size());
        for (UUID id : ids){ 
            Legion l = activeLegions.get(id); if (l != null) result.add(l); 
        }
        return Collections.unmodifiableList(result);
    }

    public List<Army> getArmiesForState(UUID stateId) {
        List<UUID> ids = stateToArmyIndex.getOrDefault(stateId, Collections.emptyList());
        List<Army> result = new ArrayList<>(ids.size());
        for (UUID id : ids){
            Army a = activeArmies.get(id); if (a != null) result.add(a);
        }
        return Collections.unmodifiableList(result);
    }

    public int getLegionCountForState(UUID stateId) {
        return stateToLegionIndex.getOrDefault(stateId, Collections.emptyList()).size();
    }

    public Collection<Legion> getAllLegions()   { return Collections.unmodifiableCollection(activeLegions.values()); }
    public Collection<Army>   getAllArmies()    { return Collections.unmodifiableCollection(activeArmies.values()); }

    //data serialization and deserialization functions, saves and loads the army manager data to disk
    private CompoundTag toTag() {
        CompoundTag root = new CompoundTag();

        ListTag legionList = new ListTag();
        for (Legion legion : activeLegions.values()) legionList.add(legion.toNBT());
        root.put("Legions", legionList);

        ListTag armyList = new ListTag();
        for (Army army : activeArmies.values()) armyList.add(army.toNBT());
        root.put("Armies", armyList);

        ListTag campaignList = new ListTag();
        for (Campaign campaign : activeCampaigns.values()) campaignList.add(campaign.toNBT());
        root.put("Campaigns", campaignList);

        return root;
    }

    private static ArmyManager fromTag(CompoundTag root) {
        ArmyManager manager = new ArmyManager();

        // 1. Load Legions (and populate cohortRegistry)
        ListTag legionList = root.getList("Legions").orElse(new ListTag());
        for (int i = 0; i < legionList.size(); i++) {
            legionList.getCompound(i).ifPresent(tag -> {
                Legion legion = Legion.fromNBT(tag);
                manager.activeLegions.put(legion.getLegionId(), legion);
                manager.stateToLegionIndex.computeIfAbsent(legion.getOwningStateId(), k -> new ArrayList<>()).add(legion.getLegionId());
                // Populate cohort registry
                for (Cohort c : allCohortsOf(legion)) {
                    manager.cohortRegistry.put(c.getCohortId(), c);
                }
            });
        }

        // 2. Load Armies (cohort assignedArmyId is already saved on each Cohort)
        ListTag armyList = root.getList("Armies").orElse(new ListTag());
        for (int i = 0; i < armyList.size(); i++) {
            armyList.getCompound(i).ifPresent(tag -> {
                Army army = Army.fromNBT(tag);
                // Reconcile: drop stale cohort IDs (cohort was deleted)
                List<UUID> validIds = new ArrayList<>();
                for (UUID cohortId : army.getDeployedCohortIds()) {
                    if (manager.cohortRegistry.containsKey(cohortId)) {
                        validIds.add(cohortId);
                    } 
                    else {
                        MinecraftEmpires.LOGGER.warn("Stale cohort {} in Army {} dropped on load.",cohortId, army.getArmyId());
                    }
                }
                // Re-build the list with only valid IDs
                Army clean = new Army(army.getArmyId(), army.getOwningStateId(), army.getStoredPosition());
                clean.setCampPosition(army.getCampPosition());
                clean.setCurrentBattleId(army.getCurrentBattleId());
                clean.setCampaignId(army.getCampaignId());
                army.getWaypoints().forEach(clean::addWaypoint);
                validIds.forEach(clean::addCohortId);

                manager.activeArmies.put(clean.getArmyId(), clean);
                manager.stateToArmyIndex.computeIfAbsent(clean.getOwningStateId(), k -> new ArrayList<>()).add(clean.getArmyId());
            });
        }

        // 3. Load Campaigns
        ListTag campaignList = root.getList("Campaigns").orElse(new ListTag());
        for (int i = 0; i < campaignList.size(); i++) {
            campaignList.getCompound(i).ifPresent(tag -> {
                Campaign campaign = Campaign.fromNBT(tag);
                if (campaign.isActive()) {
                    manager.activeCampaigns.put(campaign.getCampaignId(), campaign);
                }
            });
        }

        MinecraftEmpires.LOGGER.info("Loaded {} Legion(s), {} Army(s), {} Campaign(s).", manager.activeLegions.size(), manager.activeArmies.size(), manager.activeCampaigns.size());
        return manager;
    }

    //private helper function to get all cohorts of a legion, including infantry, cavalry, and auxiliaries
    private static List<Cohort> allCohortsOf(Legion legion) {
        List<Cohort> all = new ArrayList<>();
        all.addAll(legion.getInfantryCohorts());
        all.addAll(legion.getCavalrySquadrons());
        all.addAll(legion.getAuxiliaries());
        return all;
    }
}
