package com.devc.minecraftempires.client.gui.screen;

import com.devc.minecraftempires.client.ClientNetworking;
import com.devc.minecraftempires.client.map.ClientManagementData;
import com.devc.minecraftempires.network.packet.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.*;

/** A small, server-authoritative management hub for the first playable UI pass. */
public final class EmpireManagementScreen extends Screen {
    private static final UUID NONE = new UUID(0, 0);
    private int tab, page, settlementIndex, ticks, dataVersion = -1;
    private UUID selected;
    private final MapScreen mapScreen;
    private ManagementList overview;
    private com.devc.minecraftempires.state.WorldNews.Filter newsFilter = com.devc.minecraftempires.state.WorldNews.Filter.ALL;
    private boolean ownNews;
    private final Deque<Long> newsBack = new ArrayDeque<>();
    private int newsVersion = -1;
    public EmpireManagementScreen() { this(new MapScreen()); }
    public EmpireManagementScreen(MapScreen mapScreen) {
        super(Component.literal("Empire Management"));
        this.mapScreen = mapScreen;
    }
    private ManagementDataPayload data() { return ClientManagementData.get(); }
    private int rows() { return Math.max(1, (height - 208) / 24); }
    private static String label(String key, Object... args) { return Component.translatable("gui.minecraftempires.management." + key, args).getString(); }
    private void rebuild() { clearWidgets(); init(); }
    private Button button(String label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, 20).build());
    }
    private void stateAction(String action, UUID target, String text) {
        ClientPacketDistributor.sendToServer(new StateActionPayload(action, target == null ? NONE : target, BlockPos.ZERO, text));
        ClientNetworking.requestMapData();
    }
    @Override protected void init() {
        dataVersion = ClientManagementData.version();
        String[] tabs = {"State", "Settlements", "Legions", "Diplomacy"};
        int tabWidth = (width - 46) / 4;
        for (int i = 0; i < tabs.length; i++) {
            final int next = i;
            button((tab == i ? "[" : "") + tabs[i] + (tab == i ? "]" : ""), 20 + i * (tabWidth + 2), 72, tabWidth,
                    () -> { tab = next; page = 0; selected = null; rebuild(); });
        }
        button((tab == 4 ? "[" : "") + label("armies") + (tab == 4 ? "]" : ""), 20 + tabWidth + 2, 96, tabWidth,
                () -> { tab = 4; page = 0; overview = null; rebuild(); });
        button((tab == 5 ? "[" : "") + label("campaigns") + (tab == 5 ? "]" : ""), 20 + 2 * (tabWidth + 2), 96, tabWidth,
                () -> { tab = 5; page = 0; overview = null; rebuild(); });
        button((tab == 6 ? "[" : "") + label("news") + (tab == 6 ? "]" : ""), 20 + 3 * (tabWidth + 2), 96, tabWidth,
                () -> { tab = 6; overview = null; refreshNews(); });
        button("Map", 20, height - 25, 70, () -> { mapScreen.setManagementReturn(this); minecraft.gui.setScreen(mapScreen); });
        button("Close", width - 90, height - 25, 70, this::onClose);
        if (data().stateId() == null) return;
        switch (tab) {
            case 0 -> {
                int w = (width - 46) / 2;
                button("Deposit 64 emeralds", 20, 129, w, () -> stateAction("DEPOSIT", null, "64"));
                button("Deposit all emeralds", 26 + w, 129, w, () -> stateAction("DEPOSIT", null, "all"));
                // Logistics entry points live with state infrastructure; their work still takes place on the map.
                button("View Roads", 20, 157, w, () -> {
                    minecraft.gui.setScreen(mapScreen);
                    mapScreen.startRoadPlanning();
                }).setTooltip(Tooltip.create(Component.literal("Return to the map to draw, purchase or remove roads.")));
                button(mapScreen.isSupplyRouteVisible() ? "Hide Supply Lines" : "Show Supply Lines", 26 + w, 157, w, () -> {
                    minecraft.gui.setScreen(mapScreen);
                    mapScreen.toggleSupplyRoute();
                }).setTooltip(Tooltip.create(Component.literal("Return to the map and select an army to inspect its supply route.")));
                button("View Bridges", 20, 185, w, () -> {
                    minecraft.gui.setScreen(mapScreen);
                    mapScreen.startBridgePlanning();
                }).setTooltip(Tooltip.create(Component.literal("Return to the map and choose two banks for a three-block-wide bridge.")));
                if (data().endgameChoice()) {
                    button("Choose government", 26 + w, 185, w, () -> minecraft.gui.setScreen(new GovernmentChoiceScreen()));
                }
            }
            case 1 -> settlements();
            case 2 -> legions();
            case 3 -> diplomacy();
            case 4 -> armies();
            case 5 -> campaigns();
            case 6 -> news();
            default -> { }
        }
    }
    private void settlements() {
        var entries = data().settlements();
        clampPage(entries.size());
        for (int i = page * rows(); i < Math.min(entries.size(), (page + 1) * rows()); i++) {
            var s = entries.get(i);
            button(shorten(s.name(), 25) + " | " + s.tierLabel() + " | Pop. " + s.population(), 20, 125 + (i % rows()) * 24, width - 40,
                    () -> minecraft.gui.setScreen(new SettlementManagementScreen(s.id(), s.name(), s.position())));
        }
        pagination(entries.size());
    }
    private void legions() {
        var entries = data().legions();
        clampPage(entries.size());
        for (int i = page * rows(); i < Math.min(entries.size(), (page + 1) * rows()); i++) {
            var l = entries.get(i);
            var body = com.devc.minecraftempires.client.map.ClientArmyData.get().legionsById().get(l.id());
            String location = l.pendingTerrain() ? "Awaiting terrain verification" : body == null ? "Assigned / detached" : "Field body: " + net.minecraft.world.level.ChunkPos.unpack(body.packedChunkPos()).x() * 16
                    + ", " + net.minecraft.world.level.ChunkPos.unpack(body.packedChunkPos()).z() * 16;
            button("Legion " + (i + 1) + " | " + l.soldiers() + " soldiers | " + location,
                    20, 125 + (i % rows()) * 24, width - 40, () -> minecraft.gui.setScreen(new ComposeArmyScreen(l.id(), this, null))).active = !l.pendingTerrain();
        }
        var settlements = data().settlements();
        if (!settlements.isEmpty()) {
            settlementIndex %= settlements.size();
            var s = settlements.get(settlementIndex);
            int w = (width - 46) / 2;
            button("At: " + shorten(s.name(), 17), 20, height - 77, w, () -> { settlementIndex++; rebuild(); });
            button(data().freeLegion() ? "Raise free legion" : "Raise legion: 5,000", 26 + w, height - 77, w, () -> {
                ClientPacketDistributor.sendToServer(new ArmyActionPayload("RECRUIT_LEGION", s.id(), s.position()));
                ClientNetworking.requestMapData();
            });
        }
        pagination(entries.size());
    }
    private void diplomacy() {
        var entries = data().relations();
        clampPage(entries.size());
        for (int i = page * rows(); i < Math.min(entries.size(), (page + 1) * rows()); i++) {
            var r = entries.get(i);
            button((r.id().equals(selected) ? "> " : "") + shorten(r.name(), 25) + " | " + (r.war() ? "War" : r.passageReceived() ? "Passage allowed" : "Peace"),
                    20, 125 + (i % rows()) * 24, width - 40, () -> { selected = r.id(); rebuild(); });
        }
        entries.stream().filter(r -> r.id().equals(selected)).findFirst().ifPresent(r -> {
            int w = (width - 46) / 2;
            button(r.war() ? r.peaceOffered() ? "Accept peace" : "Offer peace" : "Declare war", 20, height - 77, w,
                    () -> stateAction(r.war() ? r.peaceOffered() ? "ACCEPT_PEACE" : "OFFER_PEACE" : "DECLARE_WAR", r.id(), ""));
            if (!r.war()) button(r.passageGranted() ? "Revoke passage" : "Grant passage", 26 + w, height - 77, w,
                    () -> stateAction(r.passageGranted() ? "REVOKE_PASSAGE" : "GRANT_PASSAGE", r.id(), ""));
            if (r.war() && r.ai()) button(r.surrenderToken() == null ? "Demand surrender" : "Review surrender", 26 + w, height - 77, w, () -> {
                if (r.surrenderToken() == null) stateAction("DEMAND_SURRENDER", r.id(), "");
                else minecraft.gui.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(accepted -> {
                    minecraft.gui.setScreen(this);
                    if (accepted) stateAction("ACCEPT_SURRENDER", r.id(), r.surrenderToken().toString());
                }, Component.literal("Accept national surrender?"), Component.literal(r.surrenderTerms())));
            });
        });
        pagination(entries.size());
    }
    private void clampPage(int count) { page = Math.min(page, Math.max(0, (count - 1) / rows())); }
    private void refreshNews() {
        newsBack.clear(); loadNews(0, 0);
    }
    private void loadNews(long before, long through) {
        if (ClientManagementData.news() == null || ClientManagementData.news().before() != before) overview = null;
        ClientNetworking.requestNews(ClientManagementData.beginNews(before, through, newsFilter, ownNews)); rebuild();
    }
    private void news() {
        newsVersion = ClientManagementData.newsVersion();
        int w = (width - 46) / 2;
        button(label("news_filter", label("news_filter_" + newsFilter.name().toLowerCase(Locale.ROOT))), 20, 125, w, () -> {
            var filters = com.devc.minecraftempires.state.WorldNews.Filter.values(); newsFilter = filters[(newsFilter.ordinal() + 1) % filters.length];
            overview = null; refreshNews();
        });
        button(label(ownNews ? "news_own" : "news_known"), 26 + w, 125, w, () -> { ownNews = !ownNews; overview = null; refreshNews(); });
        double scroll = overview == null ? 0 : overview.scrollAmount();
        overview = addRenderableWidget(new ManagementList(width, 151, height - 210));
        var current = ClientManagementData.news(); boolean loading = ClientManagementData.newsLoading();
        if (loading) overview.text(label("news_loading"));
        if (current == null || current.entries().isEmpty()) {
            if (!loading) overview.text(label("news_empty"));
        } else for (var entry : current.entries()) {
            overview.text(label("news_stamp", entry.time() / com.devc.minecraftempires.state.StateBalance.TICKS_PER_DAY + 1,
                    label("news_kind_" + entry.kind().name().toLowerCase(Locale.ROOT))));
            overview.text(entry.headline());
            if (entry.location() != null) overview.action(label("news_focus"), () -> {
                mapScreen.setManagementReturn(this); mapScreen.focusLocations(List.of(entry.location()), null); minecraft.gui.setScreen(mapScreen);
            }, true, label("news_focus_hint"));
        }
        if (current != null && current.trimmed()) overview.text(label("news_trimmed"));
        overview.text(label("news_knowledge")); overview.setScrollAmount(scroll);
        button(label("news_newer"), 20, height - 51, 90, () -> loadNews(newsBack.pop(), current.through())).active = !loading && current != null && !newsBack.isEmpty();
        button(label("news_older"), width / 2 - 45, height - 51, 90, () -> {
            newsBack.push(current.before()); loadNews(current.nextBefore(), current.through());
        }).active = !loading && current != null && current.hasOlder();
        button(label("news_refresh"), width - 110, height - 51, 90, this::refreshNews);
    }
    private void pagination(int count) {
        if (page > 0) button("Previous", 20, height - 51, 90, () -> { page--; rebuild(); });
        if ((page + 1) * rows() < count) button("Next", width - 110, height - 51, 90, () -> { page++; rebuild(); });
    }
    @Override public void tick() {
        if (++ticks % 20 == 0) ClientNetworking.requestMapData();
        if (tab == 6 && data().stateId() != null) {
            if (ClientManagementData.newsLoading()) {
                if (ticks % 20 == 0) ClientNetworking.requestNews(ClientManagementData.newsRequest());
            } else if (ClientManagementData.news() == null || ticks % 100 == 0 && newsBack.isEmpty()
                    && ClientManagementData.news().before() == 0) refreshNews();
            if (newsVersion != ClientManagementData.newsVersion()) rebuild();
        }
        if (dataVersion != ClientManagementData.version()) rebuild();
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        g.fill(0, 0, width, height, 0xF0101820);
        g.centeredText(font, title, width / 2, 10, 0xFFFFD45A);
        var d = data();
        if (d.stateId() == null) {
            g.centeredText(font, Component.literal("Place a crafted City Altar to found your county."), width / 2, 112, 0xFFFFFFFF);
        } else {
            g.centeredText(font, Component.literal(shorten(d.stateName(), 35) + " | " + d.tier().replace('_', ' ')), width / 2, 27, 0xFFFFFFFF);
            g.centeredText(font, Component.literal("Treasury " + d.treasury() + " | Tax/day " + d.dailyIncome() + " | Upkeep/day " + d.dailyUpkeep()), width / 2, 41, 0xFFCCD5DE);
            g.centeredText(font, Component.literal(d.graceDays() > 0 ? "Upkeep waived: " + d.graceDays() + " days remaining" : "Upkeep is charged daily"), width / 2, 55, 0xFFAACAA0);
            if (tab == 0 && height > 260) g.centeredText(font, Component.literal(d.endgameChoice() ? "Choose your permanent government path." : "Develop settlements to unlock further expansion."), width / 2, 217, 0xFFCCD5DE);
        }
        super.extractRenderState(g, x, y, tick);
    }
    public static String shorten(String s, int limit) { return s.length() > limit ? s.substring(0, limit - 1) + "…" : s; }

    //"army" tab
    private void armies() {
        double scroll = overview == null ? 0 : overview.scrollAmount();
        overview = addRenderableWidget(new ManagementList(width, 125, height - 181));
        for (var army : data().armies()) overview.action(army.name() + " | " + army.soldiers() + " | " + army.status(),
                () -> minecraft.gui.setScreen(new ArmyDetails(this, army.id())), true, "");
        if (data().armies().isEmpty()) overview.text(label("no_armies"));
        overview.setScrollAmount(scroll);
        button(label("choose_cohorts"), 20, height - 51, width - 40, () -> { tab = 2; overview = null; rebuild(); });
    }
    //campaign tab
    private void campaigns() {
        double scroll = overview == null ? 0 : overview.scrollAmount();
        overview = addRenderableWidget(new ManagementList(width, 125, height - 181));
        for (var campaign : data().campaigns()) overview.action((campaign.active() ? label("active") : label("planned")) + " | "
                        + campaign.name() + " | " + campaign.armyIds().size() + " " + label("armies"),
                () -> minecraft.gui.setScreen(new CampaignEditor(this, campaign.id())), true, "");
        if (data().campaigns().isEmpty()) overview.text(label("no_campaigns"));
        overview.setScrollAmount(scroll);
        button(label("new_campaign"), 20, height - 51, width - 40, () -> minecraft.gui.setScreen(new CampaignEditor(this, null)));
    }
    private void showArmyOnMap(ManagementDataPayload.ArmyEntry army, Screen returnTo) {
        mapScreen.setManagementReturn(returnTo);
        mapScreen.focusLocations(List.of(army.position()), army.id());
        minecraft.gui.setScreen(mapScreen);
    }
    private void showCampaignOnMap(BlockPos focus, Collection<UUID> ids, Screen returnTo) {
        List<BlockPos> positions = focus == null ? data().armies().stream().filter(a -> ids.contains(a.id())).map(ManagementDataPayload.ArmyEntry::position).toList() : List.of(focus);
        mapScreen.setManagementReturn(returnTo);
        mapScreen.focusLocations(positions, null);
        minecraft.gui.setScreen(mapScreen);
    }
    public static Screen composition(Screen roster, Screen parent, List<UUID> cohorts) {
        EmpireManagementScreen hub = parent instanceof EmpireManagementScreen management ? management
                : parent instanceof ArmyDetails details ? details.hub : new EmpireManagementScreen();
        hub.tab = 4;
        return new ArmyComposer(hub, roster, cohorts);
    }

    /** Existing native lists supply clipping, scrolling, keyboard focus and stable row bounds. */
    //button and text wrapper
    private static final class ManagementList extends ObjectSelectionList<ManagementRow> {
        ManagementList(int screenWidth, int top, int listHeight) {
            super(net.minecraft.client.Minecraft.getInstance(), Math.max(1, screenWidth - 32), Math.max(22, listHeight), top, 23);
            setX(16); centerListVertically = false;
        }
        @Override public int getRowWidth() { return Math.max(1, getWidth() - 16); }
        @Override protected int scrollBarX() { return getRight() - scrollbarWidth(); }
        @Override protected double scrollRate() { return 23; }
        @Override protected void extractListBackground(GuiGraphicsExtractor g) { }
        @Override protected void extractListSeparators(GuiGraphicsExtractor g) { }
        void text(String text) {
            var font = net.minecraft.client.Minecraft.getInstance().font;
            for (var line : font.split(Component.literal(text), Math.max(1, getRowWidth() - 8))) addEntry(new ManagementRow(line));
        }
        Button action(String text, Runnable run, boolean enabled, String reason) {
            Button button = Button.builder(Component.literal(text), b -> run.run()).bounds(0, 0, 1, 20).build();
            button.active = enabled;
            if (!reason.isBlank()) button.setTooltip(Tooltip.create(Component.literal(reason)));
            addEntry(new ManagementRow(button));
            return button;
        }
    }

    //row wrapper for buttons and text
    private static final class ManagementRow extends ObjectSelectionList.Entry<ManagementRow> {
        private final Button button;
        private final net.minecraft.util.FormattedCharSequence text;
        ManagementRow(Button button) { this.button = button; text = null; }
        ManagementRow(net.minecraft.util.FormattedCharSequence text) { this.text = text; button = null; }
        private void place() { if (button != null) { button.setPosition(getX(), getY() + 1); button.setSize(getWidth(), 20); } }
        @Override public void extractContent(GuiGraphicsExtractor g, int mx, int my, boolean hovered, float tick) {
            place();
            if (button != null) { button.setFocused(isFocused()); button.extractRenderState(g, mx, my, tick); }
            else g.text(net.minecraft.client.Minecraft.getInstance().font, text, getX() + 3, getY() + 6, 0xFFCCD5DE);
        }
        @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) { place(); return button != null && button.mouseClicked(event, doubleClick); }
        @Override public boolean keyPressed(KeyEvent event) { return button != null && button.keyPressed(event); }
        @Override public Component getNarration() { return button == null ? Component.empty() : button.getMessage(); }
    }

    //scrollable details screen for a single army
    private static final class ArmyDetails extends Screen {
        private final EmpireManagementScreen hub;
        private final UUID armyId;
        private final Screen returnTo;
        private ManagementList list;
        private int version, ticks;
        ArmyDetails(EmpireManagementScreen hub, UUID armyId) { this(hub, armyId, null); }
        ArmyDetails(EmpireManagementScreen hub, UUID armyId, Screen returnTo) {
            super(Component.literal(label("armies"))); this.hub = hub; this.armyId = armyId; this.returnTo = returnTo;
        }
        @Override protected void init() {
            double scroll = list == null ? 0 : list.scrollAmount();
            clearWidgets(); version = ClientManagementData.version();
            list = addRenderableWidget(new ManagementList(width, 36, height - 72));
            var data = ClientManagementData.get();
            var army = data.armies().stream().filter(a -> a.id().equals(armyId)).findFirst().orElse(null);
            if (army == null) list.text(label("army_missing"));
            else {
                list.text(army.name() + " | " + army.status());
                list.text(label("strength", army.soldiers(), army.fieldSoldiers(), army.morale(), army.upkeep()));
                list.action(label("view_map"), () -> hub.showArmyOnMap(army, this), true, "");
                var members = data.cohorts().stream().filter(c -> armyId.equals(c.armyId())).toList();
                list.action(label("manage_cohorts"), () -> {
                    if (!members.isEmpty()) minecraft.gui.setScreen(new ComposeArmyScreen(members.getFirst().legionId(), this, armyId));
                }, !members.isEmpty(), "");
                list.action(label("open_battle"), () -> ClientNetworking.requestSpectate(army.battleId()), army.battleId() != null, "");
                list.action(label("view_campaign"), () -> minecraft.gui.setScreen(new CampaignEditor(hub, army.campaignId())), army.campaignId() != null, "");
                list.action(label("release_army"), () -> {
                    ClientPacketDistributor.sendToServer(new DisbandArmyPayload(armyId)); ClientNetworking.requestMapData();
                }, army.releaseProblem().isBlank(), army.releaseProblem());
                if (!army.releaseProblem().isBlank()) list.text(army.releaseProblem());
                Set<UUID> memberIds = members.stream().map(ManagementDataPayload.CohortEntry::id).collect(java.util.stream.Collectors.toSet());
                var supplies = data.supplies().stream().filter(s -> memberIds.contains(s.cohortId())).toList();
                list.text(label("supply") + ": " + String.join(", ", supplies.stream().map(ManagementDataPayload.CohortSupplyEntry::status).distinct().toList()));
                if (!supplies.isEmpty()) list.text(label("reserves", String.format(Locale.ROOT, "%.1f", supplies.stream().mapToDouble(ManagementDataPayload.CohortSupplyEntry::reserveDays).min().orElse(0)),
                        String.format(Locale.ROOT, "%.1f", supplies.stream().mapToDouble(ManagementDataPayload.CohortSupplyEntry::reserveDays).max().orElse(0))));
                for (int i = 0; i < data.legions().size(); i++) {
                    UUID legionId = data.legions().get(i).id();
                    var group = members.stream().filter(c -> c.legionId().equals(legionId)).toList();
                    if (group.isEmpty()) continue;
                    list.text(label("legion", i + 1));
                    for (var cohort : group) {
                        list.text(cohort.label() + " | " + cohort.type() + " | " + cohort.soldiers() + "/" + cohort.maximum()
                                + " | " + label("morale", cohort.morale()) + (cohort.settlementId() == null ? "" : " | " + label("garrison")));
                        data.supplies().stream().filter(s -> s.cohortId().equals(cohort.id())).findFirst().ifPresent(s -> list.text(s.status() + ": " + s.reason()));
                    }
                }
            }
            list.setScrollAmount(scroll);
            addRenderableWidget(Button.builder(Component.literal(label("back")), b -> onClose()).bounds(width / 2 - 45, height - 25, 90, 20).build());
        }
        @Override public void tick() {
            if (++ticks % 20 == 0) ClientNetworking.requestMapData();
            if (version != ClientManagementData.version()) init();
        }
        @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
            g.fill(0, 0, width, height, 0xF0101820); g.centeredText(font, title, width / 2, 12, 0xFFFFD45A);
            super.extractRenderState(g, mx, my, partial);
        }
        @Override public void onClose() { hub.tab = 4; minecraft.gui.setScreen(returnTo == null ? hub : returnTo); }
        @Override public boolean isPauseScreen() { return false; }
    }

    //scrollable composition screen for a new army
    private static final class ArmyComposer extends Screen {
        private final EmpireManagementScreen hub;
        private final Screen roster;
        private final Set<UUID> selected = new LinkedHashSet<>();
        private final Set<UUID> candidates = new LinkedHashSet<>();
        private ManagementList list;
        private Button create;
        private int version, ticks, submittedAt = -1;
        ArmyComposer(EmpireManagementScreen hub, Screen roster, List<UUID> selected) {
            super(Component.literal(label("compose"))); this.hub = hub; this.roster = roster;
            this.selected.addAll(selected); candidates.addAll(selected);
        }
        private List<ManagementDataPayload.CohortEntry> members() { return ClientManagementData.get().cohorts().stream().filter(c -> selected.contains(c.id())).toList(); }
        private boolean ready() {
            var members = members();
            if (members.isEmpty() || members.size() != selected.size() || members.size() > 150 || members.stream().anyMatch(c -> !c.available())) return false;
            BlockPos first = members.getFirst().position();
            return members.stream().allMatch(c -> com.devc.minecraftempires.army.ArmyManager.near(first, c.position(), 16));
        }
        @Override protected void init() {
            double scroll = list == null ? 0 : list.scrollAmount();
            clearWidgets(); version = ClientManagementData.version();
            list = addRenderableWidget(new ManagementList(width, 36, height - 76));
            list.text(label("compose_hint"));
            for (var c : ClientManagementData.get().cohorts()) if (candidates.contains(c.id()))
                list.action((selected.contains(c.id()) ? "[x] " : "[ ] ") + c.label() + " | " + c.soldiers(),
                        () -> { if (!selected.remove(c.id())) selected.add(c.id()); init(); }, submittedAt < 0, "");
            if (!ready()) list.text(label("compose_unavailable"));
            list.setScrollAmount(scroll);
            create = addRenderableWidget(Button.builder(Component.literal(label("create_army")), b -> {
                if (!ready() || submittedAt >= 0) return;
                submittedAt = ticks;
                ClientPacketDistributor.sendToServer(new ComposeArmyPayload(List.copyOf(selected), BlockPos.ZERO));
                ClientNetworking.requestMapData(); create.active = false;
            }).bounds(16, height - 27, (width - 40) / 2, 20).build());
            create.active = ready() && submittedAt < 0;
            addRenderableWidget(Button.builder(Component.literal(label("back")), b -> onClose()).bounds(24 + (width - 40) / 2, height - 27, (width - 40) / 2, 20).build());
        }
        @Override public void tick() {
            if (++ticks % 20 == 0) ClientNetworking.requestMapData();
            if (submittedAt >= 0) {
                var members = members();
                if (members.size() == selected.size() && !members.isEmpty() && members.getFirst().armyId() != null
                        && members.stream().allMatch(c -> members.getFirst().armyId().equals(c.armyId()))) {
                    minecraft.gui.setScreen(new ArmyDetails(hub, members.getFirst().armyId())); return;
                }
                if (ticks - submittedAt >= 100) { submittedAt = -1; init(); }
            }
            if (version != ClientManagementData.version()) init();
        }
        @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
            g.fill(0, 0, width, height, 0xF0101820); g.centeredText(font, title, width / 2, 12, 0xFFFFD45A);
            super.extractRenderState(g, mx, my, partial);
        }
        @Override public void onClose() { minecraft.gui.setScreen(roster); }
        @Override public boolean isPauseScreen() { return false; }
    }

    //scrollable composition screen for a new campaign
    private static final class CampaignEditor extends Screen {
        private final EmpireManagementScreen hub;
        private final UUID campaignId;
        private final Set<UUID> assigned = new LinkedHashSet<>();
        private String name = "", objective = "";
        private BlockPos focus;
        private boolean loaded, dirty;
        private EditBox nameBox, objectiveBox;
        private ManagementList list;
        private Button save, start, end;
        private final List<Button> locationButtons = new ArrayList<>();
        private int version, ticks, submittedAt = -1;
        CampaignEditor(EmpireManagementScreen hub, UUID id) { super(Component.literal(label("campaigns"))); this.hub = hub; campaignId = id; }
        private ManagementDataPayload.CampaignEntry current() { return ClientManagementData.get().campaigns().stream().filter(c -> c.id().equals(campaignId)).findFirst().orElse(null); }
        @Override protected void init() {
            var current = current();
            if (!loaded && (current != null || campaignId == null)) {
                loaded = true; name = current == null ? label("new_campaign") : current.name(); objective = current == null ? "" : current.objective();
                focus = current == null ? null : current.focus(); if (current != null) assigned.addAll(current.armyIds()); dirty = current == null;
            }
            clearWidgets();
            locationButtons.clear();
            nameBox = new EditBox(font, 16, 34, width - 32, 20, Component.literal(label("name")));
            nameBox.setMaxLength(80); nameBox.setValue(name); nameBox.setResponder(value -> { name = value; dirty = true; }); addRenderableWidget(nameBox);
            objectiveBox = new EditBox(font, 16, 72, width - 32, 20, Component.literal(label("objective")));
            objectiveBox.setMaxLength(1024); objectiveBox.setValue(objective); objectiveBox.setResponder(value -> { objective = value; dirty = true; }); addRenderableWidget(objectiveBox);
            int w = (width - 40) / 3;
            locationButtons.add(addRenderableWidget(Button.builder(Component.literal(label("set_location")), b -> {
                hub.mapScreen.pickLocation(this, position -> { focus = position; dirty = true; });
                minecraft.gui.setScreen(hub.mapScreen);
            }).bounds(16, 100, w, 20).build()));
            locationButtons.add(addRenderableWidget(Button.builder(Component.literal(label("view_map")), b -> hub.showCampaignOnMap(focus, assigned, this)).bounds(20 + w, 100, w, 20).build()));
            locationButtons.add(addRenderableWidget(Button.builder(Component.literal(label("clear_location")), b -> { focus = null; dirty = true; refreshRoster(); }).bounds(24 + 2 * w, 100, w, 20).build()));
            int actionWidth = (width - 44) / 4;
            save = addRenderableWidget(Button.builder(Component.literal(label("save")), b -> {
                send("SAVE"); submittedAt = ticks; save.active = false;
            }).bounds(16, height - 27, actionWidth, 20).build());
            start = addRenderableWidget(Button.builder(Component.literal(label("start")), b -> { send("START"); onClose(); }).bounds(20 + actionWidth, height - 27, actionWidth, 20).build());
            end = addRenderableWidget(Button.builder(Component.literal(label(current != null && current.active() ? "end" : "delete")), b -> {
                send(current() != null && current().active() ? "END" : "DELETE"); onClose();
            }).bounds(24 + 2 * actionWidth, height - 27, actionWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal(label("back")), b -> onClose()).bounds(28 + 3 * actionWidth, height - 27, actionWidth, 20).build());
            refreshRoster(); updateActions();
        }
        private void refreshRoster() {
            double scroll = list == null ? 0 : list.scrollAmount();
            if (list != null) removeWidget(list);
            list = addRenderableWidget(new ManagementList(width, 127, height - 166)); version = ClientManagementData.version();
            var current = current();
            if (current != null && current.active()) assigned.addAll(current.armyIds());
            list.text(focus == null ? label("no_location") : label("location", focus.getX(), focus.getZ()));
            if (campaignId != null && current == null) list.text(label("campaign_missing"));
            else if (current != null) {
                list.text(current.active() ? label("active") : current.startProblem().isBlank() ? label("ready") : current.startProblem());
                if (current.active() && !current.endProblem().isBlank()) list.text(current.endProblem());
            }
            for (var army : ClientManagementData.get().armies()) {
                boolean checked = assigned.contains(army.id());
                boolean locked = current != null && current.active() && current.armyIds().contains(army.id());
                boolean unavailable = army.soldiers() == 0 || army.battleId() != null || army.status().contains("Retreat")
                        || army.campaignId() != null && !army.campaignId().equals(campaignId);
                list.action((checked ? "[x] " : "[ ] ") + army.name() + " | " + army.soldiers() + " | " + army.status()
                                + (army.campaignId() != null && !army.campaignId().equals(campaignId) ? " | " + label("committed") : ""),
                        () -> { if (!assigned.remove(army.id())) assigned.add(army.id()); dirty = true; refreshRoster(); },
                        submittedAt < 0 && !locked && (current == null || !current.active() || !unavailable), locked ? label("active_members_locked") : "");
                if (checked) {
                    var cohorts = ClientManagementData.get().cohorts().stream().filter(c -> army.id().equals(c.armyId())).toList();
                    long legions = cohorts.stream().map(ManagementDataPayload.CohortEntry::legionId).distinct().count();
                    list.action(label("inspect_participants", army.name(), legions, cohorts.size()),
                            () -> minecraft.gui.setScreen(new ArmyDetails(hub, army.id(), this)), submittedAt < 0, "");
                }
            }
            for (UUID missing : List.copyOf(assigned)) if (ClientManagementData.get().armies().stream().noneMatch(a -> a.id().equals(missing)))
                list.action(label("remove_missing_army"), () -> { assigned.remove(missing); dirty = true; refreshRoster(); }, submittedAt < 0, "");
            if (ClientManagementData.get().armies().isEmpty()) list.text(label("no_armies"));
            list.setScrollAmount(scroll);
        }
        private void updateActions() {
            var current = current();
            nameBox.setEditable(submittedAt < 0); objectiveBox.setEditable(submittedAt < 0);
            locationButtons.forEach(button -> button.active = submittedAt < 0);
            save.active = ClientManagementData.get().stateId() != null && loaded && !name.isBlank() && dirty && submittedAt < 0 && (campaignId == null || current != null);
            start.active = current != null && !current.active() && !dirty && current.startProblem().isBlank() && submittedAt < 0;
            end.active = current != null && submittedAt < 0 && (!current.active() || current.endProblem().isBlank());
            if (current != null) {
                start.setTooltip(Tooltip.create(Component.literal(dirty ? label("save_first") : current.startProblem())));
                end.setTooltip(Tooltip.create(Component.literal(current.endProblem())));
            }
        }
        private void send(String action) {
            ClientPacketDistributor.sendToServer(new ArmyActionPayload.CampaignAction(action, campaignId, name.strip(), objective, focus, List.copyOf(assigned)));
            ClientNetworking.requestMapData();
        }
        @Override public void tick() {
            if (++ticks % 20 == 0) ClientNetworking.requestMapData();
            if (!loaded && current() != null) { init(); return; }
            if (submittedAt >= 0) {
                boolean saved = ClientManagementData.get().campaigns().stream().anyMatch(c -> (campaignId == null || c.id().equals(campaignId))
                        && c.name().equals(name.strip()) && c.objective().equals(objective) && Objects.equals(c.focus(), focus) && new HashSet<>(c.armyIds()).equals(assigned));
                if (saved) { onClose(); return; }
                if (ticks - submittedAt >= 100) { submittedAt = -1; refreshRoster(); }
            }
            if (version != ClientManagementData.version()) refreshRoster();
            updateActions();
        }
        @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
            g.fill(0, 0, width, height, 0xF0101820); g.centeredText(font, title, width / 2, 8, 0xFFFFD45A);
            g.text(font, Component.literal(label("name")), 16, 23, 0xFFCCD5DE);
            g.text(font, Component.literal(label("objective")), 16, 60, 0xFFCCD5DE);
            super.extractRenderState(g, mx, my, partial);
        }
        @Override public void onClose() { hub.tab = 5; hub.overview = null; minecraft.gui.setScreen(hub); }
        @Override public boolean isPauseScreen() { return false; }
    }
    @Override public boolean isPauseScreen() { return false; }
}
