package com.devc.minecraftempires.state;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.army.ArmyManager;
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

/** Persistent state, founding entitlements, settlement ownership, and basic diplomacy. */
public class StateManager extends SavedData {
    //all active states, settlements, and related data are stored in these collections
    private final Map<UUID, StateData> activeStates = new HashMap<>();
    private final Map<UUID, UUID> playerToStateMap = new HashMap<>();
    private final Map<UUID, SettlementData> activeSettlements = new HashMap<>();
    private final Set<BlockPos> abandonedAltarPositions = new HashSet<>();
    private final Map<BlockPos, UUID> pendingAltars = new HashMap<>();
    private final Set<UUID> assistedFounders = new HashSet<>();
    private final Set<UUID> firstLegionGranted = new HashSet<>();
    private final Set<String> wars = new HashSet<>();
    private final Set<String> passage = new HashSet<>();
    private final Set<String> peaceOffers = new HashSet<>();
    private long lastEconomyGameTime = -1;
    private long lastTimerGameTime = -1;

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
    public StateData getStateByPlayer(UUID id) { return activeStates.get(playerToStateMap.get(id)); }
    public Collection<SettlementData> getAllSettlements() { return Collections.unmodifiableCollection(activeSettlements.values()); }
    public SettlementData getSettlement(UUID id) { return activeSettlements.get(id); }
    public long getLastEconomyGameTime() { return lastEconomyGameTime; }
    public void setLastEconomyGameTime(long time) { lastEconomyGameTime = time; setDirty(); }

    //constructors
    public void createState(StateData state) {
        activeStates.put(state.getStateId(), state);
        if (state.getLeaderId() != null) {
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
        if (activeStates.containsKey(state)) { playerToStateMap.put(player, state); setDirty(); }
    }

    //add funds to a state, if it exists
    public void addFunds(UUID stateId, long amount) {
        StateData state = getState(stateId);
        if (state != null) { state.addFunds(amount); setDirty(); }
    }

    //check if a player can claim a free legion, based on their state and whether they have already claimed it
    public boolean canClaimFreeLegion(UUID player) {
        StateData state = getStateByPlayer(player);
        return state != null && player.equals(state.getLeaderId()) && !firstLegionGranted.contains(player);
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
        if (!ArmyManager.removeForStateIfAvailable(level, stateId)) return false;
        for (SettlementData settlement : new ArrayList<>(activeSettlements.values())) {
            if (stateId.equals(settlement.getOwningStateId())) disbandSettlement(settlement.getSettlementId(), level);
        }
        activeStates.remove(stateId);
        playerToStateMap.values().removeIf(stateId::equals);
        ClaimManager.get(level).clearAllClaimsForState(stateId);
        String id = stateId.toString();
        wars.removeIf(key -> key.startsWith(id + ":") || key.endsWith(":" + id));
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
        activeSettlements.put(id, settlement);
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

    //disband a settlement, removing it from the active settlements, releasing its garrison, and clearing its claims
    public void disbandSettlement(UUID id, ServerLevel level) {
        SettlementData settlement = activeSettlements.remove(id);
        if (settlement == null) return;
        ArmyManager.get(level).releaseGarrison(id);
        StateData state = getState(settlement.getOwningStateId());
        if (state != null) {
            state.removeSettlement(id);
            if (id.equals(state.getCapitalSettlementId())) state.setCapitalSettlementId(null);
        }
        ClaimManager.get(level).clearAllClaimsForSettlement(id.toString());
        ClaimManager.get(level).removeSettlementCenter(id.toString());
        markAltarAbandoned(settlement.getCenterAltarPos());
        refreshProgression(level);
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
            if (settlementFootprintsOverlap(other.getCenterAltarPos(), pos)) //check this, settlements shouldnt overlap, but they can be near each other (though not in the first settlements existing claim)
                return "The settlement's territory would overlap an existing settlement.";
        }
        int newClaims = 0;
        ClaimManager claims = ClaimManager.get(level);
        ChunkPos centerChunk = ChunkPos.containing(pos);
        for (ChunkPos chunk : squareChunks(pos, StateBalance.SETTLEMENT_RADIUS)) {
            ChunkData data = claims.getClaim(chunk);
            if (data != null && !Objects.equals(stateId, data.getOwnerUUID())) return "The settlement's territory overlaps another state.";
            if (data == null && (chunk.equals(centerChunk) || ClaimManager.isClaimableLand(level, chunk))) newClaims++;
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
        String error = validateAltar(player.level(), pos, state == null ? null : state.getStateId());
        if (error != null) { player.sendSystemMessage(Component.literal(error)); return; }
        pendingAltars.put(pos.immutable(), player.getUUID());
        setDirty();
        com.devc.minecraftempires.network.ModNetworking.sendSnapshots(player);
        PacketDistributor.sendToPlayer(player, new OpenFoundingPayload(pos));
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
        refreshProgression(level);

        if (!newState) return "Founded " + name + ".";
        String benefits = canClaimFreeLegion(player.getUUID()) ? " Your first legion is free." : "";
        if (state.getGraceTicksRemaining() > 0) benefits += " Maintenance is waived for 14 active in-game days.";
        return "Founded " + name + "." + benefits;
    }

    /** Chunk rounding is shared by ownership, overlap validation, and settlement protection. */
    public static boolean settlementFootprintsOverlap(BlockPos first, BlockPos second) {
        int radius = StateBalance.SETTLEMENT_RADIUS;
        return Math.floorDiv(first.getX() - radius, 16) <= Math.floorDiv(second.getX() + radius, 16)
                && Math.floorDiv(second.getX() - radius, 16) <= Math.floorDiv(first.getX() + radius, 16)
                && Math.floorDiv(first.getZ() - radius, 16) <= Math.floorDiv(second.getZ() + radius, 16)
                && Math.floorDiv(second.getZ() - radius, 16) <= Math.floorDiv(first.getZ() + radius, 16);
    }

    //check if a chunk is within the footprint of a settlement, based on the settlement's altar position and radius
    public static boolean isInSettlementFootprint(BlockPos altar, ChunkPos chunk) {
        int radius = StateBalance.SETTLEMENT_RADIUS;
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
        claims.registerSettlementCenter(settlement.getSettlementId().toString(), centerChunk);
        for (ChunkPos chunk : squareChunks(settlement.getCenterAltarPos(), StateBalance.SETTLEMENT_RADIUS)) {
            ChunkData old = claims.getClaim(chunk);
            if ((old == null && (chunk.equals(centerChunk) || ClaimManager.isClaimableLand(level, chunk))) || (old != null && stateId.equals(old.getOwnerUUID())))
                claims.setClaim(chunk, stateId, settlement.getSettlementId().toString(), false, settlement.getSettlementTier());
        }
        setDirty();
    }
    
    //upgrade a settlement to the next tier, if it meets the requirements
    public String upgradeSettlement(ServerLevel level, StateData state, UUID settlementId) {
        SettlementData settlement = getSettlement(settlementId);
        if (settlement == null || !state.getStateId().equals(settlement.getOwningStateId())) return "Settlement not owned by your state.";
        if (settlement.getSettlementTier() >= StateBalance.MAXIMUM_SETTLEMENT_TIER) return "This settlement is already a city.";
        if (settlement.getLocalPopulation() < StateBalance.CITY_POPULATION) return "A city needs at least " + StateBalance.CITY_POPULATION + " residents.";
        if (state.getTreasuryBalance() < StateBalance.CITY_UPGRADE_COST) return "City upgrade requires " + StateBalance.CITY_UPGRADE_COST + " emeralds.";
        state.deductFunds(StateBalance.CITY_UPGRADE_COST);
        settlement.upgradeSettlement();
        for (ChunkData data : ClaimManager.get(level).getClaimsView().values())
            if (settlementId.toString().equals(data.getSettlementID())) data.setSettlementTier(settlement.getSettlementTier());
        ClaimManager.get(level).setDirty();
        refreshProgression(level);
        setDirty();
        return settlement.getSettlementName() + " is now a city.";
    }

    private static String directed(UUID a, UUID b) { return a + ":" + b; }
    private static String symmetric(UUID a, UUID b) { return a.compareTo(b) < 0 ? directed(a, b) : directed(b, a); }
    public boolean areAtWar(UUID a, UUID b) { return a != null && b != null && !a.equals(b) && wars.contains(symmetric(a, b)); }
    /** Guest has permission to enter owner's territory; permission need not be reciprocal. */
    public boolean hasPassage(UUID guest, UUID owner) {
        return guest != null && owner != null && (guest.equals(owner) || (!areAtWar(guest, owner) && passage.contains(directed(guest, owner))));
    }
    public boolean declareWar(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b) || getState(a) == null || getState(b) == null) return false;
        passage.remove(directed(a, b)); passage.remove(directed(b, a));
        peaceOffers.remove(directed(a, b)); peaceOffers.remove(directed(b, a));
        boolean changed = wars.add(symmetric(a, b));
        setDirty();
        return changed;
    }
    public void grantPassage(UUID owner, UUID guest) {
        if (!owner.equals(guest) && !areAtWar(owner, guest) && getState(guest) != null) passage.add(directed(guest, owner));
        setDirty();
    }
    public void revokePassage(UUID owner, UUID guest) { passage.remove(directed(guest, owner)); setDirty(); }
    public void offerPeace(UUID from, UUID to) { if (areAtWar(from, to)) peaceOffers.add(directed(from, to)); setDirty(); }
    public boolean hasPeaceOffer(UUID from, UUID to) { return peaceOffers.contains(directed(from, to)); }
    public boolean acceptPeace(UUID accepter, UUID proposer) {
        if (!peaceOffers.remove(directed(proposer, accepter))) return false;
        peaceOffers.remove(directed(accepter, proposer)); wars.remove(symmetric(accepter, proposer)); setDirty(); return true;
    }
    public void notifyState(ServerLevel level, UUID state, String message) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers())
            if (Objects.equals(playerToStateMap.get(player.getUUID()), state)) player.sendSystemMessage(Component.literal(message));
    }

    public int getCityCount(StateData state) { return (int) settlementsFor(state).stream().filter(s -> s.getSettlementTier() >= 2).count(); }
    public int getTownCount(StateData state) { return (int) settlementsFor(state).stream().filter(s -> s.getSettlementTier() == 1).count(); }
    public List<SettlementData> settlementsFor(StateData state) {
        return activeSettlements.values().stream().filter(s -> state.getStateId().equals(s.getOwningStateId())).toList();
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
        if (additional <= 0 || state.getCurrentTier().isPermanent()) return true;
        StateTier next = nextTier(state);
        if (next == null) return true;
        // The milestone itself can be reached; further growth requires promotion/choice.
        return (long) currentlyOwned + additional <= next.getMinChunks();
    }
    public void refreshProgression(ServerLevel level) {
        ClaimManager claims = ClaimManager.get(level);
        for (StateData state : activeStates.values()) {
            List<SettlementData> settlements = settlementsFor(state);
            state.getOwnedSettlements().clear();
            settlements.forEach(s -> state.addSettlement(s.getSettlementId()));
            state.setTotalPopulation((int) Math.min(Integer.MAX_VALUE, settlements.stream().mapToLong(SettlementData::getLocalPopulation).sum()));
            if (getSettlement(state.getCapitalSettlementId()) == null || !state.getStateId().equals(getSettlement(state.getCapitalSettlementId()).getOwningStateId()))
                state.setCapitalSettlementId(settlements.stream().sorted(Comparator.comparing(SettlementData::getSettlementId)).map(SettlementData::getSettlementId).findFirst().orElse(null));
            if (state.getCurrentTier().isPermanent()) { state.setEndgameChoice(false); continue; }
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
            if (before != calculated) notifyState(level, state.getStateId(), "Your state is now a " + calculated.name().replace('_', ' ') + ".");
        }
        setDirty();
    }
    public boolean chooseGovernment(ServerLevel level, StateData state, StateTier chosen) {
        refreshProgression(level);
        if (!state.hasEndgameChoice() || (chosen != StateTier.REPUBLIC && chosen != StateTier.EMPIRE)) return false;
        state.setCurrentTier(chosen); state.setEndgameChoice(false); setDirty(); return true;
    }
    /** The army must have physically reached the altar; callers additionally check nearby defenders. */
    public boolean captureSettlement(ServerLevel level, UUID settlementId, UUID attacker) {
        SettlementData settlement = getSettlement(settlementId);
        StateData state = getState(attacker);
        if (settlement == null || state == null || settlement.isLocallyImmune() || !areAtWar(attacker, settlement.getOwningStateId())) return false;
        ClaimManager claims = ClaimManager.get(level);
        long newClaims = claims.getClaimsView().values().stream().filter(c -> settlementId.toString().equals(c.getSettlementID()) && !attacker.equals(c.getOwnerUUID())).count();
        if (!canAcquireChunks(state, claims.getClaimCountForState(attacker), (int) newClaims)) return false;
        UUID oldOwner = settlement.getOwningStateId();
        ArmyManager.get(level).releaseGarrison(settlementId);
        settlement.setOwningStateId(attacker);
        settlement.setLocalSiegeImmunityTicks(StateBalance.CAPTURE_IMMUNITY_TICKS);
        settlement.setMartialLaw(false);
        for (ChunkData claim : claims.getClaimsView().values()) {
            if (settlementId.toString().equals(claim.getSettlementID())) { claim.setOwnerUUID(attacker); claim.setIsGarrisoned(false); }
        }
        claims.rebuildClaimCounts(); claims.setDirty();
        state.incrementSettlementsCaptured();
        refreshProgression(level);
        notifyState(level, oldOwner, settlement.getSettlementName() + " has been captured.");
        notifyState(level, attacker, "Captured " + settlement.getSettlementName() + ".");
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
            if (onlineStates.contains(state.getStateId())) state.setGraceTicksRemaining(state.getGraceTicksRemaining() - elapsed);
            for (int i = 0; i < elapsed; i++) { state.tickSiegeImmunity(); state.tickCampaignMomentum(); }
        }
        for (SettlementData settlement : activeSettlements.values()) for (int i = 0; i < elapsed; i++) settlement.tickLocalImmunity();
        setDirty();
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
        ListTag states = tag.getList("States").orElse(new ListTag());
        for (int i=0;i<states.size();i++) {
            try { StateData state=StateData.fromNBT(states.getCompound(i).orElse(new CompoundTag())); manager.activeStates.put(state.getStateId(),state); }
            catch (RuntimeException malformed) { MinecraftEmpires.LOGGER.warn("Skipped invalid saved state",malformed); }
        }
        ListTag settlements = tag.getList("Settlements").orElse(new ListTag());
        for (int i=0;i<settlements.size();i++) {
            try { SettlementData settlement=SettlementData.fromNBT(settlements.getCompound(i).orElse(new CompoundTag()));
                if (manager.activeStates.containsKey(settlement.getOwningStateId())) manager.activeSettlements.put(settlement.getSettlementId(),settlement); }
            catch (RuntimeException malformed) { MinecraftEmpires.LOGGER.warn("Skipped invalid saved settlement",malformed); }
        }
        CompoundTag members = tag.getCompound("PlayerStates").orElse(new CompoundTag());
        for (String key : members.keySet()) try { UUID state=UUID.fromString(members.getString(key).orElse("")); if (manager.activeStates.containsKey(state)) manager.playerToStateMap.put(UUID.fromString(key),state); } catch (IllegalArgumentException ignored) {}
        for (StateData state : manager.activeStates.values()) if (state.getLeaderId()!=null) manager.playerToStateMap.put(state.getLeaderId(),state.getStateId());
        ListTag abandoned=tag.getList("AbandonedAltars").orElse(new ListTag());
        for (int i=0;i<abandoned.size();i++) abandoned.getCompound(i).flatMap(p -> p.getLong("Pos")).ifPresent(p -> manager.abandonedAltarPositions.add(BlockPos.of(p)));
        ListTag pending=tag.getList("PendingAltars").orElse(new ListTag());
        for (int i=0;i<pending.size();i++) try { CompoundTag item=pending.getCompound(i).orElse(new CompoundTag()); manager.pendingAltars.put(BlockPos.of(item.getLong("Pos").orElseThrow()),UUID.fromString(item.getString("Player").orElseThrow())); } catch (RuntimeException ignored) {}
        for (String id : strings(tag,"AssistedFounders")) try { manager.assistedFounders.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) {}
        for (StateData state : manager.activeStates.values()) if (state.getLeaderId()!=null) manager.assistedFounders.add(state.getLeaderId());
        for (String id : strings(tag,"FirstLegionGranted")) try { manager.firstLegionGranted.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) {}
        manager.wars.addAll(strings(tag,"Wars")); manager.passage.addAll(strings(tag,"Passage")); manager.peaceOffers.addAll(strings(tag,"PeaceOffers"));
        manager.lastEconomyGameTime=tag.getLong("LastEconomyGameTime").orElse(-1L);
        manager.lastTimerGameTime=tag.getLong("LastTimerGameTime").orElse(-1L);
        return manager;
    }
}
