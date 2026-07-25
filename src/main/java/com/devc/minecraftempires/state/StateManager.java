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

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Queue;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

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

    public void addFunds(UUID stateId, double amount) {
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

    public void establishSettlementClaims(ServerLevel level, SettlementData settlement, UUID stateId) {
        BlockPos altarPos = settlement.getCenterAltarPos(); 
        ChunkPos centerChunkPos = ChunkPos.containing(altarPos); 
        ClaimManager claimManager = ClaimManager.get(level);

        //register the core chunk of the settlement in the ClaimManager
        claimManager.registerSettlementCenter(settlement.getSettlementId().toString(), centerChunkPos);
        
        //BFS variables
        int maxClaims = 9; // The maximum amount of chunks an initial outpost can claim
        int claimedCount = 0;
        int maxRadius = 2; //used to prevent flood fill algo from claiming chunks too far away
        
        Queue<ChunkPos> queue = new ArrayDeque<>(); //queue for BFS
        Set<ChunkPos> visited = new HashSet<>(); //set to track visited chunks and prevent reprocessing
        
        queue.add(centerChunkPos); 
        visited.add(centerChunkPos); 
        
        //use BFS to claim chunks around the altar, stopping at water biomes and respecting maxClaims
        while (!queue.isEmpty() && claimedCount < maxClaims) {
            ChunkPos currentChunk = queue.poll();
            
            //get altar biome
            BlockPos checkPos = currentChunk.getMiddleBlockPosition(level.getSeaLevel());
            Holder<Biome> biomeHolder = level.getBiome(checkPos);
            
            //if theres water around, stop claiming (though altar chunk is always claimed, MAY NEED BUG FIX)
            if (!currentChunk.equals(centerChunkPos) && (biomeHolder.is(BiomeTags.IS_OCEAN) || biomeHolder.is(BiomeTags.IS_RIVER))) {
                continue; 
            }

            //claim chunk, only if it is not already claimed 
            if (!claimManager.isClaimed(currentChunk)) { 
                claimManager.setClaim(
                    currentChunk, 
                    stateId, 
                    settlement.getSettlementId().toString(), 
                    false, 
                    1      
                );
                claimedCount++; 
            } 
            
            //queue nearby chunks for checking
            ChunkPos[] neighbors = {
                new ChunkPos(currentChunk.x(), currentChunk.z() - 1), 
                new ChunkPos(currentChunk.x(), currentChunk.z() + 1), 
                new ChunkPos(currentChunk.x() + 1, currentChunk.z()), 
                new ChunkPos(currentChunk.x() - 1, currentChunk.z())  
            };
            
            //new - Validate and add neighbors to the queue
            for (ChunkPos neighbor : neighbors) {
                if (visited.add(neighbor)) { // HashSet.add() returns true if it wasn't already in the set
                    // Ensure we don't snake out too far from the center altar
                    if (Math.abs(neighbor.x() - centerChunkPos.x()) <= maxRadius && Math.abs(neighbor.z() - centerChunkPos.z()) <= maxRadius) {
                        queue.add(neighbor);
                    }
                }
            }
        }
        
        // Mark State data as dirty, and tell ClaimManager to mark its data as dirty too!
        this.setDirty();
        claimManager.setDirty();
        
        //new - Updated log to show the dynamic count
        MinecraftEmpires.LOGGER.info("Established {} natural chunk claims for settlement: {}", claimedCount, settlement.getSettlementName());
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
        
        return manager;
    }
}