package com.devc.minecraftempires.client.gui.widget;

import com.devc.minecraftempires.client.ClientNetworking;
import com.devc.minecraftempires.client.map.ClientBattleData;
import com.devc.minecraftempires.network.packet.BattleCommandPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The center tactical canvas of the BattleMapScreen.
 *
 * Responsibilities:
 *  - Renders a 2-D battle grid (panning + zooming identical to InteractiveMapWidget)
 *  - Draws NATO rectangle symbols for each cohort (blue = attacker, red = defender)
 *  - Linearly interpolates cohort positions at render time for 60 FPS smoothness
 *  - Draws movement arrows for moving cohorts
 *  - Handles left-click (select), shift+left-click (multi-select / waypoint), right-click (clear)
 *  - Sends BattleCommandPayload when the player commands a cohort to move
 */
//class to house the center battle grid 
public final class BattleGridWidget {
    private static final double MIN_ZOOM    =  4.0;
    private static final double MAX_ZOOM    = 40.0;
    private static final double DEFAULT_ZOOM = 10.0;

   //variables that determine the size of the unit icons, set to a square currently
    private static final int NATO_W = 18;
    private static final int NATO_H = 10;

    private static final int COLOR_ATTACKER      = 0xFF4477CC; // blue
    private static final int COLOR_DEFENDER      = 0xFFCC3333; // red
    private static final int COLOR_SELECTED_RING = 0xFF55FF55; // green glow
    private static final int COLOR_ROUTING       = 0xFF888888; // grey when routing
    private static final int COLOR_GRID          = 0x1A8899AA;
    private static final int COLOR_BG            = 0xFF0D1218;
    private static final int COLOR_ARROW         = 0xAAFFCC33; // movement arrow

    private int x, y, width, height;

    private double centerX    = 0; // battle-space center of view
    private double centerZ    = 0;
    private double zoom       = DEFAULT_ZOOM;
    private boolean dragging  = false;

    private UUID battleId;
    private UUID attackerLegionId;
    private UUID defenderLegionId;

    private final List<UUID> selectedCohortIds = new ArrayList<>();

    public BattleGridWidget(int x, int y, int width, int height, UUID battleId, UUID attackerLegionId, UUID defenderLegionId) {
        this.x               = x;
        this.y               = y;
        this.width           = Math.max(1, width);
        this.height          = Math.max(1, height);
        this.battleId        = battleId;
        this.attackerLegionId = attackerLegionId;
        this.defenderLegionId = defenderLegionId;
    }

    // Updates the widget's position and size (e.g., on window resize)
    public void render(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, float partialTick) {
        ClientBattleData.Snapshot data = ClientBattleData.get();

        // Background
        graphics.fill(x, y, x + width, y + height, COLOR_BG);
        graphics.enableScissor(x, y, x + width, y + height);

        drawGrid(graphics);
        drawCohorts(graphics, font, data, partialTick, mouseX, mouseY);
        drawMovementArrows(graphics, data, partialTick);

        //check if we are in the deployment phase, if we are draw a timer
        if (data.hasData() && "DEPLOYMENT".equals(data.battlePhase())) {
            //convert the engine ticks into seconds (4 ticks per second)
            int seconds = data.deploymentTicksRemaining() / 4;
            String banner = "Deployment Phase: " + seconds + "s remaining";

            //render banner
            int textWidth = font.width(banner);
            int bannerX = x + (width - textWidth) / 2;
            int bannerY = y + 20;

            //semi transparent black background box
            graphics.fill(bannerX - 5, bannerY - 2, bannerX + textWidth + 5, bannerY + font.lineHeight + 2, 0xAA000000);
            graphics.text(font, Component.literal(banner), bannerX, bannerY, 0xFFFFDD55);
        }

        graphics.disableScissor();

        // Border
        graphics.fill(x,           y,            x + width, y + 1,         0xFF485563);
        graphics.fill(x,           y + height - 1, x + width, y + height,  0xFF485563);
        graphics.fill(x,           y,            x + 1,     y + height,    0xFF485563);
        graphics.fill(x + width - 1, y,          x + width, y + height,    0xFF485563);

        // Coordinates display
        drawCoordinates(graphics, font, mouseX, mouseY);


    }

    //window grid function, draws the grid lines for the battle map, based on the current zoom level and center position
    private void drawGrid(GuiGraphicsExtractor graphics) {
        double cellSize = zoom;
        int minCX = (int) Math.floor((screenToWorldX(x)     ) / cellSize);
        int maxCX = (int) Math.ceil ((screenToWorldX(x + width) ) / cellSize);
        int minCZ = (int) Math.floor((screenToWorldZ(y)     ) / cellSize);
        int maxCZ = (int) Math.ceil ((screenToWorldZ(y + height) ) / cellSize);

        for (int cx = minCX; cx <= maxCX; cx++) {
            int sx = worldToScreenX(cx * cellSize);
            graphics.fill(sx, y, sx + 1, y + height, COLOR_GRID);
        }
        for (int cz = minCZ; cz <= maxCZ; cz++) {
            int sz = worldToScreenZ(cz * cellSize);
            graphics.fill(x, sz, x + width, sz + 1, COLOR_GRID);
        }
    }

   //cohort drawer
    private void drawCohorts(GuiGraphicsExtractor graphics, Font font, ClientBattleData.Snapshot data, float partialTick, int mouseX, int mouseY) {
        if (!data.hasData()) {
            graphics.centeredText(font, Component.literal("Awaiting battle data..."), x + width / 2, y + height / 2, 0xFFB8C0C8);
            return;
        }

        for (ClientBattleData.CohortRenderState cohort : data.cohorts().values()) {
            double wx = data.getRenderX(cohort.cohortId, partialTick);
            double wz = data.getRenderZ(cohort.cohortId, partialTick);
            int sx = worldToScreenX(wx);
            int sz = worldToScreenZ(wz);

            int hw = (int) Math.max(9, NATO_W * zoom / DEFAULT_ZOOM);
            int hh = (int) Math.max(5, NATO_H * zoom / DEFAULT_ZOOM);

            int left = sx - hw / 2;
            int top  = sz - hh / 2;
            int right  = left + hw;
            int bottom = top  + hh;

            if (!intersects(left, top, hw, hh)) continue;

            // Fill color
            int fillColor = cohort.isRouting ? COLOR_ROUTING : cohort.isAttacker ? COLOR_ATTACKER : COLOR_DEFENDER;
            graphics.fill(left, top, right, bottom, fillColor);

            // NATO border (1px darker)
            graphics.fill(left,  top,    right,  top + 1,    0xFF000000);
            graphics.fill(left,  bottom - 1, right, bottom, 0xFF000000);
            graphics.fill(left,  top,    left + 1,  bottom, 0xFF000000);
            graphics.fill(right - 1, top, right, bottom,    0xFF000000);

            // NATO X cross (for infantry)
            if (hw >= 12) {
                graphics.fill(left + 1, top + 1, right - 1, top + 2, 0x88000000);
                graphics.fill(left + 1, bottom - 2, right - 1, bottom - 1, 0x88000000);
            }

            // Type letter in center (not sure if i want this, adjust later if needed)
            if (font != null && hw >= 12) {
                String letter = cohort.type.substring(0, 1); // "I", "C", "A"
                int lx = sx - font.width(letter) / 2;
                int lz = sz - 4;
                graphics.text(font, Component.literal(letter), lx, lz, 0xFFFFFFFF);
            }

            // Selection ring
            if (selectedCohortIds.contains(cohort.cohortId)) {
                graphics.fill(left - 2, top - 2, right + 2, top,      COLOR_SELECTED_RING);
                graphics.fill(left - 2, bottom,  right + 2, bottom + 2, COLOR_SELECTED_RING);
                graphics.fill(left - 2, top - 2, left,      bottom + 2, COLOR_SELECTED_RING);
                graphics.fill(right,    top - 2, right + 2, bottom + 2, COLOR_SELECTED_RING);
            }

            // Morale health bar below symbol
            if (hw >= 10) {
                int barW = hw;
                int barH = 2;
                int barY = bottom + 2;
                int moraleW = (int)(barW * (cohort.morale / 100.0));
                graphics.fill(left, barY, left + barW, barY + barH, 0xFF333333);
                graphics.fill(left, barY, left + moraleW, barY + barH,
                        cohort.morale > 50 ? 0xFF55CC55 : cohort.morale > 25 ? 0xFFDDCC33 : 0xFFCC3333);
            }
        }
    }

    //movement arrow function, draws arrows for moving cohorts based on their previous and current positions, with a line and arrowhead to indicate direction
    private void drawMovementArrows(GuiGraphicsExtractor graphics, ClientBattleData.Snapshot data, float partialTick) {
        // Movement arrows use queue peek — we only have current position and lerp data on client.
        // For now we draw an arrow from prev → current position to visualize recent movement.
        if (!data.hasData()) return;

        for (ClientBattleData.CohortRenderState cohort : data.cohorts().values()) {
            double prevX = cohort.prevX;
            double prevZ = cohort.prevZ;
            double currX = cohort.currentX;
            double currZ = cohort.currentZ;

            double dx = currX - prevX;
            double dz = currZ - prevZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.5) continue; // not moving enough to draw arrow

            int fromX = worldToScreenX(prevX);
            int fromZ = worldToScreenZ(prevZ);
            int toX   = worldToScreenX(currX);
            int toZ   = worldToScreenZ(currZ);

            drawArrow(graphics, fromX, fromZ, toX, toZ, COLOR_ARROW);
        }
    }

    // Draws a simple line + arrowhead function
    private void drawArrow(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color) {
        // Line
        drawLine(graphics, x1, y1, x2, y2, color);
        // Arrowhead: two short lines at 45° off the direction
        double dx = x2 - x1;
        double dy = y2 - y1;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1) return;
        dx /= len; dy /= len;
        int size = 4;
        graphics.fill(x2 - (int)(dx * size + dy * size), y2 - (int)(dy * size - dx * size), x2, y2, color);
        graphics.fill(x2 - (int)(dx * size - dy * size), y2 - (int)(dy * size + dx * size), x2, y2, color);
    }

    //helper method to draw a line between two points on the screen, used for drawing movement arrows and grid lines
    private void drawLine(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (steps == 0) return;
        for (int i = 0; i <= steps; i++) {
            int px = x1 + (x2 - x1) * i / steps;
            int py = y1 + (y2 - y1) * i / steps;
            graphics.fill(px, py, px + 1, py + 1, color);
        }
    }

    //draw coordinate function, draws the world coordinates of the mouse cursor when hovering over the battle grid, in the bottom-right corner of the widget
    private void drawCoordinates(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        if (font == null) return;
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) return;
        double wx = screenToWorldX(mouseX);
        double wz = screenToWorldZ(mouseY);
        String coord = String.format("%.0f, %.0f", wx, wz);
        int tx = x + width - font.width(coord) - 6;
        int ty = y + height - 14;
        graphics.fill(tx - 2, ty - 1, tx + font.width(coord) + 2, ty + 10, 0xAA0D1218);
        graphics.text(font, Component.literal(coord), tx, ty, 0xFF87939E);
    }

    //mouse input functions for the battle grid widget, handling clicks, drags, and scrolls to allow panning and zooming of the battle map, as well as selecting cohorts and issuing movement commands
    public boolean mouseClicked(double mouseX, double mouseY, int button, boolean shiftHeld) {
        if (!containsPoint((int) mouseX, (int) mouseY)) return false;

        if (button == 0) { // Left click
            UUID clicked = cohortAtScreen((int) mouseX, (int) mouseY);
            if (clicked != null) {
                // Select / multi-select cohort
                if (!shiftHeld) selectedCohortIds.clear();
                if (selectedCohortIds.contains(clicked)) {
                    selectedCohortIds.remove(clicked);
                } else {
                    selectedCohortIds.add(clicked);
                }
                return true;
            }

            // Shift + left-click on empty space = issue move command to all selected cohorts
            if (shiftHeld && !selectedCohortIds.isEmpty() && battleId != null) {
                double wx = screenToWorldX((int) mouseX);
                double wz = screenToWorldZ((int) mouseY);
                for (UUID id : selectedCohortIds) {
                    ClientPacketDistributor.sendToServer( new BattleCommandPayload(battleId, id, wx, wz, false));
                }
                return true;
            }

        } else if (button == 1) { // Right click
            // Clear selection
            if (!selectedCohortIds.isEmpty()) {
                selectedCohortIds.clear();
                return true;
            }
        }

        dragging = true;
        return false;
    }

    public boolean mouseReleased(int button) {
        if (button == 0 || button == 2) { dragging = false; return true; }
        return false;
    }

    public boolean mouseDragged(double dragX, double dragY, int button) {
        if (button == 0 && dragging) {
            centerX -= dragX / zoom;
            centerZ -= dragY / zoom;
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!containsPoint((int) mouseX, (int) mouseY)) return false;
        double factor = scrollY > 0 ? 1.15 : (1.0 / 1.15);
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * factor));
        return true;
    }

    //coordinate conversion functions between world coordinates and screen coordinates, based on the current center position and zoom level of the battle grid

    private int worldToScreenX(double wx) {
        return (int) ((wx - centerX) * zoom) + x + width / 2;
    }

    private int worldToScreenZ(double wz) {
        return (int) ((wz - centerZ) * zoom) + y + height / 2;
    }

    private double screenToWorldX(int sx) {
        return (sx - x - width  / 2.0) / zoom + centerX;
    }

    private double screenToWorldZ(int sz) {
        return (sz - y - height / 2.0) / zoom + centerZ;
    }

    //cohort selection helper function, returns the UUID of the cohort at the given screen coordinates, or null if none is present
    private UUID cohortAtScreen(int mx, int mz) {
        ClientBattleData.Snapshot data = ClientBattleData.get();
        if (!data.hasData()) return null;

        int hw = (int) Math.max(9, NATO_W * zoom / DEFAULT_ZOOM);
        int hh = (int) Math.max(5, NATO_H * zoom / DEFAULT_ZOOM);

        for (ClientBattleData.CohortRenderState cohort : data.cohorts().values()) {
            double wx = data.getRenderX(cohort.cohortId, 1.0f);
            double wz = data.getRenderZ(cohort.cohortId, 1.0f);
            int sx = worldToScreenX(wx);
            int sz = worldToScreenZ(wz);
            int left = sx - hw / 2;
            int top  = sz - hh / 2;
            if (mx >= left && mx <= left + hw && mz >= top && mz <= top + hh) {
                return cohort.cohortId;
            }
        }
        return null;
    }

    private boolean containsPoint(int mx, int mz) {
        return mx >= x && mx <= x + width && mz >= y && mz <= y + height;
    }

    private boolean intersects(int rx, int ry, int rw, int rh) {
        return rx < x + width && rx + rw > x && ry < y + height && ry + rh > y;
    }

    //getters
    public List<UUID> getSelectedCohortIds(){ return selectedCohortIds; }
    public UUID getFirstSelectedCohortId(){
        return selectedCohortIds.isEmpty() ? null : selectedCohortIds.get(0);
    }
    public void clearSelection() { selectedCohortIds.clear(); }
    public void resetView() { centerX = 0; centerZ = 0; zoom = DEFAULT_ZOOM; }
}
