package com.devc.minecraftempires.client;

import com.devc.minecraftempires.client.gui.screen.MapScreen;
import com.devc.minecraftempires.client.map.ClientBattleData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

//class handles map events and the inventory button for opening the map
public final class ClientEvents {
    private static final int LIVE_MAP_REFRESH_INTERVAL_TICKS = 40;
    private static int liveMapRefreshTicks;

    private ClientEvents() {}

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (ClientKeyMappings.OPEN_MAP.consumeClick()) {
            if (minecraft.player != null && minecraft.gui.screen() == null) {
                minecraft.gui.setScreen(new MapScreen());
            }
        }

        ClientBattleData.advanceTick(); //linear interpolation (lerp) for troop positions between server updates

        if (minecraft.gui.screen() instanceof MapScreen) {
            liveMapRefreshTicks++;
            if (liveMapRefreshTicks >= LIVE_MAP_REFRESH_INTERVAL_TICKS) {
                liveMapRefreshTicks = 0;
                ClientNetworking.requestMapData();
            }
        } else {
            liveMapRefreshTicks = 0;
        }
    }

    //adds a button to the inventory screen for opening the map
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof InventoryScreen inventoryScreen)) {
            return;
        }

        //button
        Button mapButton = Button.builder(
                Component.translatable("gui.minecraftempires.map.inventory_button"),
                button -> Minecraft.getInstance().gui.setScreen(new MapScreen())
        ).bounds(inventoryScreen.width - 52, 4, 48, 20).build();

        event.addListener(mapButton);
    }
}
