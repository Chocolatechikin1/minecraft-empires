package com.devc.minecraftempires.client;

import com.devc.minecraftempires.client.gui.screen.MapScreen;
import com.devc.minecraftempires.client.gui.screen.GovernmentChoiceScreen;
import com.devc.minecraftempires.client.map.ClientManagementData;
import com.devc.minecraftempires.client.map.ClientBattleData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

public final class ClientEvents {
    private static Object connectedLevel;
    private static java.util.UUID promptedState;
    private ClientEvents() {}
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (connectedLevel != mc.level) {
            connectedLevel = mc.level;
            ClientNetworking.clear();
            promptedState = null;
        }
        while (ClientKeyMappings.OPEN_MAP.consumeClick()) {
            if (mc.player != null && mc.gui.screen() == null) mc.gui.setScreen(new MapScreen());
        }
        ClientBattleData.advanceTick();
        var management = ClientManagementData.get();
        if (!management.endgameChoice()) promptedState = null;
        else if (!management.stateId().equals(promptedState) && (mc.gui.screen() == null || mc.gui.screen() instanceof MapScreen)) {
            promptedState = management.stateId();
            mc.gui.setScreen(new GovernmentChoiceScreen());
        }
    }
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof InventoryScreen screen)) return;
        event.addListener(Button.builder(Component.translatable("gui.minecraftempires.map.inventory_button"),
                b -> Minecraft.getInstance().gui.setScreen(new MapScreen())).bounds(screen.width - 52, 4, 48, 20).build());
    }
}
