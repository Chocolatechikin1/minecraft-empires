package com.devc.minecraftempires.blocks;

import com.devc.minecraftempires.state.StateManager;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.territory.SettlementData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import java.util.UUID;

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
                player.sendSystemMessage(Component.literal("§c[Empires] You must declare or join a State via commands before placing a City Altar!"));
                
                // Refund the block to the player and safely break it to prevent free claims
                level.destroyBlock(pos, true, player);
                return;
            }

            // 3. Initialize the unique local data model parameters
            UUID settlementId = UUID.randomUUID();
            String settlementName = player.getName().getString() + "'s Holding";
            
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
            player.sendSystemMessage(Component.literal("§6§l[Empires] §aCity Altar Ignited! Established §e" + settlementName + "§a linked to the realm of §b" + playerState.getStateName() + "§a."));
        }
    }
}