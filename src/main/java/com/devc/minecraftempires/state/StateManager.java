package com.devc.minecraftempires.state;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.util.datafix.DataFixTypes;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StateManager extends SavedData {

    private static final String DATA_NAME = "minecraftempires_states";
    private static final String STATES_LIST_KEY = "States";

    private static final Codec<StateManager> CODEC = CompoundTag.CODEC.xmap(StateManager::fromTag, StateManager::toTag);

    public static final SavedDataType<StateManager> TYPE = new SavedDataType<>(
        Identifier.withDefaultNamespace(DATA_NAME),
        StateManager::new,
        CODEC,
        DataFixTypes.LEVEL
    );
    
    // The master list of all active states on the server
    private final Map<UUID, StateData> states = new HashMap<>();

    // --- Core Management Methods ---
    
    public StateData createState(String stateName, UUID leaderId, StateTier startingTier) {
        UUID newId = UUID.randomUUID();
        StateData newState = new StateData(newId, stateName, leaderId, startingTier);
        states.put(newId, newState);
        
        this.setDirty(); // Tells the server this file needs to be saved to disk
        return newState;
    }

    public StateData getState(UUID stateId) {
        return states.get(stateId);
    }
    
    public Collection<StateData> getAllStates() {
        return states.values();
    }

    public void removeState(UUID stateId) {
        states.remove(stateId);
        this.setDirty();
    }

    // Helper method to make economy changes easy from other classes
    public void addFundsToState(UUID stateId, double amount) {
        StateData state = getState(stateId);
        if (state != null) {
            state.addFunds(amount);
            this.setDirty();
        }
    }

    // --- SavedData Saving & Loading ---

    private CompoundTag toTag() {
        ListTag stateList = new ListTag();
        
        for (StateData state : states.values()) {
            stateList.add(state.toNBT());
        }
        
        CompoundTag tag = new CompoundTag();
        tag.put(STATES_LIST_KEY, stateList);
        return tag;
    }

    private static StateManager fromTag(CompoundTag tag) {
        StateManager manager = new StateManager();
        ListTag stateList = tag.getList(STATES_LIST_KEY).orElse(new ListTag());
        
        for (int i = 0; i < stateList.size(); i++) {
            CompoundTag stateTag = stateList.getCompound(i).orElse(new CompoundTag());
            StateData loadedState = StateData.fromNBT(stateTag);
            manager.states.put(loadedState.getStateId(), loadedState);
        }
        
        return manager;
    }

    // --- Singleton Accessor ---
    // This is how you fetch the manager from anywhere in your mod
    public static StateManager get(ServerLevel level) {
        // We attach this data specifically to the Overworld so it's always loaded globally
        ServerLevel overworld = level.getServer().getLevel(ServerLevel.OVERWORLD);
        
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }
}