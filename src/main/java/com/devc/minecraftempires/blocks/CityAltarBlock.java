package com.devc.minecraftempires.blocks;

import com.devc.minecraftempires.state.StateManager;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.territory.SettlementData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import java.util.UUID;

import com.devc.minecraftempires.territory.ClaimManager; 
import com.devc.minecraftempires.territory.ChunkData; 
import net.minecraft.world.level.ChunkPos; 

public class CityAltarBlock extends Block {

    public CityAltarBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        // Security Guard: Data logic must strictly evaluate only on the Logical Server
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel && placer instanceof Player player) {
            
            // 1. Fetch our global StateManager state machine (FIXED: references serverLevel directly)
            StateManager manager = StateManager.get(serverLevel);
            
            // 2. Resolve the player's overarching macro State profile (FIXED: references manager)
            StateData playerState = manager.getStateByPlayer(player.getUUID());
            
            // Guard Clause: Check if the player is a nomadic citizen without an established country
            if (playerState == null) {
                player.sendSystemMessage(Component.literal("§c[Minecraft Empires] Create or join a state to place an altar."));
                
                // Refund the block to the player and safely break it to prevent free claims
                level.destroyBlock(pos, true, player);
                return;
            }

            ClaimManager claimManager = ClaimManager.get(serverLevel); 
            ChunkPos altarChunkPos = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
            
            if (claimManager.isClaimed(altarChunkPos)) { 
                ChunkData chunkData = claimManager.getClaim(altarChunkPos); 
                // If it's claimed, and the owner is NOT this player's state, block it. 
                if (!chunkData.getOwnerUUID().equals(playerState.getStateId())) { 
                    player.sendSystemMessage(Component.literal("§c[Minecraft Empires] You cannot establish a City Altar inside another state's territory!")); 
                    level.destroyBlock(pos, true, player); 
                    return; 
                } 
            } 

            // 3. Initialize the unique local data model parameters
            UUID settlementId = UUID.randomUUID();
            String settlementName = player.getName().getString() + "'s Outpost";
            
            SettlementData freshSettlement = new SettlementData(
                settlementId, 
                playerState.getStateId(), 
                settlementName, 
                pos
            );
            
            // 4. Register links inside both the macro State structure and the global system mapping
            playerState.addSettlement(settlementId);
            manager.registerSettlement(settlementId, freshSettlement);
            
            // 5. Fire off the geometric abstract chunk claims matrix around the altar block
            manager.establishSettlementClaims(serverLevel, freshSettlement, playerState.getStateId());
            
            // 6. Provide clear visual confirmation feedback to the player
            player.sendSystemMessage(Component.literal("§6§l[Minecraft Empires] §aEstablished §e" + settlementName + "§a linked to the realm of §b" + playerState.getStateName() + "§a."));
        }
    }

    // ── Settlement management screen (Phase 2) ────────────────────────────────
    // Right-clicking the altar opens SettlementManagementScreen.
    // The client looks up the settlement from its ClientMapData cache using the altar's
    // chunk position — no server roundtrip is needed just to open the screen.
    // TODO (Phase 2 implementation): fill in the client-side lookup and screen open.
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level,
            BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            // TODO: look up settlement from ClientMapData by ChunkPos(pos).toLong()
            // TODO: if found → open SettlementManagementScreen(id, name, pos)
            // TODO: if not found → send chat message asking player to open the Empire Map first
        }
        // Consume the interaction on both sides so vanilla doesn't cancel the client's screen open
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}