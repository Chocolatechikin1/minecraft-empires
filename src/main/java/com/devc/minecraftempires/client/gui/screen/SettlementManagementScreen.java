package com.devc.minecraftempires.client.gui.screen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Settlement management screen — opened when a player right-clicks their City Altar block.
 *
 * Phase 2 stub: wires the Abandon Settlement flow but UI layout is deferred to a
 * dedicated UI sprint. Currently shows settlement name + functional Abandon + Close buttons.
 *
 * TODO (UI sprint): add rename button, population/tier display, upgrade path, etc.
 */
public class SettlementManagementScreen extends Screen {

    private final UUID settlementId;
    private final String settlementName;
    private final BlockPos altarPos;

    public SettlementManagementScreen(UUID settlementId, String settlementName, BlockPos altarPos) {
        super(Component.literal(settlementName));
        this.settlementId  = settlementId;
        this.settlementName = settlementName;
        this.altarPos      = altarPos;
    }

    // TODO (Phase 2): add Abandon Settlement button and Close button in init()
    @Override
    protected void init() {
        super.init();
        // Buttons added in Phase 2 implementation
    }

    // TODO (Phase 2): render settlement name, stats, and button layout
    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // Getters for use by button lambdas in Phase 2
    public UUID getSettlementId()   { return settlementId; }
    public BlockPos getAltarPos()   { return altarPos; }
}
