package com.devc.minecraftempires.client;

import com.devc.minecraftempires.client.gui.screen.*;
import com.devc.minecraftempires.client.map.*;
import com.devc.minecraftempires.network.packet.*;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import java.util.UUID;

public final class ClientNetworking {
    private ClientNetworking() {}
    public static void registerPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(StateActionPayload.BridgeStatus.TYPE, (p, c) -> c.enqueueWork(() -> ClientMapData.acceptBridgeStatus(p)));
        event.register(ArmyActionPayload.RoutePreview.TYPE, (p, c) -> c.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            var previous = mc.gui.screen();
            mc.gui.setScreen(new RouteScreen(previous, p));
        }));
        event.register(MapDataPayload.TYPE, (p, c) -> c.enqueueWork(() -> ClientMapData.accept(p)));
        event.register(MapDataPayload.TerrainPayload.TYPE, (p, c) -> c.enqueueWork(() -> ClientMapData.acceptTerrain(p)));
        event.register(ArmyMapPayload.TYPE, (p, c) -> c.enqueueWork(() -> ClientArmyData.accept(p)));
        event.register(ManagementDataPayload.TYPE, (p, c) -> c.enqueueWork(() -> {
            ClientManagementData.accept(p);
            ClientMapData.setTerrainState(p.stateId());
        }));
        event.register(BattleSyncPayload.TYPE, (p, c) -> c.enqueueWork(() -> {
            if (Minecraft.getInstance().gui.screen() instanceof BattleMapScreen screen && screen.getBattleId().equals(p.battleId()))
                ClientBattleData.accept(p);
        }));
        event.register(OpenFoundingPayload.TYPE, (p, c) -> c.enqueueWork(() -> {
            if (Minecraft.getInstance().gui.screen() instanceof FoundStateScreen screen && screen.isForAltar(p.altarPos())) screen.acceptStatus(p.ready(), p.status());
            else if (p.open()) Minecraft.getInstance().gui.setScreen(new FoundStateScreen(p.altarPos(), p.ready(), p.status()));
        }));
        event.register(OpenSettlementPayload.TYPE, (p, c) -> c.enqueueWork(() -> Minecraft.getInstance().gui.setScreen(new SettlementManagementScreen(p.settlementId(), p.name(), p.position()))));
        event.register(OpenBattleMapPayload.TYPE, (p, c) -> c.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (mc.gui.screen() instanceof BattleMapScreen screen && screen.getBattleId().equals(p.battleId())) return;
            ClientBattleData.clear();
            mc.gui.setScreen(new BattleMapScreen(p.battleId(), p.attackerArmyId(), p.defenderArmyId()));
        }));
    }

    private static final class RouteScreen extends net.minecraft.client.gui.screens.Screen {
        private final net.minecraft.client.gui.screens.Screen previous;
        private final ArmyActionPayload.RoutePreview proposal;
        private com.devc.minecraftempires.client.gui.widget.InteractiveMapWidget map;
        private int textBottom;
        RouteScreen(net.minecraft.client.gui.screens.Screen previous, ArmyActionPayload.RoutePreview proposal) {
            super(net.minecraft.network.chat.Component.literal("Movement route")); this.previous = previous; this.proposal = proposal;
        }
        @Override protected void init() {
            int textLines = font.split(net.minecraft.network.chat.Component.literal(proposal.description()), Math.max(100, width - 32)).size();
            textBottom = 30 + textLines * 10;
            map = new com.devc.minecraftempires.client.gui.widget.InteractiveMapWidget(16, textBottom + 6, width - 32, Math.max(24, height - textBottom - 48));
            map.setRoutePreview(proposal.visibleRuns());
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(net.minecraft.network.chat.Component.literal(proposal.detour() ? "Use detour" : "Continue"), button -> choose(true)).bounds(width / 2 - 124, height - 30, 120, 20).build());
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(net.minecraft.network.chat.Component.literal("Cancel"), button -> choose(false)).bounds(width / 2 + 4, height - 30, 120, 20).build());
        }
        private void choose(boolean accept) {
            ClientPacketDistributor.sendToServer(new ArmyActionPayload(accept ? "ACCEPT_ROUTE" : "CANCEL_ROUTE", proposal.proposalId(), net.minecraft.core.BlockPos.ZERO));
            Minecraft.getInstance().gui.setScreen(previous);
        }
        @Override public void onClose() { choose(false); }
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xFF101820);
            graphics.centeredText(font, title, width / 2, 10, 0xFFFFDD66);
            int y = 30; for (var line : font.split(net.minecraft.network.chat.Component.literal(proposal.description()), Math.max(100, width - 32))) { graphics.text(font, line, 16, y, 0xFFE0E0E0); y += 10; }
            map.render(graphics, font, mouseX, mouseY);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
    }
    public static void clear() {
        ClientMapData.clearTerrain();
        ClientMapData.accept(MapDataPayload.empty());
        ClientArmyData.accept(ArmyMapPayload.empty());
        ClientManagementData.clear();
        ClientBattleData.clear();
    }
    public static void requestMapData() {
        if (Minecraft.getInstance().getConnection() != null) ClientPacketDistributor.sendToServer(new RequestMapDataPayload());
    }
    public static void requestTerrain(UUID battleId, java.util.List<RequestMapDataPayload.TerrainRequest> tiles) {
        if (Minecraft.getInstance().getConnection() != null && !tiles.isEmpty())
            ClientPacketDistributor.sendToServer(new RequestMapDataPayload.RequestTerrainPayload(battleId, tiles));
    }
    public static void requestSpectate(UUID id) {
        if (Minecraft.getInstance().getConnection() != null) ClientPacketDistributor.sendToServer(new RequestSpectatePayload(id));
    }
    public static void leaveSpectate(UUID id) {
        if (Minecraft.getInstance().getConnection() != null) ClientPacketDistributor.sendToServer(new LeaveSpectatePayload(id));
    }
}
