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
    private record NoSpacePair(UUID first, UUID second) {
        static NoSpacePair of(UUID a, UUID b) { return a.compareTo(b) < 0 ? new NoSpacePair(a, b) : new NoSpacePair(b, a); }
    }
    private final LinkedHashSet<NoSpacePair> noSpacePairs = new LinkedHashSet<>();
    private boolean recoveryPending;
    public BattleManager() {}
    public static BattleManager get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }
    /** The shutdown hook clears transient deployment previews; saved battles remain in SavedData. */
    public static void clearAll() { BattleSession.clearDeploymentPreviews(); }

    public BattleSession startBattle(Army attacker, Army defender, ServerLevel level) {
        if (!StateManager.get(level).mayBeginEncounter(level, attacker.getOwningStateId(), attacker.getStoredPosition(),
                defender.getOwningStateId(), defender.getStoredPosition())) return null;
        if (attacker.isEngaged()) return getBattle(attacker.getCurrentBattleId());
        if (defender.isEngaged()) return getBattle(defender.getCurrentBattleId());
        if (attacker.getArmyId().equals(defender.getArmyId())) return null;
        if (!ArmyManager.canEngage(attacker, defender)) return null;
        ArmyManager manager = ArmyManager.get(level);
        if (!attacker.isViable(manager) || !defender.isViable(manager)) return null;
        BlockPos origin = attacker.getStoredPosition();
        if (com.devc.minecraftempires.network.MapDataService.battleRoute(level, origin, defender.getStoredPosition(), origin.getX(), origin.getZ()).status()
                != com.devc.minecraftempires.network.MapDataService.Verification.VERIFIED) return null;
        NoSpacePair pair = NoSpacePair.of(attacker.getArmyId(), defender.getArmyId());
        if (noSpacePairs.contains(pair)) {
            // A failed deployment must not reopen every second. Recheck capacity privately until terrain or positions permit it.
            int a = (int) attacker.getDeployedCohortIds().stream().map(manager::resolveCohort).filter(c -> c != null && c.isAlive()).count();
            int d = (int) defender.getDeployedCohortIds().stream().map(manager::resolveCohort).filter(c -> c != null && c.isAlive()).count();
            if (BattleSession.verifyDeployment(level, origin, defender.getStoredPosition(), a, d) != com.devc.minecraftempires.network.MapDataService.Verification.VERIFIED) return null;
            noSpacePairs.remove(pair);
        }
        BattleSession session = new BattleSession(attacker.getArmyId(), defender.getArmyId(), origin.getX(), origin.getZ());
        session.attachTerrain(level);
        attacker.setCampPosition(attacker.getStoredPosition());
        defender.setCampPosition(defender.getStoredPosition());
        deployArmy(session, attacker, true, manager);
        deployArmy(session, defender, false, manager);
        session.deployStackedCohorts();
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
            CohortData snapshot = CohortData.fromCohort(cohort, cohort.getPreciseX() - session.getMapOriginX(), cohort.getPreciseZ() - session.getMapOriginZ());
            if (attacker) session.addAttackerCohort(snapshot); else session.addDefenderCohort(snapshot);
        }
        // Retain marching positions and saved bridge columns when the battlefield opens.
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
            // Resume saved casualties and positions; unloaded terrain must be inspected before resolution.
            for (BattleSession session : activeBattles.values()) session.attachTerrain(level);
        }
        for (BattleSession session : new ArrayList<>(activeBattles.values())) {
            session.attachTerrain(level);
            session.checkTerrain();
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
                    if (session.continuesWithoutViewers()) session.tick(true, true);
                    else session.tickDeployment();
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
            session.attachTerrain(level);
            if (session.isActive() && !session.continuesWithoutViewers() && !session.isSpectated()) resolve(session, ArmyManager.get(level));
            if (!session.isActive()) finalizeBattle(session, level);
        }
    }
    public static void resolveAllOnShutdown(MinecraftServer server) {
        get(server.overworld()).resolveAll(server.overworld());
    }
    private void finalizeBattle(BattleSession session, ServerLevel level) {
        ArmyManager manager = ArmyManager.get(level);
        if (!completedResults.containsKey(session.getBattleId())) {
            Army attacker = manager.getArmy(session.getAttackerArmyId()), defender = manager.getArmy(session.getDefenderArmyId());
            UUID actor = attacker == null ? null : attacker.getOwningStateId(), target = defender == null ? null : defender.getOwningStateId();
            Map<UUID, Long> strengths = new HashMap<>();
            for (UUID id : List.of(session.getAttackerArmyId(), session.getDefenderArmyId())) {
                Army army = manager.getArmy(id); if (army != null) strengths.put(army.getOwningStateId(), manager.getTotalSoldiersForState(army.getOwningStateId()));
            }
            commitSide(session, true, manager);
            commitSide(session, false, manager);
            StateManager states = StateManager.get(level);
            StateData from = states.getState(actor), to = states.getState(target);
            // Publish only the committed encounter, never a deployment that failed before combat.
            if (from != null && to != null && session.getResult() != BattleSession.BattleResult.INTERRUPTED) {
                states.observeNation(actor, target, level.getGameTime(), StateManager.KnowledgeSource.DIRECT_EVENT);
                states.observeNation(target, actor, level.getGameTime(), StateManager.KnowledgeSource.DIRECT_EVENT);
                states.getNews().publish(UUID.nameUUIDFromBytes(("battle:" + session.getBattleId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        level.getGameTime(), com.devc.minecraftempires.state.WorldNews.Kind.BATTLE, actor, from.getStateName(), target, to.getStateName(), null, "", 0);
            }
            for (var entry : strengths.entrySet()) {
                StateData nation = states.getState(entry.getKey()); long surviving = manager.getTotalSoldiersForState(entry.getKey());
                if (nation != null && nation.isAI() && (surviving <= com.devc.minecraftempires.army.Legion.AUTO_DISBAND_STRENGTH || surviving * 2 <= entry.getValue())) {
                    UUID enemy = strengths.keySet().stream().filter(id -> !id.equals(entry.getKey())).findFirst().orElse(null);
                    nation.recordMajorDefeat(level.getGameTime(), enemy); states.setDirty();
                }
            }
            completedResults.put(session.getBattleId(), session.getResult().name());
            while (completedResults.size() > 128) completedResults.remove(completedResults.keySet().iterator().next());
        }
        // Send the terminal snapshot while spectators still know the battle, then discard the active entry.
        if (session.getResultLabel().equals("INTERRUPTED_NO_SPACE")) {
            noSpacePairs.add(NoSpacePair.of(session.getAttackerArmyId(), session.getDefenderArmyId()));
            while (noSpacePairs.size() > 128) noSpacePairs.remove(noSpacePairs.iterator().next());
        }
        broadcastSync(session, level);
        activeBattles.remove(session.getBattleId());
        manager.setDirty();
        setDirty();
        MinecraftEmpires.LOGGER.info("Battle {} concluded: {}.", session.getBattleId(), session.getResult());
    }
    private void commitSide(BattleSession session, boolean attacking, ArmyManager manager) {
        Army army = manager.getArmy(attacking ? session.getAttackerArmyId() : session.getDefenderArmyId());
        boolean won = session.getResult() == (attacking ? BattleSession.BattleResult.ATTACKER_WINS : BattleSession.BattleResult.DEFENDER_WINS);
        boolean interrupted = session.getResult() == BattleSession.BattleResult.INTERRUPTED;
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
            if (!won && !interrupted) army.beginRetreat(retreat, opponent);
            army.setCampPosition(null);
        }
        for (CohortData data : snapshots) {
            Cohort cohort = manager.resolveCohort(data.getCohortId());
            if (cohort == null || cohort.hasResolvedBattle(session.getBattleId())) continue;
            if (interrupted) cohort.applyInterruptedBattle(session.getBattleId(), data.getCurrentHealth(), data.getMorale());
            else cohort.applyBattleOutcome(session.getBattleId(), data.getCurrentHealth(), data.getMorale(), won);
            cohort.setMarchState(data.marchState(session.getMapOriginX(), session.getMapOriginZ()));
            cohort.finishRetreat();
            var parent = manager.getParentLegion(cohort.getCohortId());
            if (parent != null && parent.getMainCohort() == cohort) parent.clearWaypoints();
            boolean fleeing = !interrupted && (!won || data.isRouting() || data.hasEscaped() || data.isWithdrawing());
            boolean wasGarrisoned = cohort.isGarrisoned();
            if (cohort.isGarrisoned()) {
                // A routed garrison abandons its post; successful defenders stay at the altar.
                if (fleeing) {
                    cohort.setIsGarrisoned(false);
                    cohort.setGarrisonedSettlementId(null);
                }
            } else if (session.hasTerrain()) cohort.setPrecisePos(session.getMapOriginX() + data.getPosition().x, session.getMapOriginZ() + data.getPosition().y);
            else cohort.setStoredPosition(position);
            if (data.hasRecoveredFromCollapse()) {
                if (army != null) army.removeCohortId(cohort.getCohortId());
                cohort.setAssignedArmyId(null); cohort.setDetached(true); cohort.clearWaypoints(); fleeing = false;
                CompoundTag march = cohort.getMarchState(); march.putBoolean("Recovered", false); cohort.setMarchState(march);
            }
            if (fleeing && cohort.isAlive()) cohort.beginRetreat(retreat, opponent);
            if (army != null && (cohort.isGarrisoned() || wasGarrisoned && fleeing || won && fleeing || !cohort.isAlive())) {
                army.removeCohortId(cohort.getCohortId());
                cohort.setAssignedArmyId(null);
            }
        }
        if (army != null) {
            if (!army.isViable(manager)) army.finishRetreat();
            if (!session.hasTerrain()) manager.syncCohortPositions(army);
            else army.getDeployedCohortIds().stream().map(manager::resolveCohort).filter(c -> c != null && c.isAlive()).findFirst()
                    .ifPresent(c -> army.setPrecisePos(c.getPreciseX(), c.getPreciseZ()));
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
        ListTag blocked = new ListTag();
        noSpacePairs.forEach(pair -> { CompoundTag value = new CompoundTag(); value.putString("First", pair.first.toString()); value.putString("Second", pair.second.toString()); blocked.add(value); }); tag.put("NoSpacePairs", blocked);
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
        tag.getList("NoSpacePairs").ifPresent(values -> { for (int i = 0; i < Math.min(128, values.size()); i++) values.getCompound(i).ifPresent(value -> {
            try { manager.noSpacePairs.add(NoSpacePair.of(UUID.fromString(value.getString("First").orElseThrow()), UUID.fromString(value.getString("Second").orElseThrow()))); } catch (RuntimeException ignored) { }
        }); });
        return manager;
    }
}
