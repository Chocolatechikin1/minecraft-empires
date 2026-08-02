package com.devc.minecraftempires.army;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.combat.BattleManager;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateTier;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import com.devc.minecraftempires.network.packet.DispatchArmyPayload; 
import net.neoforged.neoforge.network.handling.IPayloadContext; 
import net.minecraft.server.level.ServerPlayer; 
import com.devc.minecraftempires.network.packet.DisbandArmyPayload;

import java.util.*;

//primary data store for all Legions on the server, keyed by legionId
//allows raising legions, disbanding legions, and checks for cohort and squadron counts
//uses HashMaps for O(1) lookups and fast iteration
public class ArmyManager extends SavedData {
    private static final String DATA_NAME = "minecraftempires_armies";

    private static final Codec<ArmyManager> CODEC =
            CompoundTag.CODEC.xmap(ArmyManager::fromTag, ArmyManager::toTag);

    public static final SavedDataType<ArmyManager> TYPE = new SavedDataType<>(
            Identifier.withDefaultNamespace(DATA_NAME),
            ArmyManager::new,
            CODEC,
            DataFixTypes.LEVEL
    );

   //variable to store active legions
    private final Map<UUID, Legion> activeLegions = new HashMap<>();

    //variable to map legions to their respective states
    private final Map<UUID, List<UUID>> stateToLegionIndex = new HashMap<>();

    //constructor
    public ArmyManager() {}

    //get method
    public static ArmyManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /*
     * @param state the StateData whose tier determines the cap
     * @param pos   the abstract world coordinate for this Legion's position
     * @return Optional containing the new Legion, or empty if capped out
     */
    //legion raising method
    public Optional<Legion> raiseLegion(StateData state, BlockPos pos) {
        StateTier tier    = state.getCurrentTier();
        int       cap     = tier.getMaxLegions();
        UUID      stateId = state.getStateId();

        if (cap <= 0) { //if state tier cannot field armies, notify so and return
            MinecraftEmpires.LOGGER.info(
                    "[Minecraft Empires] State '{}' (tier {}) cannot field any Legions.",
                    state.getStateName(), tier.name());
            return Optional.empty();
        }

        //if state is at max legions already, stop allowing more raises
        int currentCount = getLegionCountForState(stateId);
        if (currentCount >= cap) {
            MinecraftEmpires.LOGGER.info(
                    "[Minecraft Empires] State '{}' already at Legion cap ({}/{}).",
                    state.getStateName(), currentCount, cap);
            return Optional.empty();
        }

        //if above methods pass, raise a legion
        //note: legion does not come with cohorts or squadrons yet, commander must recruit them separately
        Legion legion = new Legion(UUID.randomUUID(), stateId, pos);
        registerLegion(legion);

        MinecraftEmpires.LOGGER.info(
                "[Minecraft Empires] Raised Legion {} for state '{}' ({}/{} Legions).",
                legion.getLegionId(), state.getStateName(), currentCount + 1, cap);
        return Optional.of(legion);
    }

    /**
     * @param legionId ID of the Legion to remove
     * @return true if the Legion existed and was removed; false if not found
     */
    //disband legion method
    public boolean disbandLegion(UUID legionId) {
        Legion removed = activeLegions.remove(legionId);
        if (removed == null) return false;

        List<UUID> stateList = stateToLegionIndex.get(removed.getOwningStateId());
        if (stateList != null) {
            stateList.remove(legionId);
            if (stateList.isEmpty()) {
                stateToLegionIndex.remove(removed.getOwningStateId());
            }
        }

        setDirty();
        MinecraftEmpires.LOGGER.info(
                "[Minecraft Empires] Disbanded Legion {} (owner state: {}).",
                legionId, removed.getOwningStateId());
        return true;
    }

    //method that checks active legions to ensure they meet requirements for viability, disbands those that do not
    //currently disbands if one cohort or one squadron is left, may need to be adjusted (squadron)
    public void runGarbageCollection() {
        // Collect IDs first to avoid ConcurrentModificationException
        List<UUID> toDisband = new ArrayList<>();
        for (Legion legion : activeLegions.values()) {
            if (!legion.isViable()) {
                toDisband.add(legion.getLegionId());
            }
        }

        for (UUID id : toDisband) {
            MinecraftEmpires.LOGGER.info(
                    "[Minecraft Empires] GC: Legion {} is no longer viable — disbanding.", id);
            disbandLegion(id);
        }

        if (!toDisband.isEmpty()) {
            MinecraftEmpires.LOGGER.info(
                    "[Minecraft Empires] {} Legion(s) disbanded.", toDisband.size());
        }
    }

    //getter
    public Legion getLegion(UUID legionId) {
        return activeLegions.get(legionId);
    }

    //list that makes a view of all active legions
    public List<Legion> getLegionsForState(UUID stateId) {
        List<UUID> ids = stateToLegionIndex.getOrDefault(stateId, Collections.emptyList());
        List<Legion> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Legion l = activeLegions.get(id);
            if (l != null) result.add(l);
        }
        return Collections.unmodifiableList(result);
    }

    public int getLegionCountForState(UUID stateId) {
        return stateToLegionIndex.getOrDefault(stateId, Collections.emptyList()).size();
    }

    public Collection<Legion> getAllLegions() {
        return Collections.unmodifiableCollection(activeLegions.values());
    }

    //register legion
    private void registerLegion(Legion legion) {
        activeLegions.put(legion.getLegionId(), legion);
        stateToLegionIndex
                .computeIfAbsent(legion.getOwningStateId(), k -> new ArrayList<>())
                .add(legion.getLegionId());
        setDirty();
    }

    //save and load serialization methods
    private CompoundTag toTag() {
        CompoundTag root = new CompoundTag();
        ListTag legionList = new ListTag();

        for (Legion legion : activeLegions.values()) {
            legionList.add(legion.toNBT());
        }

        root.put("Legions", legionList);
        return root;
    }

    private static ArmyManager fromTag(CompoundTag root) {
        ArmyManager manager = new ArmyManager();

        ListTag legionList = root.getList("Legions").orElse(new ListTag());
        for (int i = 0; i < legionList.size(); i++) {
            legionList.getCompound(i).ifPresent(tag -> {
                Legion legion = Legion.fromNBT(tag);
                // Use registerLegion to also populate the state-index
                manager.activeLegions.put(legion.getLegionId(), legion);
                manager.stateToLegionIndex
                        .computeIfAbsent(legion.getOwningStateId(), k -> new ArrayList<>())
                        .add(legion.getLegionId());
            });
        }

        MinecraftEmpires.LOGGER.info(
                "[Minecraft Empires] Loaded {} Legion(s) from disk.", manager.activeLegions.size());
        return manager;
    }

    //network handler — receives a DispatchArmyPayload from the client and updates the target legion's waypoint queue
    public static void handleDispatchArmy(final DispatchArmyPayload payload, final IPayloadContext context) {
        // enqueueWork() schedules the block to run on the main server thread, preventing concurrent data corruption
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {

                //fetch the ArmyManager for this world, then look up the target Legion by UUID
                ArmyManager manager = ArmyManager.get(player.level());
                Legion targetLegion = manager.getLegion(payload.armyId());

                //waypoint queueing:
                //If not queueing (plain right-click), clear existing waypoints first (overwrite mode)
                //Then push the new target onto the FIFO queue
                if (targetLegion != null) {
                    if (!payload.isQueueing()) {
                        targetLegion.clearWaypoints();
                    }
                    targetLegion.addWaypoint(payload.targetPos());
                    manager.setDirty(); // Persist the updated queue to disk
                    MinecraftEmpires.LOGGER.info(
                            "[Minecraft Empires] Legion {} dispatched to {} (queuing={})",
                            payload.armyId(), payload.targetPos(), payload.isQueueing()
                    );
                }
            }
        });
    }

    //army mover - calculates the waypoints for an army to move to
    public void tickArmies(ServerLevel level){
        boolean requiresSave = false;
        for (Legion legion : activeLegions.values()) {
            //skip legions that are locked into a battle
            if (legion.isEngaged()) continue;

            if(!legion.getWaypoints().isEmpty()){
                BlockPos current = legion.getStoredPosition(); 
                BlockPos target = legion.getWaypoints().peek();
                
                //calculate the waypoint distances using 2D vector math
                double dx = target.getX() - current.getX();
                double dz = target.getZ() - current.getZ();
                double distance = Math.hypot(dx, dz);

                //actual army speed (adjust here if you want to change how fast armies move)
                double marchSpeed = 4.0; // blocks per tick

                if(distance <= marchSpeed){
                    //army is close enough to the target, snap to it and remove the waypoint
                    legion.setStoredPosition(target);
                    legion.getWaypoints().poll(); //pop the waypoint off the queue
                }
                else{
                    //army still moving, "move" them to the target
                    double ratio = marchSpeed / distance;
                    int stepX = current.getX() + (int) Math.round(dx * ratio);
                    int stepZ = current.getZ() + (int) Math.round(dz * ratio);
                    legion.setStoredPosition(new BlockPos(stepX, current.getY(), stepZ));
                }
                //collision check, force save if true
                if(checkCollisionAndEngage(legion, level)){
                    requiresSave = true;
                }
                //save position
                requiresSave = true;
            }
        }
        //save to disk 
        if(requiresSave){
            setDirty();
        }
    }

    //checks if 2 armies are within engagement range, triggers battle if so
    private boolean checkCollisionAndEngage(Legion movingLegion, ServerLevel level){
        //skip if already in a battle
        if (movingLegion.isEngaged()) return false;

        BlockPos position = movingLegion.getStoredPosition();
        for(Legion other : this.activeLegions.values()){
            //if same army, skip
            if(other.getLegionId().equals(movingLegion.getLegionId())){
                continue;
            }
            //if legions are friendly, ignore
            if(other.getOwningStateId().equals(movingLegion.getOwningStateId())){
                continue;
            }
            //if the other legion is already engaged, skip
            if(other.isEngaged()){
                continue;
            }
            //if legions are within 50 blocks, engage (distSqr < 2500)
            if(position.distSqr(other.getStoredPosition()) < 2500){
                BattleManager.get(level).startBattle(movingLegion, other, level);
                MinecraftEmpires.LOGGER.info(
                    "[Minecraft Empires] Engagement triggered: Legion {} vs Legion {} at {}.",
                    movingLegion.getLegionId(), other.getLegionId(), position
                );
                return true;
            }
        }
        return false;
    }

    //method for army disbanding
    public static void handleDisbandArmy(final DisbandArmyPayload payload, final IPayloadContext context) { 
        context.enqueueWork(() -> { 
            if (context.player() instanceof ServerPlayer player) { 
                ArmyManager manager = ArmyManager.get(player.level()); 
                
                // Remove the legion from the active map and trigger a save
                if (manager.activeLegions.containsKey(payload.armyId())) { 
                    manager.activeLegions.remove(payload.armyId()); 
                    manager.setDirty(); 
                } 
            } 
        }); 
    } 
}
