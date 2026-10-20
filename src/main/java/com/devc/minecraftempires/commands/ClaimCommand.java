package com.devc.minecraftempires.commands;

import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateManager;
import com.devc.minecraftempires.territory.ChunkData;
import com.devc.minecraftempires.territory.ClaimManager;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Operator-only tools. Ordinary expansion requires a settlement or campaigning army. */
public class ClaimCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("claim").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(c -> edit(c.getSource(), true)));
        dispatcher.register(Commands.literal("unclaim").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(c -> edit(c.getSource(), false)));
    }
    private static int edit(CommandSourceStack source, boolean claim) {
        ServerPlayer player = source.getPlayer();
        if (player == null || player.level() != player.level().getServer().overworld()) return 0;
        StateManager states = StateManager.get(player.level());
        StateData state = states.getStateByPlayer(player.getUUID());
        if (state == null || !player.getUUID().equals(state.getLeaderId())) {
            source.sendFailure(Component.literal("Found a state before using this testing tool."));
            return 0;
        }
        ClaimManager claims = ClaimManager.get(player.level());
        ChunkData data = claims.getClaim(player.chunkPosition());
        if (claim) {
            if (claims.isClaimed(player.chunkPosition()) || !states.canAcquireChunks(state, claims.getClaimCountForState(state.getStateId()), 1)) return 0;
            claims.setClaim(player.chunkPosition(), state.getStateId(), "", false, 0);
        } else {
            if (!state.getStateId().equals(claims.getStrategicOwner(player.chunkPosition()))) return 0;
            if (data != null && data.getSettlementID() != null && !data.getSettlementID().isBlank()) {
                source.sendFailure(Component.literal("Abandon the settlement in its management screen to release this land."));
                return 0;
            }
            claims.removeClaim(player.chunkPosition());
        }
        states.refreshProgression(player.level());
        source.sendSuccess(() -> Component.literal(claim ? "Testing claim added." : "Testing claim removed."), false);
        return 1;
    }
}
