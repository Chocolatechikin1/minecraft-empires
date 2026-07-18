package com.devc.minecraftempires.client;

import com.devc.minecraftempires.client.gui.screen.MapScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.util.Lazy; //new
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = "minecraftempires", value = Dist.CLIENT)
public class ClientKeybinds {

    // 1. Lazy Initialization (Mandated by NeoForge 26.1+)
    public static final Lazy<KeyMapping> OPEN_MAP = Lazy.of(() -> new KeyMapping( 
        "key.minecraftempires.open_map", 
        GLFW.GLFW_KEY_M, 
        "key.categories.minecraftempires"
    ));

    // 2. Mod Bus Registration (Called from MinecraftEmpires.java)
    public static void registerKeyBindings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_MAP.get()); //new: Unwraps the lazy variable safely
    }

    // 3. Game Bus Tick Listener
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (OPEN_MAP.get().consumeClick()) { 
            Minecraft mc = Minecraft.getInstance();
            
            // Using your exact findings from the decompiled Minecraft.java!
            if (mc.player != null && mc.gui.screen() == null) {
                mc.gui.setScreen(new MapScreen());
            }
        }
    }
}