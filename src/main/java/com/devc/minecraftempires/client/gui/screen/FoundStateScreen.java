package com.devc.minecraftempires.client.gui.screen;

import com.devc.minecraftempires.client.ClientNetworking;
import com.devc.minecraftempires.client.map.ClientManagementData;
import com.devc.minecraftempires.network.packet.StateActionPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.UUID;

//screen for founding a state or settlement, depending on whether the player is in a state already
public final class FoundStateScreen extends Screen {
    private final BlockPos altar;
    private EditBox name;
    private boolean submitted;
    private int ticks;
    private boolean foundingState;

    public FoundStateScreen(BlockPos altar){ 
        super(Component.literal("Found a State")); this.altar = altar; 
    }

    //on opening the screen, set up the text box and buttons
    @Override protected void init() {
        foundingState = ClientManagementData.get().stateId() == null;
        int x = width / 2 - 130;
        String previousName = name == null ? foundingState ? "My County" : "New Town" : name.getValue();
        name = new EditBox(font, x, 70, 260, 20, Component.literal("State name"));
        name.setMaxLength(60); name.setValue(previousName);
        addRenderableWidget(name); setInitialFocus(name);
        addRenderableWidget(Button.builder(Component.literal(foundingState ? "Found County" : "Found Town"), b -> {
            if (name.getValue().isBlank()) return;
            ClientPacketDistributor.sendToServer(new StateActionPayload("FOUND", new UUID(0, 0), altar, name.getValue().trim()));
            submitted = true; ClientNetworking.requestMapData();
        }).bounds(x, 104, 125, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(x + 135, 104, 125, 20).build());
    }

    //tick the screen, requesting map data every second and closing the screen if the settlement has been founded
    @Override public void tick() {
        if (++ticks % 20 == 0) ClientNetworking.requestMapData();
        if (submitted && ClientManagementData.get().settlements().stream().anyMatch(s -> s.position().equals(altar)))
            minecraft.gui.setScreen(new EmpireManagementScreen());
    }
    //actual ui
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xEE101820);
        g.centeredText(font, Component.literal(foundingState ? "Found a State" : "Found a Settlement"), width / 2, 22, 0xFFFFD45A);
        g.centeredText(font, Component.literal(foundingState ? "Your first state starts as a county." : "Name the new town in your state."), width / 2, 43, 0xFFE0E6EB);
        g.centeredText(font, Component.literal("The altar claims up to 100 blocks around it."), width / 2, 143, 0xFFBAC5CE);
        super.extractRenderState(g, mx, my, pt);
    }
    @Override public boolean isPauseScreen() { return false; }
}
