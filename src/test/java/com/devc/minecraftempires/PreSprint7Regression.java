package com.devc.minecraftempires;

import com.devc.minecraftempires.army.*;
import com.devc.minecraftempires.combat.*;
import com.devc.minecraftempires.state.*;
import com.devc.minecraftempires.territory.SettlementData;
import com.devc.minecraftempires.network.MapDataService;
import com.devc.minecraftempires.network.packet.ArmyMapPayload;
import com.devc.minecraftempires.network.packet.ComposeArmyPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/** Focused domain regressions, runnable without a game world or external test dependencies. */
public final class PreSprint7Regression {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        cohortStrengthAndExperience();
        persistentLocationsAndSuccession();
        grantsAndDiplomacy();
        progressionGates();
        battleOrdersAndRecovery();
        currentStateAutoresolve();
        atomicArmyAndGarrison();
        campaignWrapperAndMigration();
        footprintsAndLargeBattle();
        fourteenWaivedBills();
        networkRoundTrips();
        logisticsNetworkRoundTrips();
        logisticsPacketBounds();
        supplyReservesAndReadiness();
        settlementFootprintMigrationAndPreparation();
        settlementAbandonmentRetainsClaims();
        logisticsTopologyRevisions();
        strategicSupplyEngine();
        roadGeometryAndBoundaryMovement();
        battleSelectionBeforeFirstSnapshot();
        breachWarningSuppression();
        battleBodyCollisions();
        savedRetreatMarch();
        shortenedSavedRetreats();
        physicalFormationLocations();
        independentCohortOrders();
        movementMapRoundTrips();
        cohortRosterRowBounds();
        System.out.println("Pre-Sprint 7 regressions passed: " + checks + " checks.");
        if (java.util.Arrays.asList(args).contains("--logistics-benchmark")) logisticsBenchmark();
    }

    private static void breachWarningSuppression() {
        var history = new com.devc.minecraftempires.territory.BreachAlertService.BreachHistory();
        UUID defender = UUID.randomUUID(), attacker = UUID.randomUUID();
        long chunk = new net.minecraft.world.level.ChunkPos(-3, 9).pack();
        check(history.record(chunk, defender, attacker, 0), "A new border breach warns its defender");
        check(!history.record(chunk, defender, attacker, 0) && !history.record(chunk, defender, attacker, 20),
                "Repeated captures of the same chunk do not repeat its breach warning");
        check(history.record(chunk + 1, defender, attacker, 20) && !history.record(chunk + 2, defender, attacker, 20),
                "Multiple new chunk captures in one pass produce one combined chat warning");
        boolean quiet = true;
        for (int tick = 40; tick < 10000; tick += 20) quiet &= !history.record(chunk, defender, attacker, tick);
        check(quiet, "Continuous border churn remains silent beyond the ordinary map-alert lifetime");
        check(history.record(chunk, defender, attacker, 13000), "A new incursion after a quiet interval may warn again");
        check(history.record(chunk, defender, UUID.randomUUID(), 13000), "Another attacker has independent breach warnings");
        check(!history.record(chunk, defender, defender, 13000), "Owned territory is never a hostile breach");
        check(history.record(chunk, defender, attacker, 0), "A backwards world clock resets transient breach history safely");
    }

    private static boolean separatedBodies(java.util.List<CohortData> bodies) {
        for (int i = 0; i < bodies.size(); i++) for (int j = i + 1; j < bodies.size(); j++) {
            var a = bodies.get(i); var b = bodies.get(j);
            if (a.isAlive() && !a.hasEscaped() && b.isAlive() && !b.hasEscaped()
                    && a.getPosition().distanceSquared(b.getPosition()) < BattleSession.COHORT_SPACING * BattleSession.COHORT_SPACING - 1.0e-7) return false;
        }
        return true;
    }
    private static void battleBodyCollisions() {
        for (int layout = 0; layout < 8; layout++) battleBodyCollisionLayout(layout);
        BattleSession fast = new BattleSession(UUID.randomUUID(), UUID.randomUUID(), 0, 0);
        var runner = new CohortData(UUID.randomUUID(), "INFANTRY", 50, 100, 50, 50, 50, 0, -30);
        var blocker = new CohortData(UUID.randomUUID(), "INFANTRY", 50, 100, 1, 50, 50, 0, 0);
        fast.addAttackerCohort(runner); fast.addDefenderCohort(blocker);
        for (int i = 0; i < 120; i++) fast.tickDeployment();
        fast.tick(true, false);
        check(runner.getPosition().distance(blocker.getPosition()) >= BattleSession.COHORT_SPACING
                        && runner.getPosition().y < blocker.getPosition().y,
                "Swept movement prevents a fast unit tunnelling through an opposing formation");
    }
    private static void battleBodyCollisionLayout(int layout) {
        BattleSession battle = new BattleSession(UUID.randomUUID(), UUID.randomUUID(), 0, 0);
        for (int i = 0; i < 15; i++) {
            UUID attackId = UUID.nameUUIDFromBytes(("collision-a-" + layout + "-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            UUID defendId = UUID.nameUUIDFromBytes(("collision-d-" + layout + "-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            battle.addAttackerCohort(CohortData.fromCohort(new Cohort(attackId, CohortType.INFANTRY, null), 0, -20));
            battle.addDefenderCohort(CohortData.fromCohort(new Cohort(defendId, CohortType.INFANTRY, null), 0, 20));
        }
        battle.applyFormation(true, BattleSession.Formation.TRIPLEX);
        battle.applyFormation(false, BattleSession.Formation.TRIPLEX);
        var bodies = new java.util.ArrayList<>(battle.getAttackerCohorts()); bodies.addAll(battle.getDefenderCohorts());
        check(separatedBodies(bodies), "Initial formations give each tactical unit physical space");
        var first = battle.getAttackerCohorts().get(0); var second = battle.getAttackerCohorts().get(1);
        check(!battle.issueOrder(first.getCohortId(), second.getPosition().x, second.getPosition().y, true),
                "Deployment cannot place a unit on another unit");
        boolean spaced = true;
        for (int i = 0; i < 1000 && battle.isActive(); i++) { battle.tick(); spaced &= separatedBodies(bodies); }
        check(spaced, "Two 15-unit forces cannot merge into overlapping piles while advancing, fighting or routing");
        check(!battle.isActive(), "Non-overlapping autonomous formations still complete combat, layout " + layout);
    }
    private static void savedRetreatMarch() {
        ArmyManager manager = new ArmyManager();
        StateData state = new StateData(UUID.randomUUID(), "Retreat", UUID.randomUUID(), StateTier.COUNTY);
        BlockPos start = new BlockPos(24, 64, -32);
        Legion legion = manager.raiseLegion(state, start).orElseThrow();
        Army army = manager.autoWrapLegionInArmy(legion).orElseThrow();
        Army opponent = new Army(UUID.randomUUID(), UUID.randomUUID(), start.offset(50, 0, 0));
        manager.registerArmy(opponent);
        Army interceptor = new Army(UUID.randomUUID(), opponent.getOwningStateId(), start);
        BattleSession battle = new BattleSession(army.getArmyId(), opponent.getArmyId(), start.getX(), start.getZ());
        for (UUID id : army.getDeployedCohortIds()) {
            var snapshot = CohortData.fromCohort(manager.resolveCohort(id), 0, -20);
            snapshot.applyDamage(2, 50); battle.addAttackerCohort(snapshot);
        }
        battle.finish(BattleSession.BattleResult.DEFENDER_WINS);
        army.setCurrentBattleId(battle.getBattleId());
        try {
            var commit = BattleManager.class.getDeclaredMethod("commitSide", BattleSession.class, boolean.class, ArmyManager.class);
            commit.setAccessible(true);
            var battles = new BattleManager();
            commit.invoke(battles, battle, true, manager);
            BlockPos target = start.offset(0, 0, -StateBalance.POST_BATTLE_RETREAT_DISTANCE);
            check(army.getStoredPosition().equals(start) && army.isRetreating() && army.getRetreatTarget().equals(target),
                    "Defeat saves a 200-block retreat destination while leaving the army at the battlefield");
            check(legion.allCohorts().stream().allMatch(c -> c.getStoredPosition().equals(start) && c.isRetreating()),
                    "Surviving cohorts keep their actual position and mandatory retreat state");
            check(!ArmyManager.canEngage(army, opponent) && !ArmyManager.canEngage(opponent, army)
                            && ArmyManager.canEngage(interceptor, army),
                    "Initial disengagement blocks only the original opponent; other armies may intercept");
            for (int i = 0; i < 50; i++) ArmyManager.advanceMovement(army.getPreciseX(), army.getPreciseZ(), army.getWaypoints(), .25, army::setPrecisePos);
            manager.syncCohortPositions(army);
            double moved = army.getPreciseZ();
            check(moved < start.getZ() && moved > target.getZ() && !army.getWaypoints().isEmpty(),
                    "Retreat advances through intermediate positions rather than teleporting to the rally point");
            commit.invoke(battles, battle, true, manager);
            check(army.getPreciseZ() == moved && army.getRetreatTarget().equals(target)
                            && legion.allCohorts().stream().allMatch(c -> c.getBattlesParticipated() == 1),
                    "Reapplying a saved battle cannot reset retreat progress or grant battle rewards twice");
            ArmyManager restored = ArmyManager.fromTag(manager.toTag());
            Army loaded = restored.getArmy(army.getArmyId());
            check(loaded.isRetreating() && loaded.getRetreatTarget().equals(target) && loaded.getPreciseZ() == moved
                            && loaded.isDisengagingFrom(opponent.getArmyId()),
                    "Army retreat position, destination and original-opponent exclusion survive save/reload");
            check(restored.getLegion(legion.getLegionId()).allCohorts().stream().allMatch(c -> c.isRetreating()
                            && c.getRetreatTarget().equals(target) && c.isDisengagingFrom(opponent.getArmyId())),
                    "Cohort retreat state also survives save/reload and wrapper changes");
            check(!manager.disbandArmy(army.getArmyId(), false), "Releasing a wrapper cannot cancel its compulsory retreat");
            opponent.setStoredPosition(start.offset(0, 0, 200));
            check(manager.updateRetreatDisengagement(army) && ArmyManager.canEngage(opponent, army),
                    "The original opponent may pursue again after the armies first separate beyond engagement range");
            while (!loaded.getWaypoints().isEmpty()) ArmyManager.advanceMovement(loaded.getPreciseX(), loaded.getPreciseZ(), loaded.getWaypoints(), .25, loaded::setPrecisePos);
            loaded.finishRetreat();
            check(loaded.getStoredPosition().equals(target) && !loaded.isRetreating(), "Retreat finishes at its saved rally point");
            ArmyManager winners = new ArmyManager();
            Legion winningLegion = winners.raiseLegion(state, start).orElseThrow();
            Army winningArmy = winners.autoWrapLegionInArmy(winningLegion).orElseThrow();
            Cohort garrison = winningLegion.allCohorts().get(0), withdrawing = winningLegion.allCohorts().get(1);
            garrison.setIsGarrisoned(true); garrison.setGarrisonedSettlementId(UUID.randomUUID());
            BattleSession victory = new BattleSession(winningArmy.getArmyId(), opponent.getArmyId(), start.getX(), start.getZ());
            for (Cohort cohort : winningLegion.allCohorts()) {
                var snapshot = CohortData.fromCohort(cohort, 0, -20);
                if (cohort == garrison) snapshot.setRouting(true);
                if (cohort == withdrawing) snapshot.beginWithdrawal(0, -160);
                victory.addAttackerCohort(snapshot);
            }
            victory.finish(BattleSession.BattleResult.ATTACKER_WINS);
            commit.invoke(battles, victory, true, winners);
            check(!winningArmy.isRetreating() && winningArmy.getStoredPosition().equals(start),
                    "Winning formations hold their battlefield position");
            check(garrison.isRetreating() && !garrison.isGarrisoned() && !garrison.isDeployed()
                            && garrison.getStoredPosition().equals(start),
                    "A routed garrison abandons its post in place and marches independently");
            check(withdrawing.isRetreating() && !withdrawing.isDeployed() && withdrawing.getStoredPosition().equals(start),
                    "A surviving withdrawal leaves the winning wrapper and retreats without teleporting");
            check(winningLegion.allCohorts().stream().filter(c -> c != garrison && c != withdrawing)
                            .allMatch(c -> c.isDeployed() && !c.isRetreating()),
                    "Routed detachments do not force the winning army's other cohorts to retreat");
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Battle retreat integration", failure); }
    }

    private static void shortenedSavedRetreats() {
        check(StateBalance.POST_BATTLE_RETREAT_DISTANCE == 200, "New strategic retreats use the approved 200-block distance");
        ArmyManager manager = new ArmyManager();
        StateData owner = new StateData(UUID.randomUUID(), "Older retreat", UUID.randomUUID(), StateTier.COUNTY);
        Legion legion = manager.raiseLegion(owner, new BlockPos(-12, 64, -24)).orElseThrow();
        Army army = manager.raiseArmy(owner.getStateId(), BlockPos.ZERO, java.util.List.of(legion.getMainCohort().getCohortId())).orElseThrow();
        UUID enemy = UUID.randomUUID();
        army.setPrecisePos(-11.75, -23.25);
        army.beginRetreat(new BlockPos(-400, 64, 400), enemy);
        manager.syncCohortPositions(army);
        Cohort attached = legion.getMainCohort(), shortOrder = legion.allCohorts().get(1);
        attached.beginRetreat(army.getRetreatTarget(), enemy);
        BlockPos close = shortOrder.getStoredPosition().offset(50, 0, 0);
        shortOrder.beginRetreat(close, enemy);
        ArmyManager restored = ArmyManager.fromTag(manager.toTag());
        Army loaded = restored.getArmy(army.getArmyId());
        Cohort survivor = restored.resolveCohort(attached.getCohortId());
        check(loaded.getPreciseX() == -11.75 && loaded.getPreciseZ() == -23.25
                        && survivor.getPreciseX() == -11.75 && survivor.getPreciseZ() == -23.25,
                "Shortening a saved retreat preserves fractional position and completed progress");
        check(Math.hypot(loaded.getRetreatTarget().getX() + .5 - loaded.getPreciseX(), loaded.getRetreatTarget().getZ() + .5 - loaded.getPreciseZ()) <= 200
                        && survivor.getRetreatTarget().equals(loaded.getRetreatTarget()),
                "An old long diagonal retreat becomes at most 200 blocks for the army and its cohorts");
        check(restored.resolveCohort(shortOrder.getCohortId()).getRetreatTarget().equals(close), "Migration never extends an already shorter retreat");
        check(loaded.isDisengagingFrom(enemy) && survivor.isDisengagingFrom(enemy), "Retreat migration retains original-opponent exclusion");
        BlockPos once = loaded.getRetreatTarget();
        restored.shortenRetreats(200);
        check(loaded.getRetreatTarget().equals(once) && loaded.getWaypoints().size() == 1, "Retreat migration is idempotent");
    }

    /** Exercise actual server snapshots, wrapper transitions and save loading, not a second location model. */
    private static void physicalFormationLocations() {
        ArmyManager manager = new ArmyManager();
        StateData owner = new StateData(UUID.randomUUID(), "Locations", UUID.randomUUID(), StateTier.COUNTY);
        BlockPos home = new BlockPos(32, 64, -48), away = home.offset(400, 0, 240);
        Legion legion = manager.raiseLegion(owner, home).orElseThrow();
        var picked = legion.allCohorts().subList(0, 2);
        var ids = picked.stream().map(Cohort::getCohortId).toList();
        picked.forEach(c -> c.setStoredPosition(away));
        picked.getFirst().setPrecisePos(away.getX() + .25, away.getZ() + .75);
        legion.addWaypoint(home.offset(20, 0, 0));
        Army army = manager.raiseArmy(owner.getStateId(), home, ids).orElseThrow();
        check(army.getStoredPosition().equals(away) && army.getPreciseX() == away.getX() + .25,
                "Composing uses the selected cohort's real position, ignoring the chosen settlement/client position");
        check(picked.get(1).getPreciseX() == away.getX() + .5, "Composition does not snap other selected cohorts to the army anchor");
        var view = MapDataService.buildArmyPayload(owner.getStateId(), manager, pos -> false);
        var field = view.legions().getFirst();
        check(field.packedChunkPos() == net.minecraft.world.level.ChunkPos.containing(home).pack()
                        && field.availableSoldiers() == 450 && field.waypoints().equals(java.util.List.copyOf(legion.getWaypoints())),
                "A deployed administrative main cohort cannot pull the remaining legion marker or inflate its physical troop count");
        army.addWaypoint(away.offset(64, 0, 0));
        for (int i = 0; i < 40; i++) ArmyManager.advanceMovement(army.getPreciseX(), army.getPreciseZ(), army.getWaypoints(), .25, army::setPrecisePos);
        manager.syncCohortPositions(army);
        double x = picked.getFirst().getPreciseX(), z = picked.getFirst().getPreciseZ();
        check(manager.disbandArmy(army.getArmyId(), false) && picked.stream().allMatch(c -> c.isDetached() && !c.isDeployed()
                        && c.getPreciseX() == x && c.getPreciseZ() == z), "Army release leaves all survivors exactly where they marched");
        check(legion.getFieldCohorts().stream().noneMatch(picked::contains) && legion.getStoredPosition().equals(home),
                "Released cohorts do not automatically follow or relocate their legion's remaining field body");
        ArmyManager restored = ArmyManager.fromTag(manager.toTag());
        Cohort loaded = restored.resolveCohort(ids.getFirst());
        check(loaded.isDetached() && loaded.getPreciseX() == x && loaded.getPreciseZ() == z, "Detached identity and precise position survive reload");
        view = MapDataService.buildArmyPayload(owner.getStateId(), restored, pos -> false);
        check(view.cohorts().size() == 2 && view.cohorts().stream().allMatch(c -> c.position().equals(loaded.getStoredPosition())),
                "Released cohorts receive independent markers at their actual location");
        check(restored.regroupLegion(owner.getStateId(), legion.getLegionId()) && loaded.isRegrouping()
                        && loaded.getWaypoints().peek().equals(home) && loaded.getPreciseX() == x && loaded.getPreciseZ() == z,
                "Explicit regroup adds a real march back to the paused field body without relocating a cohort");
        ArmyManager reloaded = ArmyManager.fromTag(restored.toTag());
        Cohort marching = reloaded.resolveCohort(loaded.getCohortId());
        check(marching.isRegrouping() && marching.isDetached() && marching.getWaypoints().peek().equals(home), "Regroup orders persist across restart");
        ArmyManager.advanceMovement(marching.getPreciseX(), marching.getPreciseZ(), marching.getWaypoints(), .25, marching::setPrecisePos);
        check(marching.getPreciseX() < x && marching.getPreciseX() > home.getX(), "Regroup proceeds through intermediate positions");
        check(!reloaded.changeGarrison(owner.getStateId(), java.util.List.of(marching.getCohortId()),
                        new SettlementData(UUID.randomUUID(), owner.getStateId(), "Home", home), false),
                "A distant settlement cannot pull detached cohorts into its garrison");
    }

    private static void independentCohortOrders() {
        ArmyManager manager = new ArmyManager();
        StateData owner = new StateData(UUID.randomUUID(), "Orders", UUID.randomUUID(), StateTier.COUNTY);
        Legion legion = manager.raiseLegion(owner, BlockPos.ZERO).orElseThrow();
        var ids = legion.allCohorts().stream().limit(2).map(Cohort::getCohortId).toList();
        Army army = manager.raiseArmy(owner.getStateId(), BlockPos.ZERO, ids).orElseThrow();
        army.setCurrentBattleId(UUID.randomUUID());
        check(!manager.detachCohorts(owner.getStateId(), ids) && ids.stream().allMatch(id -> manager.resolveCohort(id).isDeployed()),
                "A battle prevents the entire detachment operation");
        army.setCurrentBattleId(null); manager.createCampaign(owner.getStateId(), army.getArmyId());
        check(!manager.detachCohorts(owner.getStateId(), ids), "Detachment cannot bypass the existing active-campaign release restriction");
        manager.getCampaign(army.getCampaignId()).disbandCampaign(manager);
        check(!manager.detachCohorts(UUID.randomUUID(), ids) && !manager.detachCohorts(owner.getStateId(), java.util.List.of(ids.getFirst(), UUID.randomUUID()))
                        && manager.resolveCohort(ids.getFirst()).isDeployed(), "Unauthorized or partly invalid detachments change no assignments");
        check(manager.detachCohorts(owner.getStateId(), ids) && manager.getArmy(army.getArmyId()) == null, "Detaching every cohort removes the empty peacetime wrapper");
        BlockPos one = new BlockPos(50, 64, 0), two = new BlockPos(100, 64, 0);
        check(manager.orderCohorts(owner.getStateId(), ids, one, false) && manager.orderCohorts(owner.getStateId(), ids, two, true),
                "Independent batches accept ordinary and queued map orders");
        check(ids.stream().allMatch(id -> java.util.List.copyOf(manager.resolveCohort(id).getWaypoints()).equals(java.util.List.of(one, two))
                        && manager.resolveCohort(id).getStoredPosition().equals(BlockPos.ZERO)), "Orders preserve current positions and append each cohort's route");
        check(!manager.orderCohorts(owner.getStateId(), java.util.List.of(ids.getFirst(), ids.getFirst()), one, false), "Duplicate cohort selections are rejected");
        Cohort blocked = manager.resolveCohort(ids.get(1)); blocked.beginRetreat(two, UUID.randomUUID());
        check(!manager.orderCohorts(owner.getStateId(), ids, one, false)
                        && manager.resolveCohort(ids.getFirst()).getWaypoints().size() == 2, "A retreating cohort rejects a mixed batch atomically");
        blocked.finishRetreat();
        while (blocked.getWaypoints().size() < 128) blocked.addWaypoint(two);
        check(!manager.orderCohorts(owner.getStateId(), ids, one, true)
                        && manager.resolveCohort(ids.getFirst()).getWaypoints().size() == 2, "A full queue rejects appending the whole batch without partial orders");
        check(manager.orderCohorts(owner.getStateId(), ids, one, false) && blocked.getWaypoints().size() == 1, "Replacing orders is allowed at the queue bound");
        legion.addWaypoint(two);
        Army otherArmy = manager.raiseArmy(owner.getStateId(), BlockPos.ZERO, java.util.List.of(legion.allCohorts().get(2).getCohortId())).orElseThrow();
        otherArmy.addWaypoint(two);
        var own = MapDataService.buildArmyPayload(owner.getStateId(), manager, pos -> false);
        var foreign = MapDataService.buildArmyPayload(UUID.randomUUID(), manager, pos -> true);
        check(own.cohorts().size() == 2 && own.cohorts().stream().allMatch(c -> c.waypoints().equals(java.util.List.of(one)) && c.legionId().equals(legion.getLegionId())),
                "The owner receives independent waypoint routes and parent membership");
        check(foreign.cohorts().stream().allMatch(c -> c.waypoints().isEmpty() && c.legionId().equals(new UUID(0, 0)))
                        && foreign.legions().stream().allMatch(l -> l.waypoints().isEmpty())
                        && foreign.armies().stream().allMatch(a -> a.waypoints().isEmpty()), "Visible enemies do not receive private movement or parent-legion data");
        check(own.legions().getFirst().waypoints().equals(java.util.List.of(two)) && own.armies().getFirst().waypoints().equals(java.util.List.of(two)),
                "Own legion and army routes remain available alongside detached-cohort orders");
        var hidden = MapDataService.buildArmyPayload(UUID.randomUUID(), manager, pos -> false);
        check(hidden.cohorts().isEmpty() && hidden.legions().isEmpty(), "Independent formations respect current scouting visibility");
    }

    /** Exercise the actual row's native button layout: compilation alone missed a swapped rectangle API. */
    private static void cohortRosterRowBounds() {
        try {
            var entry = new com.devc.minecraftempires.network.packet.ManagementDataPayload.CohortEntry(UUID.randomUUID(), UUID.randomUUID(),
                    "Cohort bounds", "INFANTRY", BlockPos.ZERO, 50, 50, 100, 50, 50, 50, 50, 0, "STONE", null, null);
            Class<?> type = Class.forName("com.devc.minecraftempires.client.gui.screen.ComposeArmyScreen$CohortRow");
            var constructor = type.getDeclaredConstructor(entry.getClass(), boolean.class, Runnable.class);
            constructor.setAccessible(true);
            var row = (net.minecraft.client.gui.components.ObjectSelectionList.Entry<?>) constructor.newInstance(entry, true, (Runnable) () -> { });
            var layout = type.getDeclaredMethod("positionButton"); layout.setAccessible(true);
            var field = type.getDeclaredField("row"); field.setAccessible(true);
            var button = (net.minecraft.client.gui.components.Button) field.get(row);
            row.setX(20); row.setY(58); row.setWidth(440); row.setHeight(23);
            layout.invoke(row);
            check(button.getX() == 20 && button.getY() == 59, "The real cohort button is placed at its row, not at the row width on the far right");
            check(button.getWidth() == 440 && button.getHeight() == 20, "Cohort row dimensions do not become screen coordinates");
            check(button.isMouseOver(100, 65) && !button.isMouseOver(470, 65), "Rendered row geometry also identifies the correct clickable cohort");
            row.setY(-10); layout.invoke(row);
            check(button.getY() == -9 && button.getHeight() == 20, "Scrolling updates position without stretching the button");
            row.setX(18); row.setY(80); row.setWidth(260); layout.invoke(row);
            check(button.getX() == 18 && button.getY() == 81 && button.getWidth() == 260 && button.getHeight() == 20,
                    "Resizing preserves the row's full width and fixed button height");
            check(button.getMessage().getString().startsWith("[x] "), "Selection remains visible inside the correctly positioned row");
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Native cohort roster geometry", failure); }
    }

    private static void movementMapRoundTrips() {
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        UUID state = UUID.randomUUID(), legion = UUID.randomUUID(), cohort = UUID.randomUUID();
        var path = java.util.List.of(new BlockPos(-40, 64, 15), new BlockPos(90, 64, -80));
        var payload = new ArmyMapPayload(java.util.List.of(new ArmyMapPayload.LegionSummary(legion, state, 0, 500, 60, path)), java.util.List.of(),
                java.util.List.of(new ArmyMapPayload.CohortSummary(cohort, state, legion, new BlockPos(-30, 64, -80), 50, 40, true, path)));
        try {
            ArmyMapPayload.STREAM_CODEC.encode(buffer, payload);
            check(payload.equals(ArmyMapPayload.STREAM_CODEC.decode(buffer)), "Legion and independent retreat/queued routes survive the typed map codec");
            for (String action : java.util.List.of("COMPOSE", "DETACH", "MOVE")) {
                buffer.clear(); var order = new ComposeArmyPayload(java.util.List.of(cohort), path.getFirst(), action, true);
                ComposeArmyPayload.STREAM_CODEC.encode(buffer, order);
                check(order.equals(ComposeArmyPayload.STREAM_CODEC.decode(buffer)), "Composition/detachment/movement operation round-trips: " + action);
            }
            check(new ComposeArmyPayload(java.util.List.of(cohort), BlockPos.ZERO).action().equals("COMPOSE"), "The existing composition constructor retains its original action");
            for (int count : new int[] {-1, 129, Integer.MAX_VALUE}) {
                buffer.clear(); buffer.writeVarInt(1); buffer.writeUUID(legion); buffer.writeUUID(state); buffer.writeLong(0);
                buffer.writeVarInt(500); buffer.writeVarInt(60); buffer.writeVarInt(count);
                boolean rejected = false;
                try { ArmyMapPayload.STREAM_CODEC.decode(buffer); } catch (IllegalArgumentException expected) { rejected = true; }
                check(rejected, "Malformed movement path is rejected before allocating waypoints: " + count);
            }
            com.devc.minecraftempires.client.map.ClientArmyData.accept(payload);
            var view = com.devc.minecraftempires.client.map.ClientArmyData.get();
            check(view.cohortsById().get(cohort).waypoints().equals(path) && view.getCohortIdsAtChunk(-2, -5).contains(cohort)
                            && view.legionsById().get(legion).waypoints().equals(path), "Client selection indexes preserve independent positions and both route types");
            var widget = new com.devc.minecraftempires.client.gui.widget.InteractiveMapWidget(0, 0, 400, 300);
            widget.selectUnit(cohort);
            check(widget.getCohortOrderSelection().equals(java.util.List.of(cohort)), "Selecting an independent marker targets that cohort's movement orders");
            widget.selectUnit(legion);
            check(widget.getCohortOrderSelection().isEmpty(), "Selecting a legion clears the previous independent movement batch");
            widget.selectUnit(null);
            check(widget.getCohortOrderSelection().isEmpty(), "Clearing selection is safe before or after a map snapshot");
            com.devc.minecraftempires.client.map.ClientArmyData.accept(ArmyMapPayload.empty());
            check(!com.devc.minecraftempires.client.map.ClientArmyData.get().hasAnyUnits(), "An empty snapshot clears independent markers and stale paths");
        } finally { buffer.release(); }
    }

    /** Reproduce the testbattle first-frame/cleared-selection path without opening a player world. */
    private static void battleSelectionBeforeFirstSnapshot() {
        var battleId = UUID.randomUUID();
        var cohortId = UUID.randomUUID();
        var grid = new com.devc.minecraftempires.client.gui.widget.BattleGridWidget(0, 0, 256, 256, battleId, null, null);
        com.devc.minecraftempires.client.map.ClientBattleData.clear();
        var empty = com.devc.minecraftempires.client.map.ClientBattleData.get();
        check(empty.getCohort(grid.getFirstSelectedCohortId()) == null,
                "A battle's first frame has no selection and accepts a null key against its immutable empty roster");
        check(empty.getRenderX(null, 0) == 0 && empty.getRenderZ(null, 0) == 0,
                "Missing battle selection also has safe interpolation coordinates before the first snapshot");
        var cohort = new com.devc.minecraftempires.network.packet.BattleSyncPayload.CohortSnapshot(
                cohortId, 12, 24, 50, 50, 100, "HEAVY_INFANTRY", false, false, false, false, 10, 10, 1);
        com.devc.minecraftempires.client.map.ClientBattleData.accept(new com.devc.minecraftempires.network.packet.BattleSyncPayload(
                battleId, java.util.List.of(cohort), java.util.List.of(), "DEPLOYMENT", 200, "ONGOING", true, false, 0, 0));
        var loaded = com.devc.minecraftempires.client.map.ClientBattleData.get();
        check(loaded.getCohort(grid.getFirstSelectedCohortId()) == null,
                "Receiving a roster before the player selects a formation keeps the empty details panel safe");
        grid.getSelectedCohortIds().add(cohortId);
        check(loaded.getCohort(grid.getFirstSelectedCohortId()).cohortId.equals(cohortId)
                        && loaded.canControl(loaded.getCohort(cohortId)),
                "Selecting a real formation still resolves its details and command permissions");
        grid.clearSelection();
        check(loaded.getCohort(grid.getFirstSelectedCohortId()) == null && !loaded.canControl(null),
                "Clearing a battle selection returns to safe empty details and no command permission");
        check(loaded.getCohort(UUID.randomUUID()) == null,
                "A selection absent from the latest battle snapshot does not resolve a different formation");
        com.devc.minecraftempires.client.map.ClientBattleData.clear();
        check(com.devc.minecraftempires.client.map.ClientBattleData.get().getCohort(cohortId) == null,
                "Closing/reopening a battle cannot retain a selected formation from its previous roster");
    }

    /** Optional representative timing exercise; no machine-dependent performance assertion. */
    private static void logisticsBenchmark() {
        final int width = 256, depth = 256, queries = 200;
        var states = new StateManager();
        StateData owner = states.createState("Benchmark state", UUID.randomUUID(), StateTier.EMPIRE);
        UUID ownerId = owner.getStateId();
        var claims = new com.devc.minecraftempires.territory.ClaimManager();
        for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++)
            claims.setClaim(new net.minecraft.world.level.ChunkPos(x, z), ownerId, "", false, 1);
        java.util.List<BlockPos> camps = new java.util.ArrayList<>();
        for (int z : new int[] {32, 96, 160, 224}) {
            SettlementData root = new SettlementData(UUID.randomUUID(), ownerId, "Root " + z, new BlockPos(32 * 16 + 8, 64, z * 16 + 8));
            states.registerSettlement(root.getSettlementId(), root);
            for (int x : new int[] {80, 128, 176, 224}) camps.add(new BlockPos(x * 16 + 8, 64, z * 16 + 8));
        }
        LogisticsManager logistics = savedLogistics(ownerId, java.util.List.of(), camps);
        BlockPos east = new BlockPos(224 * 16 + 8, 64, 128 * 16 + 8);
        long start = System.nanoTime();
        boolean coldConnected = logistics.inspectSupply(ownerId, east, claims, states, java.util.List.of()).connected();
        long coldNanos = System.nanoTime() - start;
        if (!coldConnected) throw new AssertionError("Benchmark camp chains must initially supply the eastern formations");
        start = System.nanoTime();
        for (int index = 0; index < queries; index++) {
            // Coprime strides sample the complete state, including edges and relay legs.
            BlockPos position = new BlockPos(((index * 73) % width) * 16 + 8, 64, ((index * 151) % depth) * 16 + 8);
            if (!logistics.inspectSupply(ownerId, position, claims, states, java.util.List.of()).connected())
                throw new AssertionError("Benchmark's established relay grid must supply representative position " + position);
        }
        long queryNanos = System.nanoTime() - start;
        // A complete column cut severs every eastern relay from the western roots.
        // The checked eastern formation lies farther than a 512-block final leg away.
        for (int z = 0; z < depth; z++) claims.removeClaim(new net.minecraft.world.level.ChunkPos(128, z));
        start = System.nanoTime();
        boolean cutConnected = logistics.inspectSupply(ownerId, east, claims, states, java.util.List.of()).connected();
        long cutNanos = System.nanoTime() - start;
        if (cutConnected) throw new AssertionError("Benchmark's severed camp network must not retain its previous supply roots");
        if (!logistics.inspectSupply(ownerId, new BlockPos(32 * 16 + 8, 64, 128 * 16 + 8), claims, states, java.util.List.of()).connected())
            throw new AssertionError("Benchmark cut must preserve supply on the settlement side");
        for (int z = 0; z < depth; z++) claims.setClaim(new net.minecraft.world.level.ChunkPos(128, z), ownerId, "", false, 1);
        start = System.nanoTime();
        boolean restoredConnected = logistics.inspectSupply(ownerId, east, claims, states, java.util.List.of()).connected();
        long restoreNanos = System.nanoTime() - start;
        if (!restoredConnected) throw new AssertionError("Benchmark's restored corridor must rebuild eastern supply");
        System.out.printf(java.util.Locale.ROOT,
                "Logistics benchmark: %dx%d chunks (%d claims), %d roots, %d camps; cold %.3f ms; %d troop queries %.3f ms (%.3f ms/query); cut/rebuild %.3f ms; restore/rebuild %.3f ms. Supply/cut/restore invariants passed.%n",
                width, depth, width * depth, states.getAllSettlements().size(), camps.size(), coldNanos / 1_000_000.0,
                queries, queryNanos / 1_000_000.0, queryNanos / (queries * 1_000_000.0), cutNanos / 1_000_000.0, restoreNanos / 1_000_000.0);
    }

    private static void strategicSupplyEngine() {
        var states = new StateManager();
        StateData owner = states.createState("Supply owner", UUID.randomUUID(), StateTier.COUNTY);
        StateData neighbor = states.createState("Transit owner", UUID.randomUUID(), StateTier.COUNTY);
        UUID ownerId = owner.getStateId();
        SettlementData source = new SettlementData(UUID.randomUUID(), ownerId, "Supply base", new BlockPos(8, 64, 8));
        states.registerSettlement(source.getSettlementId(), source);
        var claims = new com.devc.minecraftempires.territory.ClaimManager();
        claimCorridor(claims, ownerId, 0, 10);
        var plain = new LogisticsManager();
        check(plain.inspectSupply(ownerId, new BlockPos(680, 64, 8), claims, states, java.util.List.of()).connected(),
                "The final delivery leg reaches exactly 512 blocks beyond the network endpoint");
        check(!plain.inspectSupply(ownerId, new BlockPos(681, 64, 8), claims, states, java.util.List.of()).connected(),
                "A final delivery leg beyond 512 blocks fails even when transport budget remains");
        claimCorridor(claims, ownerId, 11, 40);
        var exactBudget = plain.inspectSupply(ownerId, new BlockPos(1032, 64, 8), claims, states, java.util.List.of());
        check(exactBudget.connected() && exactBudget.cost() == StateBalance.SUPPLY_RANGE,
                "The final leg is included in the complete 1024-block transport budget");
        check(!plain.inspectSupply(ownerId, new BlockPos(1033, 64, 8), claims, states, java.util.List.of()).connected(),
                "A nearby endpoint cannot make final delivery exceed the remaining transport budget");
        claimCorridor(claims, ownerId, 41, 128);
        java.util.List<BlockPos> roadPoints = new java.util.ArrayList<>();
        for (int x = 0; x <= 128; x++) roadPoints.add(new BlockPos(x * 16 + 8, 64, 8));
        LogisticsManager road = savedLogistics(ownerId, roadPoints, java.util.List.of());
        check(!plain.inspectSupply(ownerId, new BlockPos(2056, 64, 8), claims, states, java.util.List.of()).connected(),
                "An ordinary corridor cannot provide unlimited range merely because it is owned");
        var weighted = road.inspectSupply(ownerId, new BlockPos(2056, 64, 8), claims, states, java.util.List.of());
        check(weighted.connected() && weighted.cost() == 1024,
                "Connected road segments halve transport cost and double the supported distance");
        check(!road.inspectSupply(ownerId, new BlockPos(2057, 64, 8), claims, states, java.util.List.of()).connected(),
                "Road range also includes the unweighted final delivery leg");

        // The destination is more than a final-delivery leg beyond the cut; it needs transit.
        var transitClaims = new com.devc.minecraftempires.territory.ClaimManager();
        claimCorridor(transitClaims, ownerId, 0, 60);
        claimCorridor(transitClaims, neighbor.getStateId(), 20, 25);
        var transit = new LogisticsManager();
        BlockPos destination = new BlockPos(968, 64, 8);
        check(!transit.inspectSupply(ownerId, destination, transitClaims, states, java.util.List.of()).connected(),
                "Peace alone does not carry supplies across a foreign corridor");
        states.grantPassage(ownerId, neighbor.getStateId());
        check(!transit.inspectSupply(ownerId, destination, transitClaims, states, java.util.List.of()).connected(),
                "Granting the other state passage does not grant reciprocal transit rights");
        states.grantPassage(neighbor.getStateId(), ownerId);
        check(transit.inspectSupply(ownerId, destination, transitClaims, states, java.util.List.of()).connected(),
                "The territory owner can grant this state's actual supply path directional passage");
        states.revokePassage(neighbor.getStateId(), ownerId);
        check(!transit.inspectSupply(ownerId, destination, transitClaims, states, java.util.List.of()).connected(),
                "Revoking passage invalidates a previously cached supplied route");
        claimCorridor(transitClaims, ownerId, 20, 25);
        check(transit.inspectSupply(ownerId, destination, transitClaims, states, java.util.List.of()).connected(),
                "Restoring territorial control restores the same corridor without recreating the manager");
        transitClaims.removeClaim(new net.minecraft.world.level.ChunkPos(20, 0));
        check(!transit.inspectSupply(ownerId, destination, transitClaims, states, java.util.List.of()).connected(),
                "A territorial cut invalidates cached connectivity and cannot be skipped by adjacent claims");
        claimCorridor(transitClaims, ownerId, 20, 20);
        check(!transit.inspectSupply(ownerId, destination, transitClaims, states,
                        java.util.List.of(new BlockPos(500, 64, 58))).connected(),
                "A hostile exactly 50 blocks from the only corridor intercepts its supply");
        check(transit.inspectSupply(ownerId, destination, transitClaims, states,
                        java.util.List.of(new BlockPos(500, 64, 59))).connected(),
                "A hostile moving beyond the 50-block interception boundary restores the cached path");
        source.setSupplyPreparationTicks(StateBalance.TICKS_PER_DAY);
        check(!transit.inspectSupply(ownerId, destination, transitClaims, states, java.util.List.of()).connected(),
                "A captured source still preparing cannot provide supply through its former cached root");
        source.advanceSupplyPreparationTicks(StateBalance.TICKS_PER_DAY);
        check(transit.inspectSupply(ownerId, destination, transitClaims, states, java.util.List.of()).connected(),
                "Completing source preparation makes the settlement usable without waiting for an unrelated edit");

        var chainClaims = new com.devc.minecraftempires.territory.ClaimManager();
        claimCorridor(chainClaims, ownerId, 0, 170);
        LogisticsManager chain = savedLogistics(ownerId, java.util.List.of(),
                java.util.List.of(new BlockPos(968, 64, 8), new BlockPos(1928, 64, 8)));
        BlockPos advance = new BlockPos(2728, 64, 8);
        check(chain.inspectSupply(ownerId, advance, chainClaims, states, java.util.List.of()).connected(),
                "Two bounded camp relays extend supply while retaining a path to the settlement root");
        chainClaims.removeClaim(new net.minecraft.world.level.ChunkPos(20, 0));
        check(!chain.inspectSupply(ownerId, advance, chainClaims, states, java.util.List.of()).connected(),
                "An isolated camp chain cannot keep its previous connected status as a supply root");
        source.setSupplyPreparationTicks(1);
        check(!chain.inspectSupply(ownerId, advance, chainClaims, states, java.util.List.of()).connected(),
                "Camp-to-camp reach and circular territory do not create supplies without a ready settlement");
        source.advanceSupplyPreparationTicks(1);
        claimCorridor(chainClaims, ownerId, 20, 20);
        check(chain.inspectSupply(ownerId, advance, chainClaims, states, java.util.List.of()).connected(),
                "Restoring the root corridor rebuilds camp relay eligibility from the source");
        long campStockBefore = chain.getCamps().stream().mapToLong(LogisticsManager.Camp::stockSoldierTicks).sum();
        chain.inspectSupply(ownerId, advance, chainClaims, states, java.util.List.of());
        check(chain.getCamps().stream().mapToLong(LogisticsManager.Camp::stockSoldierTicks).sum() == campStockBefore,
                "Strategic inspection does not distribute/refill camp stock or advance simulation time");
    }

    private static void claimCorridor(com.devc.minecraftempires.territory.ClaimManager claims, UUID owner, int first, int last) {
        for (int x = first; x <= last; x++) claims.setClaim(new net.minecraft.world.level.ChunkPos(x, 0), owner, "", false, 1);
    }

    /** Build a real save fixture so the graph and movement checks also exercise the durable geometry. */
    private static LogisticsManager savedLogistics(UUID owner, java.util.List<BlockPos> road, java.util.List<BlockPos> camps) {
        CompoundTag root = new CompoundTag();
        net.minecraft.nbt.ListTag roads = new net.minecraft.nbt.ListTag();
        if (!road.isEmpty()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", UUID.randomUUID().toString()); entry.putString("Owner", owner.toString());
            entry.put("Points", savedPositions(road)); entry.put("ControlPoints", savedPositions(java.util.List.of(road.getFirst(), road.getLast())));
            roads.add(entry);
        }
        root.put("Roads", roads);
        net.minecraft.nbt.ListTag campTags = new net.minecraft.nbt.ListTag();
        for (BlockPos position : camps) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", UUID.randomUUID().toString()); entry.putString("Owner", owner.toString());
            entry.putLong("Position", position.asLong()); entry.putLong("StockTicks", 3 * StateBalance.TICKS_PER_DAY + 17);
            campTags.add(entry);
        }
        root.put("Camps", campTags);
        return LogisticsManager.fromTag(root);
    }

    private static net.minecraft.nbt.ListTag savedPositions(java.util.List<BlockPos> positions) {
        net.minecraft.nbt.ListTag result = new net.minecraft.nbt.ListTag();
        for (BlockPos position : positions) {
            CompoundTag entry = new CompoundTag(); entry.putLong("Position", position.asLong()); result.add(entry);
        }
        return result;
    }

    private static void roadGeometryAndBoundaryMovement() {
        var controls = java.util.List.of(new BlockPos(-1, 70, -1), new BlockPos(32, 72, 32));
        var expanded = LogisticsManager.expandRoad(controls);
        check(expanded.size() == 7 && net.minecraft.world.level.ChunkPos.containing(expanded.getFirst()).x() == -1,
                "Negative coordinates round to the correct chunk in a deterministic road expansion");
        for (int index = 1; index < expanded.size(); index++) {
            BlockPos before = expanded.get(index - 1), after = expanded.get(index);
            check(Math.abs(before.getX() - after.getX()) + Math.abs(before.getZ() - after.getZ()) == 16,
                    "Every expanded road segment has cardinal connectivity without diagonal gaps");
        }
        boolean rejected = false;
        try { LogisticsManager.expandRoad(java.util.List.of(BlockPos.ZERO, new BlockPos(20_000, 64, 0))); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Oversized road expansion is rejected before an unbounded allocation");
        UUID owner = UUID.randomUUID();
        LogisticsManager saved = savedLogistics(owner, expanded, java.util.List.of(new BlockPos(100, 64, 100)));
        LogisticsManager restored = LogisticsManager.fromTag(saved.toTag());
        check(restored.getRoads().iterator().next().points().equals(expanded)
                        && restored.getRoads().iterator().next().id().equals(saved.getRoads().iterator().next().id()),
                "Reload retains road identity and accepted world-coordinate geometry");
        check(restored.getCamps().iterator().next().stockSoldierTicks() == 3 * StateBalance.TICKS_PER_DAY + 17,
                "Camp stock preserves exact fractional soldier-days across save/reload");
        check(LogisticsManager.lineInterrupted(BlockPos.ZERO, new BlockPos(100, 0, 0), java.util.List.of(new BlockPos(50, 100, 50)))
                        && !LogisticsManager.lineInterrupted(BlockPos.ZERO, new BlockPos(100, 0, 0), java.util.List.of(new BlockPos(50, 0, 51))),
                "Supply interception uses the horizontal segment and includes exactly the 50-block boundary");

        LogisticsManager oneRoad = savedLogistics(owner, java.util.List.of(new BlockPos(8, 64, 8)), java.util.List.of());
        double negativeEntry = oneRoad.movementDistance(16, 8, -1, 0, 0.2, cell -> true);
        check(Math.abs(negativeEntry - 0.3) < 0.00001,
                "Moving negative from an exact boundary immediately receives the entered road's speed");
        double negativeExit = oneRoad.movementDistance(0, 8, -1, 0, 0.2, cell -> true);
        check(Math.abs(negativeExit - 0.2) < 0.00001,
                "Moving negative off a road boundary immediately uses the ordinary terrain speed");
        double split = oneRoad.movementDistance(15.9, 8, 1, 0, 0.2, cell -> true);
        check(Math.abs(split - (0.1 + (0.2 - 0.1 / StateBalance.ROAD_SPEED_MULTIPLIER))) < 0.00001,
                "A movement interval crossing off-road splits its time between both segment speeds");
        check(Math.abs(oneRoad.movementDistance(8, 8, 1, 0, 0.2, cell -> false) - 0.2) < 0.00001,
                "A road in inaccessible territory cannot grant a movement bonus");
    }

    private static void supplyReservesAndReadiness() {
        long day = StateBalance.TICKS_PER_DAY;
        Cohort cohort = Cohort.createInfantry();
        int startingMorale = cohort.getMorale();
        int trainedEndurance = cohort.getEndurance();
        check(cohort.getCarriedSupplyDays() == 7.0, "Recruitment supplies seven complete active days per soldier");
        cohort.processSupply(7 * day - 13, false, 0);
        cohort = Cohort.fromNBT(cohort.toNBT());
        cohort.processSupply(13, false, 0);
        check(cohort.isSupplyExhausted() && cohort.getMorale() == startingMorale && cohort.getLogisticsFatigue() == 0,
                "Partial-day save/reload must preserve all seven days before shortage begins");
        cohort.processSupply(day - 1, false, 0);
        cohort = Cohort.fromNBT(cohort.toNBT());
        check(cohort.getMorale() == startingMorale, "An incomplete first uncovered day has no daily penalty");
        cohort.processSupply(1, false, 0);
        check(cohort.getMorale() == startingMorale - StateBalance.SUPPLY_MORALE_PENALTY
                        && cohort.getLogisticsFatigue() == StateBalance.SUPPLY_FATIGUE_PER_DAY,
                "Exactly one uncovered day applies exactly one morale and fatigue penalty across reload");
        check(cohort.getSoldierCount() == 50 && cohort.getHealth() == 100,
                "Exhausted supply must not kill soldiers or reduce original formation health");
        check(cohort.getEndurance() == trainedEndurance && cohort.getEffectiveEndurance() == trainedEndurance - StateBalance.SUPPLY_FATIGUE_PER_DAY,
                "Logistics reduces battle readiness while preserving trained endurance");
        check(CohortData.fromCohort(cohort, 0, 0).getEndurance() == cohort.getEffectiveEndurance(),
                "Tactical and autoresolve snapshots receive the same effective endurance");
        cohort.processSupply(10 * day, false, 0);
        check(cohort.getLogisticsFatigue() == StateBalance.MAX_SUPPLY_FATIGUE && cohort.getEffectiveEndurance() >= 1,
                "Prolonged isolation caps fatigue and keeps effective endurance positive");
        cohort.gainXp(200);
        check(cohort.getEndurance() == trainedEndurance + 1 && cohort.getEffectiveEndurance() == trainedEndurance + 1 - StateBalance.MAX_SUPPLY_FATIGUE,
                "Veteran training persists independently of temporary supply fatigue");
        int survivors = cohort.getSoldierCount();
        cohort.processSupply(day / 2, true, 0);
        cohort = Cohort.fromNBT(cohort.toNBT());
        cohort.processSupply(day / 2, true, 0);
        check(cohort.getCarriedSupplyDays() == 1.0 && cohort.getLogisticsFatigue() == StateBalance.MAX_SUPPLY_FATIGUE - StateBalance.SUPPLY_FATIGUE_RECOVERY,
                "Resupply refills one reserve day and one recovery interval, including saved fractions");
        check(cohort.getSoldierCount() == survivors && cohort.getHealth() == 100,
                "Supply recovery cannot create replacement soldiers");

        Cohort campFed = Cohort.createInfantry();
        campFed.setCarriedSupplySoldierTicks(0);
        long halfDemand = campFed.getSoldierCount() * day / 2;
        check(campFed.processSupply(day, false, halfDemand) == halfDemand,
                "A partially stocked camp spends only the stock allocated to actual demand");
        check(campFed.getUnsupportedTicks() == day / 2 && campFed.getMorale() == 50,
                "Partial camp support preserves the fractional uncovered day without an early penalty");
        campFed = Cohort.fromNBT(campFed.toNBT());
        check(campFed.processSupply(day, false, halfDemand) == halfDemand && campFed.getMorale() == 45,
                "Two half-covered days conserve camp stock and combine into one uncovered-day penalty");
        campFed.processSupply(day, false, 2 * halfDemand);
        check(campFed.getCarriedSupplySoldierTicks() == 0 && campFed.getLogisticsFatigue() == 0,
                "Adequate camp stock restores readiness but never refills carried reserve");
        check(campFed.processSupply(20, true, 1_000_000) == 0,
                "A network-supplied cohort cannot also consume camp stock");

        Cohort depleted = Cohort.createInfantry();
        depleted.applyAttrition(20);
        check(depleted.getSoldierCount() == 30 && depleted.getCarriedSupplySoldierTicks() == depleted.getMaxCarriedSupplySoldierTicks()
                        && depleted.getCarriedSupplyDays() == 7.0,
                "Casualties discard excess reserve instead of creating transferable surplus");
        depleted.setCarriedSupplySoldierTicks(0);
        check(Cohort.fromNBT(depleted.toNBT()).getCarriedSupplySoldierTicks() == 0,
                "Reloading an exhausted cohort must not grant migration supplies again");
        depleted.processSupply(day, true, 0);
        check(depleted.getSoldierCount() == 30 && depleted.getHealth() == 60,
                "Resupply preserves combat losses and original maximum strength");
        CompoundTag legacy = depleted.toNBT();
        legacy.remove("LogisticsVersion");
        legacy.remove("CarriedSupplySoldierTicks");
        check(Cohort.fromNBT(legacy).getCarriedSupplyDays() == 7.0,
                "A legacy cohort receives the safe starting reserve on migration");
    }

    private static void settlementFootprintMigrationAndPreparation() {
        UUID state = UUID.randomUUID();
        SettlementData fresh = new SettlementData(UUID.randomUUID(), state, "New", BlockPos.ZERO);
        check(fresh.getProtectiveRadius() == 64 && SettlementData.fromNBT(fresh.toNBT()).getProtectiveRadius() == 64,
                "New 64-block footprints survive save/reload");
        SettlementData other = new SettlementData(UUID.randomUUID(), state, "Old", new BlockPos(160, 64, 0));
        check(!StateManager.settlementFootprintsOverlap(fresh, other), "Separated new 64-block settlement footprints are permitted");
        CompoundTag historical = other.toNBT();
        historical.remove("FootprintVersion");
        historical.putInt("Radius", 16);
        SettlementData migrated = SettlementData.fromNBT(historical);
        check(migrated.getProtectiveRadius() == 100,
                "Previously ignored small Radius fields cannot shrink an older effective 100-block footprint");
        check(StateManager.settlementFootprintsOverlap(fresh, migrated),
                "Founding overlap respects an existing legacy footprint alongside the new smaller radius");
        check(StateManager.isInSettlementFootprint(migrated, new net.minecraft.world.level.ChunkPos(3, 0))
                        && !StateManager.isInSettlementFootprint(other, new net.minecraft.world.level.ChunkPos(3, 0)),
                "Capture protection uses each settlement's own saved footprint");
        historical.remove("Radius");
        check(SettlementData.fromNBT(historical).getProtectiveRadius() == 100,
                "A missing historical radius also preserves legacy protection");
        check(SettlementData.fromNBT(migrated.toNBT()).getProtectiveRadius() == 100,
                "Migration writes an explicit footprint marker for later reloads");
        fresh.setSupplyPreparationTicks(StateBalance.CAPTURED_SUPPLY_PREPARATION_TICKS);
        fresh.setLocalSiegeImmunityTicks(StateBalance.CAPTURE_IMMUNITY_TICKS);
        fresh.advanceSupplyPreparationTicks(123);
        fresh = SettlementData.fromNBT(fresh.toNBT());
        check(fresh.getSupplyPreparationTicks() == StateBalance.TICKS_PER_DAY - 123
                        && fresh.getLocalSiegeImmunityTicks() == StateBalance.CAPTURE_IMMUNITY_TICKS,
                "Saved supply preparation advances independently of capture immunity");
        fresh.tickLocalImmunity();
        check(fresh.getSupplyPreparationTicks() == StateBalance.TICKS_PER_DAY - 123,
                "Ordinary immunity ticking cannot bypass offline-paused source preparation");
        fresh.advanceSupplyPreparationTicks(StateBalance.TICKS_PER_DAY);
        check(fresh.isSupplyReady() && fresh.getSupplyPreparationTicks() == 0,
                "A fully prepared source clamps at zero and becomes eligible");
    }

    private static void settlementAbandonmentRetainsClaims() {
        var claims = new com.devc.minecraftempires.territory.ClaimManager();
        UUID owner = UUID.randomUUID(), neighbor = UUID.randomUUID();
        String settlement = UUID.randomUUID().toString(), otherSettlement = UUID.randomUUID().toString();
        var inherited = new net.minecraft.world.level.ChunkPos(-4, 3);
        var founded = new net.minecraft.world.level.ChunkPos(-3, 3);
        var captured = new net.minecraft.world.level.ChunkPos(-2, 3);
        var otherTown = new net.minecraft.world.level.ChunkPos(8, 10);
        var unassigned = new net.minecraft.world.level.ChunkPos(9, 10);
        var foreign = new net.minecraft.world.level.ChunkPos(10, 10);
        claims.setClaim(inherited, owner, "", false, 1);
        claims.setClaim(inherited, owner, settlement, true, 2); // Settlement founded on existing state land.
        claims.setClaim(founded, owner, settlement, false, 1); // Land acquired when the town was founded.
        claims.setClaim(captured, neighbor, settlement, true, 2); // Stale association after a foreign capture.
        claims.setClaim(otherTown, owner, otherSettlement, true, 2);
        claims.setClaim(unassigned, owner, "", false, 1);
        claims.setClaim(foreign, neighbor, "", false, 1);
        claims.registerSettlementCenter(settlement, inherited);
        claims.registerSettlementCenter(otherSettlement, otherTown);
        var beforeOtherTown = claims.getClaim(otherTown).toNBT();
        var beforeUnassigned = claims.getClaim(unassigned).toNBT();
        int ownedBefore = claims.getClaimCountForState(owner), foreignBefore = claims.getClaimCountForState(neighbor);
        long revision = claims.getTopologyRevision();
        claims.releaseSettlementClaims(settlement);
        check(claims.isClaimed(inherited) && owner.equals(claims.getClaim(inherited).getOwnerUUID()),
                "Abandoning a settlement returns previously owned land to the owner's unassigned territory");
        check(claims.isClaimed(founded) && owner.equals(claims.getClaim(founded).getOwnerUUID()),
                "Land acquired by founding also remains with the settlement's state");
        check(java.util.List.of(inherited, founded, captured).stream().map(claims::getClaim)
                        .allMatch(c -> c.getSettlementID().isEmpty() && !c.isGarrisoned() && c.getSettlementTier() == 0),
                "Released chunks lose settlement/garrison/tier metadata without losing claims");
        check(neighbor.equals(claims.getClaim(captured).getOwnerUUID()) && neighbor.equals(claims.getClaim(foreign).getOwnerUUID()),
                "Abandonment never takes chunks back from their current foreign owner");
        check(claims.getClaimCountForState(owner) == ownedBefore && claims.getClaimCountForState(neighbor) == foreignBefore
                        && claims.getClaimsView().size() == 6, "Settlement release preserves both states' claim counts and the full territory map");
        check(beforeOtherTown.equals(claims.getClaim(otherTown).toNBT()) && beforeUnassigned.equals(claims.getClaim(unassigned).toNBT()),
                "Other settlements and unrelated unassigned territory retain their original metadata");
        check(!claims.getSettlementCentersView().containsKey(settlement) && claims.getSettlementCentersView().containsKey(otherSettlement),
                "Only the removed settlement's map center disappears");
        check(claims.getTopologyRevision() > revision, "Removing settlement administration invalidates map/supply topology");
        revision = claims.getTopologyRevision();
        claims.releaseSettlementClaims(settlement);
        claims.releaseSettlementClaims(""); claims.releaseSettlementClaims(null);
        check(claims.getTopologyRevision() == revision && beforeUnassigned.equals(claims.getClaim(unassigned).toNBT()),
                "Repeated or empty settlement release cannot clear arbitrary state land or invalidate topology endlessly");
        try {
            var save = claims.getClass().getDeclaredMethod("toTag"); save.setAccessible(true);
            var load = claims.getClass().getDeclaredMethod("fromTag", CompoundTag.class); load.setAccessible(true);
            var restored = (com.devc.minecraftempires.territory.ClaimManager) load.invoke(null, save.invoke(claims));
            check(owner.equals(restored.getClaim(inherited).getOwnerUUID()) && restored.getClaim(inherited).getSettlementID().isEmpty()
                            && !restored.getSettlementCentersView().containsKey(settlement), "Save/reload preserves the returned state land and removed center");
            check(restored.getClaimCountForState(owner) == ownedBefore && restored.getClaimCountForState(neighbor) == foreignBefore,
                    "Claim counts remain correct when rebuilt from the saved released territory");
            restored.clearAllClaimsForState(owner);
            check(restored.getClaimCountForState(owner) == 0 && !restored.isClaimed(inherited)
                            && restored.getClaimCountForState(neighbor) == foreignBefore,
                    "Disbanding the entire state still removes its territory while retaining other states' land");
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Settlement release save/reload", failure); }
        String rebuilt = UUID.randomUUID().toString();
        claims.setClaim(inherited, owner, rebuilt, false, 1);
        claims.registerSettlementCenter(rebuilt, inherited);
        claims.releaseSettlementClaims(rebuilt);
        check(owner.equals(claims.getClaim(inherited).getOwnerUUID()) && claims.getClaimCountForState(owner) == ownedBefore,
                "Founding and abandoning again on retained land does not erode ownership");
    }

    private static void logisticsTopologyRevisions() {
        com.devc.minecraftempires.territory.ClaimManager claims = new com.devc.minecraftempires.territory.ClaimManager();
        UUID firstOwner = UUID.randomUUID(), secondOwner = UUID.randomUUID();
        net.minecraft.world.level.ChunkPos position = new net.minecraft.world.level.ChunkPos(0, 0);
        long revision = claims.getTopologyRevision();
        claims.setClaim(position, firstOwner, "", false, 1);
        check(claims.getTopologyRevision() > revision, "New land invalidates cached supply topology");
        revision = claims.getTopologyRevision();
        claims.setClaim(position, secondOwner, "", false, 1);
        check(claims.getTopologyRevision() > revision && claims.getClaimCountForState(firstOwner) == 0,
                "Transferred land invalidates topology and preserves owner counts");
        revision = claims.getTopologyRevision();
        claims.clearAllClaimsForState(secondOwner);
        check(claims.getTopologyRevision() > revision && !claims.isClaimed(position),
                "Bulk state removal invalidates topology as well as individual edits");
        StateManager states = new StateManager();
        StateData first = states.createState("A", UUID.randomUUID(), StateTier.COUNTY);
        StateData second = states.createState("B", UUID.randomUUID(), StateTier.COUNTY);
        revision = states.getLogisticsRevision();
        states.grantPassage(first.getStateId(), second.getStateId());
        check(states.getLogisticsRevision() > revision, "Passage grants invalidate foreign transit routes");
        revision = states.getLogisticsRevision();
        states.revokePassage(first.getStateId(), second.getStateId());
        check(states.getLogisticsRevision() > revision, "Passage revocation invalidates foreign transit routes");
        revision = states.getLogisticsRevision();
        states.declareWar(first.getStateId(), second.getStateId());
        check(states.getLogisticsRevision() > revision, "War invalidates transit and hostile interception");
    }
    private static void cohortStrengthAndExperience() {
        Cohort c = Cohort.createInfantry();
        c.applyAttrition(10);
        check(c.getHealth() == 80 && c.getSoldierCount() == 40, "40/50 soldiers must display 80% health");
        c.applyAttrition(-10);
        check(c.getSoldierCount() == 40, "Negative attrition cannot replenish soldiers");
        c.gainXp(200);
        check(c.getEndurance() == 51 && c.getStrength() == 51 && c.getSpeed() == 51 && c.getMorale() == 51,
                "XP must rotate once through the four trainable stats");
        check(c.getHealth() == 80 && c.getMaxSoldiers() == 50, "Experience cannot increase health or original formation size");
        UUID battle = UUID.randomUUID();
        c.applyBattleOutcome(battle, 30, 25, true);
        int xp = c.getXp();
        Cohort loaded = Cohort.fromNBT(c.toNBT());
        loaded.applyBattleOutcome(battle, 20, 5, true);
        check(loaded.getSoldierCount() == 30 && loaded.getXp() == xp && loaded.getBattlesParticipated() == 1,
                "Reloading and replaying a battle result must not apply casualties or XP twice");
        Cohort cavalry = Cohort.createCavalrySquadron();
        cavalry.applyAttrition(2);
        check(cavalry.getHealth() == 80, "Cavalry health must use its own original size");
        CompoundTag legacy = c.toNBT();
        legacy.remove("Health");
        check(Cohort.fromNBT(legacy).getHealth() == 60, "Legacy health fields must not override the soldier proportion");
    }
    private static void persistentLocationsAndSuccession() {
        Legion legion = new Legion(UUID.randomUUID(), UUID.randomUUID(), new BlockPos(10, 64, 10));
        for (int i = 0; i < 10; i++) legion.addInfantryCohort(Cohort.createInfantry());
        for (int i = 0; i < 5; i++) legion.addCavalrySquadron(Cohort.createCavalrySquadron());
        check(legion.getTotalStrength() == 550, "Whole regular legion must have 550 soldiers");
        check(!legion.addInfantryCohort(Cohort.createInfantry()), "Regular cohort slots cannot be replenished beyond original roster");
        Cohort first = legion.getInfantryCohorts().getFirst();
        Cohort second = legion.getInfantryCohorts().get(1);
        second.setStoredPosition(new BlockPos(450, 70, -230));
        UUID settlement = UUID.randomUUID();
        second.setIsGarrisoned(true); second.setGarrisonedSettlementId(settlement);
        first.applyAttrition(50);
        check(legion.getMainCohort() == second, "Main cohort must transfer to the next surviving regular formation");
        Legion restored = Legion.fromNBT(legion.toNBT());
        Cohort restoredSecond = restored.getInfantryCohorts().get(1);
        check(restoredSecond.getStoredPosition().equals(second.getStoredPosition()), "Detached location must survive serialization");
        check(restoredSecond.isGarrisoned() && settlement.equals(restoredSecond.getGarrisonedSettlementId()), "Garrison assignment must survive serialization");
        for (Cohort cohort : restored.allCohorts()) cohort.applyAttrition(cohort.getSoldierCount());
        restored.addAuxiliary(Cohort.createAuxiliary("minecraft:plains"));
        check(restored.getTotalStrength() == 50 && !restored.isViable(), "Auto dissolution includes auxiliaries at the 50-soldier boundary");
    }
    private static void grantsAndDiplomacy() {
        StateManager manager = new StateManager();
        UUID leader = UUID.randomUUID();
        StateData a = manager.createState("First", leader, StateTier.EMPIRE);
        StateData b = manager.createState("Second", UUID.randomUUID(), StateTier.COUNTY);
        check(a.getCurrentTier() == StateTier.COUNTY, "Founding always begins as county");
        check(a.getGraceTicksRemaining() == 14 * 24000L, "Founding grants fourteen in-game days");
        check(manager.canClaimFreeLegion(leader), "First legion grant is initially available");
        check(manager.claimFirstLegionGrant(leader) && !manager.claimFirstLegionGrant(leader), "First legion grant can only be consumed once");
        manager.grantPassage(a.getStateId(), b.getStateId());
        check(manager.hasPassage(b.getStateId(), a.getStateId()), "Owner grants guest military passage");
        check(!manager.hasPassage(a.getStateId(), b.getStateId()), "Passage is directional");
        manager.declareWar(a.getStateId(), b.getStateId());
        check(manager.areAtWar(a.getStateId(), b.getStateId()) && manager.areAtWar(b.getStateId(), a.getStateId()), "War must be mutual");
        check(!manager.hasPassage(b.getStateId(), a.getStateId()), "War revokes military passage");
        check(!manager.acceptPeace(a.getStateId(), b.getStateId()), "Peace requires a proposal from the other side");
        manager.offerPeace(a.getStateId(), b.getStateId());
        check(manager.acceptPeace(b.getStateId(), a.getStateId()) && !manager.areAtWar(a.getStateId(), b.getStateId()), "Accepted peace ends war for both states");
        a.setGraceTicksRemaining(12345); a.setUnpaidDays(2);
        StateData loaded = StateData.fromNBT(a.toNBT());
        check(loaded.getGraceTicksRemaining() == 12345 && loaded.getUnpaidDays() == 2, "Economy state survives saves");
    }
    private static void progressionGates() {
        StateManager manager = new StateManager();
        StateData state = manager.createState("County", UUID.randomUUID(), StateTier.COUNTY);
        check(manager.canAcquireChunks(state, 1024, 1), "The old county map size must not cap expansion");
        check(manager.canAcquireChunks(state, 16383, 1), "County can reach city-state milestone");
        check(!manager.canAcquireChunks(state, 16384, 1), "Growth is blocked at unmet milestone");
        check(!manager.qualifiesFor(state, StateTier.CITY_STATE), "City-state requires settlements");
        for (int i = 0; i < 3; i++) {
            SettlementData settlement = new SettlementData(UUID.randomUUID(), state.getStateId(), "Town " + i, new BlockPos(i * 300, 64, 0));
            if (i == 0) settlement.upgradeSettlement();
            manager.registerSettlement(settlement.getSettlementId(), settlement);
        }
        check(manager.qualifiesFor(state, StateTier.CITY_STATE), "One city plus two towns qualifies");
        state.setTotalPopulation(5000);
        check(!manager.qualifiesFor(state, StateTier.KINGDOM), "Kingdom requires population over 5000");
        state.setTotalPopulation(5001);
        check(manager.qualifiesFor(state, StateTier.KINGDOM), "Kingdom threshold uses strict greater than");
        state.setCurrentTier(StateTier.REPUBLIC);
        check(manager.canAcquireChunks(state, 262144, 10000), "Republic expansion has no final size ceiling");
        check(StateTier.REPUBLIC.isPermanent() && StateTier.EMPIRE.isPermanent(), "Both final governments are permanent");
        check(StateTier.REPUBLIC.getMaxLegions() == 40 && StateTier.EMPIRE.getMaxLegions() == 28, "Final legion allocations");
    }
    private static void battleOrdersAndRecovery() {
        BattleSession session = new BattleSession(UUID.randomUUID(), UUID.randomUUID(), 20, 30);
        CohortData a = CohortData.fromCohort(Cohort.createInfantry(), 0, -20);
        CohortData b = CohortData.fromCohort(Cohort.createInfantry(), 0, 20);
        session.addAttackerCohort(a); session.addDefenderCohort(b);
        check(!session.issueOrder(a.getCohortId(), 0, 20, true), "Deployment cannot place units on enemy side");
        check(!session.issueOrder(a.getCohortId(), Double.NaN, -20, true), "Non-finite movement is rejected");
        for (int i = 0; i < 119; i++) session.tick(false, false);
        check(session.getPhase() == BattleSession.BattlePhase.DEPLOYMENT, "Deployment lasts thirty seconds");
        session.tick(false, false);
        check(session.getPhase() == BattleSession.BattlePhase.ENGAGEMENT, "Engagement begins after 120 five-tick steps");
        check(session.issueOrder(a.getCohortId(), 5, -10, true) && session.issueOrder(a.getCohortId(), 10, -5, false), "Shift order queue remains available");
        check(a.getMovementWaypoints().size() == 2, "Queued order preserves prior destination");
        a.setEngaged(true);
        check(!session.issueOrder(a.getCohortId(), 20, -5, true), "Engaged units cannot receive ordinary movement");
        a.beginWithdrawal(0, -160);
        double before = a.getPosition().y;
        for (int i = 0; i < 12; i++) a.tickMovement();
        check(a.getPosition().y == before, "Engaged withdrawal has a delay");
        a.tickMovement();
        check(a.getPosition().y < before, "Withdrawal eventually moves out of combat");
        b.applyDamage(15, 10);
        BattleSession recovered = BattleSession.fromNBT(session.toNBT());
        check(recovered.getBattleId().equals(session.getBattleId()) && recovered.getDefenderCohorts().getFirst().getCurrentHealth() == 35,
                "Battle recovery retains the latest casualties and stable identity");
    }
    private static void currentStateAutoresolve() {
        ArmyManager manager = new ArmyManager();
        Army attacker = new Army(UUID.randomUUID(), UUID.randomUUID(), BlockPos.ZERO);
        Army defender = new Army(UUID.randomUUID(), UUID.randomUUID(), BlockPos.ZERO);
        BattleSession session = new BattleSession(attacker.getArmyId(), defender.getArmyId(), 0, 0);
        CohortData a = CohortData.fromCohort(Cohort.createInfantry(), 0, -20);
        CohortData b = CohortData.fromCohort(Cohort.createInfantry(), 0, 20);
        a.applyDamage(49, 0);
        session.addAttackerCohort(a); session.addDefenderCohort(b);
        var result = AutoResolveEngine.resolve(session, attacker, defender, manager);
        check(result.result() == BattleSession.BattleResult.DEFENDER_WINS, "Autoresolve uses current surviving forces");
        check(a.getCurrentHealth() <= 1 && b.getCurrentHealth() <= 50, "Autoresolve never restores previous casualties");
        int remaining = b.getCurrentHealth();
        AutoResolveEngine.resolve(session, attacker, defender, manager);
        check(b.getCurrentHealth() == remaining, "Finished battle cannot be resolved twice");
    }
    private static void atomicArmyAndGarrison() {
        ArmyManager manager = new ArmyManager();
        StateData owner = new StateData(UUID.randomUUID(), "Owner", UUID.randomUUID(), StateTier.KINGDOM);
        Legion legion = manager.raiseLegion(owner, BlockPos.ZERO).orElseThrow();
        check(legion.getTotalSoldiers() == 550, "Recruitment API creates whole legion");
        var ids = legion.getInfantryCohorts().stream().limit(2).map(Cohort::getCohortId).toList();
        SettlementData town = new SettlementData(UUID.randomUUID(), owner.getStateId(), "Town", BlockPos.ZERO);
        check(!manager.changeGarrison(owner.getStateId(), ids, town, false), "Over-capacity garrison rejects whole selection");
        check(ids.stream().noneMatch(id -> manager.resolveCohort(id).isGarrisoned()), "Rejected garrison has no partial assignments");
        town.upgradeSettlement();
        check(manager.changeGarrison(owner.getStateId(), ids, town, false), "City accommodates two full cohorts");
        check(manager.changeGarrison(owner.getStateId(), ids, town, true), "Garrisons release in place");
        check(ids.stream().allMatch(id -> manager.resolveCohort(id).getStoredPosition().equals(BlockPos.ZERO)), "Garrison release preserves locations");
        manager.resolveCohort(ids.get(1)).setStoredPosition(new BlockPos(100, 0, 0));
        check(manager.raiseArmy(owner.getStateId(), BlockPos.ZERO, ids).isEmpty(), "Distant cohort cannot teleport into composition");
        check(ids.stream().noneMatch(id -> manager.resolveCohort(id).isDeployed()), "Failed composition is atomic");
        manager.resolveCohort(ids.get(1)).setStoredPosition(BlockPos.ZERO);
        Army army = manager.raiseArmy(owner.getStateId(), new BlockPos(999, 0, 999), ids).orElseThrow();
        check(army.getStoredPosition().equals(BlockPos.ZERO), "Composition ignores client-provided teleport destination");
        army.setCurrentBattleId(UUID.randomUUID());
        check(!manager.changeGarrison(owner.getStateId(), ids, town, false), "Cannot remove engaged cohorts into garrisons");
        check(!manager.disbandArmy(army.getArmyId(), false), "Cannot release engaged army");
        army.setCurrentBattleId(null);
        check(manager.disbandArmy(army.getArmyId(), false), "Peacetime unengaged army can be released");
        check(manager.canDissolveLegion(legion), "Uncommitted legion permits voluntary dissolution");
    }
    private static void campaignWrapperAndMigration() {
        ArmyManager manager = new ArmyManager();
        StateData owner = new StateData(UUID.randomUUID(), "Owner", UUID.randomUUID(), StateTier.COUNTY);
        Legion legion = manager.raiseLegion(owner, new BlockPos(25, 64, -40)).orElseThrow();
        var ids = legion.allCohorts().stream().map(Cohort::getCohortId).toList();
        Army army = manager.raiseArmy(owner.getStateId(), BlockPos.ZERO, ids).orElseThrow();
        Campaign campaign = manager.createCampaign(owner.getStateId(), army.getArmyId());
        check(!manager.disbandArmy(army.getArmyId(), false), "Active campaign blocks wrapper release");
        for (UUID id : ids) { army.removeCohortId(id); manager.resolveCohort(id).setAssignedArmyId(null); }
        manager.runGarbageCollection();
        check(manager.getArmy(army.getArmyId()) != null && !manager.canDissolveLegion(legion), "Empty campaign wrapper retains commitment to detached legion");
        army.addWaypoint(new BlockPos(600, 64, 600));
        ArmyManager restored = ArmyManager.fromTag(manager.toTag());
        Legion loadedLegion = restored.getLegion(legion.getLegionId());
        check(!restored.canDissolveLegion(loadedLegion), "Campaign commitment persists across saves");
        restored.getCampaign(campaign.getCampaignId()).disbandCampaign(restored);
        check(restored.getArmy(army.getArmyId()).getWaypoints().isEmpty(), "Ending campaign cancels pending marches before wrapper release");
        restored.runGarbageCollection();
        check(restored.getArmy(army.getArmyId()) != null && !restored.canDissolveLegion(loadedLegion), "Ending campaign still requires explicit wrapper release");
        check(restored.disbandArmy(army.getArmyId(), false) && restored.canDissolveLegion(loadedLegion), "Ended campaign plus wrapper release permits legion dissolution");
        Cohort detached = loadedLegion.getInfantryCohorts().get(4);
        detached.setStoredPosition(new BlockPos(987, 65, -654));
        restored = ArmyManager.fromTag(restored.toTag());
        check(restored.resolveCohort(detached.getCohortId()).getStoredPosition().equals(new BlockPos(987, 65, -654)), "Manager save preserves detached locations");
        StateManager states = new StateManager();
        StateData first = states.createState("First", owner.getLeaderId(), StateTier.COUNTY);
        states.claimFirstLegionGrant(owner.getLeaderId());
        StateData replacement = states.createState("Replacement", owner.getLeaderId(), StateTier.COUNTY);
        check(replacement.getGraceTicksRemaining() == 0 && !states.canClaimFreeLegion(owner.getLeaderId()), "Re-founding does not reset grants or introductory time");
        try {
            var write = StateManager.class.getDeclaredMethod("toTag"); write.setAccessible(true);
            var read = StateManager.class.getDeclaredMethod("fromTag", CompoundTag.class); read.setAccessible(true);
            StateManager reloaded = (StateManager) read.invoke(null, write.invoke(states));
            check(!reloaded.canClaimFreeLegion(owner.getLeaderId()), "Grant consumption survives manager serialization");
        } catch (ReflectiveOperationException e) { throw new AssertionError("State save roundtrip", e); }
    }
    private static void footprintsAndLargeBattle() {
        check(StateManager.settlementFootprintsOverlap(BlockPos.ZERO, new BlockPos(201, 0, 0)), "Adjacent block footprints may still overlap after chunk rounding");
        check(!StateManager.settlementFootprintsOverlap(BlockPos.ZERO, new BlockPos(212, 0, 0)), "Separated settlement chunk footprints are permitted");
        check(StateManager.isInSettlementFootprint(BlockPos.ZERO, new net.minecraft.world.level.ChunkPos(-7, 0)), "Negative chunk rounding includes protection edge");
        BattleSession session = new BattleSession(UUID.randomUUID(), UUID.randomUUID(), 0, 0);
        for (int i = 0; i < 150; i++) session.addAttackerCohort(CohortData.fromCohort(Cohort.createInfantry(), 0, -20));
        for (BattleSession.Formation formation : BattleSession.Formation.values()) {
            session.applyFormation(true, formation);
            check(session.getAttackerCohorts().stream().allMatch(c -> Math.abs(c.getPosition().x) <= 160 && c.getPosition().y <= -8 && c.getPosition().y >= -100), "Maximum army formation stays in its deployment zone: " + formation);
            check(separatedBodies(session.getAttackerCohorts()), "Maximum army formation preserves unit spacing: " + formation);
        }
        BattleSession battle = new BattleSession(UUID.randomUUID(), UUID.randomUUID(), 0, 0);
        battle.addAttackerCohort(CohortData.fromCohort(Cohort.createInfantry(), 0, -20));
        battle.addDefenderCohort(CohortData.fromCohort(Cohort.createInfantry(), 0, 20));
        for (int i = 0; i < 3000 && battle.isActive(); i++) battle.tick();
        check(!battle.isActive(), "Autonomous opponents advance and finish a battle");
    }
    private static void fourteenWaivedBills() {
        StateData state = new StateData(UUID.randomUUID(), "Grace", UUID.randomUUID(), StateTier.COUNTY);
        state.addFunds(10000);
        for (int day = 1; day <= 14; day++) {
            // Simulate the 1199 earlier timer updates, then the shared daily billing tick.
            state.setGraceTicksRemaining(state.getGraceTicksRemaining() - 23980);
            check(EconomyTickHandler.chargeUpkeep(state, 600), "Introductory day " + day + " counts as paid");
            check(state.getTreasuryBalance() == 10000 && state.getUnpaidDays() == 0, "Introductory bill " + day + " is waived without debt");
            state.setGraceTicksRemaining(state.getGraceTicksRemaining() - 20);
        }
        check(state.getGraceTicksRemaining() == 0, "Fourteenth day exhausts introductory time");
        check(EconomyTickHandler.chargeUpkeep(state, 600) && state.getTreasuryBalance() == 9400, "First post-grace bill charges normal upkeep");
        state.deductFunds(state.getTreasuryBalance());
        check(!EconomyTickHandler.chargeUpkeep(state, 600) && state.getUnpaidDays() == 1, "Unpaid upkeep begins the morale/desertion sequence");
    }
    private static void logisticsNetworkRoundTrips() {
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            UUID state = UUID.randomUUID(), cohort = UUID.randomUUID(), camp = UUID.randomUUID(), source = UUID.randomUUID();
            var points = java.util.List.of(new BlockPos(-8, 64, 8), new BlockPos(8, 64, 8), new BlockPos(24, 64, 8));
            var supply = new com.devc.minecraftempires.network.packet.ManagementDataPayload.CohortSupplyEntry(
                    cohort, "Using camp stock", "Route unavailable", "Camp stores", null, camp, 384.5, 6.25, 20, 31);
            var settlement = new com.devc.minecraftempires.network.packet.ManagementDataPayload.SettlementEntry(
                    source, "Preparing town", points.getFirst(), 1, 100, 50, false, 23_975L);
            var payload = new com.devc.minecraftempires.network.packet.ManagementDataPayload(state, "County", "COUNTY", 1000,
                    0, 600, 550, false, false, java.util.List.of(settlement), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                    java.util.List.of(supply), java.util.List.of(new com.devc.minecraftempires.network.packet.ManagementDataPayload.CampEntry(
                            camp, points.getLast(), 1099L, 1100L, false)),
                    new com.devc.minecraftempires.network.packet.ManagementDataPayload.SelectedSupplyRoute(cohort, points, true));
            var managementCodec = com.devc.minecraftempires.network.packet.ManagementDataPayload.STREAM_CODEC;
            managementCodec.encode(buffer, payload);
            check(payload.equals(managementCodec.decode(buffer)),
                    "Logistics snapshots retain partial reserves, effective endurance, private camp stock, preparation and selected-route truncation");
            buffer.clear();
            var action = new com.devc.minecraftempires.network.packet.StateActionPayload("BUILD_ROAD", new UUID(0, 0), BlockPos.ZERO, "", points);
            var actionCodec = com.devc.minecraftempires.network.packet.StateActionPayload.STREAM_CODEC;
            actionCodec.encode(buffer, action);
            check(action.equals(actionCodec.decode(buffer)), "Road actions retain typed world-coordinate geometry through the codec");
            buffer.clear();
            var road = UUID.randomUUID();
            // Repeated IDs represent disconnected visible runs, so fog cannot invent a line across hidden territory.
            var map = new com.devc.minecraftempires.network.packet.MapDataPayload(state, "County", java.util.List.of(),
                    java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(
                    new com.devc.minecraftempires.network.packet.MapDataPayload.RoadSummary(road, state, points.subList(0, 2)),
                    new com.devc.minecraftempires.network.packet.MapDataPayload.RoadSummary(road, state, java.util.List.of(new BlockPos(104, 64, 8)))),
                    java.util.List.of(new com.devc.minecraftempires.network.packet.MapDataPayload.CampSummary(camp, state, points.getLast())), true);
            var mapCodec = com.devc.minecraftempires.network.packet.MapDataPayload.STREAM_CODEC;
            mapCodec.encode(buffer, map);
            check(map.equals(mapCodec.decode(buffer)), "Map infrastructure retains separate visible runs and an explicit overlay limit indicator");
            check(java.util.Arrays.stream(com.devc.minecraftempires.network.packet.MapDataPayload.CampSummary.class.getRecordComponents())
                            .noneMatch(field -> field.getName().equals("stock") || field.getName().equals("sourceId") || field.getName().equals("connected")),
                    "Public foreign camp markers cannot serialize private stores or supply-source connectivity");
            buffer.clear();
            var empty = com.devc.minecraftempires.network.packet.ManagementDataPayload.empty();
            managementCodec.encode(buffer, empty);
            check(empty.equals(managementCodec.decode(buffer)) && empty.selectedRoute().targetId() == null,
                    "An empty management snapshot clears logistics and selected routes through the same codec");
        } finally { buffer.release(); }
    }

    private static void logisticsPacketBounds() {
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            boolean rejected = false;
            try {
                new com.devc.minecraftempires.network.packet.StateActionPayload("BUILD_ROAD", new UUID(0, 0), BlockPos.ZERO, "",
                        java.util.Collections.nCopies(65, BlockPos.ZERO));
            } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Road submissions cannot exceed 64 control points even when constructed directly");
            for (int count : new int[] {-1, 65}) {
                buffer.clear(); buffer.writeUtf("BUILD_ROAD", 32); buffer.writeUUID(new UUID(0, 0));
                buffer.writeBlockPos(BlockPos.ZERO); buffer.writeUtf("", 80); buffer.writeVarInt(count);
                rejected = false;
                try { com.devc.minecraftempires.network.packet.StateActionPayload.STREAM_CODEC.decode(buffer); }
                catch (IllegalArgumentException expected) { rejected = true; }
                check(rejected, "Malformed road geometry count is rejected before allocating coordinates: " + count);
            }
            rejected = false;
            try {
                new com.devc.minecraftempires.network.packet.ManagementDataPayload.SelectedSupplyRoute(UUID.randomUUID(),
                        java.util.Collections.nCopies(2049, BlockPos.ZERO), false);
            } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Selected-unit routes enforce the 2048-point display bound");
            buffer.clear(); buffer.writeBoolean(false); buffer.writeUtf(""); buffer.writeVarInt(-1);
            rejected = false;
            try { com.devc.minecraftempires.network.packet.MapDataPayload.STREAM_CODEC.decode(buffer); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Negative map list sizes fail before allocation or unrelated reads");

            buffer.clear(); buffer.writeBoolean(false); buffer.writeUtf(""); buffer.writeVarInt(Integer.MAX_VALUE);
            rejected = false;
            try { com.devc.minecraftempires.network.packet.MapDataPayload.STREAM_CODEC.decode(buffer); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "A declared map count larger than the actual remaining packet is rejected before allocation");

            buffer.clear();
            UUID listOwner = UUID.randomUUID();
            var legacyRun = new com.devc.minecraftempires.network.packet.MapDataPayload.MapChunkData(0L, 1, listOwner, "", false, 0, false);
            var fragmented = new com.devc.minecraftempires.network.packet.MapDataPayload(listOwner, "Large map",
                    java.util.Collections.nCopies(65_537, legacyRun), java.util.List.of(), java.util.List.of(), java.util.List.of());
            var mapCodec = com.devc.minecraftempires.network.packet.MapDataPayload.STREAM_CODEC;
            mapCodec.encode(buffer, fragmented);
            check(fragmented.equals(mapCodec.decode(buffer)),
                    "Legacy map lists above 65536 entries round-trip when the packet actually contains all records");

            buffer.clear(); buffer.writeBoolean(false); buffer.writeUtf("");
            for (int list = 0; list < 4; list++) buffer.writeVarInt(0);
            buffer.writeVarInt(2);
            UUID road = UUID.randomUUID(), owner = UUID.randomUUID();
            for (int part = 0; part < 2; part++) {
                buffer.writeUUID(road); buffer.writeUUID(owner); buffer.writeVarInt(4097);
                for (int point = 0; point < 4097; point++) buffer.writeBlockPos(new BlockPos(point, 64, part));
            }
            rejected = false;
            try { com.devc.minecraftempires.network.packet.MapDataPayload.STREAM_CODEC.decode(buffer); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Splitting infrastructure into several records cannot bypass the total 8192-point map limit");
        } finally { buffer.release(); }
    }

    private static void networkRoundTrips() {
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            UUID state = UUID.randomUUID(), legion = UUID.randomUUID(), cohort = UUID.randomUUID(), settlement = UUID.randomUUID();
            var entry = new com.devc.minecraftempires.network.packet.ManagementDataPayload.CohortEntry(cohort, legion,
                    "L1 Cohort 1", "INFANTRY", new BlockPos(10, 64, -30), 40, 50, 80, 60, 50, 51, 52, 150, "IRON", null, settlement);
            var payload = new com.devc.minecraftempires.network.packet.ManagementDataPayload(state, "County", "COUNTY", 1000,
                    14, 600, 550, true, false, java.util.List.of(), java.util.List.of(), java.util.List.of(entry), java.util.List.of());
            var codec = com.devc.minecraftempires.network.packet.ManagementDataPayload.STREAM_CODEC;
            codec.encode(buffer, payload);
            check(payload.equals(codec.decode(buffer)), "Management snapshots round-trip all cohort stats, positions and optional assignments");
            buffer.clear();
            var batch = new com.devc.minecraftempires.network.packet.GarrisonCohortPayload(java.util.List.of(cohort, UUID.randomUUID()), settlement, false);
            var garrisonCodec = com.devc.minecraftempires.network.packet.GarrisonCohortPayload.STREAM_CODEC;
            garrisonCodec.encode(buffer, batch);
            check(batch.equals(garrisonCodec.decode(buffer)), "Atomic garrison selection survives the network codec");
            buffer.clear();
            var action = new com.devc.minecraftempires.network.packet.StateActionPayload("FOUND", new UUID(0, 0), BlockPos.ZERO, "A County");
            var stateCodec = com.devc.minecraftempires.network.packet.StateActionPayload.STREAM_CODEC;
            stateCodec.encode(buffer, action);
            check(action.equals(stateCodec.decode(buffer)), "Founding action round-trips");
            buffer.clear();
            buffer.writeVarInt(151);
            boolean rejected = false;
            try { garrisonCodec.decode(buffer); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Oversized garrison selections are rejected before allocation");
        } finally { buffer.release(); }
    }
}
