package com.devc.minecraftempires.combat;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.army.Army;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Cohort;
import com.devc.minecraftempires.network.packet.BattleSyncPayload;
import com.devc.minecraftempires.network.packet.OpenBattleMapPayload;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateManager;
import com.devc.minecraftempires.state.StateBalance;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

//battle manager class, handles the management of battles, including starting, resolving, and finalizing battles
/** Saved battle snapshots. This store is the sole committer of permanent battle results. */
public final class BattleManager extends SavedData {
    private static final Codec<BattleManager> CODEC = CompoundTag.CODEC.xmap(BattleManager::fromTag, BattleManager::toTag);
    public static final SavedDataType<BattleManager> TYPE = new SavedDataType<>(
            Identifier.withDefaultNamespace("minecraftempires_battles"), BattleManager::new, CODEC, DataFixTypes.LEVEL);
    private final Map<UUID, BattleSession> activeBattles = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, String> completedResults = new LinkedHashMap<>();
    private boolean recoveryPending;
    public BattleManager() {}
    public static BattleManager get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }
    /** Kept for the existing shutdown hook; SavedData has no process-wide cache. */
    public static void clearAll() {}

    public BattleSession startBattle(Army attacker, Army defender, ServerLevel level) {
        if (attacker.isEngaged()) return getBattle(attacker.getCurrentBattleId());
        if (defender.isEngaged()) return getBattle(defender.getCurrentBattleId());
        if (attacker.getArmyId().equals(defender.getArmyId())) return null;
        if (!ArmyManager.canEngage(attacker, defender)) return null;
        ArmyManager manager = ArmyManager.get(level);
        if (!attacker.isViable(manager) || !defender.isViable(manager)) return null;
        BlockPos origin = attacker.getStoredPosition();
        BattleSession session = new BattleSession(attacker.getArmyId(), defender.getArmyId(), origin.getX(), origin.getZ());
        attacker.setCampPosition(attacker.getStoredPosition());
        defender.setCampPosition(defender.getStoredPosition());
        deployArmy(session, attacker, true, manager);
        deployArmy(session, defender, false, manager);
        attacker.setCurrentBattleId(session.getBattleId());
        defender.setCurrentBattleId(session.getBattleId());
        attacker.clearWaypoints();
        defender.clearWaypoints();
        activeBattles.put(session.getBattleId(), session);
        setDirty();
        manager.setDirty();
        notifyOnlinePlayers(session, attacker, defender, level);
        return session;
    }
    private void deployArmy(BattleSession session, Army army, boolean attacker, ArmyManager manager) {
        // Defensive wrappers deliberately retain their garrison assignments.
        for (UUID id : army.getDeployedCohortIds()) {
            Cohort cohort = manager.resolveCohort(id);
            if (cohort == null || !cohort.isAlive()) continue;
            CohortData snapshot = CohortData.fromCohort(cohort, 0, attacker ? -20 : 20);
            if (attacker) session.addAttackerCohort(snapshot); else session.addDefenderCohort(snapshot);
        }
        session.applyFormation(attacker, BattleSession.Formation.TRIPLEX);
    }
    public boolean canControlSide(ServerPlayer player, BattleSession session, boolean attacker) {
        if (player.level() != player.level().getServer().overworld()) return false;
        StateData state = StateManager.get(player.level()).getStateByPlayer(player.getUUID());
        Army army = ArmyManager.get(player.level()).getArmy(attacker ? session.getAttackerArmyId() : session.getDefenderArmyId());
        return state != null && army != null && state.getStateId().equals(army.getOwningStateId())
                && player.getUUID().equals(state.getLeaderId());
    }
    public boolean canSpectate(ServerPlayer player, BattleSession session) {
        if (player.level() != player.level().getServer().overworld()) return false;
        StateData state = StateManager.get(player.level()).getStateByPlayer(player.getUUID());
        if (state == null) return false;
        ArmyManager manager = ArmyManager.get(player.level());
        Army attacker = manager.getArmy(session.getAttackerArmyId());
        Army defender = manager.getArmy(session.getDefenderArmyId());
        return attacker != null && state.getStateId().equals(attacker.getOwningStateId())
                || defender != null && state.getStateId().equals(defender.getOwningStateId());
    }
    public void tick(ServerLevel level) {
        if (level != level.getServer().overworld()) return;
        ArmyManager manager = ArmyManager.get(level);
        if (recoveryPending) {
            recoveryPending = false;
            // Never reconstruct fresh-strength armies after a restart: resolve the saved snapshots.
            resolveAll(level);
        }
        for (BattleSession session : new ArrayList<>(activeBattles.values())) {
            for (UUID id : new HashSet<>(session.getSpectatingPlayerIds())) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
                if (player == null || !canSpectate(player, session)) session.removeSpectator(id);
            }
            if (session.isActive()) {
                if (session.isSpectated()) {
                    session.resetIdleTicks();
                    session.tick(!hasCommander(session, true, level), !hasCommander(session, false, level));
                } else {
                    session.tickIdle();
                    session.tickDeployment();
                    if (session.isAutoResolveAllowed()) resolve(session, manager);
                }
            }
            setDirty();
            if (!session.isActive()) finalizeBattle(session, level);
            else broadcastSync(session, level);
        }
        // Old saves had battle IDs without any saved battle data. Unlock only those stale locks.
        for (Army army : manager.getAllArmies()) {
            if (army.isEngaged() && !activeBattles.containsKey(army.getCurrentBattleId())) {
                army.setCurrentBattleId(null);
                army.setCampPosition(null);
                manager.setDirty();
            }
        }
    }
    private boolean hasCommander(BattleSession session, boolean attacker, ServerLevel level) {
        return session.getSpectatingPlayerIds().stream().map(level.getServer().getPlayerList()::getPlayer)
                .anyMatch(p -> p != null && canControlSide(p, session, attacker));
    }
    private void resolve(BattleSession session, ArmyManager manager) {
        AutoResolveEngine.resolve(session, manager.getArmy(session.getAttackerArmyId()),
                manager.getArmy(session.getDefenderArmyId()), manager);
    }
    private void resolveAll(ServerLevel level) {
        for (BattleSession session : new ArrayList<>(activeBattles.values())) {
            if (session.isActive()) resolve(session, ArmyManager.get(level));
            finalizeBattle(session, level);
        }
    }
    public static void resolveAllOnShutdown(MinecraftServer server) {
        get(server.overworld()).resolveAll(server.overworld());
    }
    private void finalizeBattle(BattleSession session, ServerLevel level) {
        ArmyManager manager = ArmyManager.get(level);
        if (!completedResults.containsKey(session.getBattleId())) {
            commitSide(session, true, manager);
            commitSide(session, false, manager);
            completedResults.put(session.getBattleId(), session.getResult().name());
            while (completedResults.size() > 128) completedResults.remove(completedResults.keySet().iterator().next());
        }
        // Send the terminal snapshot while spectators still know the battle, then discard the active entry.
        broadcastSync(session, level);
        activeBattles.remove(session.getBattleId());
        manager.setDirty();
        setDirty();
        MinecraftEmpires.LOGGER.info("Battle {} concluded: {}.", session.getBattleId(), session.getResult());
    }
    private void commitSide(BattleSession session, boolean attacking, ArmyManager manager) {
        Army army = manager.getArmy(attacking ? session.getAttackerArmyId() : session.getDefenderArmyId());
        boolean won = session.getResult() == (attacking ? BattleSession.BattleResult.ATTACKER_WINS : BattleSession.BattleResult.DEFENDER_WINS);
        List<CohortData> snapshots = attacking ? session.getAttackerCohorts() : session.getDefenderCohorts();
        boolean needsCommit = snapshots.stream().map(c -> manager.resolveCohort(c.getCohortId()))
                .anyMatch(c -> c != null && !c.hasResolvedBattle(session.getBattleId()));
        if (!needsCommit) {
            // SavedData files may have been flushed separately before an interruption. The army
            // store can already contain this result while the battle store still contains its snapshot.
            // In that case even retreat movement must not be applied a second time.
            if (army != null && session.getBattleId().equals(army.getCurrentBattleId())) {
                army.setCurrentBattleId(null);
                army.setCampPosition(null);
            }
            return;
        }
        BlockPos position = army == null ? new BlockPos((int)session.getMapOriginX(), 64, (int)session.getMapOriginZ()) : army.getStoredPosition();
        // A defeated formation retreats 200 blocks from the field; this is the abstract battle's recovery leg.
        // Keep its current position and save a mandatory march instead of jumping to the rally point.
        BlockPos retreat = position.offset(0, 0, attacking ? -StateBalance.POST_BATTLE_RETREAT_DISTANCE : StateBalance.POST_BATTLE_RETREAT_DISTANCE);
        UUID opponent = attacking ? session.getDefenderArmyId() : session.getAttackerArmyId();
        if (army != null) {
            army.setCurrentBattleId(null);
            army.finishRetreat();
            if (!won) army.beginRetreat(retreat, opponent);
            army.setCampPosition(null);
        }
        for (CohortData data : snapshots) {
            Cohort cohort = manager.resolveCohort(data.getCohortId());
            if (cohort == null || cohort.hasResolvedBattle(session.getBattleId())) continue;
            cohort.applyBattleOutcome(session.getBattleId(), data.getCurrentHealth(), data.getMorale(), won);
            cohort.finishRetreat();
            var parent = manager.getParentLegion(cohort.getCohortId());
            if (parent != null && parent.getMainCohort() == cohort) parent.clearWaypoints();
            boolean fleeing = !won || data.isRouting() || data.hasEscaped() || data.isWithdrawing();
            boolean wasGarrisoned = cohort.isGarrisoned();
            if (cohort.isGarrisoned()) {
                // A routed garrison abandons its post; successful defenders stay at the altar.
                if (fleeing) {
                    cohort.setIsGarrisoned(false);
                    cohort.setGarrisonedSettlementId(null);
                }
            } else cohort.setStoredPosition(position);
            if (fleeing && cohort.isAlive()) cohort.beginRetreat(retreat, opponent);
            if (army != null && (cohort.isGarrisoned() || wasGarrisoned && fleeing || won && fleeing || !cohort.isAlive())) {
                army.removeCohortId(cohort.getCohortId());
                cohort.setAssignedArmyId(null);
            }
        }
        if (army != null) {
            if (!army.isViable(manager)) army.finishRetreat();
            manager.syncCohortPositions(army);
        }
    }
    private void notifyOnlinePlayers(BattleSession session, Army attacker, Army defender, ServerLevel level) {
        OpenBattleMapPayload payload = new OpenBattleMapPayload(session.getBattleId(), attacker.getArmyId(), defender.getArmyId());
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (canSpectate(player, session)) PacketDistributor.sendToPlayer(player, payload);
        }
    }
    public void broadcastSync(BattleSession session, ServerLevel level) {
        for (UUID id : session.getSpectatingPlayerIds()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player != null) PacketDistributor.sendToPlayer(player, BattleSyncPayload.fromSession(session,
                    canControlSide(player, session, true), canControlSide(player, session, false)));
        }
    }
    public BattleSession getBattle(UUID id) { return activeBattles.get(id); }
    public Collection<BattleSession> getAllBattles() { return Collections.unmodifiableCollection(activeBattles.values()); }
    public boolean hasBattle(UUID id) { return activeBattles.containsKey(id); }
    public String getRecordedResult(UUID id) { return completedResults.get(id); }
    private CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        ListTag battles = new ListTag();
        activeBattles.values().forEach(s -> battles.add(s.toNBT()));
        tag.put("Battles", battles);
        ListTag results = new ListTag();
        completedResults.forEach((id, result) -> { CompoundTag r = new CompoundTag(); r.putString("Id", id.toString()); r.putString("Result", result); results.add(r); });
        tag.put("Results", results);
        return tag;
    }
    private static BattleManager fromTag(CompoundTag tag) {
        BattleManager manager = new BattleManager();
        tag.getList("Battles").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) list.getCompound(i).ifPresent(t -> {
                try { BattleSession session = BattleSession.fromNBT(t); manager.activeBattles.put(session.getBattleId(), session); }
                catch (RuntimeException ex) { MinecraftEmpires.LOGGER.warn("Unable to recover malformed battle record", ex); }
            });
        });
        tag.getList("Results").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) list.getCompound(i).ifPresent(t -> {
                try { manager.completedResults.put(UUID.fromString(t.getString("Id").orElseThrow()), t.getString("Result").orElse("UNKNOWN")); }
                catch (RuntimeException ex) { MinecraftEmpires.LOGGER.warn("Unable to read battle result", ex); }
            });
        });
        manager.recoveryPending = true;
        return manager;
    }
}
