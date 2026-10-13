package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.network.ModNetworking;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateManager;
import com.devc.minecraftempires.state.StateTier;
import com.devc.minecraftempires.territory.SettlementData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.UUID;

/** All state mutations are authenticated against current server ownership. */
public record StateActionPayload(String action, UUID targetId, BlockPos position, String text) implements CustomPacketPayload {
    public static final Type<StateActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "state_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StateActionPayload> STREAM_CODEC = StreamCodec.ofMember(StateActionPayload::write, StateActionPayload::new);
    public StateActionPayload(RegistryFriendlyByteBuf buf) { this(buf.readUtf(32), buf.readUUID(), buf.readBlockPos(), buf.readUtf(80)); }
    private void write(RegistryFriendlyByteBuf buf) { buf.writeUtf(action, 32); buf.writeUUID(targetId); buf.writeBlockPos(position); buf.writeUtf(text, 80); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(StateActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (player.level() != player.level().getServer().overworld()) {
                player.sendSystemMessage(Component.literal("Manage your state from the Overworld."));
                return;
            }
            StateManager manager = StateManager.get(player.level());
            StateData state = manager.getStateByPlayer(player.getUUID());
            if (payload.action.equals("FOUND")) {
                player.sendSystemMessage(Component.literal(manager.confirmFounding(player, payload.position, payload.text)));
                ModNetworking.sendSnapshots(player);
                return;
            }
            if (state == null || !player.getUUID().equals(state.getLeaderId())) {
                player.sendSystemMessage(Component.literal("Only the state leader may change these settings."));
                return;
            }
            String result = switch (payload.action) {
                case "RENAME" -> rename(manager, state, payload.targetId, payload.text);
                case "UPGRADE" -> manager.upgradeSettlement(player.level(), state, payload.targetId);
                case "DEPOSIT" -> deposit(player, state, payload.text);
                case "CHOOSE_REPUBLIC", "CHOOSE_EMPIRE" -> manager.chooseGovernment(player.level(), state,
                        payload.action.equals("CHOOSE_REPUBLIC") ? StateTier.REPUBLIC : StateTier.EMPIRE)
                        ? "Government chosen permanently." : "Your state has not met the requirements for this choice.";
                case "DECLARE_WAR", "GRANT_PASSAGE", "REVOKE_PASSAGE", "OFFER_PEACE", "ACCEPT_PEACE" -> diplomacy(player, manager, state, payload);
                default -> "Unknown state action.";
            };
            manager.setDirty();
            player.sendSystemMessage(Component.literal(result));
            ModNetworking.sendSnapshots(player);
        });
    }
    private static String rename(StateManager manager, StateData state, UUID id, String name) {
        SettlementData settlement = manager.getSettlement(id);
        if (settlement == null || !state.getStateId().equals(settlement.getOwningStateId())) return "You do not own this settlement.";
        String clean = StateManager.cleanName(name);
        if (clean.isBlank()) return "Choose a settlement name first.";
        settlement.setSettlementName(clean);
        return "Settlement renamed to " + clean + ".";
    }
    private static String deposit(ServerPlayer player, StateData state, String amountText) {
        int available = 0;
        // Iterate inventory slots once; armor/offhand emerald stacks also count exactly once.
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.EMERALD)) available += stack.getCount();
        }
        int requested;
        try { requested = amountText.equalsIgnoreCase("all") ? available : Integer.parseInt(amountText); }
        catch (NumberFormatException invalid) { return "Enter a whole number of emeralds."; }
        if (requested <= 0 || requested > available) return "You do not have that many emeralds to deposit.";
        if (requested > Long.MAX_VALUE - state.getTreasuryBalance()) return "The treasury is full.";
        int remaining = requested;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(Items.EMERALD)) continue;
            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            remaining -= taken;
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        state.addFunds(requested);
        return "Deposited " + requested + " emeralds into the treasury.";
    }
    private static String diplomacy(ServerPlayer player, StateManager manager, StateData state, StateActionPayload payload) {
        UUID own = state.getStateId(), otherId = payload.targetId;
        StateData other = manager.getState(otherId);
        if (other == null || otherId.equals(own)) return "Choose another existing state.";
        switch (payload.action) {
            case "DECLARE_WAR" -> {
                if (!manager.declareWar(own, otherId)) return "You are already at war.";
                manager.notifyState(player.level(), otherId, state.getStateName() + " has declared war on your state.");
                return "War declared against " + other.getStateName() + ".";
            }
            case "GRANT_PASSAGE" -> {
                if (manager.areAtWar(own, otherId)) return "Agree to peace before granting passage.";
                manager.grantPassage(own, otherId);
                manager.notifyState(player.level(), otherId, state.getStateName() + " grants your military passage.");
                return "Military passage granted to " + other.getStateName() + ".";
            }
            case "REVOKE_PASSAGE" -> { manager.revokePassage(own, otherId); return "Passage revoked."; }
            case "OFFER_PEACE" -> {
                if (!manager.areAtWar(own, otherId)) return "You are already at peace.";
                manager.offerPeace(own, otherId);
                manager.notifyState(player.level(), otherId, state.getStateName() + " offers peace. Accept in Diplomacy.");
                return "Peace offered to " + other.getStateName() + ".";
            }
            case "ACCEPT_PEACE" -> {
                if (!manager.acceptPeace(own, otherId)) return "There is no peace offer from this state.";
                manager.notifyState(player.level(), otherId, state.getStateName() + " accepted your peace offer.");
                return "Peace agreed with " + other.getStateName() + ".";
            }
            default -> { return "Unknown diplomatic action."; }
        }
    }
}
