package com.devc.minecraftempires.commands;

import com.devc.minecraftempires.army.Army;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Cohort;
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

            // 1. Mock Data: Generate Player Army
            Army attacker = new Army(UUID.randomUUID(), playerState.getStateId(), player.blockPosition());
            Cohort a1 = Cohort.createInfantry();
            Cohort a2 = Cohort.createInfantry();
            armyManager.registerCohort(a1);
            armyManager.registerCohort(a2);
            attacker.addCohortId(a1.getCohortId());
            attacker.addCohortId(a2.getCohortId());

            // 2. Mock Data: Generate Enemy Army (random state ID)
            Army defender = new Army(UUID.randomUUID(), UUID.randomUUID(), player.blockPosition());
            Cohort d1 = Cohort.createInfantry();
            Cohort d2 = Cohort.createInfantry();
            armyManager.registerCohort(d1);
            armyManager.registerCohort(d2);
            defender.addCohortId(d1.getCohortId());
            defender.addCohortId(d2.getCohortId());

            // 3. Force the Engine: Inject them into BattleManager
            // This will automatically call notifyOnlinePlayers which sends the OpenBattleMapPayload
            BattleManager.get(player.level()).startBattle(attacker, defender, player.level());

            player.sendSystemMessage(Component.literal("§a[Minecraft Empires] Test Battle Initialized! GUI should open."));
            return 1;

        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("§c[Minecraft Empires] Command failed: " + e.getMessage()));
            return 0;
        }
    }
}
