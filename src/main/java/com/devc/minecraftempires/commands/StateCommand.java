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
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

//current commands: 
// /state create <name> - creates a new state with the given name, with the player as the leader   
// /state info - displays information about the state the player leads (if any)
// /state disband - disbanded the state the player leads (if any)
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
            .then(Commands.literal("disband")
                .executes(StateCommand::disbandState)
            )
            .then(Commands.literal("leave")
                .executes(StateCommand::leaveState)
            )
        );
    }

    private static int createState(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        if (source.getPlayer() != null) {
            UUID leaderId = source.getPlayer().getUUID();
            StateManager manager = StateManager.get((ServerLevel) source.getLevel());
            
            //bug/exploit fix: prevent infinite state creation
            StateData existingState = manager.getStateByPlayer(leaderId);
            if (existingState != null) {
                source.sendFailure(Component.literal("§c[Minecraft Empires] You are already part of a state! You must leave or /state disband it first."));
                return 0; //Cancel the command execution
            }
            // Create a base Tier 1 (County) State for testing and save the returned data object
            StateData newState = manager.createState(name, leaderId, StateTier.COUNTY);
            
            // NEW MAPPING FIX: Explicitly add player to the map so CityAltarBlock can validate them
            manager.addPlayerToState(leaderId, newState.getStateId());
            
            source.sendSuccess(() -> Component.literal("§a[Minecraft Empires] Successfully founded the Realm of " + name + "!"), false);
            return 1;
        }
        return 0;
    }

    private static int stateInfo(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (source.getPlayer() != null) {
            UUID playerId = source.getPlayer().getUUID();
            StateManager manager = StateManager.get((ServerLevel) source.getLevel());
            
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

                source.sendSuccess(() -> Component.literal("§6--- State of " + foundState.getStateName() + " ---"), false);
                source.sendSuccess(() -> Component.literal("§eTier: §f" + foundState.getCurrentTier().name()), false);
                source.sendSuccess(() -> Component.literal("§eTreasury: §f" + String.format("%.2f", foundState.getTreasuryBalance()) + " coins"), false);
                source.sendSuccess(() -> Component.literal("§ePopulation: §f" + foundState.getTotalPopulation()), false);
                //dont think we need a siege tick display but if we do uncomment this line
                //source.sendSuccess(() -> Component.literal("§eSiege Lock Ticks: §f" + foundState.getSiegeImmunityTicks()), false);
            } else {
                source.sendFailure(Component.literal("§c[Minecraft Empires] You do not lead any states! Use /state create <name> to found one."));
            }
            return 1;
        }
        return 0;
    }

    private static int disbandState(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (source.getPlayer() != null) {
            UUID playerId = source.getPlayer().getUUID();
            ServerLevel level = (ServerLevel) source.getLevel();
            StateManager manager = StateManager.get(level);

            StateData existingState = manager.getStateByPlayer(playerId);
            if (existingState == null) {
                source.sendFailure(Component.literal("§c[Minecraft Empires] No state to disband."));
                return 0;
            }

            // Ensure only the leader can destroy the state
            if (!existingState.getLeaderId().equals(playerId)) {
                source.sendFailure(Component.literal("§c[Minecraft Empires] Only current leader can disband the state."));
                return 0;
            }

            String stateName = existingState.getStateName();
            manager.disbandState(existingState.getStateId(), level);
            source.sendSuccess(() -> Component.literal("§a[Minecraft Empires] " + stateName + " successfully disbanded."), true);
            
            return 1;
        }
        return 0;
    }

    private static int leaveState(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (source.getPlayer() != null) {
            UUID playerId = source.getPlayer().getUUID();
            StateManager manager = StateManager.get((ServerLevel) source.getLevel());

            StateData existingState = manager.getStateByPlayer(playerId);
            if (existingState == null) {
                source.sendFailure(Component.literal("§c[Minecraft Empires] You are not currently part of any state."));
                return 0;
            }

            if (existingState.getLeaderId().equals(playerId)) {
                source.sendFailure(Component.literal("§c[Minecraft Empires] You are the leader of this state! You must transfer leadership or use /state disband."));
                return 0;
            }

            manager.leaveState(playerId);
            source.sendSuccess(() -> Component.literal("§a[Minecraft Empires] You have resigned as a citizen of " + existingState.getStateName() + "."), true);
            return 1;
        }
        return 0;
    }
}