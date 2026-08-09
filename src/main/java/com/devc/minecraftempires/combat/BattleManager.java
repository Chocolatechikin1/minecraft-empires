package com.devc.minecraftempires.combat;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.army.Army;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Cohort;
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

//battle manager class
public final class BattleManager {
    private static final double COHORT_SPACING   = 6.0;
    private static final double ATTACKER_OFFSET_Z = -20.0;
    private static final double DEFENDER_OFFSET_Z =  20.0;

    private static final Map<ResourceKey<Level>, BattleManager> INSTANCES = new HashMap<>();

    public static BattleManager get(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level.dimension(), k -> new BattleManager());
    }

    public static void clearAll() { INSTANCES.clear(); }

    private final Map<UUID, BattleSession> activeBattles = new HashMap<>();

    private BattleManager() {}

    //start of a battle function
    public BattleSession startBattle(Army attacker, Army defender, ServerLevel level) {
        BlockPos origin = attacker.getStoredPosition(); 
        double originX  = origin.getX();
        double originZ  = origin.getZ();

        //save armies current positions to their camp
        attacker.setCampPosition(attacker.getStoredPosition());
        defender.setCampPosition(defender.getStoredPosition());

        BattleSession session = new BattleSession(
            attacker.getArmyId(), defender.getArmyId(), originX, originZ
        );

        ArmyManager armyManager = ArmyManager.get(level);
        deployArmy(session, attacker, true,  ATTACKER_OFFSET_Z, armyManager); //deploy armies
        deployArmy(session, defender, false, DEFENDER_OFFSET_Z, armyManager);

        //lock the armies into battle
        attacker.setCurrentBattleId(session.getBattleId());
        defender.setCurrentBattleId(session.getBattleId());
        attacker.clearWaypoints();
        defender.clearWaypoints();

        activeBattles.put(session.getBattleId(), session);

        //notify all players
        MinecraftEmpires.LOGGER.info(
                "Battle {} started: Army {} (att) vs Army {} (def) at ({}, {}).",
                session.getBattleId(), attacker.getArmyId(), defender.getArmyId(),
                (int) originX, (int) originZ);

        notifyOnlinePlayers(session, attacker, defender, level);
        return session;
    }

    //deploy army units helper function
    private void deployArmy(BattleSession session, Army army, boolean isAttacker, double offsetZ, ArmyManager armyManager) {
        List<Cohort> combatCohorts = new ArrayList<>(); //arraylist of cohorts to deploy, use an arraylist for quick O(1) access by index
        //loop through all available cohorts in the army and add them to the combatCohorts list if they are alive and not garrisoned
        for (UUID cohortId : army.getDeployedCohortIds()) {
            Cohort c = armyManager.resolveCohort(cohortId);
            if (c != null && c.isAlive() && !c.isGarrisoned()) {
                combatCohorts.add(c);
            }
        }

        int count  = combatCohorts.size();
        double startX = -((count - 1) / 2.0) * COHORT_SPACING;

        //loop through all cohorts and deploy them in a line with spacing (currently only a line formation, but can be expanded to more complex formations in the future)
        for (int i = 0; i < count; i++) {
            Cohort cohort = combatCohorts.get(i);
            double x = startX + i * COHORT_SPACING;
            CohortData data = CohortData.fromCohort(cohort, x, offsetZ);
            if (isAttacker) session.addAttackerCohort(data);
            else            session.addDefenderCohort(data);
        }
    }

    //tick function to update all active battles
    public void tick(ServerLevel level) {
        if (activeBattles.isEmpty()) return;
        ArmyManager armyManager = ArmyManager.get(level);
        List<UUID> toFinish = new ArrayList<>();

        //loop through all active battles and update them, if they are not active anymore, add them to the toFinish list
        for (BattleSession session : activeBattles.values()) {
            if (!session.isActive()) {
                toFinish.add(session.getBattleId());
                continue;
            }

            //if the battle is being spectated, tick it and signal retreats for both sides, then broadcast the sync to all players (okay tf check this later that shouldnt be what the armies do)
            if (session.isSpectated()) {
                session.tick();

                Army attackerArmy = armyManager.getArmy(session.getAttackerArmyId());
                Army defenderArmy = armyManager.getArmy(session.getDefenderArmyId());
                if (attackerArmy != null)
                    signalRetreatsForSide(session, true, attackerArmy.getCampPosition(), session.getMapOriginZ());
                if (defenderArmy != null)
                    signalRetreatsForSide(session, false, defenderArmy.getCampPosition(), session.getMapOriginZ());

                broadcastSync(session, level);
            } else {
                //no spectator advances the idle timer.
                //auto-resolve is blocked during the grace period so players have time to open the specator view
                session.tickIdle();

                if (session.isAutoResolveAllowed()) {
                    //grace period has elapsed: resolve the battle automatically
                    Army attackerArmy = armyManager.getArmy(session.getAttackerArmyId());
                    Army defenderArmy = armyManager.getArmy(session.getDefenderArmyId());
                    if (attackerArmy != null && defenderArmy != null) {
                        AutoResolveEngine.BattleOutcome outcome = AutoResolveEngine.resolve(session, attackerArmy, defenderArmy, armyManager);
                        MinecraftEmpires.LOGGER.info( "Auto-resolved battle {}: {} (att -{}, def -{}).", session.getBattleId(), outcome.result(), outcome.attackerCasualties(), outcome.defenderCasualties());
                    }
                    else{
                        session.setInactive();
                    }
                    toFinish.add(session.getBattleId());
                }
                // else: still within the grace period — do nothing this tick
            }

            if (!session.isActive()) toFinish.add(session.getBattleId());
        }

        for (UUID id : toFinish) {
            BattleSession finished = activeBattles.remove(id);
            if (finished != null) finalizeBattle(finished, level);
        }
    }

    //starts a retreat for a side, retreating to their camp position if it exists, otherwise retreating to the battle origin (note good autocorrect fix for campPos, suggests using army parameter and then getting the camp position from that, but this is fine)
    private void signalRetreatsForSide(BattleSession session, boolean isAttacker, BlockPos campPos, double originZ) {
        if (campPos == null) return;
        double retreatX = campPos.getX() - session.getMapOriginX();
        double retreatZ = campPos.getZ() - session.getMapOriginZ();
        session.signalRetreat(retreatX, retreatZ, isAttacker);
    }

    //finalize battle function, called when a battle ends
    private void finalizeBattle(BattleSession session, ServerLevel level) {
        ArmyManager armyManager = ArmyManager.get(level);
        Army attacker = armyManager.getArmy(session.getAttackerArmyId());
        Army defender = armyManager.getArmy(session.getDefenderArmyId());

        if (attacker != null) finalizeArmy(attacker, session.getResult() == BattleSession.BattleResult.ATTACKER_WINS, session);
        if (defender != null) finalizeArmy(defender, session.getResult() == BattleSession.BattleResult.DEFENDER_WINS, session);

        armyManager.setDirty(); //mark dirty so next tick saves to disk
        MinecraftEmpires.LOGGER.info("Battle {} concluded: {}.", session.getBattleId(), session.getResult());
    }

    //finalize army function, called when a battle ends, sets the army's current battle to null, and sets their stored position to their camp position if they won, or to the battle origin if they lost
    private void finalizeArmy(Army army, boolean isWinner, BattleSession session) {
        // Clear battle lock — Army continues its campaign
        army.setCurrentBattleId(null);

        //logic was flipped here, CHECK IF IT IS RIGHT
        if (isWinner) { //if the army won, they remain on the field
            BlockPos camp = army.getCampPosition();
            army.setStoredPosition(camp != null ? camp : new BlockPos((int) session.getMapOriginX(), 64, (int) session.getMapOriginZ()));
        } else { //if the army lost, they retreat to their camp position
            army.setStoredPosition(new BlockPos((int) session.getMapOriginX(), 64, (int) session.getMapOriginZ()));
        }

        army.setCampPosition(null);
        army.clearWaypoints();
    }

    //network function to notify all online players in the battle's states that a battle has started, sending them the OpenBattleMapPayload packet
    private void notifyOnlinePlayers(BattleSession session, Army attacker, Army defender, ServerLevel level) {
        OpenBattleMapPayload payload = new OpenBattleMapPayload(session.getBattleId(), attacker.getArmyId(), defender.getArmyId());

        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            com.devc.minecraftempires.state.StateManager sm = com.devc.minecraftempires.state.StateManager.get(level);
            com.devc.minecraftempires.state.StateData sd = sm.getStateByPlayer(player.getUUID());
            if (sd != null && (sd.getStateId().equals(attacker.getOwningStateId()) || sd.getStateId().equals(defender.getOwningStateId()))) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    public void broadcastSync(BattleSession session, ServerLevel level) {
        if (session.getSpectatingPlayerIds().isEmpty()) return;
        BattleSyncPayload payload = BattleSyncPayload.fromSession(session);
        MinecraftServer server = level.getServer();
        for (UUID playerId : session.getSpectatingPlayerIds()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) PacketDistributor.sendToPlayer(player, payload);
        }
    }

    //getters
    public BattleSession getBattle(UUID battleId)           { return activeBattles.get(battleId); }
    public Collection<BattleSession> getAllBattles()        { return Collections.unmodifiableCollection(activeBattles.values()); }
    public boolean hasBattle(UUID battleId)                 { return activeBattles.containsKey(battleId); }
}
