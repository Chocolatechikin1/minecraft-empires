package com.devc.minecraftempires.state;

import com.devc.minecraftempires.territory.SettlementData;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.tags.BiomeTags;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.territory.ChunkData;
import com.devc.minecraftempires.territory.ClaimManager;

public class StateManager extends SavedData {

    private static final String DATA_NAME = "minecraftempires_states";
    private static final String STATES_LIST_KEY = "States";
    private static final String SETTLEMENTS_LIST_KEY = "Settlements";

    //map of all states
    private final Map<UUID, StateData> activeStates = new HashMap<>();
    private final Map<UUID, UUID> playerToStateMap = new HashMap<>();
    //all settlements
    private final Map<UUID, SettlementData> activeSettlements = new HashMap<>();
    // Altar positions whose settlements have been abandoned — these blocks may now be broken.
    // Persisted to disk so a server restart doesn't permanently lock players out.
    private final Set<BlockPos> abandonedAltarPositions = new HashSet<>();

    public StateManager() {}

    private static final Codec<StateManager> CODEC = CompoundTag.CODEC.xmap(StateManager::fromTag, StateManager::toTag);

    public static final SavedDataType<StateManager> TYPE = new SavedDataType<>(
        Identifier.withDefaultNamespace(DATA_NAME),
        StateManager::new,
        //StateManager::fromTag,
        CODEC,
        DataFixTypes.LEVEL
    );
    
    // The master list of all active states on the server
    //private final Map<UUID, StateData> states = new HashMap<>();

    //Management methods
    
    //all state methods
    public static StateManager get(ServerLevel level) {
        // This tells NeoForge: "Go to this world's storage. If minecraftempires_states.dat exists, load it. If not, create a new one."
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public StateData getState(UUID stateId) {
        return activeStates.get(stateId);
    }

    public Collection<StateData> getAllStates() {
        return activeStates.values();
    }

    public StateData getStateByPlayer(UUID playerUUID) {
        /*UUID stateId = playerToStateMap.get(playerUUID);
        return stateId != null ? activeStates.get(stateId) : null;*/
        UUID stateId = playerToStateMap.get(playerUUID);
        if (stateId != null) {
            return activeStates.get(stateId);
        }
        return null;
    }

    public void createState(StateData state) {
        activeStates.put(state.getStateId(), state);
        this.setDirty();
    }

    public StateData createState(String stateName, UUID leaderId, StateTier startingTier) {
        UUID newId = UUID.randomUUID();
        StateData state = new StateData(newId, stateName, leaderId, startingTier);
        createState(state);
        return state;
    }

    public void addPlayerToState(UUID playerUUID, UUID stateId) {
        playerToStateMap.put(playerUUID, stateId);
        this.setDirty();
    }

    public void addFunds(UUID stateId, long amount) {
        StateData state = getState(stateId);
        if (state != null) {
            state.addFunds(amount);
            this.setDirty();
        }
    }

    public boolean disbandState(UUID stateId, ServerLevel level) {
        StateData state = activeStates.remove(stateId);
        if (state != null) {
            playerToStateMap.remove(state.getLeaderId());
            
            // Unclaim all chunks owned by this state/leader
            ClaimManager claimManager = ClaimManager.get(level);
            claimManager.clearAllClaimsForState(state.getLeaderId());
            
            // TODO: Delete Settlements from activeSettlements if applicable
            setDirty();
            return true;
        }
        return false;
    }

    public boolean leaveState(UUID playerId) {
        if (playerToStateMap.containsKey(playerId)) {
            playerToStateMap.remove(playerId);
            setDirty();
            return true;
        }
        return false;
    }

    //method to handle settlement abandonment here

    
    //all settlement methods
    
    public SettlementData getSettlement(UUID settlementId) {
        return activeSettlements.get(settlementId);
    }

    public Collection<SettlementData> getAllSettlements() { 
        return java.util.Collections.unmodifiableCollection(activeSettlements.values());
    }

    public void registerSettlement(UUID settlementId, SettlementData data) {
        activeSettlements.put(settlementId, data);
        this.setDirty(); //tells server to save this new town to disk
    }

    // ── Altar abandonment state ───────────────────────────────────────────────

    /** Returns true if this altar's settlement has been abandoned and the block may be broken. */
    public boolean isAltarAbandoned(BlockPos pos) {
        return abandonedAltarPositions.contains(pos);
    }

    /** Marks an altar position as abandoned so the block-break event allows it. */
    public void markAltarAbandoned(BlockPos pos) {
        abandonedAltarPositions.add(pos);
        setDirty();
    }

    /** Clears the abandoned marker once the block has actually been broken. */
    public void clearAltarAbandoned(BlockPos pos) {
        abandonedAltarPositions.remove(pos);
        setDirty();
    }

    // ── Settlement disbanding ─────────────────────────────────────────────────

    /**
     * Removes a single settlement and all its chunk claims from the world.
     * The owning state is left intact regardless of remaining settlement count.
     *
     * TODO (Phase 2): implement body
     */
    public void disbandSettlement(UUID settlementId, ServerLevel level) {
        // TODO: look up SettlementData by settlementId
        // TODO: remove all ClaimManager claims whose settlementId string matches
        // TODO: call owningState.removeSettlement(settlementId)
        // TODO: remove entry from activeSettlements
        // TODO: setDirty()
        MinecraftEmpires.LOGGER.warn("disbandSettlement() called but not yet implemented for: {}", settlementId);
    }

    public void establishSettlementClaims(ServerLevel level, SettlementData settlement, UUID stateId) {
        BlockPos altarPos = settlement.getCenterAltarPos(); 
        ChunkPos centerChunkPos = ChunkPos.containing(altarPos); 
        ClaimManager claimManager = ClaimManager.get(level);

        // Register the core chunk as the province centre
        claimManager.registerSettlementCenter(settlement.getSettlementId().toString(), centerChunkPos);

        int claimedCount = 0;
        int waterSlotsSkipped = 0;
        //tracks what this pass has claimed so subsequent BFS passes don't re-select the same chunk.
        Set<ChunkPos> claimedInPass = new HashSet<>();

        // Claim every land chunk; count water slots, BFS algo not ran here
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkPos candidate = new ChunkPos(centerChunkPos.x() + dx, centerChunkPos.z() + dz);

                // Altar chunk (dx==0, dz==0) is always claimed — skip water check for it (TODO: add it so that player cannot place the altar in water)
                if (dx != 0 || dz != 0) {
                    BlockPos checkPos = candidate.getMiddleBlockPosition(level.getSeaLevel());
                    Holder<Biome> biomeHolder = level.getBiome(checkPos);
                    if (biomeHolder.is(BiomeTags.IS_OCEAN) || biomeHolder.is(BiomeTags.IS_RIVER)) {
                        waterSlotsSkipped++;
                        continue;
                    }
                }

                if (!claimManager.isClaimed(candidate)) {
                    claimManager.setClaim(candidate, stateId, settlement.getSettlementId().toString(), false, 1);
                    claimedInPass.add(candidate);
                    claimedCount++;
                }
            }
        }
        // If we have skipped water slots, we need to find replacements for them.
        final int replacementMaxRadius = 8;
        //pre-mark the entire 3×3 as visited so BFS cannot return any of them as replacements.
        Set<ChunkPos> squareChunks = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                squareChunks.add(new ChunkPos(centerChunkPos.x() + dx, centerChunkPos.z() + dz));
            }
        }

        for (int slot = 0; slot < waterSlotsSkipped; slot++) {
            // Seed queue from all land chunks claimed so far (both 3×3 and prior replacements).
            // This expands outward from the settlement boundary, not from the center.
            Queue<ChunkPos> bfsQueue = new ArrayDeque<>(claimedInPass);
            Set<ChunkPos> bfsVisited = new HashSet<>(squareChunks);
            bfsVisited.addAll(claimedInPass); //don't re-select previously found replacements
            ChunkPos replacement = null;

            outer:
            while (!bfsQueue.isEmpty()) {
                ChunkPos curr = bfsQueue.poll();
                ChunkPos[] neighbors = {
                    new ChunkPos(curr.x(), curr.z() - 1),
                    new ChunkPos(curr.x(), curr.z() + 1),
                    new ChunkPos(curr.x() + 1, curr.z()),
                    new ChunkPos(curr.x() - 1, curr.z())
                };
                for (ChunkPos neighbor : neighbors) {
                    if (!bfsVisited.add(neighbor)) continue;
                    if (Math.abs(neighbor.x() - centerChunkPos.x()) > replacementMaxRadius
                            || Math.abs(neighbor.z() - centerChunkPos.z()) > replacementMaxRadius) continue;
                    BlockPos nCheck = neighbor.getMiddleBlockPosition(level.getSeaLevel());
                    Holder<Biome> nBiome = level.getBiome(nCheck);
                    if (nBiome.is(BiomeTags.IS_OCEAN) || nBiome.is(BiomeTags.IS_RIVER)) {
                        continue; 
                    }
                    if (!claimManager.isClaimed(neighbor)) { //found a valid land replacement outside the square, set to claim
                        replacement = neighbor;
                        break outer; 
                    }
                    bfsQueue.add(neighbor); //claimed land — keep searching further out
                }
            }

            if (replacement != null) {
                claimManager.setClaim(replacement, stateId, settlement.getSettlementId().toString(), false, 1);
                claimedInPass.add(replacement);
                claimedCount++;
            }
        }

        // Mark State data as dirty, and tell ClaimManager to mark its data as dirty too
        this.setDirty();
        claimManager.setDirty();
        MinecraftEmpires.LOGGER.info("Established {} chunk claims for settlement: {}", claimedCount, settlement.getSettlementName());
    }

    //saving and loading methods
   private CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();

        // 1. Save States
        ListTag stateList = new ListTag();
        for (StateData state : activeStates.values()) { 
            stateList.add(state.toNBT());
        }
        tag.put(STATES_LIST_KEY, stateList);

        // 2. Save Settlements
        ListTag settlementList = new ListTag();
        for (SettlementData settlement : activeSettlements.values()) {
            settlementList.add(settlement.toNBT());
        }
        tag.put(SETTLEMENTS_LIST_KEY, settlementList);

        // 3. Save Player-to-State Map (THE FIX)
        CompoundTag playerMapTag = new CompoundTag();
        for (Map.Entry<UUID, UUID> entry : playerToStateMap.entrySet()) {
            playerMapTag.putString(entry.getKey().toString(), entry.getValue().toString());
        }
        tag.put("PlayerStates", playerMapTag);

        // 4. Save Abandoned Altar Positions
        ListTag abandonedList = new ListTag();
        for (BlockPos pos : abandonedAltarPositions) {
            CompoundTag posTag = new CompoundTag();
            posTag.putLong("Pos", pos.asLong());
            abandonedList.add(posTag);
        }
        tag.put("AbandonedAltars", abandonedList);

        return tag;
    }

    private static StateManager fromTag(CompoundTag tag) {
        StateManager manager = new StateManager();
        
        // 1. Load States
        ListTag stateList = tag.getList(STATES_LIST_KEY).orElse(new ListTag());
        for (int i = 0; i < stateList.size(); i++) {
            CompoundTag stateTag = stateList.getCompound(i).orElse(new CompoundTag());
            StateData loadedState = StateData.fromNBT(stateTag);
            manager.activeStates.put(loadedState.getStateId(), loadedState);
        }

        // 2. Load Settlements
        // FIX: Just check if the key exists; the orElse() handle handles the empty check
        if (tag.contains(SETTLEMENTS_LIST_KEY)) {
            ListTag settlementList = tag.getList(SETTLEMENTS_LIST_KEY).orElse(new ListTag());
            for (int i = 0; i < settlementList.size(); i++) {
                CompoundTag sTag = settlementList.getCompound(i).orElse(new CompoundTag());
                SettlementData loadedSettlement = SettlementData.fromNBT(sTag);
                manager.activeSettlements.put(loadedSettlement.getSettlementId(), loadedSettlement);
            }
        }

        // 3. Load Player-to-State Map
        // FIX: Just check if key exists, then read keySet() instead of getAllKeys()
        if (tag.contains("PlayerStates")) {
            CompoundTag playerMapTag = tag.getCompound("PlayerStates").orElse(new CompoundTag());
            for (String key : playerMapTag.keySet()) {
                String valueStr = playerMapTag.getString(key).orElse("");
                if (!valueStr.isEmpty()) {
                    manager.playerToStateMap.put(UUID.fromString(key), UUID.fromString(valueStr));
                }
            }
        }

        // 4. Load Abandoned Altar Positions
        if (tag.contains("AbandonedAltars")) {
            ListTag abandonedList = tag.getList("AbandonedAltars").orElse(new ListTag());
            for (int i = 0; i < abandonedList.size(); i++) {
                CompoundTag posTag = abandonedList.getCompound(i).orElse(new CompoundTag());
                posTag.getLong("Pos").ifPresent(l -> manager.abandonedAltarPositions.add(BlockPos.of(l)));
            }
        }
        
        return manager;
    }
}