package com.devc.minecraftempires.client;

import com.devc.minecraftempires.client.gui.screen.MapScreen;
import com.devc.minecraftempires.client.gui.screen.SettlementManagementScreen;
import com.devc.minecraftempires.client.map.ClientBattleData;
import com.devc.minecraftempires.client.map.ClientMapData;
import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.network.packet.MapDataPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.UUID;

//class handles map events and the inventory button for opening the map, and any other client events
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

    //city altar block block interaction method
    //reference: CityAltarBlock class
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()) return;

        BlockPos pos = event.getPos();
        if (!event.getLevel().getBlockState(pos).is(MinecraftEmpires.CITY_ALTAR.get())) return; //only handle city altar block interactions

        ClientMapData.Snapshot snapshot = ClientMapData.get(); //fetch map data

        //if the map hasn't synced yet, guide the player
        if (snapshot.viewerStateId() == null || snapshot.settlementsById().isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("§e[Minecraft Empires] Open the Empire Map first, then try again."));
            }
            return;
        }

        //find the settlement whose altar sits in this chunk and belongs to the viewer's state
        long packedChunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4).pack();
        MapDataPayload.SettlementSummary match = snapshot.settlementsById().values().stream().filter(s -> s.packedCenterChunk() == packedChunk && snapshot.viewerStateId().equals(s.stateId())).findFirst().orElse(null);

        //if altar doesn't belong to the user, ignore request
        if (match == null) {
            return;
        }

        //open the settlement management screen for the settlement
        UUID settlementUUID = UUID.fromString(match.settlementId());
        Minecraft.getInstance().gui.setScreen(new SettlementManagementScreen(settlementUUID, match.settlementName(), pos));
    }
}
