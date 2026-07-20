package com.devc.minecraftempires.commands;

import com.devc.minecraftempires.territory.ClaimManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;

import com.devc.minecraftempires.state.StateData;  
import com.devc.minecraftempires.state.StateManager;  
import com.devc.minecraftempires.territory.SettlementData;  
import com.devc.minecraftempires.territory.ChunkData;  
import java.util.UUID; 

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
            StateManager stateManager = StateManager.get(level);
            StateData playerState = stateManager.getStateByPlayer(player.getUUID());

            if (playerState == null) {
                player.sendSystemMessage(Component.literal("§c[Minecraft Empires] Create a state to claim territory. Use /state create <name> to begin."));
                return 0;
            }

            if (manager.isClaimed(pos)) {
                player.sendSystemMessage(Component.literal("§c[Minecraft Empires] This chunk is already claimed!"));
                return 0;
            }

            //adjacency check: ensure the player is claiming adjacent to their existing territory
            boolean isAdjacent = false;
            UUID stateId = playerState.getStateId();
            //check all four cardinal directions for adjacency
            ChunkPos[] adjacentChunks = {
                new ChunkPos(pos.x() + 1, pos.z()),
                new ChunkPos(pos.x() - 1, pos.z()),
                new ChunkPos(pos.x(), pos.z() + 1),
                new ChunkPos(pos.x(), pos.z() - 1)
            };

            for (ChunkPos adj : adjacentChunks) {
                if (manager.isClaimed(adj)) { 
                    ChunkData adjData = manager.getClaim(adj); 
                    if (adjData.getOwnerUUID().equals(stateId)) { 
                        isAdjacent = true; 
                        break; 
                    } 
                } 
            }

            if (!isAdjacent) {
                player.sendSystemMessage(Component.literal("§c[Minecraft Empires] You can only claim chunks adjacent to your existing territory!"));
                return 0;
            }

            //nearest settlement attribution
            SettlementData nearestSettlement = null;
            double minDistance = Double.MAX_VALUE;

            //get chunk center position
            int chunkCenterX = (pos.x() << 4) + 8;
            int chunkCenterZ = (pos.z() << 4) + 8;

            //loop through all owned settlements to find the nearest one
            for (UUID sId : playerState.getOwnedSettlements()) {
                SettlementData settlement = stateManager.getSettlement(sId);
                if (settlement != null) {
                    BlockPos center = settlement.getCenterAltarPos();
                    double distance = Math.sqrt(Math.pow(center.getX() - chunkCenterX, 2) + Math.pow(center.getZ() - chunkCenterZ, 2));
                    if (distance < minDistance) {
                        minDistance = distance;
                        nearestSettlement = settlement;
                    }
                }
            }

            //if no settlements, warn player
            if (nearestSettlement == null) {
                player.sendSystemMessage(Component.literal("§c[Minecraft Empires] You must have at least one settlement to claim territory!"));
                return 0;
            }

            //register claim with the nearest settlement's ID
            manager.setClaim(pos, stateId, nearestSettlement.getSettlementId().toString(), false, nearestSettlement.getSettlementTier());

            //checks biome type in the settlement radius
            BlockPos samplePos = new BlockPos(chunkCenterX, 64, chunkCenterZ);
            Holder<Biome> biomeHolder = level.getBiome(samplePos);
            java.util.Optional<ResourceKey<Biome>> biomeKeyOpt = biomeHolder.unwrapKey();
            if (biomeKeyOpt.isPresent()) {
                ResourceKey<Biome> biomeKey = biomeKeyOpt.get();
                // .identifier() returns net.minecraft.resources.Identifier in NeoForge 26.x
                // .getPath() strips the namespace: "minecraft:badlands" -> "badlands"
                Identifier biomeId = biomeKey.identifier();
                nearestSettlement.incrementBiomeTally(biomeId.getPath());
            }

            //mark data as dirty
            stateManager.setDirty();
            manager.setDirty();

            player.sendSystemMessage(Component.literal("§a[Minecraft Empires] Successfully claimed chunk at [" + pos.x() + ", " + pos.z() + "]!"));

            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("§c[Minecraft Empires] Only players can run this command!"));
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
                player.sendSystemMessage(Component.literal("§c[Minecraft Empires] This chunk is not claimed!"));
                return 0;
            }

            manager.removeClaim(pos);
            player.sendSystemMessage(Component.literal("§e[Minecraft Empires] Successfully unclaimed chunk at [" + pos.x() + ", " + pos.z() + "]!"));

            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("§c[Minecraft Empires] Only players can run this command!"));
            return 0;
        }
    }
}