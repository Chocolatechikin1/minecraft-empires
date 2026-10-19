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

    //tick armies function: called every five ticks to update the positions of all armies and legions, and check for engagements
    //ngl this function got so complicated idek whats going on anymore
    public void tickArmies(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) return;
        reconcileLocations(level);
        tickMoveProposals(level);
        for (Cohort cohort : new ArrayList<>(cohortRegistry.values())) {
            Army army = getArmy(cohort.getAssignedArmyId());
            if (cohort.isAlive() && (army == null || !army.isEngaged())) recoverCohortCrossing(level, cohort);
        }
        boolean changed = false;
        for (Army army : new ArrayList<>(activeArmies.values())) {
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
        for (Cohort cohort : body) moveCohort(level, owner, cohort, retreating);
        orders.clear();
        body.stream().filter(c -> !c.getWaypoints().isEmpty()).findFirst().ifPresent(c -> orders.addAll(c.getWaypoints()));
    }

    //cohort movement function: moves the given cohort along its waypoints, if it is not deployed or garrisoned
    private void moveCohort(ServerLevel level, UUID owner, Cohort cohort, boolean autonomous) {
        if (cohort.getWaypoints().isEmpty()) return;
        if (autonomous) rerouteAutonomous(level, cohort);
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
                        ChunkData claim = ClaimManager.get(level).getClaim(ChunkPos.containing(point));
                        if (claim != null && !proposal.owner.equals(claim.getOwnerUUID())) {
                            StateData state = StateManager.get(level).getState(claim.getOwnerUUID());
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
