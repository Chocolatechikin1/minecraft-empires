package com.devc.minecraftempires.combat;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Cohort;
import com.devc.minecraftempires.army.Legion;
import com.devc.minecraftempires.network.packet.BattleSyncPayload;
import com.devc.minecraftempires.network.packet.OpenBattleMapPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

//class tracks all active battles in the server and manages their lifecycle
//uses a HashMap to store active battles by their UUID, and ease of garbage collection
//tramsient state is not serialized to disk, and is cleared on server stop
//accessed thru BattleManager.get(level)
public final class BattleManager {
    private static final double COHORT_SPACING = 6.0; //spacing between cohorts in battle
    //offsets for attacker and defender cohorts in battle space
    private static final double ATTACKER_OFFSET_Z = -20.0; 
    private static final double DEFENDER_OFFSET_Z =  20.0; 

    private static final Map<ResourceKey<Level>, BattleManager> INSTANCES = new HashMap<>();

    public static BattleManager get(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level.dimension(), k -> new BattleManager());
    }

    //clears all transient states (active battles)
    public static void clearAll() {
        INSTANCES.clear();
    }

    private final Map<UUID, BattleSession> activeBattles = new HashMap<>();

    private BattleManager() {}

    // Starts a new battle between two legions. attacker is the initator
    //defender is the one standing at the engagement area
    //level is just server level
    public BattleSession startBattle(Legion attacker, Legion defender, ServerLevel level) {
        BlockPos origin = attacker.getStoredPosition();
        double originX  = origin.getX();
        double originZ  = origin.getZ();

        //set a retreat point 
        attacker.setCampPosition(attacker.getStoredPosition());
        defender.setCampPosition(defender.getStoredPosition());

        //get all legions and cohorts
        BattleSession session = new BattleSession(
                attacker.getLegionId(), defender.getLegionId(), originX, originZ);

        //hardcoded single line deployment for now (later, we can add more complex formations)
        deployLegion(session, attacker, true, ATTACKER_OFFSET_Z);
        deployLegion(session, defender, false, DEFENDER_OFFSET_Z);

        //lock legions into battle
        attacker.setCurrentBattleId(session.getBattleId());
        defender.setCurrentBattleId(session.getBattleId());
        attacker.clearWaypoints();
        defender.clearWaypoints();
        activeBattles.put(session.getBattleId(), session);

        MinecraftEmpires.LOGGER.info(
                "[Minecraft Empires] Battle {} started: Legion {} (att) vs Legion {} (def) at ({}, {})",
                session.getBattleId(), attacker.getLegionId(), defender.getLegionId(),
                (int) originX, (int) originZ
        );

        //notify all online players
        notifyOnlinePlayers(session, attacker, defender, level);

        return session;
    }

    //deployer helper method. currently deploys all cohorts in a single line formation, with a fixed spacing between them
    //adjust later to allow for more complex formations, and possibly a formation editor in the future
    private void deployLegion(BattleSession session, Legion legion, boolean isAttacker, double offsetZ) {
        List<Cohort> allCohorts = new ArrayList<>();
        allCohorts.addAll(legion.getInfantryCohorts());
        allCohorts.addAll(legion.getCavalrySquadrons());
        allCohorts.addAll(legion.getAuxiliaries());

        int count = allCohorts.size();
        double startX = -((count - 1) / 2.0) * COHORT_SPACING;

        //iterate through all cohorts and deploy them
        for (int i = 0; i < count; i++) {
            Cohort cohort = allCohorts.get(i);
            double x = startX + i * COHORT_SPACING;
            CohortData data = CohortData.fromCohort(cohort, x, offsetZ);
            if (isAttacker){
                session.addAttackerCohort(data);
            } 
            else{
                session.addDefenderCohort(data);
            }
        }
    }

    //actions handler per tick
    public void tick(ServerLevel level) {
        if (activeBattles.isEmpty()) return;

        List<UUID> toFinish = new ArrayList<>();

        for (BattleSession session : activeBattles.values()) {
            if (!session.isActive()) {
                toFinish.add(session.getBattleId());
                continue;
            }

            //if a player is spectating, tick the battle session and broadcast updates to spectators
            if (session.isSpectated()) {
                session.tick();

                //for routing legions, show retreat waypoints
                Legion attackerLegion = ArmyManager.get(level).getLegion(session.getAttackerArmyId());
                Legion defenderLegion = ArmyManager.get(level).getLegion(session.getDefenderArmyId());
                if (attackerLegion != null)
                    signalRetreatsForSide(session, true, attackerLegion.getCampPosition(), session.getMapOriginZ());
                if (defenderLegion != null)
                    signalRetreatsForSide(session, false, defenderLegion.getCampPosition(), session.getMapOriginZ());

                // Broadcast updated state to spectators
                broadcastSync(session, level);
            } else {
                //auto resolver, currently resolves instantly, later make it time based too
                Legion attackerLegion = ArmyManager.get(level).getLegion(session.getAttackerArmyId());
                Legion defenderLegion = ArmyManager.get(level).getLegion(session.getDefenderArmyId());
                if (attackerLegion != null && defenderLegion != null) {
                    AutoResolveEngine.BattleOutcome outcome =
                            AutoResolveEngine.resolve(session, attackerLegion, defenderLegion);
                    MinecraftEmpires.LOGGER.info(
                            "[Minecraft Empires] Auto-resolved battle {}: {} (att -{}, def -{})",
                            session.getBattleId(), outcome.result(),
                            outcome.attackerCasualties(), outcome.defenderCasualties()
                    );
                } else {
                    session.setInactive();
                }
                toFinish.add(session.getBattleId());
            }

            //check if session ended this tick
            if (!session.isActive()) {
                toFinish.add(session.getBattleId());
            }
        }

        //clean up finished sessions
        for (UUID id : toFinish) {
            BattleSession finished = activeBattles.remove(id);
            if (finished != null) finalizeBattle(finished, level);
        }
    }

    //retreat variable
    private void signalRetreatsForSide(BattleSession session, boolean isAttacker, BlockPos campPos, double originZ) {
        if (campPos == null) return;

        // Battle-space retreat point = camp position offset from origin
        double retreatX = campPos.getX() - session.getMapOriginX();
        double retreatZ = campPos.getZ() - session.getMapOriginZ();

        session.signalRetreat(retreatX, retreatZ, isAttacker);
    }

    //post battle stat adjuster variale
    private void finalizeBattle(BattleSession session, ServerLevel level) {
        ArmyManager armyManager = ArmyManager.get(level);
        Legion attacker = armyManager.getLegion(session.getAttackerArmyId());
        Legion defender = armyManager.getLegion(session.getDefenderArmyId());

        if (attacker != null) finalizeLegion(attacker, session.getResult() == BattleSession.BattleResult.ATTACKER_WINS, session, level, armyManager);
        if (defender != null) finalizeLegion(defender, session.getResult() == BattleSession.BattleResult.DEFENDER_WINS, session, level, armyManager);

        armyManager.setDirty();

        MinecraftEmpires.LOGGER.info(
                "[Minecraft Empires] Battle {} concluded: {}", session.getBattleId(), session.getResult()
        );
    }

    //apply adjustments variable
    private void finalizeLegion(Legion legion, boolean isWinner, BattleSession session, ServerLevel level, ArmyManager armyManager) {
        // Clear battle lock
        legion.setCurrentBattleId(null);

        if (isWinner) {
            //winner remainds in position, they "won the field"
            legion.setStoredPosition(new BlockPos(
                    (int) session.getMapOriginX(),
                    64,
                    (int) session.getMapOriginZ()
            ));
        } else {
            //loser retreat to their campPosition (or origin if camp is null)
            BlockPos camp = legion.getCampPosition();
            legion.setStoredPosition(camp != null ? camp : new BlockPos(
                    (int) session.getMapOriginX(), 64, (int) session.getMapOriginZ()));
        }

        legion.setCampPosition(null);
        legion.clearWaypoints();

        //check if legion is still standing, disband if no longer viable after attrition
        armyManager.runGarbageCollection();
    }

    //network functions
    //player notification system
    private void notifyOnlinePlayers(BattleSession session, Legion attacker, Legion defender, ServerLevel level) {
        OpenBattleMapPayload payload = new OpenBattleMapPayload(
            session.getBattleId(), attacker.getLegionId(), defender.getLegionId()
        );

        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            UUID stateId = null;
            //determine if this player is in the attacker or defender state
            com.devc.minecraftempires.state.StateManager sm =
                    com.devc.minecraftempires.state.StateManager.get(level);
            com.devc.minecraftempires.state.StateData sd = sm.getStateByPlayer(player.getUUID());
            if (sd != null) stateId = sd.getStateId();

            if (stateId != null &&
               (stateId.equals(attacker.getOwningStateId()) ||
                stateId.equals(defender.getOwningStateId()))) {
                PacketDistributor.sendToPlayer(player, payload); //send notification to player that battle is active
            }
        }
    }

    //notify players the current state of the battle
    public void broadcastSync(BattleSession session, ServerLevel level) {
        if (session.getSpectatingPlayerIds().isEmpty()) return;

        BattleSyncPayload payload = BattleSyncPayload.fromSession(session);
        MinecraftServer server    = level.getServer();

        for (UUID playerId : session.getSpectatingPlayerIds()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    //getters
    public BattleSession getBattle(UUID battleId) { return activeBattles.get(battleId); }
    public Collection<BattleSession> getAllBattles() { return Collections.unmodifiableCollection(activeBattles.values()); }
    public boolean hasBattle(UUID battleId) { return activeBattles.containsKey(battleId); }
}
