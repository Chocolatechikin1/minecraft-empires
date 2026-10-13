package com.devc.minecraftempires.client.gui.screen;

import com.devc.minecraftempires.network.packet.StateActionPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.UUID;

//screen that handles the final government tier
//player can choose between an empire and republic
//screen is shown again after a republic civil war
public final class GovernmentChoiceScreen extends Screen {
    public GovernmentChoiceScreen() { super(Component.literal("Choose your government")); }
    @Override protected void init() {
        int x = width / 2 - 130;
        addRenderableWidget(Button.builder(Component.literal("Republic"), b -> choose("CHOOSE_REPUBLIC")).bounds(x, 101, 260, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Empire"), b -> choose("CHOOSE_EMPIRE")).bounds(x, 127, 260, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Decide later"), b -> onClose()).bounds(x, 162, 260, 20).build());
    }
    private void choose(String action) {
        ClientPacketDistributor.sendToServer(new StateActionPayload(action, new UUID(0, 0), BlockPos.ZERO, ""));
        minecraft.gui.setScreen(new EmpireManagementScreen());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        g.fill(0, 0, width, height, 0xF0101820);
        g.centeredText(font, title, width / 2, 25, 0xFFFFD45A);
        g.centeredText(font, Component.literal("Your state has reached its final milestone."), width / 2, 48, 0xFFFFFFFF);
        g.centeredText(font, Component.literal("The choice needs to be made now."), width / 2, 63, 0xFFCCD5DE);
        g.centeredText(font, Component.literal("Further expansion waits for your choice."), width / 2, 78, 0xFFCCD5DE);
        super.extractRenderState(g, x, y, tick);
    }
    @Override public boolean isPauseScreen() { return false; }
}
