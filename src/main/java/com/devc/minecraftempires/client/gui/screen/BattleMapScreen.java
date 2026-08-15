package com.devc.minecraftempires.client.gui.screen;

import com.devc.minecraftempires.client.ClientNetworking;
import com.devc.minecraftempires.client.map.ClientBattleData;
import com.devc.minecraftempires.client.gui.widget.BattleGridWidget;
import com.devc.minecraftempires.network.packet.RequestSpectatePayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.UUID;

/** good desc ill keep it
 * The fullscreen Tactical Battle Map screen.
 *
 * Layout:
 * ┌──────────────────┬────────────────────────────┬──────────────────┐
 * │  LEFT PANE       │   CENTER — BattleGridWidget │  RIGHT PANE      │
 * │  (micro-mgmt)    │   (tactical canvas)         │  (macro-mgmt)    │
 * │  130px           │   fills remaining space     │  150px           │
 * └──────────────────┴────────────────────────────┴──────────────────┘
 *  ↑ Both side panels stop at height-20 so the bottom bar is never covered.
 *                         ↑ Control tooltips (bottom bar)
 *
 * Left pane:  Stats for the selected cohort (Endurance, Strength, Health, Speed, Morale)
 *             + "Move Cohort" and "Auto-Resolve" action buttons.
 * Center:     BattleGridWidget — draws NATO symbols, handles mouse/key input.
 * Right pane: Army macro-stats panel — total strength, aggregate progress bars,
 *             collapsible unit tree (Cohorts 1–N, Squadrons 1–N).
 */
public final class BattleMapScreen extends Screen {
    private static final int LEFT_PANE_W  = 130;
    private static final int RIGHT_PANE_W = 150;
    private static final int MARGIN = 0;

    // ── Dark / accent palette (matches MapScreen aesthetic) ───────────────────
    private static final int BG_PANEL    = 0xEE0D1218; //dark panel
    private static final int DIVIDER     = 0xFF657381; //gray
    private static final int ACCENT_GOLD = 0xFFFFD45A; //gold
    private static final int TEXT_LABEL  = 0xFFAAB4BE; //light gray
    private static final int TEXT_VALUE  = 0xFFF3F5F7; //white
    private static final int TEXT_HINT   = 0xFF87939E; //medium gray
    private static final int TEXT_RED    = 0xFFFF4444; //red
    private static final int TEXT_GREEN  = 0xFF44FF44; //green

    // Height of the bottom control bar — panels must not extend below this boundary
    private static final int BOTTOM_BAR_H = 20;

    private final UUID battleId;
    private final UUID attackerLegionId;
    private final UUID defenderLegionId;

    private BattleGridWidget grid; //widget

    private boolean compositionExpanded = true; //whether the right pane's composition tree is expanded or collapsed

    public BattleMapScreen(UUID battleId, UUID attackerLegionId, UUID defenderLegionId) {
        super(Component.literal("Tactical Battle Map"));
        this.battleId        = battleId;
        this.attackerLegionId = attackerLegionId;
        this.defenderLegionId = defenderLegionId;
    }

    @Override
    protected void init() {
        super.init();

        // Automatically register this player as a spectator so the server starts
        // ticking the BattleSession and streaming BattleSyncPayload packets.
        // Without this, the deployment timer never appears and the phase never transitions.
        ClientPacketDistributor.sendToServer(new RequestSpectatePayload(battleId));

        int centerX = LEFT_PANE_W;
        int centerW = this.width - LEFT_PANE_W - RIGHT_PANE_W;
        int centerH = this.height - BOTTOM_BAR_H; // leave room for bottom bar

        grid = new BattleGridWidget(centerX, 0, centerW, centerH, battleId, attackerLegionId, defenderLegionId);

        // Buttons sit above the bottom bar: moved up by 20 px relative to the old positions
        //auto resolve button in the left pane
        this.addRenderableWidget(Button.builder(Component.literal("Auto-Resolve"), btn -> autoResolve()).bounds(8, this.height - 74, LEFT_PANE_W - 16, 20).build());

        //close button in left pane
        this.addRenderableWidget(Button.builder(Component.literal("Close"), btn -> this.onClose()).bounds(8, this.height - 50, LEFT_PANE_W - 16, 20).build());

        //toggle composition tree in right pane
        this.addRenderableWidget(Button.builder(Component.literal("▶ By Unit"), btn -> { compositionExpanded = !compositionExpanded; }).bounds(this.width - RIGHT_PANE_W + 8, 140, RIGHT_PANE_W - 16, 16).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int panelH = this.height - BOTTOM_BAR_H; // panels stop before the bottom bar

        // Left pane — capped at panelH so it never covers the bottom bar
        graphics.fill(0, 0, LEFT_PANE_W, panelH, BG_PANEL);
        graphics.fill(LEFT_PANE_W - 2, 0, LEFT_PANE_W, panelH, DIVIDER);
        drawLeftPane(graphics, mouseX, mouseY);

        // Center tactical canvas
        if (grid != null) {
            grid.render(graphics, this.font, mouseX, mouseY, partialTick);
        }

        // Right pane — capped at panelH so it never covers the bottom bar
        graphics.fill(this.width - RIGHT_PANE_W, 0, this.width, panelH, BG_PANEL);
        graphics.fill(this.width - RIGHT_PANE_W, 0, this.width - RIGHT_PANE_W + 2, panelH, DIVIDER);
        drawRightPane(graphics);

        // Bottom control hint bar — drawn last so it always wins
        drawBottomBar(graphics);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    //left pane class: micro-management of the selected cohort, including stats and action buttons
    private void drawLeftPane(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x  = 8;
        int y  = 10;

        // Heading
        graphics.centeredText(this.font, Component.literal("UNIT DETAIL"), LEFT_PANE_W / 2, y, ACCENT_GOLD);
        y += 14;
        graphics.fill(8, y, LEFT_PANE_W - 8, y + 1, 0xFF3E4A55); //gray?
        y += 8;

        // Get selected cohort
        UUID selectedId = grid != null ? grid.getFirstSelectedCohortId() : null;
        ClientBattleData.Snapshot data = ClientBattleData.get();

        if (selectedId == null || !data.hasData()) { //options for when no unit is selected or no battle data is available
            graphics.text(this.font,
                    Component.literal("No unit selected"),
                    x, y, TEXT_HINT);
            y += 12;
            graphics.text(this.font,
                    Component.literal("Click a unit on"),
                    x, y, TEXT_HINT);
            y += 10;
            graphics.text(this.font,
                    Component.literal("the map to select."),
                    x, y, TEXT_HINT);
            return;
        }

        // Get the selected cohort's data
        ClientBattleData.CohortRenderState cohort = data.cohorts().get(selectedId);
        if (cohort == null) return;

        //cohort label
        String sideLabel = cohort.isAttacker ? "Attacker" : "Defender";
        graphics.text(this.font, Component.literal(cohort.type + " (" + sideLabel + ")"), x, y, cohort.isAttacker ? 0xFF7799EE : 0xFFEE6666);
        y += 14;

        // Stats display
        y = drawStatRow(graphics, x, y, "Troops",  cohort.currentHealth + "/" + cohort.maxHealth);
        y = drawStatBar(graphics, x, y, "Morale",  cohort.morale,  100, cohort.morale > 50 ? 0xFF55CC55 : cohort.morale > 25 ? 0xFFDDCC33 : 0xFFCC3333, LEFT_PANE_W - 16);
        y = drawStatBar(graphics, x, y, "HP",      cohort.currentHealth, cohort.maxHealth, 0xFF4499CC, LEFT_PANE_W - 16);
        y += 6;

        // Status
        if (cohort.isRouting) {
            graphics.text(this.font, Component.literal("⚠ ROUTING"), x, y, TEXT_RED);
        }
    }

    //helper function to draw a label-value pair in the left pane, returning the next y position
    private int drawStatRow(GuiGraphicsExtractor graphics, int x, int y, String label, String value) {
        graphics.text(this.font, Component.literal(label + ":"), x, y, TEXT_LABEL);
        graphics.text(this.font, Component.literal(value),  x + 65, y, TEXT_VALUE);
        return y + 12;
    }

    //helper function to draw a label-value pair with a progress bar in the left pane, returning the next y position
    private int drawStatBar(GuiGraphicsExtractor graphics, int x, int y, String label, int current, int max, int barColor, int paneWidth) {
        graphics.text(this.font, Component.literal(label + ":"), x, y, TEXT_LABEL);
        int barX = x + 50;
        int barW = paneWidth - 50 - 28; // reserve space for the numeric value on the right
        int fillW = (int)((double) current / Math.max(1, max) * barW);
        graphics.fill(barX, y + 1, barX + barW, y + 7, 0xFF1A2530);
        if (fillW > 0) {
            graphics.fill(barX, y + 1, barX + fillW, y + 7, barColor);
        }
        graphics.text(this.font, Component.literal(current + "/" + max), barX + barW + 2, y, TEXT_HINT);
        return y + 12;
    }

    //right pane class: macro-management of the battle, including aggregate stats and a collapsible unit tree
    private void drawRightPane(GuiGraphicsExtractor graphics) {
        int px = this.width - RIGHT_PANE_W + 8;
        int y  = 10;

        // Heading
        graphics.centeredText(this.font, Component.literal("ARMY OVERVIEW"), this.width - RIGHT_PANE_W / 2, y, ACCENT_GOLD);
        y += 14;
        graphics.fill(this.width - RIGHT_PANE_W + 6, y, this.width - 6, y + 1, 0xFF3E4A55);
        y += 8;

        ClientBattleData.Snapshot data = ClientBattleData.get();
        if (!data.hasData()) {
            graphics.text(this.font, Component.literal("No data"), px, y, TEXT_HINT);
            return;
        }

        // Aggregate stats
        int attackerCount = 0, attackerMoraleSum = 0, aTotal = 0;
        int defenderCount = 0, defenderMoraleSum = 0, dTotal = 0;
        for (ClientBattleData.CohortRenderState c : data.cohorts().values()) {
            if (c.isAttacker) {
                attackerCount++;
                attackerMoraleSum += c.morale;
                aTotal += c.currentHealth;
            } else {
                defenderCount++;
                defenderMoraleSum += c.morale;
                dTotal += c.currentHealth;
            }
        }

        // Attacker side
        graphics.text(this.font, Component.literal("━ Attacker ━"), px, y, 0xFF7799EE);
        y += 12;
        y = drawStatRow(graphics, px, y, "Strength", aTotal + " men");
        int aMorale = attackerCount > 0 ? attackerMoraleSum / attackerCount : 0;
        y = drawStatBar(graphics, px, y, "Morale", aMorale, 100, aMorale > 50 ? 0xFF55CC55 : aMorale > 25 ? 0xFFDDCC33 : 0xFFCC3333, RIGHT_PANE_W - 16);
        y += 8;

        // Defender side
        graphics.text(this.font, Component.literal("━ Defender ━"), px, y, 0xFFEE6666);
        y += 12;
        y = drawStatRow(graphics, px, y, "Strength", dTotal + " men");
        int dMorale = defenderCount > 0 ? defenderMoraleSum / defenderCount : 0;
        y = drawStatBar(graphics, px, y, "Morale", dMorale, 100, dMorale > 50 ? 0xFF55CC55 : dMorale > 25 ? 0xFFDDCC33 : 0xFFCC3333, RIGHT_PANE_W - 16);
        y += 12;
        graphics.fill(this.width - RIGHT_PANE_W + 6, y, this.width - 6, y + 1, 0xFF3E4A55);
        y += 8;

        // Collapsible unit tree (label is rendered by the Button widget above)
        graphics.text(this.font, Component.literal("Composition:"), px, y, ACCENT_GOLD);
        y = 160; // Start list below the "By Unit" button which is at y=140

        if (compositionExpanded) {
            int attackerIdx = 1, defenderIdx = 1;
            for (ClientBattleData.CohortRenderState c : data.cohorts().values()) {
                //render each cohort in the right pane, with a label indicating whether it's an attacker or defender, and its type. Routing units are marked with a warning symbol.
                String label = c.isAttacker ? "A-" + (attackerIdx++) + " " + c.type.substring(0, 3) : "D-" + (defenderIdx++) + " " + c.type.substring(0, 3);
                int color = c.isRouting ? TEXT_HINT : c.isAttacker ? 0xFF7799EE : 0xFFEE6666;
                boolean selected = grid != null && grid.getSelectedCohortIds().contains(c.cohortId); //highlight selected cohorts
                if (selected) {
                    graphics.fill(px - 2, y - 1, this.width - 8, y + 9, 0x44FFE84A);
                }
                graphics.text(this.font, Component.literal(label + (c.isRouting ? " ⚠" : "")), px, y, color);
                y += 10;
                if (y > this.height - 80) {
                    graphics.text(this.font, Component.literal("..."), px, y, TEXT_HINT);
                    break;
                }
            }
        }
    }

    //bottom bar class: displays control hints for the player, including how to select multiple units
    private void drawBottomBar(GuiGraphicsExtractor graphics) {
        int barY = this.height - BOTTOM_BAR_H;
        // Full-width background so neither panel bleeds through
        graphics.fill(0, barY, this.width, this.height, 0xDD0A0F14);
        graphics.fill(0, barY, this.width, barY + 1, 0xFF485563);

        int tx = LEFT_PANE_W + 8;
        int ty = barY + 5;
        graphics.text(this.font, Component.literal("● Normal View"), tx, ty, TEXT_HINT);
        graphics.text(this.font, Component.literal("Shift + Left Click to select multiple units"), tx + 110, ty, TEXT_HINT);
    }

   //input handles
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;

        boolean shiftHeld = net.minecraft.client.Minecraft.getInstance().options.keyShift.isDown();
        if (grid != null) {
            return grid.mouseClicked(event.x(), event.y(), event.button(), shiftHeld);
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (grid != null && grid.mouseReleased(event.button())) return true;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (grid != null && grid.mouseDragged(dragX, dragY, event.button())) return true;
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (grid != null && grid.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (grid != null && !grid.getSelectedCohortIds().isEmpty()) {
                grid.clearSelection();
                return true;
            }
            this.onClose();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_R && grid != null) {
            grid.resetView();
            return true;
        }
        return super.keyPressed(event);
    }

    //auto resolver
    private void autoResolve() {
        // Send a withdraw command for all cohorts on the player's side.
        // The server will process auto-resolve via AutoResolveEngine on the next tick
        // when it detects isSpectated = false after we close.
        this.onClose();
    }

    @Override
    public void onClose() {
        ClientNetworking.leaveSpectate(battleId); // tell server to remove us from spectating set
        ClientBattleData.clear();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
