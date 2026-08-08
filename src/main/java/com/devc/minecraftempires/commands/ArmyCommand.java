package com.devc.minecraftempires.commands;

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
                
                // 2. Inject troops to make it viable (survives Garbage Collection)
                Cohort inf1 = Cohort.createInfantry();
                Cohort inf2 = Cohort.createInfantry();
                Cohort cav  = Cohort.createCavalrySquadron();
                activeLegion.addInfantryCohort(inf1);
                activeLegion.addInfantryCohort(inf2);
                activeLegion.addCavalrySquadron(cav);

                // 3. Register cohorts in the flat lookup registry
                armyManager.registerCohort(inf1);
                armyManager.registerCohort(inf2);
                armyManager.registerCohort(cav);

                // 4. Save the newly populated legion to disk
                armyManager.setDirty();

                player.sendSystemMessage(Component.literal("§a[Minecraft Empires] Test Legion raised! (2 Cohorts, 1 Squadron)"));
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