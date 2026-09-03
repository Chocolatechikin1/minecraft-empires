package com.devc.minecraftempires;

import com.devc.minecraftempires.client.ClientEvents;
import com.devc.minecraftempires.client.ClientKeyMappings;
import com.devc.minecraftempires.client.ClientNetworking;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = MinecraftEmpires.MODID, dist = Dist.CLIENT)
public class MinecraftEmpiresClient {
    public MinecraftEmpiresClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        //bus registers
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(ClientKeyMappings::register);
        modEventBus.addListener(ClientNetworking::registerPayloadHandlers);

        //bus listeners
        NeoForge.EVENT_BUS.addListener(ClientEvents::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onScreenInit);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onRightClickBlock);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        MinecraftEmpires.LOGGER.info("(ricky was here) HELLO FROM CLIENT SETUP");
        MinecraftEmpires.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }
}
