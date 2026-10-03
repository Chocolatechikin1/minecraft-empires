package com.devc.minecraftempires.blocks;

import com.devc.minecraftempires.network.ModNetworking;
import com.devc.minecraftempires.network.packet.OpenSettlementPayload;
import com.devc.minecraftempires.state.StateManager;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.territory.SettlementData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

public class CityAltarBlock extends Block {
    public CityAltarBlock(Properties properties) { super(properties); }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof ServerPlayer player) openAltar(player, pos);
    }

    private void openAltar(ServerPlayer player, BlockPos pos) {
        if (player.level() != player.level().getServer().overworld()) {
            player.sendSystemMessage(Component.literal("[Minecraft Empires] City Altars can only found settlements in the Overworld."));
            return;
        }
        StateManager manager = StateManager.get(player.level());
        SettlementData settlement = manager.getSettlementByAltarPos(pos);
        if (settlement == null) {
            manager.beginFounding(player, pos);
            return;
        }
        StateData state = manager.getStateByPlayer(player.getUUID());
        if (state == null || !state.getStateId().equals(settlement.getOwningStateId())) {
            player.sendSystemMessage(Component.literal("[Minecraft Empires] This settlement belongs to another state."));
            return;
        }
        ModNetworking.sendSnapshots(player);
        PacketDistributor.sendToPlayer(player, new OpenSettlementPayload(settlement.getSettlementId(), settlement.getSettlementName(), pos));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer) openAltar(serverPlayer, pos);
        return InteractionResult.SUCCESS;
    }
}
