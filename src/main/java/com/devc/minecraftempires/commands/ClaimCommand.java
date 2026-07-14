package com.devc.minecraftempires.commands;

import com.devc.minecraftempires.territory.ClaimManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

public class ClaimCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("claim")
                .executes(ClaimCommand::executeClaim)
        );

        dispatcher.register(
            Commands.literal("unclaim")
                .executes(ClaimCommand::executeUnclaim)
        );
    }

    private static int executeClaim(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            ServerLevel level = player.level();
            ChunkPos pos = player.chunkPosition();

            ClaimManager manager = ClaimManager.get(level);

            if (manager.isClaimed(pos)) {
                player.sendSystemMessage(Component.literal("§cThis chunk is already claimed!"));
                return 0;
            }

            // Claim the chunk (using player UUID, default settlement ID, ungarrisoned, tier 1)
            manager.setClaim(pos, player.getUUID(), "player_settlement", false, 1);
            player.sendSystemMessage(Component.literal("§aSuccessfully claimed chunk at [" + pos.x() + ", " + pos.z() + "]!"));

            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("Only players can run this command!"));
            return 0;
        }
    }

    private static int executeUnclaim(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            ServerLevel level = player.level();
            ChunkPos pos = player.chunkPosition();

            ClaimManager manager = ClaimManager.get(level);

            if (!manager.isClaimed(pos)) {
                player.sendSystemMessage(Component.literal("§cThis chunk is not claimed!"));
                return 0;
            }

            manager.removeClaim(pos);
            player.sendSystemMessage(Component.literal("§eSuccessfully unclaimed chunk at [" + pos.x() + ", " + pos.z() + "]!"));

            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("Only players can run this command!"));
            return 0;
        }
    }
}