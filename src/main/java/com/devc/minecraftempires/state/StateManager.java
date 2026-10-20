package com.devc.minecraftempires.state;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.network.MapDataService;
import com.devc.minecraftempires.network.packet.OpenFoundingPayload;
import com.devc.minecraftempires.territory.ChunkData;
import com.devc.minecraftempires.territory.ClaimManager;
import com.devc.minecraftempires.territory.SettlementData;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

//state manager class: handles all the state data, settlements, and diplomacy between states. It also handles founding settlements and upgrading them to cities.
public class StateManager extends SavedData {
    //all active states, settlements, and related data are stored in these collections
    private final Map<UUID, StateData> activeStates = new HashMap<>();
    private final Map<UUID, StateData> humanStates = new HashMap<>();
    private final Map<UUID, UUID> playerToStateMap = new HashMap<>();
    private final Map<UUID, SettlementData> activeSettlements = new HashMap<>();
    private final Set<BlockPos> abandonedAltarPositions = new HashSet<>();
    private final Map<BlockPos, UUID> pendingAltars = new HashMap<>();
    private final Set<UUID> assistedFounders = new HashSet<>();
    private final Set<UUID> firstLegionGranted = new HashSet<>();
    private final Set<String> wars = new HashSet<>();
    private final Map<UUID, Set<UUID>> enemyIndex = new HashMap<>();
    private final Set<String> passage = new HashSet<>();
    private final Set<String> peaceOffers = new HashSet<>();
    private long lastEconomyGameTime = -1;
    private long lastTimerGameTime = -1;
    // Runtime-only invalidation token; cached networks are rebuilt after loading anyway.
    private long logisticsRevision;
    private final Map<UUID, Long> foundingPolls = new HashMap<>();
    private final Map<BlockPos, Long> foundingDiagnostics = new HashMap<>();
    // Political memory is shared by a human nation's members, never a second world simulation.
    public enum KnowledgeSource { OBSERVATION, DIRECT_EVENT, OWNERSHIP, SHARED }
    public record KnownNation(UUID id, String name, StateTier tier, long observedAt, boolean ai, KnowledgeSource source) {
        public KnownNation(UUID id, String name, StateTier tier, long observedAt) { this(id, name, tier, observedAt, false, KnowledgeSource.OBSERVATION); }
    }
    public record KnownSettlement(UUID id, UUID owner, String name, BlockPos position, int tier, long observedAt, KnowledgeSource source, SettlementData.Kind kind) {
        public KnownSettlement(UUID id, UUID owner, String name, BlockPos position, int tier, long observedAt, KnowledgeSource source) { this(id, owner, name, position, tier, observedAt, source, tier >= 2 ? SettlementData.Kind.CITY : SettlementData.Kind.TOWN); }
        public KnownSettlement(UUID id, UUID owner, String name, BlockPos position, int tier, long observedAt) { this(id, owner, name, position, tier, observedAt, KnowledgeSource.OBSERVATION); }
    }
    public record KnownRegion(long key, UUID owner, long[] land, long observedAt, KnowledgeSource source) {
        public KnownRegion(long key, UUID owner, long[] land, long observedAt) { this(key, owner, land, observedAt, KnowledgeSource.OBSERVATION); }
        public KnownRegion { land = land.clone(); }
        @Override public long[] land() { return land.clone(); }
    }
    private final Map<UUID, Map<Long, KnownRegion>> knownRegions = new HashMap<>();
    public Collection<KnownRegion> getKnownRegions(UUID viewer) { return Collections.unmodifiableCollection(knownRegions.getOrDefault(viewer, Map.of()).values()); }
    /** Future intelligence exchange can import dated facts through this same monotonic merge boundary. */
    public void learnRegion(UUID viewer, KnownRegion fact) {
        StateData observer = getState(viewer); if (observer == null || observer.isAI() || fact.land().length > 64 || fact.observedAt() < 0) return;
        Map<Long, KnownRegion> memory = knownRegions.computeIfAbsent(viewer, ignored -> new HashMap<>());
        KnownRegion before = memory.get(fact.key());
        if (before != null && before.observedAt() <= fact.observedAt() && fact.observedAt() - before.observedAt() < 200
                && Objects.equals(before.owner(), fact.owner()) && java.util.Arrays.equals(before.land(), fact.land()) && before.source() == fact.source()) return;
        if (before == null || fact.observedAt() >= before.observedAt()) { memory.put(fact.key(), fact); setDirty(); }
    }
    public void observePolitics(ServerLevel level, UUID viewer, BlockPos at) {
        StateData nation = getState(viewer); if (nation == null || nation.isAI()) return;
        ClaimManager claims = ClaimManager.get(level); long now = level.getGameTime();
        // An observation discovers the local region's coarse polity, never a whole nation's footprint.
        var region = claims.getStrategicRegions().get(ClaimManager.regionKey(ChunkPos.containing(at)));
        if (region != null) {
            learnRegion(viewer, new KnownRegion(region.key(), region.owner(), region.landMask(), now));
            if (region.owner() != null) observeNation(viewer, region.owner(), now);
        }
        UUID concrete = claims.getStrategicOwner(ChunkPos.containing(at)); if (concrete != null) observeNation(viewer, concrete, now);
        for (SettlementData site : activeSettlements.values()) if (ArmyManager.near(at, site.getCenterAltarPos(), 250)) observeSettlement(viewer, site.getSettlementId(), now);
        var remembered = knownSettlements.get(viewer);
        if (remembered != null && remembered.values().removeIf(fact -> ArmyManager.near(at, fact.position(), 250)
                && (getSettlement(fact.id()) == null || !getSettlement(fact.id()).getCenterAltarPos().equals(fact.position())))) setDirty();
    }
    private final Map<UUID, Map<UUID, KnownNation>> knownNations = new HashMap<>();
    private final Map<UUID, Map<UUID, KnownSettlement>> knownSettlements = new HashMap<>();
    private final WorldNews news = new WorldNews(this::setDirty);
    private final AIStateController aiController = new AIStateController();
    private final Set<UUID> aiInitializations = new HashSet<>();
    private final AIRegionInfo aiRegions = new AIRegionInfo(this);
    private final AIWarManager aiWars = new AIWarManager(this);
    private final PlayerConflictManager playerConflicts = new PlayerConflictManager(this);
    private long interactionSequence;
    private long macroSequence;

    //codec and type for saving and loading the state manager data
    private static final Codec<StateManager> CODEC = CompoundTag.CODEC.xmap(StateManager::fromTag, StateManager::toTag);
    public static final SavedDataType<StateManager> TYPE = new SavedDataType<>(
        Identifier.withDefaultNamespace("minecraftempires_states"), StateManager::new, CODEC, DataFixTypes.LEVEL);

    public static StateManager get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    //getters
    public StateData getState(UUID id) { return activeStates.get(id); }
    public Collection<StateData> getAllStates() { return Collections.unmodifiableCollection(activeStates.values()); }
    public Collection<StateData> getHumanStates() { return Collections.unmodifiableCollection(humanStates.values()); }
    public StateData getStateByPlayer(UUID id) { return activeStates.get(playerToStateMap.get(id)); }
    public Collection<SettlementData> getAllSettlements() { return Collections.unmodifiableCollection(activeSettlements.values()); }
    public SettlementData getSettlement(UUID id) { return activeSettlements.get(id); }
    public long getLastEconomyGameTime() { return lastEconomyGameTime; }
    public void setLastEconomyGameTime(long time) { lastEconomyGameTime = time; setDirty(); }
    public long getLogisticsRevision() { return logisticsRevision; }
    public void markLogisticsChanged() { logisticsRevision++; }
    public WorldNews getNews() { return news; }
    /** Both military representations commit the same recovery transition and one historical report. */
    public boolean recordAIRecovery(StateData state, long now) {
        if (state == null || !state.isAI() || getState(state.getStateId()) != state || state.getMajorDefeatTime() < 0 || now < 0) return false;
        long defeat = state.getMajorDefeatTime();
        state.clearDefeatAssessment(); state.setStrategy(StateData.Strategy.RECOVER); setDirty();
        news.publish(UUID.nameUUIDFromBytes(("recovery:" + state.getStateId() + ":" + defeat).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                now, WorldNews.Kind.RECOVERY, state.getStateId(), state.getStateName(), null, "", null, "", 0);
        return true;
    }
    public AIStateController getAIController() { return aiController; }
    public AIRegionInfo getAIRegions() { return aiRegions; }
    public AIWarManager getAIWars() { return aiWars; }
    public PlayerConflictManager getPlayerConflicts() { return playerConflicts; }
    public long getInteractionSequence() { return interactionSequence; }
    public CompoundTag interactionSnapshot(Set<UUID> owners) {
        CompoundTag result = new CompoundTag(); ListTag nations = new ListTag(), sites = new ListTag();
        owners.stream().sorted().forEach(id -> nations.add(getState(id).toNBT()));
        activeSettlements.values().stream().filter(s -> owners.contains(s.getOwningStateId())).sorted(Comparator.comparing(SettlementData::getSettlementId))
                .forEach(s -> sites.add(s.toNBT()));
        result.put("States", nations); result.put("Settlements", sites); return result;
    }
    /** Last domain: assets are installed before the donor identity and diplomacy are retired. */
    public void applySurrender(long sequence, long time, UUID donor, UUID recipient, CompoundTag snapshot) {
        if (interactionSequence >= sequence) return;
        StateData giver = null, receiver = null;
        for (var value : snapshot.getListOrEmpty("States")) {
            StateData state = StateData.fromNBT((CompoundTag) value);
            if (donor.equals(state.getStateId())) giver = state;
            if (recipient.equals(state.getStateId())) receiver = state;
        }
        if (giver == null || receiver == null || !giver.isAI()) throw new IllegalStateException("Invalid surrender participants");
        if (giver.getTreasuryBalance() > Long.MAX_VALUE - receiver.getTreasuryBalance()) throw new IllegalStateException("Treasury transfer overflow");
        receiver.addFunds(giver.getTreasuryBalance()); receiver.setSurrenderOverflow(true); receiver.getOwnedSettlements().clear();
        activeStates.put(recipient, receiver); if (!receiver.isAI()) humanStates.put(recipient, receiver);
        activeSettlements.values().removeIf(s -> donor.equals(s.getOwningStateId()) || recipient.equals(s.getOwningStateId()));
        for (var value : snapshot.getListOrEmpty("Settlements")) {
            SettlementData site = SettlementData.fromNBT((CompoundTag) value);
            if (donor.equals(site.getOwningStateId())) site.setOwningStateId(recipient);
            activeSettlements.put(site.getSettlementId(), site); receiver.addSettlement(site.getSettlementId());
        }
        receiver.setTotalPopulation((int) Math.min(Integer.MAX_VALUE, settlementsFor(receiver).stream().mapToLong(SettlementData::getLocalPopulation).sum()));
        if (getSettlement(receiver.getCapitalSettlementId()) == null || !recipient.equals(getSettlement(receiver.getCapitalSettlementId()).getOwningStateId()))
            receiver.setCapitalSettlementId(settlementsFor(receiver).stream().map(SettlementData::getSettlementId).sorted().findFirst().orElse(null));
        activeStates.remove(donor); knownNations.remove(donor); knownSettlements.remove(donor); knownRegions.remove(donor);
        String id = donor.toString(); wars.removeIf(k -> k.startsWith(id + ":") || k.endsWith(":" + id));
        passage.removeIf(k -> k.startsWith(id + ":") || k.endsWith(":" + id)); peaceOffers.removeIf(k -> k.startsWith(id + ":") || k.endsWith(":" + id));
        for (Set<UUID> enemies : enemyIndex.values()) enemies.remove(donor); enemyIndex.remove(donor);
        playerConflicts.removeOffers(donor);
        for (SettlementData site : settlementsFor(receiver)) observeSettlement(recipient, site.getSettlementId(), time, KnowledgeSource.OWNERSHIP);
        news.publish(UUID.nameUUIDFromBytes(("surrender:" + sequence).getBytes(java.nio.charset.StandardCharsets.UTF_8)), time,
                WorldNews.Kind.SURRENDER, donor, giver.getStateName(), recipient, receiver.getStateName(), null, "", 0);
        interactionSequence = sequence; markLogisticsChanged(); setDirty();
    }
    public long getMacroSequence() { return macroSequence; }
    public boolean hasAIInitialization(UUID id) { return aiInitializations.contains(id); }
    public void recordAIInitialization(UUID id) { if (aiInitializations.add(id)) setDirty(); }

    public boolean knowsNation(UUID viewer, UUID subject) {
        return viewer != null && subject != null && (viewer.equals(subject)
                || knownNations.getOrDefault(viewer, Map.of()).containsKey(subject));
    }
    public Collection<KnownNation> getKnownNations(UUID viewer) {
        return Collections.unmodifiableCollection(knownNations.getOrDefault(viewer, Map.of()).values());
    }
    public KnownSettlement getKnownSettlement(UUID viewer, UUID settlement) {
        if (viewer == null || settlement == null) return null;
        return knownSettlements.getOrDefault(viewer, Map.of()).get(settlement);
    }
    public Collection<KnownSettlement> getKnownSettlements(UUID viewer) {
        return Collections.unmodifiableCollection(knownSettlements.getOrDefault(viewer, Map.of()).values());
    }

    /** Call for an actual observation/eligible political report, never merely because a record exists. */
    public boolean observeNation(UUID viewer, UUID subject, long now) {
        return observeNation(viewer, subject, now, KnowledgeSource.OBSERVATION);
    }
    public boolean observeNation(UUID viewer, UUID subject, long now, KnowledgeSource source) {
        StateData observer = getState(viewer), target = getState(subject);
        if (observer == null || observer.isAI() || target == null || now < 0 || viewer.equals(subject)) return false;
        var knowledge = knownNations.computeIfAbsent(viewer, ignored -> new HashMap<>());
        KnownNation before = knowledge.get(subject);
        if (before != null && (now < before.observedAt() || before.name().equals(target.getStateName())
                && before.tier() == target.getCurrentTier() && now - before.observedAt() < 200)) return false;
        knowledge.put(subject, new KnownNation(subject, target.getStateName(), target.getCurrentTier(), now, target.isAI(), source));
        setDirty(); return true;
    }

    /** Record only a visible settlement. A remembered ID must not expose later unseen changes. */
    public boolean observeSettlement(UUID viewer, UUID settlementId, long now) {
        return observeSettlement(viewer, settlementId, now, KnowledgeSource.OBSERVATION);
    }
    public boolean observeSettlement(UUID viewer, UUID settlementId, long now, KnowledgeSource source) {
        StateData observer = getState(viewer); SettlementData settlement = getSettlement(settlementId);
        if (observer == null || observer.isAI() || settlement == null || now < 0) return false;
        var knowledge = knownSettlements.computeIfAbsent(viewer, ignored -> new HashMap<>());
        KnownSettlement before = knowledge.get(settlementId);
        if (before != null && (now < before.observedAt() || before.owner().equals(settlement.getOwningStateId())
                && before.name().equals(settlement.getSettlementName()) && before.position().equals(settlement.getCenterAltarPos())
                && before.tier() == settlement.getSettlementTier() && before.kind() == settlement.getKind() && now - before.observedAt() < 200)) return false;
        knowledge.put(settlementId, new KnownSettlement(settlementId, settlement.getOwningStateId(), settlement.getSettlementName(),
                settlement.getCenterAltarPos().immutable(), settlement.getSettlementTier(), now, source, settlement.getKind()));
        observeNation(viewer, settlement.getOwningStateId(), now, source); setDirty(); return true;
    }

    //constructors
    public void createState(StateData state) {
        if (SettlementData.INDEPENDENT_OWNER.equals(state.getStateId())) throw new IllegalArgumentException("Independent sites are not a nation");
        // Initialization retries never replace an existing state or repeat its introductory grant.
        if (activeStates.putIfAbsent(state.getStateId(), state) != null) return;
        if (state.isAI()) aiController.register(state.getStateId());
        else humanStates.put(state.getStateId(), state);
        markLogisticsChanged();
        if (!state.isAI() && state.getLeaderId() != null) {
            playerToStateMap.put(state.getLeaderId(), state.getStateId());
            if (!assistedFounders.add(state.getLeaderId())) state.setGraceTicksRemaining(0);
        }
        setDirty();
    }
    public StateData createState(String name, UUID leader, StateTier ignoredStartingTier) {
        StateData state = new StateData(UUID.randomUUID(), cleanName(name), leader, StateTier.COUNTY);
        createState(state);
        return state;
    }

    //add a player to a state
    public void addPlayerToState(UUID player, UUID state) {
        StateData target = activeStates.get(state);
        if (target != null && !target.isAI()) { playerToStateMap.put(player, state); setDirty(); }
    }

    //add funds to a state, if it exists
    public void addFunds(UUID stateId, long amount) {
        StateData state = getState(stateId);
        if (state != null) { state.addFunds(amount); setDirty(); }
    }

    //yea this works. check if a player can claim a free legion, based on their state and whether they have already claimed it
    public boolean canClaimFreeLegion(UUID player) {
        StateData state = getStateByPlayer(player);
        return state != null && !state.isAI() && player.equals(state.getLeaderId()) && !firstLegionGranted.contains(player);
    }

    /** Membership is world-wide: an online member in another dimension also keeps a nation online. */
    public boolean hasOnlineMembers(ServerLevel level, UUID stateId) {
        return stateId != null && level.getServer().getPlayerList().getPlayers().stream()
                .anyMatch(player -> stateId.equals(playerToStateMap.get(player.getUUID())));
    }

    /** AI time does not depend on a fictional logged-in player; human supply activity is unchanged. */
    public boolean isSimulationActive(ServerLevel level, UUID stateId) {
        StateData state = getState(stateId);
        return state != null && !state.isInitializationPending() && !aiWars.blocks(stateId) && (state.isAI() || hasOnlineMembers(level, stateId));
    }

    /** New AI aggression is unavailable against wholly offline humans, independent of existing war status. */
    public boolean mayInitiateHostility(UUID attacker, UUID target, java.util.function.Predicate<UUID> online) {
        StateData first = getState(attacker), second = getState(target);
        return first != null && second != null && !attacker.equals(target)
                && !first.isInitializationPending() && !second.isInitializationPending()
                && !aiWars.blocks(attacker) && !aiWars.blocks(target)
                && (!first.isAI() || first.getStrategy() != StateData.Strategy.SURRENDER)
                && (!first.isAI() || second.isAI() || online.test(target));
    }

    public boolean mayInitiateHostility(ServerLevel level, UUID attacker, UUID target) {
        return mayInitiateHostility(attacker, target, id -> hasOnlineMembers(level, id));
    }

    /** Symmetric admission: iteration order cannot turn an AI attack into a human-initiated encounter. */
    public boolean mayBeginEncounter(UUID first, UUID second, UUID firstGroundOwner, UUID secondGroundOwner,
                                     java.util.function.Predicate<UUID> online) {
        StateData a = getState(first), b = getState(second);
        if (a == null || b == null || a.isInitializationPending() || b.isInitializationPending() || first.equals(second) || a.isAI() && b.isAI()) return false;
        if (aiWars.blocks(first) || aiWars.blocks(second)) return false;
        if (!a.isAI() && !b.isAI()) return true;
        UUID ai = a.isAI() ? first : second, human = a.isAI() ? second : first;
        UUID humanGround = a.isAI() ? secondGroundOwner : firstGroundOwner;
        // Existing hostile human forces inside AI land can still be met defensively after logout.
        return online.test(human) || ai.equals(humanGround);
    }

    public boolean mayBeginEncounter(ServerLevel level, UUID first, BlockPos firstPosition, UUID second, BlockPos secondPosition) {
        ClaimManager claims = ClaimManager.get(level);
        return mayBeginEncounter(first, second, claims.getStrategicOwner(ChunkPos.containing(firstPosition)),
                claims.getStrategicOwner(ChunkPos.containing(secondPosition)), id -> hasOnlineMembers(level, id));
    }
    public boolean claimFirstLegionGrant(UUID player) {
        if (!canClaimFreeLegion(player)) return false;
        firstLegionGranted.add(player);
        setDirty();
        return true;
    }
    public boolean claimFirstLegionGrant(ServerPlayer player) { return claimFirstLegionGrant(player.getUUID()); }

    //disband a state, removing all associated settlements, armies, and claims
    public boolean disbandState(UUID stateId, ServerLevel level) {
        StateData state = activeStates.get(stateId);
        if (state == null) return false;
        if (state.isInitializationPending()) return false;
        if (aiWars.blocks(stateId)) return false;
        if (!ArmyManager.removeForStateIfAvailable(level, stateId)) return false;
        for (SettlementData settlement : settlementsFor(state)) disbandSettlement(settlement.getSettlementId(), level);
        activeStates.remove(stateId);
        humanStates.remove(stateId);
        knownNations.remove(stateId); knownSettlements.remove(stateId);
        knownRegions.remove(stateId);
        markLogisticsChanged();
        playerToStateMap.values().removeIf(stateId::equals);
        ClaimManager.get(level).clearAllClaimsForState(stateId);
        String id = stateId.toString();
        wars.removeIf(key -> key.startsWith(id + ":") || key.endsWith(":" + id));
        for (UUID enemy : enemyIndex.getOrDefault(stateId, Set.of())) {
            Set<UUID> opponents = enemyIndex.get(enemy);
            if (opponents != null) { opponents.remove(stateId); if (opponents.isEmpty()) enemyIndex.remove(enemy); }
        }
        enemyIndex.remove(stateId);
        passage.removeIf(key -> key.startsWith(id + ":") || key.endsWith(":" + id));
        peaceOffers.removeIf(key -> key.startsWith(id + ":") || key.endsWith(":" + id));
        setDirty();
        return true;
    }

    //remove a player from a state, if they are not the leader
    public boolean leaveState(UUID player) {
        StateData state = getStateByPlayer(player);
        if (state == null || player.equals(state.getLeaderId())) return false;
        playerToStateMap.remove(player);
        setDirty();
        return true;
    }

    //register a new settlement, adding it to the active settlements and updating the owning state
    public void registerSettlement(UUID id, SettlementData settlement) {
        if (!id.equals(settlement.getSettlementId()) || !activeStates.containsKey(settlement.getOwningStateId()) && !(settlement.isIndependent() && settlement.isAbstractSettlement()))
            throw new IllegalArgumentException("Settlement identity and owning state must exist");
        if (activeSettlements.putIfAbsent(id, settlement) != null) return;
        markLogisticsChanged();
        StateData state = getState(settlement.getOwningStateId());
        if (state != null) {
            state.addSettlement(id);
            if (state.getCapitalSettlementId() == null) state.setCapitalSettlementId(id);
        }
        pendingAltars.remove(settlement.getCenterAltarPos());
        abandonedAltarPositions.remove(settlement.getCenterAltarPos());
        setDirty();
    }

    //check if an altar position is marked as abandoned, and mark or clear it as needed
    public boolean isAltarAbandoned(BlockPos pos){ 
        return abandonedAltarPositions.contains(pos); 
    }

    //mark an altar position as abandoned, or clear it if it is no longer abandoned
    public void markAltarAbandoned(BlockPos pos){ 
        abandonedAltarPositions.add(pos.immutable()); setDirty(); 
    }

    //clear an altar position from the abandoned list and remove it from pending altars
    public void clearAltarAbandoned(BlockPos pos){ 
        abandonedAltarPositions.remove(pos); pendingAltars.remove(pos); setDirty(); 
    }

    //disband a settlement, removing it from the active settlements, releasing its garrison, and returning its land to unassigned state territory
    public void disbandSettlement(UUID id, ServerLevel level) {
        SettlementData settlement = activeSettlements.remove(id);
        if (settlement == null) return;
        markLogisticsChanged();
        ArmyManager.get(level).releaseGarrison(id);
        StateData state = getState(settlement.getOwningStateId());
        if (state != null) {
            state.removeSettlement(id);
            if (id.equals(state.getCapitalSettlementId())) state.setCapitalSettlementId(null);
        }
        ClaimManager.get(level).releaseSettlementClaims(id.toString());
        markAltarAbandoned(settlement.getCenterAltarPos());
        refreshProgression(level, state);
        setDirty();
    }

    //get a settlement by its altar position, returning null if no settlement is found
    public SettlementData getSettlementByAltarPos(BlockPos pos) {
        return activeSettlements.values().stream().filter(s -> pos.equals(s.getCenterAltarPos())).findFirst().orElse(null);
    }

    //clean a settlement name by removing control characters and limiting its length to 48 characters
    public static String cleanName(String name) {
        String result = name == null ? "" : name.replaceAll("[\\p{Cntrl}§]", "").strip();
        return result.substring(0, Math.min(48, result.length()));
    }

    //validate an altar position for founding a settlement, checking for world, border, overlap, and state requirements
    //TODO: verify that the methods listed here are correct. some of the conditions outlined may be too strict 
    public String validateAltar(ServerLevel level, BlockPos pos, UUID stateId) {
        if (level != level.getServer().overworld()) return "Settlements can only be founded in the Overworld.";
        if (!level.getWorldBorder().isWithinBounds(pos)) return "This altar is outside the world border.";
        if (getSettlementByAltarPos(pos) != null) return "This altar already belongs to a settlement.";
        for (SettlementData other : activeSettlements.values()) {
            if (settlementFootprintsOverlap(other.getCenterAltarPos(), other.getProtectiveRadius(), pos, StateBalance.SETTLEMENT_RADIUS)) //check this, settlements shouldnt overlap, but they can be near each other (though not in the first settlements existing claim)
                return "The settlement's territory would overlap an existing settlement.";
        }
        int newClaims = 0;
        ClaimManager claims = ClaimManager.get(level);
        var dryArea = MapDataService.reachableArea(level, pos, StateBalance.SETTLEMENT_RADIUS, true);
        var area = MapDataService.reachableArea(level, pos, StateBalance.SETTLEMENT_RADIUS, false);
        if (dryArea.status() == MapDataService.Verification.PENDING || area.status() == MapDataService.Verification.PENDING)
            return "Checking the ground around this altar. Use the altar again shortly.";
        if (!dryArea.dryOrigin()) return "The altar must stand on connected, usable dry ground.";
        if (dryArea.originDryColumns() < 128) return "The altar chunk needs at least 128 connected, usable dry columns.";
        if (dryArea.qualifyingDryChunks() < 9) return dryArea.status() == MapDataService.Verification.UNKNOWN
                ? "Explore the surrounding terrain before founding. Nine connected dry chunks must be verified."
                : "Founding needs nine connected chunks, each with at least 128 connected dry columns.";
        if (area.status() == MapDataService.Verification.LIMITED) return "The terrain check reached its limit. Choose another altar site.";
        for (ChunkPos chunk : squareChunks(pos, StateBalance.SETTLEMENT_RADIUS)) {
            UUID owner = claims.getStrategicOwner(chunk);
            if (owner != null && !Objects.equals(stateId, owner)) return "The settlement's territory overlaps another state.";
            if (owner == null && area.chunks().contains(chunk)) newClaims++;
        }
        StateData state = getState(stateId);
        if (state != null && !canAcquireChunks(state, claims.getClaimCountForState(stateId), newClaims))
            return "Meet your next state's settlement and population requirements before expanding further.";
        return null;
    }

    //begin the founding process for a settlement, checking for player permissions and altar validation
    public void beginFounding(ServerPlayer player, BlockPos pos) {
        if (player.level() != player.level().getServer().overworld()) return;
        UUID pendingOwner = pendingAltars.get(pos);
        if (pendingOwner != null && !pendingOwner.equals(player.getUUID())) {
            player.sendSystemMessage(Component.literal("This altar is awaiting its founder's confirmation."));
            return;
        }
        StateData state = getStateByPlayer(player.getUUID());
        if (state != null && !player.getUUID().equals(state.getLeaderId())) {
            player.sendSystemMessage(Component.literal("Only the state leader can found settlements."));
            return;
        }
        if (player.blockPosition().distSqr(pos) > 64 || !player.level().getBlockState(pos).is(MinecraftEmpires.CITY_ALTAR.get())) return;
        pendingAltars.put(pos.immutable(), player.getUUID());
        MapDataService.requestFoundingSurvey(player.level(), pos);
        setDirty();
        com.devc.minecraftempires.network.ModNetworking.sendSnapshots(player);
        OpenFoundingPayload status = foundingStatus(player, pos);
        PacketDistributor.sendToPlayer(player, new OpenFoundingPayload(pos, status.ready(), status.status(), true));
    }

    /** Opening the form grants no founding rights; every confirmation still validates the current server terrain. */
    public OpenFoundingPayload foundingStatus(ServerPlayer player, BlockPos pos) {
        if (player.level() != player.level().getServer().overworld() || player.blockPosition().distSqr(pos) > 64
                || !player.level().getBlockState(pos).is(MinecraftEmpires.CITY_ALTAR.get()) || !player.getUUID().equals(pendingAltars.get(pos)))
            return new OpenFoundingPayload(pos, false, "Stand near your City Altar and use it again.");
        StateData state = getStateByPlayer(player.getUUID());
        if (state != null && !player.getUUID().equals(state.getLeaderId())) return new OpenFoundingPayload(pos, false, "Only the state leader can found settlements.");
        String error = validateAltar(player.level(), pos, state == null ? null : state.getStateId());
        if (error == null) return new OpenFoundingPayload(pos, true, "Ground verified. Nine connected chunks meet the dry-area requirement.");
        if (error.startsWith("Checking the ground")) {
            var dry = MapDataService.reachableArea(player.level(), pos, StateBalance.SETTLEMENT_RADIUS, true);
            var claims = MapDataService.reachableArea(player.level(), pos, StateBalance.SETTLEMENT_RADIUS, false);
            String status = "Surveying: " + Math.min(9, dry.qualifyingDryChunks()) + "/9 qualifying dry chunks; " + claims.chunks().size() + " reachable claim chunks checked.";
            long time = player.level().getGameTime();
            if (time >= foundingDiagnostics.getOrDefault(pos, Long.MIN_VALUE / 2) + 100) {
                foundingDiagnostics.put(pos.immutable(), time);
                MinecraftEmpires.LOGGER.info("Altar survey {}: dry [{}], claims [{}]", pos,
                        MapDataService.areaProgress(player.level(), pos, StateBalance.SETTLEMENT_RADIUS, true),
                        MapDataService.areaProgress(player.level(), pos, StateBalance.SETTLEMENT_RADIUS, false));
            }
            return new OpenFoundingPayload(pos, false, status);
        }
        return new OpenFoundingPayload(pos, false, error);
    }

    public void pollFounding(ServerPlayer player, BlockPos pos) {
        long time = player.level().getGameTime();
        if (time - foundingPolls.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2) < 10) return;
        foundingPolls.put(player.getUUID(), time);
        if (player.getUUID().equals(pendingAltars.get(pos))) PacketDistributor.sendToPlayer(player, foundingStatus(player, pos));
    }

    //confirm the founding of a settlement, creating the state and settlement data as needed, and establishing claims
    public String confirmFounding(ServerPlayer player, BlockPos pos, String name) {
        ServerLevel level = player.level();
        if (level != level.getServer().overworld() || player.blockPosition().distSqr(pos) > 64
            || !level.getBlockState(pos).is(MinecraftEmpires.CITY_ALTAR.get())) return "Stand near your City Altar to found a settlement.";

        if (!player.getUUID().equals(pendingAltars.get(pos))) return "Use this altar first to start founding.";

        StateData state = getStateByPlayer(player.getUUID());
        if (state != null && !player.getUUID().equals(state.getLeaderId())) return "Only the state leader may found settlements.";

        name = cleanName(name);
        if (name.isBlank()) return "Choose a name first.";
        String error = validateAltar(level, pos, state == null ? null : state.getStateId());

        if (error != null) return error;
        boolean newState = state == null;

        if (newState) state = createState(name, player.getUUID(), StateTier.COUNTY);

        SettlementData settlement = new SettlementData(UUID.randomUUID(), state.getStateId(), newState ? name + " Capital" : name, pos.immutable());
        registerSettlement(settlement.getSettlementId(), settlement);
        establishSettlementClaims(level, settlement, state.getStateId());
        pendingAltars.remove(pos); foundingDiagnostics.remove(pos);
        refreshProgression(level);

        if (!newState) return "Founded " + name + ".";
        String benefits = canClaimFreeLegion(player.getUUID()) ? " Your first legion is free." : "";
        if (state.getGraceTicksRemaining() > 0) benefits += " Maintenance is waived for 14 active in-game days.";
        return "Founded " + name + "." + benefits;
    }

    /** Chunk rounding is shared by ownership, overlap validation, and settlement protection. */
    public static boolean settlementFootprintsOverlap(BlockPos first, BlockPos second) {
        // Preserve the historical helper for external callers with legacy footprints.
        return settlementFootprintsOverlap(first, StateBalance.LEGACY_SETTLEMENT_RADIUS, second, StateBalance.LEGACY_SETTLEMENT_RADIUS);
    }
    public static boolean settlementFootprintsOverlap(BlockPos first, int firstRadius, BlockPos second, int secondRadius) {
        return Math.floorDiv(first.getX() - firstRadius, 16) <= Math.floorDiv(second.getX() + secondRadius, 16)
                && Math.floorDiv(second.getX() - secondRadius, 16) <= Math.floorDiv(first.getX() + firstRadius, 16)
                && Math.floorDiv(first.getZ() - firstRadius, 16) <= Math.floorDiv(second.getZ() + secondRadius, 16)
                && Math.floorDiv(second.getZ() - secondRadius, 16) <= Math.floorDiv(first.getZ() + firstRadius, 16);
    }
    public static boolean settlementFootprintsOverlap(SettlementData first, SettlementData second) {
        return settlementFootprintsOverlap(first.getCenterAltarPos(), first.getProtectiveRadius(), second.getCenterAltarPos(), second.getProtectiveRadius());
    }

    //check if a chunk is within the footprint of a settlement, based on the settlement's altar position and radius
    public static boolean isInSettlementFootprint(BlockPos altar, ChunkPos chunk) {
        return isInSettlementFootprint(altar, chunk, StateBalance.LEGACY_SETTLEMENT_RADIUS);
    }
    public static boolean isInSettlementFootprint(SettlementData settlement, ChunkPos chunk) {
        return isInSettlementFootprint(settlement.getCenterAltarPos(), chunk, settlement.getProtectiveRadius());
    }
    public static boolean isInSettlementFootprint(BlockPos altar, ChunkPos chunk, int radius) {
        return chunk.x() >= Math.floorDiv(altar.getX() - radius, 16)
            && chunk.x() <= Math.floorDiv(altar.getX() + radius, 16)
            && chunk.z() >= Math.floorDiv(altar.getZ() - radius, 16)
            && chunk.z() <= Math.floorDiv(altar.getZ() + radius, 16);
    }
    
    //calculate the square of chunks around a given position, within a specified radius, and return them as a list
    public static List<ChunkPos> squareChunks(BlockPos pos, int radius) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = Math.floorDiv(pos.getX() - radius, 16); x <= Math.floorDiv(pos.getX() + radius, 16); x++) {
            for (int z = Math.floorDiv(pos.getZ() - radius, 16); z <= Math.floorDiv(pos.getZ() + radius, 16); z++) chunks.add(new ChunkPos(x, z));
        }
        return chunks;
    }

    //establish the claims for a newly founded settlement, setting the initial claim data for each chunk in the settlement's footprint
    public void establishSettlementClaims(ServerLevel level, SettlementData settlement, UUID stateId) {
        ClaimManager claims = ClaimManager.get(level);
        ChunkPos centerChunk = ChunkPos.containing(settlement.getCenterAltarPos());
        var reachable = MapDataService.reachableArea(level, settlement.getCenterAltarPos(), settlement.getProtectiveRadius(), false);
        if (reachable.status() == MapDataService.Verification.PENDING || reachable.status() == MapDataService.Verification.LIMITED) return;
        claims.registerSettlementCenter(settlement.getSettlementId().toString(), centerChunk);
        for (ChunkPos chunk : reachable.chunks()) {
            UUID owner = claims.getStrategicOwner(chunk);
            if (owner == null || stateId.equals(owner))
                claims.setClaim(chunk, stateId, settlement.getSettlementId().toString(), false, settlement.getSettlementTier());
        }
        setDirty();
    }
    
    //upgrade a settlement to the next tier, if it meets the requirements
    public String upgradeSettlement(ServerLevel level, StateData state, UUID settlementId) {
        SettlementData settlement = getSettlement(settlementId);
        if (state == null || getState(state.getStateId()) != state) return "State no longer exists.";
        String problem = settlementUpgradeProblem(state, settlement);
        if (!problem.isEmpty()) return problem;
        state.deductFunds(StateBalance.CITY_UPGRADE_COST);
        settlement.upgradeSettlement();
        for (ChunkData data : ClaimManager.get(level).getClaimsView().values())
            if (settlementId.toString().equals(data.getSettlementID())) data.setSettlementTier(settlement.getSettlementTier());
        ClaimManager.get(level).setDirty();
        refreshProgression(level, state);
        setDirty();
        return settlement.getSettlementName() + " is now a city.";
    }

    /** Shared upgrade eligibility; committing upgrades always recheck the current owner and treasury. */
    public static String settlementUpgradeProblem(StateData state, SettlementData settlement) {
        if (state == null || settlement == null || !state.getStateId().equals(settlement.getOwningStateId())) return "Settlement not owned by your state.";
        if (state.isInitializationPending()) return "This nation is still being initialized.";
        if (settlement.getSettlementTier() >= StateBalance.MAXIMUM_SETTLEMENT_TIER) return "This settlement is already a city.";
        if (settlement.getKind() != SettlementData.Kind.TOWN) return "This settlement must be a town before it can become a city.";
        if (settlement.getLocalPopulation() < StateBalance.CITY_POPULATION) return "A city needs at least " + StateBalance.CITY_POPULATION + " residents.";
        if (state.getTreasuryBalance() < StateBalance.CITY_UPGRADE_COST) return "City upgrade requires " + StateBalance.CITY_UPGRADE_COST + " emeralds.";
        return "";
    }

    private static String directed(UUID a, UUID b) { return a + ":" + b; }
    private static String symmetric(UUID a, UUID b) { return a.compareTo(b) < 0 ? directed(a, b) : directed(b, a); }
    public boolean areAtWar(UUID a, UUID b) { return a != null && b != null && !a.equals(b) && wars.contains(symmetric(a, b)); }
    public List<StateData> enemiesFor(UUID state) {
        return enemyIndex.getOrDefault(state, Set.of()).stream().map(activeStates::get).filter(Objects::nonNull).toList();
    }
    /** Guest has permission to enter owner's territory; permission need not be reciprocal. */
    public boolean hasPassage(UUID guest, UUID owner) {
        return guest != null && owner != null && (guest.equals(owner) || (!areAtWar(guest, owner) && passage.contains(directed(guest, owner))));
    }

    //diplomacy functions below

    //war status functions: declare war, grant passage, revoke passage, offer peace, accept peace, and notify states of war status changes
    public boolean declareWar(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b) || getState(a) == null || getState(b) == null) return false;
        passage.remove(directed(a, b)); passage.remove(directed(b, a));
        peaceOffers.remove(directed(a, b)); peaceOffers.remove(directed(b, a));
        boolean changed = wars.add(symmetric(a, b));
        enemyIndex.computeIfAbsent(a, ignored -> new HashSet<>()).add(b);
        enemyIndex.computeIfAbsent(b, ignored -> new HashSet<>()).add(a);
        markLogisticsChanged();
        setDirty();
        return changed;
    }
    /** Live mutation and its news share StateManager's saved snapshot; retries publish only on a real transition. */
    public boolean declareWar(ServerLevel level, UUID actor, UUID target) {
        if (!mayInitiateHostility(level, actor, target) || !declareWar(actor, target)) return false;
        long now = level.getGameTime();
        observeNation(actor, target, now, KnowledgeSource.DIRECT_EVENT); observeNation(target, actor, now, KnowledgeSource.DIRECT_EVENT);
        news.publish(UUID.randomUUID(), now, WorldNews.Kind.WAR, actor, getState(actor).getStateName(), target,
                getState(target).getStateName(), null, "", 0);
        notifyState(level, target, getState(actor).getStateName() + " has declared war on your state.");
        return true;
    }
    public void grantPassage(UUID owner, UUID guest) {
        if (!owner.equals(guest) && !areAtWar(owner, guest) && getState(guest) != null && passage.add(directed(guest, owner))) markLogisticsChanged();
        setDirty();
    }
    public void revokePassage(UUID owner, UUID guest) { if (passage.remove(directed(guest, owner))) markLogisticsChanged(); setDirty(); }
    public void offerPeace(UUID from, UUID to) { if (areAtWar(from, to)) peaceOffers.add(directed(from, to)); setDirty(); }
    public boolean hasPeaceOffer(UUID from, UUID to) { return peaceOffers.contains(directed(from, to)); }
    public boolean acceptPeace(UUID accepter, UUID proposer) {
        if (!peaceOffers.remove(directed(proposer, accepter))) return false;
        peaceOffers.remove(directed(accepter, proposer)); wars.remove(symmetric(accepter, proposer));
        Set<UUID> a = enemyIndex.get(accepter), b = enemyIndex.get(proposer);
        if (a != null) { a.remove(proposer); if (a.isEmpty()) enemyIndex.remove(accepter); }
        if (b != null) { b.remove(accepter); if (b.isEmpty()) enemyIndex.remove(proposer); }
        markLogisticsChanged(); setDirty(); return true;
    }
    public boolean acceptPeace(ServerLevel level, UUID accepter, UUID proposer) {
        if (getState(accepter) == null || getState(proposer) == null || !acceptPeace(accepter, proposer)) return false;
        news.publish(UUID.randomUUID(), level.getGameTime(), WorldNews.Kind.PEACE, accepter, getState(accepter).getStateName(),
                proposer, getState(proposer).getStateName(), null, "", 0);
        notifyState(level, proposer, getState(accepter).getStateName() + " accepted your peace offer.");
        return true;
    }
    public void notifyState(ServerLevel level, UUID state, String message) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers())
            if (Objects.equals(playerToStateMap.get(player.getUUID()), state)) player.sendSystemMessage(Component.literal(message));
    }

    public int getCityCount(StateData state) { return (int) settlementsFor(state).stream().filter(s -> s.getSettlementTier() >= 2).count(); }
    public int getTownCount(StateData state) { return (int) settlementsFor(state).stream().filter(s -> s.getKind() == SettlementData.Kind.TOWN).count(); }
    public List<SettlementData> settlementsFor(StateData state) {
        return state.getOwnedSettlements().stream().map(activeSettlements::get)
                .filter(Objects::nonNull).filter(s -> state.getStateId().equals(s.getOwningStateId())).toList();
    }
    public boolean qualifiesFor(StateData state, StateTier tier) {
        int cities = getCityCount(state);
        return switch (tier) {
            case CITY_STATE -> cities >= 1 && getTownCount(state) >= 2;
            case KINGDOM -> state.getTotalPopulation() > 5_000;
            case STATE -> state.getTotalPopulation() > 15_000 && cities >= 5;
            case REPUBLIC, EMPIRE -> state.getTotalPopulation() > 15_000 && cities >= 5;
            default -> true;
        };
    }
    public StateTier nextTier(StateData state) {
        return switch (state.getCurrentTier()) {
            case NOMADIC, COUNTY, DUCHY -> StateTier.CITY_STATE;
            case CITY_STATE -> StateTier.KINGDOM;
            case KINGDOM -> StateTier.STATE;
            case STATE -> StateTier.REPUBLIC;
            default -> null;
        };
    }
    public boolean canAcquireChunks(StateData state, int currentlyOwned, int additional) {
        if (additional > 0 && aiWars.blocks(state.getStateId())) return false;
        if (additional > 0 && state.isAI() && state.getStrategy() == StateData.Strategy.SURRENDER) return false;
        if (additional > 0 && state.hasSurrenderOverflow()) return false;
        if (additional <= 0 || state.getCurrentTier().isPermanent()) return true;
        StateTier next = nextTier(state);
        if (next == null) return true;
        // The milestone itself can be reached; further growth requires promotion/choice.
        return (long) currentlyOwned + additional <= next.getMinChunks();
    }

    //state progession updater function, called every 10 seconds to update state progression and handle old saves
    public void refreshProgression(ServerLevel level) {
        for (StateData state : activeStates.values()) refreshProgression(level, state);
    }
    public void refreshHumanProgression(ServerLevel level) {
        for (StateData state : humanStates.values()) refreshProgression(level, state);
    }
    /** Scheduled AI work and affected-owner changes never refresh unrelated nations. */
    public void refreshProgression(ServerLevel level, StateData state) {
        if (state == null || state.isInitializationPending() || aiWars.blocks(state.getStateId()) || getState(state.getStateId()) != state) return;
        ClaimManager claims = ClaimManager.get(level);
        List<SettlementData> settlements = settlementsFor(state);
        state.getOwnedSettlements().clear();
        settlements.forEach(s -> state.addSettlement(s.getSettlementId()));
        state.setTotalPopulation((int) Math.min(Integer.MAX_VALUE, settlements.stream().mapToLong(SettlementData::getLocalPopulation).sum()));
        if (getSettlement(state.getCapitalSettlementId()) == null || !state.getStateId().equals(getSettlement(state.getCapitalSettlementId()).getOwningStateId()))
            state.setCapitalSettlementId(settlements.stream().sorted(Comparator.comparing(SettlementData::getSettlementId)).map(SettlementData::getSettlementId).findFirst().orElse(null));
        if (state.getCurrentTier().isPermanent()) {
            state.setEndgameChoice(false);
            if (ArmyManager.get(level).getLegionCountForState(state.getStateId()) <= state.getCurrentTier().getMaxLegions()) state.setSurrenderOverflow(false);
            setDirty(); return;
        }
        int area = claims.getClaimCountForState(state.getStateId());
        StateTier calculated = getCityCount(state) > 0 ? StateTier.DUCHY : StateTier.COUNTY;
        if (area >= StateTier.CITY_STATE.getMinChunks() && qualifiesFor(state, StateTier.CITY_STATE)) calculated = StateTier.CITY_STATE;
        if (area >= StateTier.KINGDOM.getMinChunks() && qualifiesFor(state, StateTier.KINGDOM)) calculated = StateTier.KINGDOM;
        if (area >= StateTier.STATE.getMinChunks() && qualifiesFor(state, StateTier.STATE)) calculated = StateTier.STATE;
        StateTier before = state.getCurrentTier();
        state.setCurrentTier(calculated);
        boolean ready = calculated == StateTier.STATE && area >= StateTier.REPUBLIC.getMinChunks() && qualifiesFor(state, StateTier.REPUBLIC);
        if (ready && !state.hasEndgameChoice()) notifyState(level, state.getStateId(), "Your state may now choose Republic or Empire in State Management.");
        state.setEndgameChoice(ready);
        if (state.hasSurrenderOverflow() && ArmyManager.get(level).getLegionCountForState(state.getStateId()) <= state.getCurrentTier().getMaxLegions()
                && (nextTier(state) == null || area <= nextTier(state).getMinChunks())) state.setSurrenderOverflow(false);
        if (before != calculated) notifyState(level, state.getStateId(), "Your state is now a " + calculated.name().replace('_', ' ') + ".");
        setDirty();
    }

    //for endgame states only, allows users to choose between Republic or Empire, if they meet the requirements and have not already made a choice
    public boolean chooseGovernment(ServerLevel level, StateData state, StateTier chosen) {
        refreshProgression(level, state);
        if (!state.hasEndgameChoice() || (chosen != StateTier.REPUBLIC && chosen != StateTier.EMPIRE)) return false;
        state.setCurrentTier(chosen); state.setEndgameChoice(false);
        news.publish(UUID.randomUUID(), level.getGameTime(), WorldNews.Kind.GOVERNMENT, state.getStateId(), state.getStateName(), null, "", null, "", 0);
        setDirty(); return true;
    }

    //settlement capture function: called when an army successfully captures a settlement or it checks if the capture is valid, transferring ownership and updating claims and state data
    /** The army must have physically reached the altar; callers additionally check nearby defenders. */
    public boolean captureSettlement(ServerLevel level, UUID settlementId, UUID attacker) {
        SettlementData settlement = getSettlement(settlementId);
        StateData state = getState(attacker);
        if (settlement == null || state == null || !settlement.isCenterVerified() || settlement.isLocallyImmune()
                || !areAtWar(attacker, settlement.getOwningStateId()) || !mayInitiateHostility(level, attacker, settlement.getOwningStateId())) return false;
        ClaimManager claims = ClaimManager.get(level);
        List<ChunkPos> abstractCore = List.of();
        if (settlement.isAbstractSettlement()) {
            var ground = MapDataService.reachableArea(level, settlement.getCenterAltarPos(), settlement.getProtectiveRadius(), true);
            if (ground.status() != MapDataService.Verification.VERIFIED || !ground.dryOrigin()) return false;
            UUID previous = settlement.getOwningStateId();
            abstractCore = ground.dryChunks().stream().filter(p -> isInSettlementFootprint(settlement, p) && previous.equals(claims.getStrategicOwner(p))).toList();
        }
        long newClaims = claims.getClaimsView().values().stream().filter(c -> settlementId.toString().equals(c.getSettlementID()) && !attacker.equals(c.getOwnerUUID())).count();
        newClaims += abstractCore.stream().filter(p -> claims.getClaim(p) == null || !settlementId.toString().equals(claims.getClaim(p).getSettlementID())).count();
        if (!canAcquireChunks(state, claims.getClaimCountForState(attacker), (int) newClaims)) return false;
        UUID oldOwner = settlement.getOwningStateId();
        StateData defender = getState(oldOwner);
        if (defender != null && settlementId.equals(defender.getCapitalSettlementId())) {
            defender.recordCapitalLoss(level.getGameTime()); defender.recordMajorDefeat(level.getGameTime(), attacker);
        }
        ArmyManager.get(level).releaseGarrison(settlementId);
        settlement.setOwningStateId(attacker);
        if (defender != null) defender.removeSettlement(settlementId);
        state.addSettlement(settlementId);
        settlement.setLocalSiegeImmunityTicks(StateBalance.CAPTURE_IMMUNITY_TICKS);
        settlement.setSupplyPreparationTicks(StateBalance.CAPTURED_SUPPLY_PREPARATION_TICKS);
        settlement.setMartialLaw(false);
        for (ChunkData claim : claims.getClaimsView().values()) {
            if (oldOwner.equals(claim.getOwnerUUID()) && settlementId.toString().equals(claim.getSettlementID())) { claim.setOwnerUUID(attacker); claim.setIsGarrisoned(false); }
        }
        for (ChunkPos p : abstractCore) claims.setClaim(p, attacker, settlementId.toString(), false, settlement.getSettlementTier());
        claims.rebuildClaimCounts(); claims.markTopologyChanged();
        markLogisticsChanged();
        state.incrementSettlementsCaptured();
        refreshProgression(level, defender); refreshProgression(level, state);
        notifyState(level, oldOwner, settlement.getSettlementName() + " has been captured.");
        notifyState(level, attacker, "Captured " + settlement.getSettlementName() + ".");
        observeSettlement(attacker, settlementId, level.getGameTime());
        observeSettlement(oldOwner, settlementId, level.getGameTime());
        news.publish(UUID.randomUUID(), level.getGameTime(), WorldNews.Kind.CAPTURE, attacker, state.getStateName(), oldOwner,
                defender == null ? "Unknown nation" : defender.getStateName(), settlementId, settlement.getSettlementName(), 0);
        setDirty(); return true;
    }
    /** Timers use saved game time and are advanced once per second, never wall-clock time. */
    public void tickTimers(ServerLevel level) {
        long now = level.getGameTime();
        if (lastTimerGameTime < 0 || now < lastTimerGameTime) { lastTimerGameTime = now; setDirty(); return; }
        int elapsed = (int) Math.min(20, now - lastTimerGameTime);
        lastTimerGameTime = now;
        Set<UUID> onlineStates = new HashSet<>();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            UUID id = playerToStateMap.get(player.getUUID()); if (id != null) onlineStates.add(id);
        }
        for (StateData state : activeStates.values()) {
            if (aiWars.blocks(state.getStateId())) continue;
            if (onlineStates.contains(state.getStateId())) state.setGraceTicksRemaining(state.getGraceTicksRemaining() - elapsed);
            for (int i = 0; i < elapsed; i++) { state.tickSiegeImmunity(); state.tickCampaignMomentum(); }
        }
        for (SettlementData settlement : activeSettlements.values()) if (!aiWars.blocks(settlement.getOwningStateId()))
            for (int i = 0; i < elapsed; i++) settlement.tickLocalImmunity();
        setDirty();
    }

    /** Journal participants are exclusively abstract AI. Never restore a snapshot over a human nation. */
    public void validateMacroOutcome(AIWarManager.Outcome outcome) {
        if (macroSequence >= outcome.sequence()) return;
        Set<UUID> participants = outcome.participants();
        for (UUID id : participants) {
            StateData current = getState(id);
            if (current != null && !current.isAI()) throw new IllegalStateException("Macro state identity conflicts with human ownership");
        }
        for (CompoundTag tag : outcome.settlements()) {
            SettlementData site = SettlementData.fromNBT(tag), current = getSettlement(site.getSettlementId());
            if (!participants.contains(site.getOwningStateId()) || current != null && !participants.contains(current.getOwningStateId()))
                throw new IllegalStateException("Macro site identity conflict");
        }
        if (activeSettlements.values().stream().anyMatch(site -> participants.contains(site.getOwningStateId()) && !site.isAbstractSettlement()))
            throw new IllegalStateException("Detailed settlement requires handoff");
    }
    public void applyMacroOutcome(AIWarManager.Outcome outcome) {
        if (macroSequence >= outcome.sequence()) return;
        validateMacroOutcome(outcome); Set<UUID> participants = outcome.participants();
        activeSettlements.values().removeIf(site -> participants.contains(site.getOwningStateId()));
        for (CompoundTag tag : outcome.states()) {
            StateData state = StateData.fromNBT(tag);
            if (!state.isAI()) throw new IllegalStateException("Human macro snapshot");
            state.getOwnedSettlements().clear(); activeStates.put(state.getStateId(), state); aiController.register(state.getStateId());
        }
        for (CompoundTag tag : outcome.settlements()) {
            SettlementData site = SettlementData.fromNBT(tag); activeSettlements.put(site.getSettlementId(), site);
            getState(site.getOwningStateId()).addSettlement(site.getSettlementId());
        }
        for (UUID owner : participants) {
            StateData state = getState(owner); List<SettlementData> sites = settlementsFor(state);
            state.setTotalPopulation((int) Math.min(Integer.MAX_VALUE, sites.stream().mapToLong(SettlementData::getLocalPopulation).sum()));
            if (sites.stream().noneMatch(site -> site.getSettlementId().equals(state.getCapitalSettlementId())))
                state.setCapitalSettlementId(sites.stream().map(SettlementData::getSettlementId).sorted().findFirst().orElse(null));
        }
        if (outcome.kind() == AIWarManager.Kind.DECLARE) declareWar(outcome.actor(), outcome.target());
        if (outcome.retired() != null) {
            UUID retired = outcome.retired(); activeStates.remove(retired); knownNations.remove(retired); knownSettlements.remove(retired); knownRegions.remove(retired);
            String id = retired.toString();
            wars.removeIf(key -> key.startsWith(id + ":") || key.endsWith(":" + id));
            passage.removeIf(key -> key.startsWith(id + ":") || key.endsWith(":" + id));
            peaceOffers.removeIf(key -> key.startsWith(id + ":") || key.endsWith(":" + id));
            for (UUID enemy : enemyIndex.getOrDefault(retired, Set.of())) {
                Set<UUID> peers = enemyIndex.get(enemy);
                if (peers != null) { peers.remove(retired); if (peers.isEmpty()) enemyIndex.remove(enemy); }
            }
            enemyIndex.remove(retired);
        }
        WorldNews.Kind report = switch (outcome.kind()) {
            case DECLARE -> WorldNews.Kind.WAR;
            case CAPTURE -> WorldNews.Kind.CAPTURE;
            case CLASH -> WorldNews.Kind.BATTLE;
            case DISSOLVE -> WorldNews.Kind.DEFEAT;
            case RECRUIT -> null;
        };
        if (report != null) news.publish(outcome.eventId(), outcome.time(), report, outcome.actor(), outcome.actorName(), outcome.target(), outcome.targetName(),
                outcome.focus(), outcome.focusName(), outcome.kind() == AIWarManager.Kind.CAPTURE ? outcome.regions().size() : 0);
        macroSequence = outcome.sequence(); markLogisticsChanged(); setDirty();
    }

    private CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        ListTag states = new ListTag(); activeStates.values().forEach(state -> states.add(state.toNBT())); tag.put("States", states);
        ListTag settlements = new ListTag(); activeSettlements.values().forEach(s -> settlements.add(s.toNBT())); tag.put("Settlements", settlements);
        CompoundTag members = new CompoundTag(); playerToStateMap.forEach((p,s) -> members.putString(p.toString(),s.toString())); tag.put("PlayerStates",members);
        ListTag abandoned = new ListTag(); for (BlockPos pos : abandonedAltarPositions) { CompoundTag item = new CompoundTag(); item.putLong("Pos",pos.asLong()); abandoned.add(item); } tag.put("AbandonedAltars", abandoned);
        ListTag pending = new ListTag(); pendingAltars.forEach((pos, player) -> { CompoundTag item = new CompoundTag(); item.putLong("Pos",pos.asLong()); item.putString("Player",player.toString()); pending.add(item); }); tag.put("PendingAltars",pending);
        putStrings(tag,"AssistedFounders",assistedFounders.stream().map(UUID::toString).toList());
        putStrings(tag,"FirstLegionGranted",firstLegionGranted.stream().map(UUID::toString).toList());
        putStrings(tag,"Wars",wars); putStrings(tag,"Passage",passage); putStrings(tag,"PeaceOffers",peaceOffers);
        tag.putLong("LastEconomyGameTime",lastEconomyGameTime); tag.putLong("LastTimerGameTime",lastTimerGameTime);
        ListTag knowledge = new ListTag();
        for (UUID viewer : activeStates.keySet()) {
            if (!knownNations.containsKey(viewer) && !knownSettlements.containsKey(viewer) && !knownRegions.containsKey(viewer)) continue;
            CompoundTag entry = new CompoundTag(); entry.putString("Viewer", viewer.toString());
            ListTag nations = new ListTag(), places = new ListTag();
            for (KnownNation known : getKnownNations(viewer)) {
                CompoundTag item = new CompoundTag(); item.putString("Id", known.id().toString());
                item.putString("Name", known.name()); item.putString("Tier", known.tier().name()); item.putLong("Seen", known.observedAt()); item.putBoolean("AI", known.ai()); item.putString("Source", known.source().name()); nations.add(item);
            }
            for (KnownSettlement known : getKnownSettlements(viewer)) {
                CompoundTag item = new CompoundTag(); item.putString("Id", known.id().toString()); item.putString("Owner", known.owner().toString());
                item.putString("Name", known.name()); item.putInt("Tier", known.tier()); item.putString("Kind", known.kind().name()); item.putLong("Position", known.position().asLong());
                item.putLong("Seen", known.observedAt()); item.putString("Source", known.source().name()); places.add(item);
            }
            entry.put("Nations", nations); entry.put("Settlements", places); knowledge.add(entry);
            ListTag regions = new ListTag();
            for (KnownRegion known : getKnownRegions(viewer)) {
                CompoundTag item = new CompoundTag(); item.putLong("Key", known.key()); item.putLongArray("Land", known.land()); item.putLong("Seen", known.observedAt());
                if (known.owner() != null) item.putString("Owner", known.owner().toString()); item.putString("Source", known.source().name()); regions.add(item);
            }
            entry.put("Regions", regions);
        }
        tag.put("PoliticalKnowledge", knowledge); tag.put("WorldNews", news.toNBT());
        putStrings(tag, "AIInitializations", aiInitializations.stream().map(UUID::toString).toList());
        tag.putLong("MacroSequence", macroSequence);
        tag.putLong("InteractionSequence", interactionSequence); tag.put("SurrenderOffers", playerConflicts.toNBT());
        return tag;
    }
    private static void putStrings(CompoundTag tag,String key,Collection<String> values) {
        ListTag list = new ListTag(); values.forEach(s -> list.add(StringTag.valueOf(s))); tag.put(key,list);
    }
    private static List<String> strings(CompoundTag tag,String key) {
        List<String> result = new ArrayList<>(); ListTag list = tag.getList(key).orElse(new ListTag());
        for (int i=0;i<list.size();i++) list.getString(i).ifPresent(result::add); return result;
    }
    private static StateManager fromTag(CompoundTag tag) {
        StateManager manager = new StateManager();
        manager.macroSequence = Math.max(0, tag.getLong("MacroSequence").orElse(0L));
        manager.interactionSequence = Math.max(0, tag.getLong("InteractionSequence").orElse(0L));
        manager.playerConflicts.loadOffers(tag.getListOrEmpty("SurrenderOffers"));
        ListTag states = tag.getList("States").orElse(new ListTag());
        for (int i=0;i<states.size();i++) {
            try {
                CompoundTag saved = states.getCompound(i).orElse(new CompoundTag());
                StateData state = StateData.fromNBT(saved);
                // Earlier Sprint 8 builds billed AI on the global cadence. Migrate its last completed day once.
                if (state.isAI() && !state.isInitializationPending() && !saved.contains("LastEconomyTime"))
                    state.initializeEconomyTime(tag.getLong("LastEconomyGameTime").orElse(-1L));
                if (!state.isAI() && state.getLastEconomyTime() < 0) state.initializeEconomyTime(tag.getLong("LastEconomyGameTime").orElse(-1L));
                manager.activeStates.put(state.getStateId(), state);
            }
            catch (RuntimeException malformed) { MinecraftEmpires.LOGGER.warn("Skipped invalid saved state",malformed); }
        }
        manager.activeStates.values().stream().filter(StateData::isAI).map(StateData::getStateId).sorted().forEach(manager.aiController::register);
        manager.activeStates.values().stream().filter(state -> !state.isAI()).forEach(state -> manager.humanStates.put(state.getStateId(), state));
        ListTag settlements = tag.getList("Settlements").orElse(new ListTag());
        for (int i=0;i<settlements.size();i++) {
            try { SettlementData settlement=SettlementData.fromNBT(settlements.getCompound(i).orElse(new CompoundTag()));
                if (manager.activeStates.containsKey(settlement.getOwningStateId()) || settlement.isIndependent() && settlement.isAbstractSettlement()) manager.activeSettlements.put(settlement.getSettlementId(),settlement); }
            catch (RuntimeException malformed) { MinecraftEmpires.LOGGER.warn("Skipped invalid saved settlement",malformed); }
        }
        // Owner lists are runtime lookup indices as well as saved summaries; repair stale legacy lists once on load.
        manager.activeStates.values().forEach(state -> state.getOwnedSettlements().clear());
        manager.activeSettlements.values().forEach(site -> { StateData owner = manager.getState(site.getOwningStateId()); if (owner != null) owner.addSettlement(site.getSettlementId()); });
        CompoundTag members = tag.getCompound("PlayerStates").orElse(new CompoundTag());
        for (String key : members.keySet()) try { UUID state=UUID.fromString(members.getString(key).orElse("")); if (manager.activeStates.containsKey(state)) manager.playerToStateMap.put(UUID.fromString(key),state); } catch (IllegalArgumentException ignored) {}
        manager.playerToStateMap.values().removeIf(id -> manager.getState(id).isAI());
        for (StateData state : manager.activeStates.values()) if (!state.isAI() && state.getLeaderId()!=null) manager.playerToStateMap.put(state.getLeaderId(),state.getStateId());
        ListTag abandoned=tag.getList("AbandonedAltars").orElse(new ListTag());
        for (int i=0;i<abandoned.size();i++) abandoned.getCompound(i).flatMap(p -> p.getLong("Pos")).ifPresent(p -> manager.abandonedAltarPositions.add(BlockPos.of(p)));
        ListTag pending=tag.getList("PendingAltars").orElse(new ListTag());
        for (int i=0;i<pending.size();i++) try { CompoundTag item=pending.getCompound(i).orElse(new CompoundTag()); manager.pendingAltars.put(BlockPos.of(item.getLong("Pos").orElseThrow()),UUID.fromString(item.getString("Player").orElseThrow())); } catch (RuntimeException ignored) {}
        for (String id : strings(tag,"AssistedFounders")) try { manager.assistedFounders.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) {}
        for (StateData state : manager.activeStates.values()) if (!state.isAI() && state.getLeaderId()!=null) manager.assistedFounders.add(state.getLeaderId());
        for (String id : strings(tag,"FirstLegionGranted")) try { manager.firstLegionGranted.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) {}
        manager.wars.addAll(strings(tag,"Wars")); manager.passage.addAll(strings(tag,"Passage")); manager.peaceOffers.addAll(strings(tag,"PeaceOffers"));
        for (String war : manager.wars) try {
            String[] pair = war.split(":");
            if (pair.length != 2) continue;
            UUID a = UUID.fromString(pair[0]), b = UUID.fromString(pair[1]);
            if (!manager.areAtWar(a, b) || manager.getState(a) == null || manager.getState(b) == null) continue;
            manager.enemyIndex.computeIfAbsent(a, ignored -> new HashSet<>()).add(b);
            manager.enemyIndex.computeIfAbsent(b, ignored -> new HashSet<>()).add(a);
        } catch (IllegalArgumentException malformed) { /* Invalid diplomacy cannot enter the owner index. */ }
        manager.lastEconomyGameTime=tag.getLong("LastEconomyGameTime").orElse(-1L);
        manager.lastTimerGameTime=tag.getLong("LastTimerGameTime").orElse(-1L);
        for (var value : tag.getListOrEmpty("PoliticalKnowledge")) if (value instanceof CompoundTag entry) {
            try {
                UUID viewer = UUID.fromString(entry.getString("Viewer").orElseThrow());
                StateData observer = manager.getState(viewer); if (observer == null || observer.isAI()) continue;
                for (var record : entry.getListOrEmpty("Regions")) if (record instanceof CompoundTag item) try {
                    manager.learnRegion(viewer, new KnownRegion(item.getLong("Key").orElseThrow(), item.getString("Owner").map(UUID::fromString).orElse(null),
                            item.getLongArray("Land").orElseThrow(), item.getLong("Seen").orElseThrow(), KnowledgeSource.valueOf(item.getString("Source").orElse("OBSERVATION"))));
                } catch (RuntimeException malformed) { /* Retain other independently remembered facts. */ }
                for (var record : entry.getListOrEmpty("Nations")) if (record instanceof CompoundTag item) try {
                    UUID id = UUID.fromString(item.getString("Id").orElseThrow()); long seen = item.getLong("Seen").orElseThrow();
                    if (seen < 0) continue;
                    KnownNation fact = new KnownNation(id, cleanName(item.getString("Name").orElse("")),
                            StateTier.valueOf(item.getString("Tier").orElse("COUNTY")), seen,
                            item.getBoolean("AI").orElse(manager.getState(id) != null && manager.getState(id).isAI()), KnowledgeSource.valueOf(item.getString("Source").orElse("OBSERVATION")));
                    manager.knownNations.computeIfAbsent(viewer, ignored -> new HashMap<>()).putIfAbsent(id, fact);
                } catch (RuntimeException malformed) { /* Skip an invalid remembered nation only. */ }
                for (var record : entry.getListOrEmpty("Settlements")) if (record instanceof CompoundTag item) try {
                    UUID id = UUID.fromString(item.getString("Id").orElseThrow()); long seen = item.getLong("Seen").orElseThrow();
                    int tier = item.getInt("Tier").orElse(1); if (seen < 0 || tier < 1 || tier > StateBalance.MAXIMUM_SETTLEMENT_TIER) continue;
                    KnownSettlement fact = new KnownSettlement(id, UUID.fromString(item.getString("Owner").orElseThrow()),
                            cleanName(item.getString("Name").orElse("")), BlockPos.of(item.getLong("Position").orElseThrow()), tier, seen, KnowledgeSource.valueOf(item.getString("Source").orElse("OBSERVATION")), SettlementData.Kind.valueOf(item.getString("Kind").orElse(tier >= 2 ? "CITY" : "TOWN")));
                    manager.knownSettlements.computeIfAbsent(viewer, ignored -> new HashMap<>()).putIfAbsent(id, fact);
                } catch (RuntimeException malformed) { /* Political memory must not corrupt authoritative ownership. */ }
            } catch (RuntimeException malformed) { /* Unknown or malformed observer. */ }
        }
        manager.news.loadNBT(tag.getCompound("WorldNews").orElse(new CompoundTag()));
        for (String id : strings(tag, "AIInitializations")) try { manager.aiInitializations.add(UUID.fromString(id)); }
        catch (IllegalArgumentException malformed) { /* An invalid receipt cannot opt an old nation into generation. */ }
        return manager;
    }
}
