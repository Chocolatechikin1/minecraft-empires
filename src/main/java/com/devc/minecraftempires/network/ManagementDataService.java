package com.devc.minecraftempires.network;

import com.devc.minecraftempires.army.*;
import com.devc.minecraftempires.state.*;
import com.devc.minecraftempires.network.packet.ManagementDataPayload;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

//network handler for building and sending settlement management data to the client
public final class ManagementDataService {
    private ManagementDataService() {}
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
                        s.isMartialLaw(), s.getSupplyPreparationTicks())).toList();
        var legions = new ArrayList<ManagementDataPayload.LegionEntry>();
        var cohorts = new ArrayList<ManagementDataPayload.CohortEntry>();
        var supplies = new ArrayList<ManagementDataPayload.CohortSupplyEntry>();
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
                cohorts.add(new ManagementDataPayload.CohortEntry(c.getCohortId(), legion.getLegionId(), label, c.getType().name(),
                        c.getStoredPosition(), c.getSoldierCount(), c.getMaxSoldiers(), c.getHealth(), c.getMorale(),
                        c.getEndurance(), c.getStrength(), c.getSpeed(), c.getXp(), c.getGearTier().name(),
                        c.getAssignedArmyId(), c.getGarrisonedSettlementId()));
                var supply = logistics.describe(state.getStateId(), c, level);
                supplies.add(new ManagementDataPayload.CohortSupplyEntry(c.getCohortId(), supply.status(),
                        supply.reason(), supply.sourceName(), supply.sourceId(), supply.campId(), supply.cost(),
                        c.getCarriedSupplyDays(), c.getLogisticsFatigue(), c.getEffectiveEndurance()));
            }
        }
        UUID own = state.getStateId();
        var relations = states.getAllStates().stream().filter(s -> !s.getStateId().equals(own))
                .sorted(Comparator.comparing(StateData::getStateName, String.CASE_INSENSITIVE_ORDER))
                .map(s -> new ManagementDataPayload.RelationEntry(s.getStateId(), s.getStateName(), states.areAtWar(own, s.getStateId()),
                        states.hasPassage(s.getStateId(), own), states.hasPassage(own, s.getStateId()), states.hasPeaceOffer(s.getStateId(), own))).toList();
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
        return new ManagementDataPayload(own, state.getStateName(), state.getCurrentTier().name(), state.getTreasuryBalance(),
                (int) ((state.getGraceTicksRemaining() + StateBalance.TICKS_PER_DAY - 1) / StateBalance.TICKS_PER_DAY),
                EconomyTickHandler.calculateTax(state, states), EconomyTickHandler.calculateMaintenance(state, states, armies),
                states.canClaimFreeLegion(player.getUUID()), state.hasEndgameChoice(), settlements, legions, cohorts, relations,
                supplies, camps, selectedRoute);
    }
}
