package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.army.LogisticsManager;
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
import java.util.ArrayList;
import java.util.List;

/** All state mutations are authenticated against current server ownership. */
public record StateActionPayload(String action, UUID targetId, BlockPos position, String text,
        List<BlockPos> routePoints) implements CustomPacketPayload {
    public static final Type<StateActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "state_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StateActionPayload> STREAM_CODEC = StreamCodec.ofMember(StateActionPayload::write, StateActionPayload::new);
    public static final int MAX_ROUTE_POINTS = 64;
    public StateActionPayload {
        routePoints = routePoints.stream().map(BlockPos::immutable).toList();
        if (routePoints.size() > MAX_ROUTE_POINTS) throw new IllegalArgumentException("Too many road control points");
    }
    public StateActionPayload(String action, UUID targetId, BlockPos position, String text) {
        this(action, targetId, position, text, List.of());
    }
    public StateActionPayload(RegistryFriendlyByteBuf buf) { this(buf.readUtf(32), buf.readUUID(), buf.readBlockPos(), buf.readUtf(80), readRoute(buf)); }
    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(action, 32); buf.writeUUID(targetId); buf.writeBlockPos(position); buf.writeUtf(text, 80);
        buf.writeVarInt(routePoints.size()); routePoints.forEach(buf::writeBlockPos);
    }
    private static List<BlockPos> readRoute(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ROUTE_POINTS) throw new IllegalArgumentException("Invalid road control point count");
        List<BlockPos> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) points.add(buf.readBlockPos());
        return points;
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Private bridge survey result tied to the player's exact draft, never a client-supplied purchase approval. */
    public record BridgeStatus(UUID planId, UUID ownerId, List<BlockPos> points, String phase, String message) implements CustomPacketPayload {
        public static final Type<BridgeStatus> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "bridge_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BridgeStatus> STREAM_CODEC = StreamCodec.ofMember(BridgeStatus::write, BridgeStatus::new);
        public BridgeStatus {
            points = points.stream().map(BlockPos::immutable).toList();
            if (points.size() > 2) throw new IllegalArgumentException("Invalid bridge endpoints");
        }
        public BridgeStatus(RegistryFriendlyByteBuf buf) { this(buf.readUUID(), buf.readUUID(), readRoute(buf), buf.readUtf(16), buf.readUtf(256)); }
        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeUUID(planId); buf.writeUUID(ownerId); buf.writeVarInt(points.size()); points.forEach(buf::writeBlockPos);
            buf.writeUtf(phase, 16); buf.writeUtf(message, 256);
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void handle(StateActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            // Clearing private inspection is safe in any dimension, including a world-transition callback.
            if (payload.action.equals("SELECT_SUPPLY") && payload.targetId.equals(new UUID(0, 0))) {
                LogisticsManager.clearPlayer(player.getUUID());
                return;
            }
            if (player.level() != player.level().getServer().overworld()) {
                player.sendSystemMessage(Component.literal("Manage your state from the Overworld."));
                return;
            }
            StateManager manager = StateManager.get(player.level());
            StateData state = manager.getStateByPlayer(player.getUUID());
            if (payload.action.equals("CHECK_FOUNDING")) { manager.pollFounding(player, payload.position); return; }
            if (payload.action.equals("FOUND")) {
                player.sendSystemMessage(Component.literal(manager.confirmFounding(player, payload.position, payload.text)));
                ModNetworking.sendSnapshots(player);
                return;
            }
            // Inspecting an owned route is read-only; state members can inspect without leader privileges.
            if (payload.action.equals("SELECT_SUPPLY") && state != null) {
                String result = LogisticsManager.get(player.level()).handleAction(payload.action, player, state,
                        payload.targetId, payload.routePoints);
                if (!result.isBlank()) player.sendSystemMessage(Component.literal(result));
                ModNetworking.sendSupplySelectionSnapshot(player);
                return;
            }
            if (state == null || !player.getUUID().equals(state.getLeaderId())) {
                player.sendSystemMessage(Component.literal("Only the state leader may change these settings."));
                return;
            }
            if (manager.getAIWars().blocks(state.getStateId())) { player.sendSystemMessage(Component.literal("Your nation's handover is being reconciled. Retry shortly.")); return; }
            String result = switch (payload.action) {
                case "RENAME" -> rename(manager, state, payload.targetId, payload.text);
                case "UPGRADE" -> manager.upgradeSettlement(player.level(), state, payload.targetId);
                case "DEPOSIT" -> deposit(player, state, payload.text);
                case "CHOOSE_REPUBLIC", "CHOOSE_EMPIRE" -> manager.chooseGovernment(player.level(), state,
                        payload.action.equals("CHOOSE_REPUBLIC") ? StateTier.REPUBLIC : StateTier.EMPIRE)
                        ? "Government chosen permanently." : "Your state has not met the requirements for this choice.";
                case "DECLARE_WAR", "GRANT_PASSAGE", "REVOKE_PASSAGE", "OFFER_PEACE", "ACCEPT_PEACE" -> diplomacy(player, manager, state, payload);
                case "DEMAND_SURRENDER" -> manager.getPlayerConflicts().demand(player.level(), state.getStateId(), payload.targetId);
                case "ACCEPT_SURRENDER" -> {
                    try { yield manager.getPlayerConflicts().accept(player.level(), state.getStateId(), payload.targetId, UUID.fromString(payload.text)); }
                    catch (IllegalArgumentException invalid) { yield "Invalid surrender token."; }
                }
                case "BUILD_ROAD", "REMOVE_ROAD", "CREATE_CAMP", "DISMANTLE_CAMP", "CHECK_BRIDGE", "BUILD_BRIDGE", "CANCEL_BRIDGE", "REMOVE_BRIDGE" ->
                        LogisticsManager.get(player.level()).handleAction(payload.action, player, state, payload.targetId, payload.routePoints);
                default -> "Unknown state action.";
            };
            manager.setDirty();
            if (!result.isBlank()) player.sendSystemMessage(Component.literal(result));
            ModNetworking.sendSnapshots(player);
        });
    }
    private static String rename(StateManager manager, StateData state, UUID id, String name) {
        SettlementData settlement = manager.getSettlement(id);
        if (settlement == null || !state.getStateId().equals(settlement.getOwningStateId())) return "You do not own this settlement.";
        String clean = StateManager.cleanName(name);
        if (clean.isBlank()) return "Choose a settlement name first.";
        settlement.setSettlementName(clean);
        manager.markLogisticsChanged(); // Refresh the source name retained in cached supply routes.
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
        if (!manager.knowsNation(own, otherId)) return "This nation has not been discovered.";
        if (other.isInitializationPending()) return "This nation is still being initialized.";
        if (manager.getAIWars().blocks(other.getStateId())) return "This nation's military outcome is being reconciled. Retry shortly.";
        switch (payload.action) {
            case "DECLARE_WAR" -> {
                if (!manager.declareWar(player.level(), own, otherId)) return "You are already at war.";
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
                if (!manager.acceptPeace(player.level(), own, otherId)) return "There is no peace offer from this state.";
                return "Peace agreed with " + other.getStateName() + ".";
            }
            default -> { return "Unknown diplomatic action."; }
        }
    }
}
