package com.devc.minecraftempires.client.gui.screen;

import com.devc.minecraftempires.network.packet.AbandonSettlementPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.UUID;

//settlement management UI, triggered by right-clicking the altar
//TODO (UI sprint): rename, population/tier display, upgrade path, etc.
public class SettlementManagementScreen extends Screen {

    //color selection
    private static final int BG_PANEL    = 0xEE0D1218; // dark panel
    private static final int BORDER_GOLD = 0xFFFFD45A; // gold accent
    private static final int DIVIDER     = 0xFF657381; // gray
    private static final int TEXT_TITLE  = 0xFFFFD45A; // gold
    private static final int TEXT_HINT   = 0xFF87939E; // medium gray
    private static final int TEXT_WARN   = 0xFFFF4444; // red

    //UI window size
    private static final int PANEL_W = 240;
    private static final int PANEL_H = 140;

    private final UUID settlementId;
    private final String settlementName;
    private final BlockPos altarPos;

    public SettlementManagementScreen(UUID settlementId, String settlementName, BlockPos altarPos) {
        super(Component.literal(settlementName));
        this.settlementId   = settlementId;
        this.settlementName = settlementName;
        this.altarPos       = altarPos;
    }

    @Override
    protected void init() {
        super.init();

        int panelX = (this.width  - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        int btnW   = 200;
        int btnX   = panelX + (PANEL_W - btnW) / 2;

        // "Abandon Settlement" button
        this.addRenderableWidget(
            Button.builder(
                Component.literal("Abandon Settlement"),
                btn -> {
                    ClientPacketDistributor.sendToServer(new AbandonSettlementPayload(this.settlementId, this.altarPos)); //send packet to server to abandon settlement
                    this.onClose(); //on button click, send packet to server and close the screen
                }
            ).bounds(btnX, panelY + 78, btnW, 20).build()
        );

        // "Close" button
        this.addRenderableWidget(
            Button.builder(
                Component.literal("Close"),
                btn -> this.onClose()
            ).bounds(btnX, panelY + 104, btnW, 20).build()
        );
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int panelX = (this.width  - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        int cx     = this.width / 2;

        //ui background
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, BG_PANEL);

        //top border
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + 1, BORDER_GOLD);

        //settlement name display
        graphics.centeredText(this.font, Component.literal(this.settlementName), cx, panelY + 12, TEXT_TITLE);

        //hint line
        graphics.centeredText(this.font, Component.literal("Manage your settlement").withStyle(ChatFormatting.GRAY), cx, panelY + 26, TEXT_HINT);

        //divider
        graphics.fill(panelX + 10, panelY + 42, panelX + PANEL_W - 10, panelY + 43, DIVIDER);

        //warning text above the abandon button
        graphics.centeredText(this.font, Component.literal("Abandoning cannot be undone.").withStyle(ChatFormatting.DARK_RED), cx, panelY + 60, TEXT_WARN);

        //render buttons on top
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    public UUID getSettlementId()   { return settlementId; }
    public BlockPos getAltarPos()   { return altarPos; }
}
