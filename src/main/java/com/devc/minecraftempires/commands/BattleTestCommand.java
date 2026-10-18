package com.devc.minecraftempires.commands;

import com.devc.minecraftempires.army.Army;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Legion;
import com.devc.minecraftempires.state.StateTier;
import com.devc.minecraftempires.combat.BattleManager;
import com.devc.minecraftempires.combat.BattleSession;
import com.devc.minecraftempires.network.MapDataService;
import net.minecraft.core.BlockPos;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateManager;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;

//test command function
public class BattleTestCommand {
    private static final int[][] SITE_OFFSETS = {{50, 0}, {0, 50}, {-50, 0}, {0, -50}, {36, 36}, {-36, 36}, {36, -36}, {-36, -36}}; //just hardcode poll offsets for now, could be improved later
    private static final int SEARCH_TIMEOUT = 2400;
    private static final Map<ServerLevel, Map<UUID, PendingTest>> PENDING = new WeakHashMap<>();
    private static final class PendingTest {
        final UUID stateId;
        final BlockPos origin;
        final long startedAt;
        long nextAttempt, nextReport;
        int site;
        boolean waiting;
        String phase = "Checking nearby routes";
        PendingTest(ServerPlayer player, StateData state) {
            stateId = state.getStateId(); origin = player.blockPosition().immutable(); startedAt = player.level().getGameTime();
            nextReport = startedAt + 200;
        }
    }

    public static int executeTestBattle(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            StateData state = StateManager.get(player.level()).getStateByPlayer(player.getUUID());
            String error = validate(player, state);
            if (error != null) { tell(player, error); return 0; }
            Map<UUID, PendingTest> jobs = PENDING.computeIfAbsent(player.level(), ignored -> new LinkedHashMap<>());
            PendingTest existing = jobs.get(player.getUUID());
            if (existing != null) {
                tell(player, existing.phase + ". The current request will finish automatically; no extra forces have been created.");
                return 0;
            }
            PendingTest request = new PendingTest(player, state);
            var result = advance(player, request);
            if (result == MapDataService.Verification.PENDING) {
                jobs.put(player.getUUID(), request);
                request.nextAttempt = player.level().getGameTime() + 20;
                tell(player, "Checking nearby battle sites. The test will start automatically when verified. Stay within 16 blocks of this position; no forces have been created yet.");
            } else if (result != MapDataService.Verification.VERIFIED) noSite(player);
            return result == MapDataService.Verification.VERIFIED ? 1 : 0;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("§c[Minecraft Empires] Command failed: " + e.getMessage()));
            return 0;
        }
    }

    /** Retry the original request without requiring another command or creating duplicate fixtures. */
    public static void tickPending(ServerLevel level) {
        Map<UUID, PendingTest> jobs = PENDING.get(level);
        if (jobs == null || jobs.isEmpty()) return;
        long now = level.getGameTime();
        for (var entry : new ArrayList<>(jobs.entrySet())) {
            PendingTest request = entry.getValue();
            if (now < request.nextAttempt) continue;
            request.nextAttempt = now + 20;
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            jobs.remove(entry.getKey());
            if (player == null || player.level() != level) continue;
            StateData state = StateManager.get(level).getStateByPlayer(player.getUUID());
            String error = validate(player, state);
            if (error != null) { tell(player, error); continue; }
            if (!request.stateId.equals(state.getStateId()) || !ArmyManager.near(player.blockPosition(), request.origin, 16)) {
                tell(player, "Test-battle search canceled because you moved away or changed states. Run /army testbattle at the new position."); continue;
            }
            if (now - request.startedAt >= SEARCH_TIMEOUT) {
                tell(player, "Could not verify a suitable battlefield within two minutes. No forces were created. Try a flatter, more open and explored position."); continue;
            }
            try {
                var result = advance(player, request);
                if (result == MapDataService.Verification.PENDING) {
                    jobs.put(entry.getKey(), request);
                    if (now >= request.nextReport) {
                        request.nextReport = now + 200;
                        tell(player, request.phase + ". The test will start automatically when verified.");
                    }
                } else if (result != MapDataService.Verification.VERIFIED) noSite(player);
            } catch (Exception errorDuringCheck) {
                tell(player, "Test-battle check failed: " + errorDuringCheck.getMessage());
                com.devc.minecraftempires.MinecraftEmpires.LOGGER.warn("Test-battle check failed near {}", request.origin, errorDuringCheck);
            }
        }
    }
    private static String validate(ServerPlayer player, StateData state) {
        if (player.level() != player.level().getServer().overworld()) return "Run test battles in the Overworld.";
        if (state == null) return "You must be in a State to run this test!";
        if (ArmyManager.get(player.level()).getLegionCountForState(state.getStateId()) >= state.getCurrentTier().getMaxLegions())
            return "Your state's legion cap is reached; dissolve an unused legion before creating this fixture.";
        return null;
    }
    /** At most one full deployment preview per attempt; all candidates share the same saved origin. */
    private static MapDataService.Verification advance(ServerPlayer player, PendingTest request) {
        for (int checked = 0; checked < SITE_OFFSETS.length; checked++) {
            int index = request.site++;
            int[] offset = SITE_OFFSETS[index];
            BlockPos candidate = request.origin.offset(offset[0], 0, offset[1]);
            var route = MapDataService.battleRoute(player.level(), request.origin, candidate, request.origin.getX(), request.origin.getZ());
            boolean previewed = route.status() == MapDataService.Verification.VERIFIED;
            var status = previewed ? BattleSession.verifyDeployment(player.level(), request.origin, candidate, Legion.MAX_INFANTRY_COHORTS + Legion.MAX_CAVALRY_SQUADRONS) : route.status();
            request.phase = (previewed ? "Checking room for both full legions" : "Checking nearby routes") + " (site " + (index + 1) + "/" + SITE_OFFSETS.length + ")";
            if (previewed && status == MapDataService.Verification.VERIFIED) {
                createBattle(player, request.origin, candidate); return MapDataService.Verification.VERIFIED;
            }
            request.waiting |= status == MapDataService.Verification.PENDING;
            if (request.site == SITE_OFFSETS.length) {
                request.site = 0;
                boolean waiting = request.waiting; request.waiting = false;
                return waiting ? MapDataService.Verification.PENDING : MapDataService.Verification.BLOCKED;
            }
            if (previewed) return MapDataService.Verification.PENDING;
        }
        return MapDataService.Verification.PENDING;
    }
    private static void noSite(ServerPlayer player) {
        tell(player, "No nearby site has both a verified route and enough usable space for two legions. Try a flatter, more open and explored position.");
    }
    private static void tell(ServerPlayer player, String message) { player.sendSystemMessage(Component.literal("[Minecraft Empires] " + message)); }

    private static void createBattle(ServerPlayer player, BlockPos origin, BlockPos enemySite) {
        StateManager stateManager = StateManager.get(player.level());
        StateData playerState = stateManager.getStateByPlayer(player.getUUID());
        ArmyManager armyManager = ArmyManager.get(player.level());
        // Complete persistent fixtures survive a save/load during the test battle.
        Legion attackingLegion = armyManager.raiseLegion(playerState, origin).orElse(null);
        if (attackingLegion == null) {
            tell(player, "Your state's legion cap is reached; dissolve an unused legion before creating this fixture.");
            return;
        }
        StateData enemyState = stateManager.createState("Battle Test Opponent", UUID.randomUUID(), StateTier.COUNTY);
        Legion defendingLegion = armyManager.raiseLegion(enemyState, enemySite).orElseThrow();
        Army attacker = armyManager.autoWrapLegionInArmy(attackingLegion).orElseThrow();
        Army defender = armyManager.autoWrapLegionInArmy(defendingLegion).orElseThrow();
        stateManager.declareWar(playerState.getStateId(), enemyState.getStateId());
        armyManager.createCampaign(playerState.getStateId(), attacker.getArmyId());
        armyManager.createCampaign(enemyState.getStateId(), defender.getArmyId());

        // 3. Force the Engine: Inject them into BattleManager
        // This will automatically call notifyOnlinePlayers which sends the OpenBattleMapPayload
        var battle = BattleManager.get(player.level()).startBattle(attacker, defender, player.level());

        player.sendSystemMessage(Component.literal(battle == null
                ? "§e[Minecraft Empires] Test forces created. They will engage after a usable route is verified; impassable terrain can keep them apart. The fixture persists in this world."
                : "§a[Minecraft Empires] Test battle started. The opponent state and surviving forces persist in this test world."));
    }
}
