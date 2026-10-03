package com.devc.minecraftempires;

import com.devc.minecraftempires.army.*;
import com.devc.minecraftempires.combat.*;
import com.devc.minecraftempires.state.*;
import com.devc.minecraftempires.territory.SettlementData;
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
        System.out.println("Pre-Sprint 7 regressions passed: " + checks + " checks.");
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
