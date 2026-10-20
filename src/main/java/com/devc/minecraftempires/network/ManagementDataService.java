package com.devc.minecraftempires.network;

import com.devc.minecraftempires.army.*;
import com.devc.minecraftempires.state.*;
import com.devc.minecraftempires.network.packet.ManagementDataPayload;
import com.devc.minecraftempires.combat.BattleManager;
import com.devc.minecraftempires.combat.CohortData;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

//network handler for building and sending settlement management data to the client
public final class ManagementDataService {
    private ManagementDataService() {}
    public static ManagementDataPayload.NewsPage buildNews(ServerPlayer player, ManagementDataPayload.NewsRequest request) {
        var level = player.level().getServer().overworld(); var states = StateManager.get(level);
        var state = states.getStateByPlayer(player.getUUID()); UUID viewer = state == null ? null : state.getStateId();
        var page = states.getNews().browse(viewer, states, request.before(), request.through(), WorldNews.MAX_PAGE, request.filter(), request.ownOnly());
        var entries = page.reports().stream().map(report -> {
            var place = states.getKnownSettlement(viewer, report.settlement());
            return new ManagementDataPayload.NewsEntry(report.id(), report.sequence(), report.time(), report.kind(), report.headline(),
                    place == null ? null : place.id(), place == null ? null : place.position());
        }).toList();
        return new ManagementDataPayload.NewsPage(request.requestId(), viewer, request.before(), page.through(), request.filter(), request.ownOnly(),
                page.hasOlder(), page.trimmed(), level.getGameTime(), entries);
    }
    public static ManagementDataPayload build(ServerPlayer player) {
        var level = player.level().getServer().overworld();
        var states = StateManager.get(level);
        var state = states.getStateByPlayer(player.getUUID());
        if (state == null) return ManagementDataPayload.empty();
        var armies = ArmyManager.get(level);
        var logistics = LogisticsManager.get(level);
        var settlements = states.settlementsFor(state).stream()
                .sorted(Comparator.comparing(s -> s.getSettlementName().toLowerCase(Locale.ROOT)))
                .map(s -> new ManagementDataPayload.SettlementEntry(s.getSettlementId(), s.getSettlementName(),
                        s.getCenterAltarPos(), s.getSettlementTier(), s.getLocalPopulation(), s.getGarrisonCapacity(),
                        s.isMartialLaw(), s.getSupplyPreparationTicks(), s.getKind())).toList();
        var legions = new ArrayList<ManagementDataPayload.LegionEntry>();
        var cohorts = new ArrayList<ManagementDataPayload.CohortEntry>();
        var supplies = new ArrayList<ManagementDataPayload.CohortSupplyEntry>();
        Map<UUID, CohortData> fighting = new HashMap<>();
        for (var army : armies.getArmiesForState(state.getStateId())) {
            var battle = army.isEngaged() ? BattleManager.get(level).getBattle(army.getCurrentBattleId()) : null;
            if (battle != null) for (UUID id : army.getDeployedCohortIds()) battle.getCohortById(id).ifPresent(c -> fighting.put(id, c));
        }
        var ordered = armies.getLegionsForState(state.getStateId()).stream().sorted(Comparator.comparing(Legion::getLegionId)).toList();
        int number = 0;
        for (var legion : ordered) {
            number++;
            legions.add(new ManagementDataPayload.LegionEntry(legion.getLegionId(), legion.getStoredPosition(), legion.getTotalStrength()));
            int infantry = 0, cavalry = 0, auxiliary = 0;
            for (var c : legion.allCohorts()) {
                String label;
                if (legion.getInfantryCohorts().contains(c)) label = "L" + number + " Cohort " + (++infantry);
                else if (legion.getCavalrySquadrons().contains(c)) label = "L" + number + " Cavalry " + (++cavalry);
                else label = "L" + number + " Light infantry " + (++auxiliary);
                if (c == legion.getMainCohort()) label += " (main)";
                var live = fighting.get(c.getCohortId());
                cohorts.add(new ManagementDataPayload.CohortEntry(c.getCohortId(), legion.getLegionId(), label, c.getType().name(),
                        c.getStoredPosition(), live == null ? c.getSoldierCount() : live.getCurrentHealth(), c.getMaxSoldiers(),
                        live == null ? c.getHealth() : (int) Math.round(100.0 * live.getCurrentHealth() / live.getMaxHealth()), live == null ? c.getMorale() : live.getMorale(),
                        c.getEndurance(), c.getStrength(), c.getSpeed(), c.getXp(), c.getGearTier().name(),
                        c.getAssignedArmyId(), c.getGarrisonedSettlementId()));
                var supply = logistics.describe(state.getStateId(), c, level);
                supplies.add(new ManagementDataPayload.CohortSupplyEntry(c.getCohortId(), supply.status(),
                        supply.reason(), supply.sourceName(), supply.sourceId(), supply.campId(), supply.cost(),
                        c.getCarriedSupplyDays(), c.getLogisticsFatigue(), c.getEffectiveEndurance()));
            }
        }
        UUID own = state.getStateId();
        var relations = states.getKnownNations(own).stream()
                .sorted(Comparator.comparing(StateManager.KnownNation::name, String.CASE_INSENSITIVE_ORDER))
                .map(s -> {
                    var offer = states.getPlayerConflicts().terms(own, s.id(), level.getGameTime());
                    return new ManagementDataPayload.RelationEntry(s.id(), s.name(), states.areAtWar(own, s.id()),
                        states.hasPassage(s.id(), own), states.hasPassage(own, s.id()), states.hasPeaceOffer(s.id(), own), s.ai(),
                        offer == null ? null : offer.token(), offer == null ? "" : offer.summary());
                }).toList();
        for (var force : armies.getStrategicForcesForState(own)) legions.add(new ManagementDataPayload.LegionEntry(force.id(), force.position(), force.soldiers(), true));
        // Only the owning state receives camp stock and rooted-connection details.
        var camps = logistics.getCamps().stream().filter(c -> own.equals(c.ownerId()))
                .sorted(Comparator.comparing(LogisticsManager.Camp::id))
                .map(c -> new ManagementDataPayload.CampEntry(c.id(), c.position(), c.stock(),
                        StateBalance.CAMP_STOCK_CAPACITY, logistics.isCampConnected(c.id(), level))).toList();
        var selection = logistics.selectedRoute(player);
        var selectedRoute = selection == null ? ManagementDataPayload.SelectedSupplyRoute.empty()
                : new ManagementDataPayload.SelectedSupplyRoute(selection.targetId(),
                        selection.points().stream().limit(ManagementDataPayload.SelectedSupplyRoute.MAX_POINTS).toList(),
                        selection.truncated() || selection.points().size() > ManagementDataPayload.SelectedSupplyRoute.MAX_POINTS);
        var armyEntries = new ArrayList<ManagementDataPayload.ArmyEntry>();
        int armyNumber = 0;
        for (var army : armies.getArmiesForState(own)) {
            var members = cohorts.stream().filter(c -> army.getArmyId().equals(c.armyId())).toList();
            int strength = members.stream().mapToInt(ManagementDataPayload.CohortEntry::soldiers).sum();
            String terrain = army.getDeployedCohortIds().stream().map(armies::resolveCohort).filter(Objects::nonNull)
                    .map(Cohort::getTerrainStatus).filter(s -> !s.isBlank()).findFirst().orElse("");
            String status = army.isEngaged() ? "In battle" : army.isRetreating() ? terrain.isBlank() ? "Retreating" : terrain
                    : !terrain.isBlank() ? terrain : army.getWaypoints().isEmpty() ? "Stationary" : "Marching";
            String release = army.isEngaged() ? "Finish the battle first" : army.isRetreating() ? "Finish the retreat first" : army.isOnCampaign() ? "End the campaign first" : "";
            armyEntries.add(new ManagementDataPayload.ArmyEntry(army.getArmyId(), "Army " + (++armyNumber), army.getStoredPosition(), strength,
                    members.stream().filter(c -> c.settlementId() == null).mapToInt(ManagementDataPayload.CohortEntry::soldiers).sum(),
                    (int) members.stream().filter(c -> c.soldiers() > 0).mapToInt(ManagementDataPayload.CohortEntry::morale).average().orElse(0),
                    strength, army.getCampaignId(), army.getCurrentBattleId(), status, release));
        }
        var campaignEntries = armies.getCampaignsForState(own).stream()
                .sorted(Comparator.comparing(Campaign::isActive).reversed().thenComparing(Campaign::getName, String.CASE_INSENSITIVE_ORDER).thenComparing(Campaign::getCampaignId))
                .map(c -> new ManagementDataPayload.CampaignEntry(c.getCampaignId(), c.getName(), c.getObjective(), c.isActive(), c.getFocusPosition(),
                        c.getArmyIds().stream().sorted().toList(), c.isPlanned() ? armies.campaignStartProblem(c) : "",
                        c.isActive() ? armies.campaignEndProblem(c, level) : "")).toList();
        return new ManagementDataPayload(own, state.getStateName(), state.getCurrentTier().name(), state.getTreasuryBalance(),
                (int) ((state.getGraceTicksRemaining() + StateBalance.TICKS_PER_DAY - 1) / StateBalance.TICKS_PER_DAY),
                EconomyTickHandler.calculateTax(state, states), EconomyTickHandler.calculateMaintenance(state, states, armies),
                states.canClaimFreeLegion(player.getUUID()), state.hasEndgameChoice(), settlements, legions, cohorts, relations,
                supplies, camps, selectedRoute, armyEntries, campaignEntries);
    }
}
