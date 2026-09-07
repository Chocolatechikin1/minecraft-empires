package com.devc.minecraftempires.commands;

import com.devc.minecraftempires.army.Army;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Cohort;
import com.devc.minecraftempires.army.Legion;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

public class ArmyCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("army")
                .then(Commands.literal("raise")
                    .then(Commands.literal("test") // Added "test" argument
                        .executes(ArmyCommand::executeRaise)
                    )
                )
                .then(Commands.literal("testbattle")
                    .executes(BattleTestCommand::executeTestBattle)
                )
        );
    }

    private static int executeRaise(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            StateManager stateManager = StateManager.get(player.level());
            StateData playerState = stateManager.getStateByPlayer(player.getUUID());

            if (playerState == null) {
                player.sendSystemMessage(Component.literal("§c[Minecraft Empires] You must be part of a State to raise an army!"));
                return 0;
            }

            ArmyManager armyManager = ArmyManager.get(player.level());
            
            //command will tru to raise a legion at th eplayers position
            Optional<Legion> newLegion = armyManager.raiseLegion(playerState, player.blockPosition());

            if (newLegion.isPresent()) {
                Legion activeLegion = newLegion.get();

                //inject troops to make it viable (survives Garbage Collection)
                Cohort inf1 = Cohort.createInfantry();
                Cohort inf2 = Cohort.createInfantry();
                Cohort cav  = Cohort.createCavalrySquadron();
                activeLegion.addInfantryCohort(inf1);
                activeLegion.addInfantryCohort(inf2);
                activeLegion.addCavalrySquadron(cav);

                //register the cohorts and cavalry squadron with the ArmyManager so they are tracked and not garbage collected
                armyManager.registerCohort(inf1);
                armyManager.registerCohort(inf2);
                armyManager.registerCohort(cav);

                //wrap the legion in an Army so it appears on the map and can be dispatched (TODO: bare legions cannot be interacted with on the map, eventually make it so that they can for the purpose of moving it around in friendly territory)
                Optional<Army> newArmy = armyManager.autoWrapLegionInArmy(activeLegion);

                armyManager.setDirty();

                if (newArmy.isPresent()) {
                    player.sendSystemMessage(Component.literal("§a[Minecraft Empires] Test Army raised! Legion with 2 Cohorts + 1 Cavalry Squadron, now visible on the map."));
                } else {
                    // Legion exists but Army wrapping failed — still useful for data-layer testing
                    player.sendSystemMessage(Component.literal("§e[Minecraft Empires] Test Legion raised, but Army wrap failed. Legion is live but not on the map."));
                }
                return 1;
            } else {
                player.sendSystemMessage(Component.literal("§c[Minecraft Empires] Failed to raise Legion. Legion cap reached."));
                return 0;
            }

        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("§c[Minecraft Empires] Only players can run this command!"));
            return 0;
        }
    }
}