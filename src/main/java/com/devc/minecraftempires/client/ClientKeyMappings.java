package com.devc.minecraftempires.client;

import com.devc.minecraftempires.MinecraftEmpires;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

//class that handles the key mapping
public final class ClientKeyMappings {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "empire_management")
    );

    public static final KeyMapping OPEN_MAP = new KeyMapping(
            "key.minecraftempires.open_map",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M, //set to M
            CATEGORY
    );

    private ClientKeyMappings() {}

    public static void register(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(OPEN_MAP);
    }
}
