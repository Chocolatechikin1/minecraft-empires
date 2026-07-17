package com.devc.minecraftempires.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class CityAltarBlock extends Block {

    public CityAltarBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        //ensures data handling remains server-side only, and that the placer is a player
        if (!level.isClientSide() && placer instanceof Player player) {
            
            // TODO: In the next step, we will add the logic here to:
            // 1. Check if the player belongs to a State.
            // 2. Check if this chunk is far enough away from enemy borders.
            // 3. Generate the actual SettlementData object.
            
            player.sendSystemMessage(Component.literal("§6City Altar placed! Establishing settlement link..."));
        }
    }
}