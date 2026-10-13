package com.devc.minecraftempires.commands;

import com.devc.minecraftempires.army.Army;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Cohort;
import com.devc.minecraftempires.army.Legion;
import com.devc.minecraftempires.state.StateTier;
import com.devc.minecraftempires.combat.BattleManager;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateManager;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class BattleTestCommand {

    public static int executeTestBattle(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            StateManager stateManager = StateManager.get(player.level());
            StateData playerState = stateManager.getStateByPlayer(player.getUUID());

            if (playerState == null) {
                player.sendSystemMessage(Component.literal("§c[Minecraft Empires] You must be in a State to run this test!"));
                return 0;
            }

            ArmyManager armyManager = ArmyManager.get(player.level());
            // Complete persistent fixtures survive a save/load during the test battle.
            Legion attackingLegion = armyManager.raiseLegion(playerState, player.blockPosition()).orElse(null);
            if (attackingLegion == null) {
                player.sendSystemMessage(Component.literal("Your state's legion cap is reached; dissolve an unused legion before creating this fixture."));
                return 0;
            }
            StateData enemyState = stateManager.createState("Battle Test Opponent", UUID.randomUUID(), StateTier.COUNTY);
            Legion defendingLegion = armyManager.raiseLegion(enemyState, player.blockPosition().offset(50, 0, 0)).orElseThrow();
            Army attacker = armyManager.autoWrapLegionInArmy(attackingLegion).orElseThrow();
            Army defender = armyManager.autoWrapLegionInArmy(defendingLegion).orElseThrow();
            stateManager.declareWar(playerState.getStateId(), enemyState.getStateId());
            armyManager.createCampaign(playerState.getStateId(), attacker.getArmyId());
            armyManager.createCampaign(enemyState.getStateId(), defender.getArmyId());

            // 3. Force the Engine: Inject them into BattleManager
            // This will automatically call notifyOnlinePlayers which sends the OpenBattleMapPayload
            BattleManager.get(player.level()).startBattle(attacker, defender, player.level());

            player.sendSystemMessage(Component.literal("§a[Minecraft Empires] Test battle started. The opponent state and surviving forces persist in this test world."));
            return 1;

        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("§c[Minecraft Empires] Command failed: " + e.getMessage()));
            return 0;
        }
    }
}
