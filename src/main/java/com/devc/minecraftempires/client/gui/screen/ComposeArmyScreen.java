package com.devc.minecraftempires.client.gui.screen;

import com.devc.minecraftempires.client.ClientNetworking;
import com.devc.minecraftempires.client.map.ClientManagementData;
import com.devc.minecraftempires.client.map.ClientArmyData;
import com.devc.minecraftempires.network.packet.*;
import com.devc.minecraftempires.state.StateBalance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Cohort roster, army composition, auxiliary levy and physical regrouping. */
public final class ComposeArmyScreen extends Screen {
    private final UUID sourceLegionId;
    private final Screen returnScreen;
    private final UUID armyFilter;
    private final Set<UUID> selected = new LinkedHashSet<>();
    private UUID hoveredCohort;
    private long cohortHoverStarted;
    private int ticks, dataVersion = -1, settlementIndex;
    private CohortList cohortList;
    private Button helpButton;
    private boolean allLegions, confirmDisband, helpOpen;
    public ComposeArmyScreen(UUID sourceLegionId) { this(sourceLegionId, null, null); }
    public ComposeArmyScreen(UUID sourceLegionId, Screen returnScreen, UUID armyFilter) {
        super(Component.literal("Legion and Cohorts")); this.sourceLegionId = sourceLegionId;
        this.returnScreen = returnScreen; this.armyFilter = armyFilter;
    }
    private ManagementDataPayload data() { return ClientManagementData.get(); }
    private List<ManagementDataPayload.CohortEntry> roster() {
        return data().cohorts().stream().filter(c -> armyFilter == null ? allLegions || c.legionId().equals(sourceLegionId) : armyFilter.equals(c.armyId())).toList();
    }
    private void rebuild() { clearWidgets(); init(); }
    private Button button(String label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, 20).build());
    }
    private void action(String name) {
        ClientPacketDistributor.sendToServer(new ArmyActionPayload(name, sourceLegionId, BlockPos.ZERO));
        ClientNetworking.requestMapData();
    }
    @Override protected void init() {
        dataVersion = ClientManagementData.version();
        button(armyFilter != null ? "Army cohorts" : allLegions ? "All legions" : "This legion", 12, 31, 95, () -> {
            allLegions = !allLegions;
            if (cohortList != null) cohortList.setScrollAmount(0);
            rebuild();
        }).active = armyFilter == null;
        button("Select available", 111, 31, 105, () -> { roster().stream().filter(ManagementDataPayload.CohortEntry::available).forEach(c -> selected.add(c.id())); rebuild(); });
        button("Clear", 220, 31, 58, () -> { selected.clear(); rebuild(); });
        helpButton = button("?", 282, 31, 20, () -> { helpOpen = !helpOpen; hoveredCohort = null; });
        helpButton.setTooltip(Tooltip.create(Component.literal("Army and cohort help")));
        // Only the roster scrolls; fixed actions remain reachable at every GUI scale.
        // Keep the exact scroll offset through selection changes, fresh snapshots and window resizing.
        double scroll = cohortList == null ? 0 : cohortList.scrollAmount();
        cohortList = addRenderableWidget(new CohortList(roster()));
        cohortList.setScrollAmount(scroll);
        int w = (width - 32) / 3;
        var chosen = data().cohorts().stream().filter(c -> selected.contains(c.id()) && c.soldiers() > 0).toList();
        int half = (width - 28) / 2;
        button("Detach selected", 12, height - 122, half, () -> {
            ClientPacketDistributor.sendToServer(new ComposeArmyPayload(chosen.stream().map(ManagementDataPayload.CohortEntry::id).toList(), BlockPos.ZERO, "DETACH", false));
        }).active = !chosen.isEmpty() && chosen.size() <= 150;
        var move = button("Move selected on map", 16 + half, height - 122, half,
                () -> minecraft.gui.setScreen(new MapScreen(chosen.stream().map(ManagementDataPayload.CohortEntry::id).toList())));
        move.active = !chosen.isEmpty() && chosen.size() <= 150 && chosen.stream().allMatch(ManagementDataPayload.CohortEntry::available);
        move.setTooltip(Tooltip.create(Component.literal("Detach deployed cohorts first. Right-click a destination on the map. Shift to add another waypoint.")));
        button("Compose army", 12, height - 98, w, this::compose);
        button("Light infantry", 16 + w, height - 98, w, () -> action("RECRUIT_AUXILIARY")).active = armyFilter == null;
        button("Regroup legion", 20 + 2 * w, height - 98, w, () -> action("REGROUP")).active = armyFilter == null;
        var settlements = data().settlements();
        if (!settlements.isEmpty()) {
            settlementIndex %= settlements.size();
            var s = settlements.get(settlementIndex);
            button("Target: " + EmpireManagementScreen.shorten(s.name(), 14), 12, height - 74, w, () -> { settlementIndex++; rebuild(); })
                    .setTooltip(Tooltip.create(Component.literal("Garrison destination: " + s.name()
                            + ". Click to cycle settlements. Choosing a target does not move or station any cohorts.")));
            button("Garrison", 16 + w, height - 74, w, () -> garrison(s.id(), false))
                    .setTooltip(Tooltip.create(Component.literal("Station the selected cohorts at " + s.name()
                            + ". They must already be within 16 blocks. Use Move selected on map to march there first.")));
        }
        button("Release garrison", 20 + 2 * w, height - 74, w, () -> garrison(null, true));
        button(confirmDisband ? "Confirm dissolution" : "Dissolve legion", width - 148, height - 49, 136, () -> {
            if (!confirmDisband) { confirmDisband = true; rebuild(); return; }
            ClientPacketDistributor.sendToServer(new DisbandArmyPayload(sourceLegionId));
            minecraft.gui.setScreen(new EmpireManagementScreen());
        }).active = armyFilter == null;
        button("Back", width / 2 - 45, height - 25, 90, this::onClose);
    }
    private void compose() {
        var cohorts = data().cohorts().stream().filter(c -> selected.contains(c.id()) && c.soldiers() > 0).toList();
        if (cohorts.isEmpty()) return;
        minecraft.gui.setScreen(EmpireManagementScreen.composition(this, returnScreen, cohorts.stream().map(ManagementDataPayload.CohortEntry::id).toList()));
    }
    private void garrison(UUID settlement, boolean release) {
        Map<UUID, List<UUID>> groups = new LinkedHashMap<>();
        for (var c : data().cohorts()) {
            if (!selected.contains(c.id())) continue;
            UUID target = release ? c.settlementId() : settlement;
            if (target != null) groups.computeIfAbsent(target, id -> new ArrayList<>()).add(c.id());
        }
        groups.forEach((target, ids) -> ClientPacketDistributor.sendToServer(new GarrisonCohortPayload(ids, target, release)));
        selected.clear();
        ClientNetworking.requestMapData();
    }
    @Override public void tick() {
        if (++ticks % 20 == 0) ClientNetworking.requestMapData();
        if (dataVersion != ClientManagementData.version() && !isDragging()) rebuild();
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        g.fill(0, 0, width, height, 0xF0101820);
        g.centeredText(font, title, width / 2, 10, 0xFFFFD45A);
        g.text(font, font.plainSubstrByWidth(roster().size() + " cohorts | " + selected.size() + " selected", Math.max(1, width - 176)),
                12, height - 43, 0xFF9DAFBE);
        // The help dropdown covers the roster, so covered rows must not appear hovered underneath it.
        super.extractRenderState(g, helpOpen ? -1 : x, helpOpen ? -1 : y, tick);
        if (helpOpen) drawHelp(g);
        drawCohortTooltip(g, x, y);
    }

    private void drawCohortTooltip(GuiGraphicsExtractor g, int mx, int my) {
        UUID hovered = helpOpen || cohortList == null ? null : cohortList.cohortAt(mx, my);
        long now = System.nanoTime();
        // Snapshots rebuild row widgets every second; key the delay by cohort ID, not by widget lifetime.
        if (!Objects.equals(hovered, hoveredCohort)) { hoveredCohort = hovered; cohortHoverStarted = now; }
        if (hovered == null || now - cohortHoverStarted < 1_000_000_000L) return;
        var c = data().cohorts().stream().filter(entry -> entry.id().equals(hovered)).findFirst().orElse(null);
        if (c == null) return;
        var supply = data().supplies().stream().filter(s -> s.cohortId().equals(c.id())).findFirst().orElse(null);
        List<Component> info = new ArrayList<>();
        info.add(Component.literal(c.label()));
        info.add(Component.literal("Health " + c.health() + "% | Morale " + c.morale() + " | XP " + c.xp()));
        info.add(Component.literal("Endurance " + c.endurance() + " base / "
                + (supply == null ? c.endurance() : supply.effectiveEndurance()) + " effective"));
        info.add(Component.literal("Strength " + c.strength() + " | Speed " + c.speed() + " | Equipment: " + c.gear()));
        info.add(Component.literal("Position: " + c.position().getX() + ", " + c.position().getZ()));
        // Logistics stays on the permanent cohort roster, including garrisons and detached soldiers.
        if (supply != null) {
            info.add(Component.literal(String.format(Locale.ROOT, "%s | Reserve %.1f / %d days | Fatigue -%d",
                    supply.status(), supply.reserveDays(), StateBalance.CARRIED_SUPPLY_DAYS, supply.fatigue())));
            info.add(Component.literal(supply.reason()));
        }
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component line : info) lines.addAll(font.split(line, Math.min(280, width - 24)));
        g.setTooltipForNextFrame(font, lines, mx, my);
    }

    private record HelpPanel(int left, int top, int width, int height, List<FormattedCharSequence> lines) {
        boolean contains(double x, double y) { return x >= left && x < left + width && y >= top && y < top + height; }
    }
    private HelpPanel helpPanel() {
        int panelWidth = Math.min(280, width - 24);
        var lines = font.split(Component.literal("Form armies at the selected cohorts' current location.\n\n"
                + "Detach/move selected cohorts. Regroup marches split cohorts back."), panelWidth - 12);
        int left = Math.clamp(helpButton.getRight() - panelWidth, 12, width - panelWidth - 12);
        return new HelpPanel(left, helpButton.getBottom() + 4, panelWidth, lines.size() * (font.lineHeight + 2) + 12, lines);
    }
    private void drawHelp(GuiGraphicsExtractor g) {
        HelpPanel p = helpPanel();
        g.fill(p.left() - 1, p.top() - 1, p.left() + p.width() + 1, p.top() + p.height() + 1, 0xFFD3BC77);
        g.fill(p.left(), p.top(), p.left() + p.width(), p.top() + p.height(), 0xFF17212C);
        int lineY = p.top() + 6;
        for (var line : p.lines()) { g.text(font, line, p.left() + 6, lineY, 0xFFE1E7ED); lineY += font.lineHeight + 2; }
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (helpOpen && !helpButton.isMouseOver(event.x(), event.y())) {
            if (!helpPanel().contains(event.x(), event.y())) helpOpen = false;
            return true; // Consume outside dismissal so it cannot also select a cohort or trigger an action.
        }
        return super.mouseClicked(event, doubleClick);
    }
    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        hoveredCohort = null;
        return helpOpen || super.mouseScrolled(x, y, sx, sy);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (helpOpen) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) helpOpen = false;
            else helpButton.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    /** Reuse Minecraft's clipped wheel/drag/keyboard list; no extra screen or widget file is needed. */
    private final class CohortList extends ObjectSelectionList<CohortRow> {
        CohortList(List<ManagementDataPayload.CohortEntry> cohorts) {
            super(ComposeArmyScreen.this.minecraft, ComposeArmyScreen.this.width - 24,
                    Math.max(25, ComposeArmyScreen.this.height - 182), 54, 23);
            setX(12);
            centerListVertically = false;
            cohorts.forEach(c -> addEntry(new CohortRow(c, selected.contains(c.id()), () -> {
                if (c.soldiers() > 0 && !selected.remove(c.id())) selected.add(c.id());
                rebuild();
            })));
        }
        @Override public int getRowWidth() { return Math.max(1, getWidth() - 16); }
        @Override protected int scrollBarX() { return getRight() - scrollbarWidth(); }
        @Override protected double scrollRate() { return 23; }
        @Override protected void extractListBackground(GuiGraphicsExtractor g) { }
        @Override protected void extractListSeparators(GuiGraphicsExtractor g) { }
        UUID cohortAt(double x, double y) {
            if (!isMouseOver(x, y)) return null;
            CohortRow entry = getEntryAtPosition(x, y);
            if (entry == null) return null;
            entry.positionButton();
            return entry.row.isMouseOver(x, y) ? entry.cohortId : null;
        }
    }

    private static final class CohortRow extends ObjectSelectionList.Entry<CohortRow> {
        private final Button row;
        private final UUID cohortId;
        CohortRow(ManagementDataPayload.CohortEntry c, boolean selected, Runnable toggle) {
            cohortId = c.id();
            var marker = ClientArmyData.get().cohortsById().get(c.id());
            String status = c.soldiers() == 0 ? "Lost" : c.settlementId() != null ? "Garrison" : c.armyId() != null ? "Deployed"
                    : marker != null ? marker.retreating() ? "Retreating" : "Detached" : "Field body";
            row = Button.builder(Component.literal((selected ? "[x] " : "[ ] ") + c.label()
                    + " " + c.soldiers() + "/" + c.maximum() + " | " + status), b -> toggle.run()).bounds(0, 0, 1, 20).build();
        }
        private void positionButton() {
            // setRectangle takes width/height before x/y in this version; explicit setters avoid swapping them.
            row.setPosition(getX(), getY() + 1);
            row.setSize(getWidth(), 20);
        }
        @Override public void extractContent(GuiGraphicsExtractor g, int mx, int my, boolean hovered, float tick) {
            positionButton();
            row.setFocused(isFocused());
            row.extractRenderState(g, mx, my, tick);
        }
        @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            positionButton();
            return row.mouseClicked(event, doubleClick);
        }
        @Override public boolean keyPressed(KeyEvent event) { return row.keyPressed(event); }
        @Override public Component getNarration() { return row.getMessage(); }
    }
    @Override public void onClose() { minecraft.gui.setScreen(returnScreen == null ? new EmpireManagementScreen() : returnScreen); }
    @Override public boolean isPauseScreen() { return false; }
}
