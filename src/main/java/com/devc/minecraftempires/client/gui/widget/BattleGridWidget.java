package com.devc.minecraftempires.client.gui.widget;

import com.devc.minecraftempires.client.map.ClientBattleData;
import com.devc.minecraftempires.client.map.ClientMapData;
import com.devc.minecraftempires.combat.BattleSession;
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
 *  - Handles left-click selection, Shift multi-selection and right-click movement orders
 *  - Sends BattleCommandPayload when the player commands a cohort to move
 */
//class to house the center battle grid 
public final class BattleGridWidget {
    private static final double MIN_ZOOM    =  0.25;
    private static final double MAX_ZOOM    = 40.0;
    private static final double DEFAULT_ZOOM = 2.0;

   //variables that determine the size of the rectangular unit icons
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
    private final java.util.Map<UUID, RenderedMarker> renderedMarkers = new java.util.HashMap<>();
    private record RenderedMarker(double worldX, double worldZ, double facing) { }

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

        // One local battle unit corresponds to one world block relative to the saved battle origin.
        if (data.hasData()) ClientMapData.renderTerrain(graphics, font, x, y, width, height,
                data.mapOriginX() + centerX, data.mapOriginZ() + centerZ, zoom, battleId);

        drawGrid(graphics);
        drawField(graphics, data.isDeploymentPhase());
        drawMovementArrows(graphics, font, data, partialTick);
        drawCohorts(graphics, font, data, partialTick, mouseX, mouseY);
        drawTargetIndicators(graphics, data, partialTick);


        //check if we are in the deployment phase, if we are draw a timer
        if (data.hasData() && (data.isPreparing() || data.isDeploymentPhase() || data.hasEnded())) {
            // The payload sends game ticks, although simulation runs four times per second.
            int seconds = (data.deploymentTicksRemaining() + 19) / 20;
            String banner = data.hasEnded() ? data.result().replace('_', ' ') : data.isPreparing() ? "Preparing formations on verified ground..." : "Deployment: " + seconds + "s";

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
        // Keep grid spacing readable at every zoom level; using zoom itself as the world
        // interval produced thousands of subpixel lines when fully zoomed out.
        double cellSize = Math.pow(2, Math.ceil(Math.log(20.0 / zoom) / Math.log(2)));
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

    private void drawField(GuiGraphicsExtractor graphics, boolean deployment) {
        int left = worldToScreenX(-BattleSession.FIELD_BOUND);
        int right = worldToScreenX(BattleSession.FIELD_BOUND);
        int top = worldToScreenZ(-BattleSession.FIELD_BOUND);
        int bottom = worldToScreenZ(BattleSession.FIELD_BOUND);
        if (deployment) {
            graphics.fill(left, worldToScreenZ(-100), right, worldToScreenZ(-8), 0x224477CC);
            graphics.fill(left, worldToScreenZ(8), right, worldToScreenZ(100), 0x22CC3333);
        }
        graphics.fill(left, top, right, top + 1, 0xFF657381);
        graphics.fill(left, bottom - 1, right, bottom, 0xFF657381);
        graphics.fill(left, top, left + 1, bottom, 0xFF657381);
        graphics.fill(right - 1, top, right, bottom, 0xFF657381);
    }

   //cohort drawer
    private void drawCohorts(GuiGraphicsExtractor graphics, Font font, ClientBattleData.Snapshot data, float partialTick, int mouseX, int mouseY) {
        renderedMarkers.clear();
        if (!data.hasData()) {
            graphics.centeredText(font, Component.literal("Awaiting battle data..."), x + width / 2, y + height / 2, 0xFFB8C0C8);
            return;
        }

        for (ClientBattleData.CohortRenderState cohort : data.cohorts().values()) {
            if (cohort.currentHealth <= 0 || cohort.escaped) continue;
            double wx = data.getRenderX(cohort.cohortId, partialTick);
            double wz = data.getRenderZ(cohort.cohortId, partialTick);
            int sx = worldToScreenX(wx);
            int sz = worldToScreenZ(wz);
            double facing = data.getRenderFacing(cohort.cohortId, partialTick);
            renderedMarkers.put(cohort.cohortId, new RenderedMarker(wx, wz, facing));

            if (cohort.width < 10) {
                drawColumn(graphics, cohort, wx, wz);
                continue;
            }

            int hw = (int) Math.max(9, NATO_W * zoom / DEFAULT_ZOOM);
            int hh = (int) Math.max(5, NATO_H * zoom / DEFAULT_ZOOM);

            int left = -hw / 2;
            int top  = -hh / 2;
            int right  = left + hw;
            int bottom = top  + hh;

            int extent = (int) Math.ceil(Math.hypot(hw, hh) / 2) + 5;
            if (!intersects(sx - extent, sz - extent, 2 * extent, 2 * extent)) continue;

            graphics.pose().pushMatrix();
            graphics.pose().translate((float) sx, (float) sz);
            // The local top edge is the formation's front. Symbols and selection rotate as one body.
            graphics.pose().rotate((float) (facing + Math.PI / 2));

            // Fill color
            int fillColor = cohort.isRouting ? COLOR_ROUTING : cohort.isAttacker ? COLOR_ATTACKER : COLOR_DEFENDER;
            graphics.fill(left, top, right, bottom, fillColor);

            // NATO border (1px darker)
            graphics.fill(left,  top,    right,  top + 1,    0xFF000000);
            graphics.fill(left,  bottom - 1, right, bottom, 0xFF000000);
            graphics.fill(left,  top,    left + 1,  bottom, 0xFF000000);
            graphics.fill(right - 1, top, right, bottom,    0xFF000000);

            // NATO X cross (for infantry); cavalry uses the single diagonal from the owner's reference.
            if (hw >= 9 && hh >= 5) {
                drawLine(graphics, left + 2, bottom - 2, right - 2, top + 2, 0xFF101820);
                if (!"CAVALRY".equals(cohort.type)) drawLine(graphics, left + 2, top + 2, right - 2, bottom - 2, 0xFF101820);
            }

            // Type symbol in center (not sure if i want this, adjust later if needed): crossed infantry or diagonal cavalry.

            // Selection ring
            if (selectedCohortIds.contains(cohort.cohortId)) {
                graphics.fill(left - 2, top - 2, right + 2, top,      COLOR_SELECTED_RING);
                graphics.fill(left - 2, bottom,  right + 2, bottom + 2, COLOR_SELECTED_RING);
                graphics.fill(left - 2, top - 2, left,      bottom + 2, COLOR_SELECTED_RING);
                graphics.fill(right,    top - 2, right + 2, bottom + 2, COLOR_SELECTED_RING);
            }

            // A front-edge tick distinguishes a half-turn, which a symmetric rectangle alone cannot show.
            graphics.fill(-1, top - 4, 1, top + 1, 0xFFFFFFFF);
            graphics.pose().popMatrix();

            // Morale health bar below symbol
            if (hw >= 10) {
                int barW = hw;
                int barH = 2;
                int barY = sz + extent;
                int barLeft = sx - hw / 2;
                int moraleW = (int)(barW * (cohort.morale / 100.0));
                graphics.fill(barLeft, barY, barLeft + barW, barY + barH, 0xFF333333);
                graphics.fill(barLeft, barY, barLeft + moraleW, barY + barH,
                        cohort.morale > 50 ? 0xFF55CC55 : cohort.morale > 25 ? 0xFFDDCC33 : 0xFFCC3333);
            }
        }
    }

    private void drawColumn(GuiGraphicsExtractor graphics, ClientBattleData.CohortRenderState cohort, double wx, double wz) {
        int color = cohort.isRouting ? COLOR_ROUTING : cohort.isAttacker ? COLOR_ATTACKER : COLOR_DEFENDER;
        int edge = selectedCohortIds.contains(cohort.cohortId) ? COLOR_SELECTED_RING : 0xFF000000;
        for (int i = 1; i < cohort.body.size(); i++) {
            var a = cohort.body.get(i - 1); var b = cohort.body.get(i);
            double dx = b.x() - a.x(), dz = b.z() - a.z(), length = Math.hypot(dx, dz);
            if (length < .000001) continue;
            double sideX = -dz / length, sideZ = dx / length;
            for (double side = -cohort.width / 2; side <= cohort.width / 2; side += .2)
                drawLine(graphics, worldToScreenX(a.x() + wx - cohort.currentX + sideX * side), worldToScreenZ(a.z() + wz - cohort.currentZ + sideZ * side),
                        worldToScreenX(b.x() + wx - cohort.currentX + sideX * side), worldToScreenZ(b.z() + wz - cohort.currentZ + sideZ * side), Math.abs(side) >= cohort.width / 2 - .2 ? edge : color);
        }
    }

    // Movement paths show the selected formation's server-accepted route and queued objectives, even while it waits.
    private void drawMovementArrows(GuiGraphicsExtractor graphics, Font font, ClientBattleData.Snapshot data, float partialTick) {
        // Private orders are sent only for the controlled side; unknown routes remain visibly pending.
        if (!data.hasData()) return;
        for (UUID id : selectedCohortIds) {
            var cohort = data.getCohort(id); if (cohort == null || !data.canControl(cohort) || cohort.escaped || cohort.currentHealth == 0) continue;
            int px = worldToScreenX(data.getRenderX(id, partialTick)), py = worldToScreenZ(data.getRenderZ(id, partialTick));
            var target = data.getCohort(cohort.attackTarget);
            if (target != null && target.currentHealth > 0 && !target.escaped) {
                int tx = worldToScreenX(data.getRenderX(target.cohortId, partialTick)), ty = worldToScreenZ(data.getRenderZ(target.cohortId, partialTick));
                drawDashedLine(graphics, px, py, tx, ty, 0xFFFF6666);
            }
            for (var point : cohort.route) {
                int nx = worldToScreenX(point.x()), ny = worldToScreenZ(point.z());
                drawDashedLine(graphics, px, py, nx, ny, COLOR_ARROW); px = nx; py = ny;
            }
            int number = 1;
            for (var point : cohort.objectives) {
                int nx = worldToScreenX(point.x()), ny = worldToScreenZ(point.z());
                graphics.fill(nx - 3, ny - 3, nx + 4, ny + 4, 0xFFFFD45A);
                if (font != null) graphics.text(font, Component.literal(Integer.toString(number++)), nx + 5, ny - 4, 0xFFFFE89A);
            }
            for (var point : cohort.pending) {
                int nx = worldToScreenX(point.x()), ny = worldToScreenZ(point.z());
                drawDashedLine(graphics, px, py, nx, ny, 0xFF91BACB);
                graphics.fill(nx - 2, ny - 2, nx + 3, ny + 3, 0xFF91BACB); px = nx; py = ny;
            }
            if (!cohort.route.isEmpty()) {
                var goal = cohort.route.getLast(); int nx = worldToScreenX(goal.x()), ny = worldToScreenZ(goal.z());
                drawArrow(graphics, nx - 3, ny - 3, nx, ny, COLOR_ARROW);
            }
        }
    }
    private void drawTargetIndicators(GuiGraphicsExtractor graphics, ClientBattleData.Snapshot data, float partialTick) {
        java.util.Set<UUID> targets = new java.util.HashSet<>();
        for (UUID id : selectedCohortIds) {
            var cohort = data.getCohort(id);
            if (data.canControl(cohort) && cohort.attackTarget != null) targets.add(cohort.attackTarget);
        }
        int radius = (int) Math.ceil(Math.hypot(Math.max(9, NATO_W * zoom / DEFAULT_ZOOM), Math.max(5, NATO_H * zoom / DEFAULT_ZOOM)) / 2) + 3;
        for (UUID id : targets) {
            var target = data.getCohort(id); if (target == null || target.escaped || target.currentHealth <= 0) continue;
            int cx = worldToScreenX(data.getRenderX(id, partialTick)), cy = worldToScreenZ(data.getRenderZ(id, partialTick));
            for (int sideX : new int[]{-1, 1}) for (int sideY : new int[]{-1, 1}) {
                int px = cx + sideX * radius, py = cy + sideY * radius;
                graphics.fill(Math.min(px, px - sideX * 4), py, Math.max(px, px - sideX * 4) + 1, py + 1, 0xFFFF6666);
                graphics.fill(px, Math.min(py, py - sideY * 4), px + 1, Math.max(py, py - sideY * 4) + 1, 0xFFFF6666);
            }
        }
    }
    private void drawDashedLine(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        double first = 0, last = 1;
        double[] origin = {x1, y1}, delta = {(double) x2 - x1, (double) y2 - y1};
        int[] low = {x - 2, y - 2}, high = {x + width + 2, y + height + 2};
        for (int axis = 0; axis < 2; axis++) {
            if (delta[axis] == 0) { if (origin[axis] < low[axis] || origin[axis] > high[axis]) return; }
            else {
                double a = (low[axis] - origin[axis]) / delta[axis], b = (high[axis] - origin[axis]) / delta[axis];
                first = Math.max(first, Math.min(a, b)); last = Math.min(last, Math.max(a, b));
            }
        }
        if (first > last) return;
        for (int i = Math.max(0, (int) (first * steps) / 2 * 2); i <= Math.min(steps, (int) Math.ceil(last * steps)); i += 2) {
            if ((i / 6 & 1) != 0) continue;
            int px = x1 + (x2 - x1) * i / Math.max(1, steps), py = y1 + (y2 - y1) * i / Math.max(1, steps);
            if (px >= x && px < x + width && py >= y && py < y + height) graphics.fill(px, py, px + 2, py + 2, color);
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
        var data = ClientBattleData.get();
        double realX = data.mapOriginX() + wx, realZ = data.mapOriginZ() + wz;
        Integer elevation = ClientMapData.terrainHeight(realX, realZ);
        String coord = String.format("%.0f, %.0f", realX, realZ)
                + (elevation == null ? "" : " | Y " + elevation);
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

        } else if (button == 1) {
            // Right-click an enemy pursues it; ground replaces orders, or Shift appends a waypoint.
            if (!selectedCohortIds.isEmpty() && battleId != null) {
                double wx = screenToWorldX((int) mouseX);
                double wz = screenToWorldZ((int) mouseY);
                ClientBattleData.Snapshot data = ClientBattleData.get();
                var attacks = attackCommands(cohortAtScreen((int) mouseX, (int) mouseY));
                if (!attacks.isEmpty()) { attacks.forEach(ClientPacketDistributor::sendToServer); return true; }
                var moving = selectedCohortIds.stream().map(data::getCohort)
                        .filter(c -> data.canControl(c) && !c.isRouting && !c.isEngaged && !c.isWithdrawing && !c.escaped).toList();
                double centerX = moving.stream().mapToDouble(c -> c.currentX).average().orElse(0);
                double centerZ = moving.stream().mapToDouble(c -> c.currentZ).average().orElse(0);
                // Multi-unit orders preserve relative spacing rather than sending every unit to one point.
                for (var cohort : moving) ClientPacketDistributor.sendToServer(new BattleCommandPayload(battleId, cohort.cohortId,
                        Math.clamp(wx + cohort.currentX - centerX, -BattleSession.FIELD_BOUND, BattleSession.FIELD_BOUND),
                        Math.clamp(wz + cohort.currentZ - centerZ, -BattleSession.FIELD_BOUND, BattleSession.FIELD_BOUND), !shiftHeld));
                return true;
            }
        }

        dragging = button == 0 || button == 2;
        return dragging;
    }

    /** Build identity-based orders only for selected, controlled cohorts on the opposing side. */
    private List<BattleCommandPayload> attackCommands(UUID clicked) {
        var data = ClientBattleData.get(); var target = data.getCohort(clicked);
        if (!java.util.Objects.equals(battleId, data.battleId()) || target == null || target.escaped || target.currentHealth <= 0
                || data.isDeploymentPhase() || data.isPreparing() || data.hasEnded()) return List.of();
        return selectedCohortIds.stream().map(data::getCohort)
                .filter(c -> data.canControl(c) && c.isAttacker != target.isAttacker && !c.isRouting && !c.isWithdrawing && !c.escaped && c.currentHealth > 0)
                .map(c -> new BattleCommandPayload(battleId, c.cohortId, 0, 0, true, "ATTACK", target.cohortId)).toList();
    }

    public boolean mouseReleased(int button) {
        if (button == 0 || button == 2) { dragging = false; return true; }
        return false;
    }

    public boolean mouseDragged(double dragX, double dragY, int button) {
        if ((button == 0 || button == 2) && dragging) {
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
        return (int) Math.round((wx - centerX) * zoom + x + width / 2.0);
    }

    private int worldToScreenZ(double wz) {
        return (int) Math.round((wz - centerZ) * zoom + y + height / 2.0);
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
            if (cohort.currentHealth <= 0 || cohort.escaped) continue;
            RenderedMarker rendered = renderedMarkers.get(cohort.cohortId);
            double wx = rendered == null ? data.getRenderX(cohort.cohortId, 1.0f) : rendered.worldX();
            double wz = rendered == null ? data.getRenderZ(cohort.cohortId, 1.0f) : rendered.worldZ();
            if (cohort.width < 10) {
                double px = screenToWorldX(mx) - wx + cohort.currentX, pz = screenToWorldZ(mz) - wz + cohort.currentZ;
                for (int i = 1; i < cohort.body.size(); i++) {
                    var a = cohort.body.get(i - 1); var b = cohort.body.get(i); double dx = b.x() - a.x(), dz = b.z() - a.z();
                    double t = Math.clamp(((px - a.x()) * dx + (pz - a.z()) * dz) / Math.max(.000001, dx * dx + dz * dz), 0, 1);
                    if (Math.hypot(px - a.x() - t * dx, pz - a.z() - t * dz) <= cohort.width / 2) return cohort.cohortId;
                }
                continue;
            }
            int sx = worldToScreenX(wx);
            int sz = worldToScreenZ(wz);
            double facing = rendered == null ? data.getRenderFacing(cohort.cohortId, 1.0f) : rendered.facing();
            if (markerContains(mx - sx, mz - sz, hw, hh, facing)) {
                return cohort.cohortId;
            }
        }
        return null;
    }

    /** Inverse of the drawing transform, using the same displayed position and interpolated heading. */
    public static boolean markerContains(double dx, double dz, int width, int height, double facing) {
        double localX = -Math.sin(facing) * dx + Math.cos(facing) * dz;
        double localY = -Math.cos(facing) * dx - Math.sin(facing) * dz;
        int left = -width / 2, top = -height / 2;
        return localX >= left && localX <= left + width && localY >= top && localY <= top + height;
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
