package com.devc.minecraftempires.client.gui.screen;

import com.devc.minecraftempires.client.ClientNetworking;
import com.devc.minecraftempires.client.map.ClientManagementData;
import com.devc.minecraftempires.network.packet.*;
import com.devc.minecraftempires.state.StateBalance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.UUID;

//settlement management screen for players to manage their settlements
public final class SettlementManagementScreen extends Screen {
    private final UUID settlementId;
    private final String settlementName;
    private final BlockPos altarPos;
    private EditBox name;
    private int ticks;
    private boolean confirmAbandon;

    //constructor for the settlement management screen
    public SettlementManagementScreen(UUID id, String name, BlockPos altar) {
        super(Component.literal("Settlement Management"));
        settlementId = id; settlementName = name; altarPos = altar;
    }

    //sends a packet to the server with the specified action and text, then requests updated map data
    private void action(String action, String text) {
        ClientPacketDistributor.sendToServer(new StateActionPayload(action, settlementId, altarPos, text));
        ClientNetworking.requestMapData();
    }

    //helper method to create a button with the specified label, y position, and action
    private void button(String label, int y, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(width / 2 - 130, y, 260, 20).build());
    }

    //initializes the settlement management screen, setting up the name edit box and buttons for various actions
    @Override protected void init() {
        String previous = name == null ? settlementName : name.getValue();
        name = new EditBox(font, width / 2 - 130, 45, 185, 20, Component.literal("Settlement Name"));
        name.setMaxLength(60); name.setValue(previous); addRenderableWidget(name);
        addRenderableWidget(Button.builder(Component.literal("Rename"), b -> action("RENAME", name.getValue())).bounds(width / 2 + 59, 45, 71, 20).build());
        button("Upgrade to city: " + StateBalance.CITY_UPGRADE_COST, 113, () -> action("UPGRADE", "")); //button for upgrading the settlement to a city
        button("Raise legion", 137, () -> { //button for raising a legion from the settlement
            ClientPacketDistributor.sendToServer(new ArmyActionPayload("RECRUIT_LEGION", settlementId, altarPos));
            ClientNetworking.requestMapData();
        });

        //button for abandoning the settlement, with a confirmation step to prevent accidental abandonment
        button(confirmAbandon ? "Confirm: abandon settlement" : "Abandon settlement", 161, () -> {
            if (!confirmAbandon) { confirmAbandon = true; clearWidgets(); init(); return; }
            ClientPacketDistributor.sendToServer(new AbandonSettlementPayload(settlementId, altarPos));
            minecraft.gui.setScreen(new EmpireManagementScreen());
        });
        //return button to go back to the empire management screen
        button("Back to management", height - 25, () -> minecraft.gui.setScreen(new EmpireManagementScreen()));
    }

    @Override public void tick(){ 
        if (++ticks % 20 == 0) ClientNetworking.requestMapData(); 
    }

    //renders the settlement management screen, displaying the settlement's tier, population, garrison capacity, and other relevant information
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        g.fill(0, 0, width, height, 0xF0101820);
        g.centeredText(font, title, width / 2, 15, 0xFFFFD45A);
        var d = ClientManagementData.get();
        var s = d.settlements().stream().filter(e -> e.id().equals(settlementId)).findFirst().orElse(null);
        if (s != null) {
            g.centeredText(font, Component.literal(s.tierLabel() + " | Population " + s.population() + " | Garrison capacity " + s.capacity()), width / 2, 77, 0xFFFFFFFF);
            g.centeredText(font, Component.literal("City requires " + StateBalance.CITY_POPULATION + " residents | " + (d.freeLegion() ? "1st raised Legion is free" : "Legion costs 5,000")), width / 2, 94, 0xFFCCD5DE);
        }
        super.extractRenderState(g, x, y, tick);
    }

    @Override public boolean isPauseScreen() { return false; }

    //getters
    public UUID getSettlementId() { return settlementId; }
    public BlockPos getAltarPos() { return altarPos; }
}
