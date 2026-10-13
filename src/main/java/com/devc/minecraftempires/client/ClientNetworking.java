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
        event.register(MapDataPayload.TYPE, (p, c) -> c.enqueueWork(() -> ClientMapData.accept(p)));
        event.register(ArmyMapPayload.TYPE, (p, c) -> c.enqueueWork(() -> ClientArmyData.accept(p)));
        event.register(ManagementDataPayload.TYPE, (p, c) -> c.enqueueWork(() -> ClientManagementData.accept(p)));
        event.register(BattleSyncPayload.TYPE, (p, c) -> c.enqueueWork(() -> {
            if (Minecraft.getInstance().gui.screen() instanceof BattleMapScreen screen && screen.getBattleId().equals(p.battleId()))
                ClientBattleData.accept(p);
        }));
        event.register(OpenFoundingPayload.TYPE, (p, c) -> c.enqueueWork(() -> Minecraft.getInstance().gui.setScreen(new FoundStateScreen(p.altarPos()))));
        event.register(OpenSettlementPayload.TYPE, (p, c) -> c.enqueueWork(() -> Minecraft.getInstance().gui.setScreen(new SettlementManagementScreen(p.settlementId(), p.name(), p.position()))));
        event.register(OpenBattleMapPayload.TYPE, (p, c) -> c.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (mc.gui.screen() instanceof BattleMapScreen screen && screen.getBattleId().equals(p.battleId())) return;
            ClientBattleData.clear();
            mc.gui.setScreen(new BattleMapScreen(p.battleId(), p.attackerArmyId(), p.defenderArmyId()));
        }));
    }
    public static void clear() {
        ClientMapData.accept(MapDataPayload.empty());
        ClientArmyData.accept(ArmyMapPayload.empty());
        ClientManagementData.clear();
        ClientBattleData.clear();
    }
    public static void requestMapData() {
        if (Minecraft.getInstance().getConnection() != null) ClientPacketDistributor.sendToServer(new RequestMapDataPayload());
    }
    public static void requestSpectate(UUID id) {
        if (Minecraft.getInstance().getConnection() != null) ClientPacketDistributor.sendToServer(new RequestSpectatePayload(id));
    }
    public static void leaveSpectate(UUID id) {
        if (Minecraft.getInstance().getConnection() != null) ClientPacketDistributor.sendToServer(new LeaveSpectatePayload(id));
    }
}
