package com.devc.minecraftempires.commands;

import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateManager;
import com.devc.minecraftempires.state.StateTier;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public class StateCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("state")
            .then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(StateCommand::createState)
                )
            )
            .then(Commands.literal("info")
                .executes(StateCommand::stateInfo)
            )
        );
    }

    private static int createState(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        if (source.getPlayer() != null) {
            UUID leaderId = source.getPlayer().getUUID();
            StateManager manager = StateManager.get(source.getLevel());
            
            // Create a base Tier 1 (County) State for testing
            manager.createState(name, leaderId, StateTier.COUNTY);
            
            source.sendSuccess(() -> Component.literal("§aSuccessfully founded the Realm of " + name + "!"), false);
            return 1;
        }
        return 0;
    }

    private static int stateInfo(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (source.getPlayer() != null) {
            UUID playerId = source.getPlayer().getUUID();
            StateManager manager = StateManager.get(source.getLevel());
            
            // Find the state this player leads
            StateData playerState = null;
            for (StateData state : manager.getAllStates()) {
                if (state.getLeaderId() != null && state.getLeaderId().equals(playerId)) {
                    playerState = state;
                    break;
                }
            }
            
            if (playerState != null) {
                final StateData foundState = playerState; //assign to final variable for lambda (preventing "variable used in lambda should be final or effectively final" error)

                source.sendSuccess(() -> Component.literal("§6--- Realm of " + foundState.getStateName() + " ---"), false);
                source.sendSuccess(() -> Component.literal("§eTier: §f" + foundState.getCurrentTier().name()), false);
                source.sendSuccess(() -> Component.literal("§eTreasury: §f" + String.format("%.2f", foundState.getTreasuryBalance()) + " coins"), false);
                source.sendSuccess(() -> Component.literal("§ePopulation: §f" + foundState.getTotalPopulation()), false);
                source.sendSuccess(() -> Component.literal("§eSiege Lock Ticks: §f" + foundState.getSiegeImmunityTicks()), false);
            } else {
                source.sendFailure(Component.literal("§cYou do not lead any states! Use /state create <name> to found one."));
            }
            return 1;
        }
        return 0;
    }
}