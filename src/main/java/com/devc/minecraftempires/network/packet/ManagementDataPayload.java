package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.territory.SettlementData;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.state.WorldNews;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Private, owner-only information for management screens; never used as authority by the server. */
public record ManagementDataPayload(UUID stateId, String stateName, String tier, long treasury,
        int graceDays, long dailyIncome, long dailyUpkeep, boolean freeLegion, boolean endgameChoice,
        List<SettlementEntry> settlements, List<LegionEntry> legions, List<CohortEntry> cohorts,
        List<RelationEntry> relations, List<CohortSupplyEntry> supplies, List<CampEntry> camps,
        SelectedSupplyRoute selectedRoute, List<ArmyEntry> armies, List<CampaignEntry> campaigns) implements CustomPacketPayload {
    public static final Type<ManagementDataPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "management_data"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ManagementDataPayload> STREAM_CODEC = StreamCodec.ofMember(ManagementDataPayload::write, ManagementDataPayload::new);
    public ManagementDataPayload {
        settlements = List.copyOf(settlements); legions = List.copyOf(legions);
        cohorts = List.copyOf(cohorts); relations = List.copyOf(relations);
        supplies = List.copyOf(supplies); camps = List.copyOf(camps);
        armies = List.copyOf(armies); campaigns = List.copyOf(campaigns);
        selectedRoute = selectedRoute == null ? SelectedSupplyRoute.empty() : selectedRoute;
        if (settlements.size() > 8192 || legions.size() > 8192 || cohorts.size() > 8192
                || relations.size() > 8192 || supplies.size() > 8192 || camps.size() > 8192 || armies.size() > 8192 || campaigns.size() > 8192)
            throw new IllegalArgumentException("Too many management entries");
    }
    // Retain construction convenience for older callers; matching clients use the extended wire format.
    public ManagementDataPayload(UUID stateId, String stateName, String tier, long treasury,
            int graceDays, long dailyIncome, long dailyUpkeep, boolean freeLegion, boolean endgameChoice,
            List<SettlementEntry> settlements, List<LegionEntry> legions, List<CohortEntry> cohorts,
            List<RelationEntry> relations, List<CohortSupplyEntry> supplies, List<CampEntry> camps, SelectedSupplyRoute selectedRoute) {
        this(stateId, stateName, tier, treasury, graceDays, dailyIncome, dailyUpkeep, freeLegion, endgameChoice,
                settlements, legions, cohorts, relations, supplies, camps, selectedRoute, List.of(), List.of());
    }
    public ManagementDataPayload(UUID stateId, String stateName, String tier, long treasury,
            int graceDays, long dailyIncome, long dailyUpkeep, boolean freeLegion, boolean endgameChoice,
            List<SettlementEntry> settlements, List<LegionEntry> legions, List<CohortEntry> cohorts,
            List<RelationEntry> relations) {
        this(stateId, stateName, tier, treasury, graceDays, dailyIncome, dailyUpkeep, freeLegion,
                endgameChoice, settlements, legions, cohorts, relations, List.of(), List.of(), SelectedSupplyRoute.empty());
    }
    public ManagementDataPayload(RegistryFriendlyByteBuf b) {
        this(readId(b), b.readUtf(80), b.readUtf(32), b.readLong(), b.readVarInt(), b.readLong(), b.readLong(),
                b.readBoolean(), b.readBoolean(), readList(b, SettlementEntry::new), readList(b, LegionEntry::new),
                readList(b, CohortEntry::new), readList(b, RelationEntry::new), readList(b, CohortSupplyEntry::new),
                readList(b, CampEntry::new), new SelectedSupplyRoute(b), readList(b, ArmyEntry::new), readList(b, CampaignEntry::new));
    }
    private void write(RegistryFriendlyByteBuf b) {
        writeId(b, stateId); b.writeUtf(stateName, 80); b.writeUtf(tier, 32); b.writeLong(treasury);
        b.writeVarInt(graceDays); b.writeLong(dailyIncome); b.writeLong(dailyUpkeep);
        b.writeBoolean(freeLegion); b.writeBoolean(endgameChoice);
        b.writeVarInt(settlements.size()); settlements.forEach(s -> s.write(b));
        b.writeVarInt(legions.size()); legions.forEach(s -> s.write(b));
        b.writeVarInt(cohorts.size()); cohorts.forEach(s -> s.write(b));
        b.writeVarInt(relations.size()); relations.forEach(s -> s.write(b));
        b.writeVarInt(supplies.size()); supplies.forEach(s -> s.write(b));
        b.writeVarInt(camps.size()); camps.forEach(s -> s.write(b));
        selectedRoute.write(b);
        b.writeVarInt(armies.size()); armies.forEach(s -> s.write(b));
        b.writeVarInt(campaigns.size()); campaigns.forEach(s -> s.write(b));
    }
    /** Complete private army state is independent of scouted foreign map markers. */
    public record ArmyEntry(UUID id, String name, BlockPos position, int soldiers, int fieldSoldiers, int morale,
                           int upkeep, UUID campaignId, UUID battleId, String status, String releaseProblem) {
        ArmyEntry(RegistryFriendlyByteBuf b) {
            this(b.readUUID(), b.readUtf(80), b.readBlockPos(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                    readId(b), readId(b), b.readUtf(80), b.readUtf(160));
        }
        void write(RegistryFriendlyByteBuf b) {
            b.writeUUID(id); b.writeUtf(name, 80); b.writeBlockPos(position); b.writeVarInt(soldiers); b.writeVarInt(fieldSoldiers);
            b.writeVarInt(morale); b.writeVarInt(upkeep); writeId(b, campaignId); writeId(b, battleId); b.writeUtf(status, 80); b.writeUtf(releaseProblem, 160);
        }
    }
    public record CampaignEntry(UUID id, String name, String objective, boolean active, BlockPos focus,
                                List<UUID> armyIds, String startProblem, String endProblem) {
        public CampaignEntry {
            armyIds = List.copyOf(armyIds);
            if (armyIds.size() > 128) throw new IllegalArgumentException("Campaign roster too large");
        }
        CampaignEntry(RegistryFriendlyByteBuf b) {
            this(b.readUUID(), b.readUtf(80), b.readUtf(1024), b.readBoolean(), b.readBoolean() ? b.readBlockPos() : null,
                    readArmies(b), b.readUtf(160), b.readUtf(160));
        }
        private static List<UUID> readArmies(RegistryFriendlyByteBuf b) {
            int count = b.readVarInt();
            if (count < 0 || count > 128 || count > b.readableBytes() / 16) throw new IllegalArgumentException("Invalid campaign roster");
            List<UUID> ids = new ArrayList<>(count);
            for (int i = 0; i < count; i++) ids.add(b.readUUID());
            return ids;
        }
        void write(RegistryFriendlyByteBuf b) {
            b.writeUUID(id); b.writeUtf(name, 80); b.writeUtf(objective, 1024); b.writeBoolean(active);
            b.writeBoolean(focus != null); if (focus != null) b.writeBlockPos(focus);
            b.writeVarInt(armyIds.size()); armyIds.forEach(b::writeUUID); b.writeUtf(startProblem, 160); b.writeUtf(endProblem, 160);
        }
    }
    public record SettlementEntry(UUID id, String name, BlockPos position, int tier, int population, int capacity,
            boolean martialLaw, long supplyPreparationTicks, SettlementData.Kind kind) {
        public SettlementEntry(UUID id, String name, BlockPos position, int tier, int population, int capacity, boolean martialLaw, long supplyPreparationTicks) {
            this(id, name, position, tier, population, capacity, martialLaw, supplyPreparationTicks, tier >= 2 ? SettlementData.Kind.CITY : SettlementData.Kind.TOWN);
        }
        public SettlementEntry(UUID id, String name, BlockPos position, int tier, int population, int capacity, boolean martialLaw) {
            this(id, name, position, tier, population, capacity, martialLaw, 0L);
        }
        SettlementEntry(RegistryFriendlyByteBuf b) { this(b.readUUID(), b.readUtf(80), b.readBlockPos(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readVarLong(), b.readEnum(SettlementData.Kind.class)); }
        void write(RegistryFriendlyByteBuf b) { b.writeUUID(id); b.writeUtf(name, 80); b.writeBlockPos(position); b.writeVarInt(tier); b.writeVarInt(population); b.writeVarInt(capacity); b.writeBoolean(martialLaw); b.writeVarLong(supplyPreparationTicks); b.writeEnum(kind); }
        public String tierLabel() { return kind.label(); }
    }
    public record LegionEntry(UUID id, BlockPos position, int soldiers, boolean pendingTerrain) {
        public LegionEntry(UUID id, BlockPos position, int soldiers) { this(id, position, soldiers, false); }
        LegionEntry(RegistryFriendlyByteBuf b) { this(b.readUUID(), b.readBlockPos(), b.readVarInt(), b.readBoolean()); }
        void write(RegistryFriendlyByteBuf b) { b.writeUUID(id); b.writeBlockPos(position); b.writeVarInt(soldiers); b.writeBoolean(pendingTerrain); }
    }
    public record CohortEntry(UUID id, UUID legionId, String label, String type, BlockPos position,
            int soldiers, int maximum, int health, int morale, int endurance, int strength, int speed, int xp,
            String gear, UUID armyId, UUID settlementId) {
        CohortEntry(RegistryFriendlyByteBuf b) {
            this(b.readUUID(), b.readUUID(), b.readUtf(80), b.readUtf(32), b.readBlockPos(), b.readVarInt(),
                    b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                    b.readVarInt(), b.readUtf(32), readId(b), readId(b));
        }
        void write(RegistryFriendlyByteBuf b) {
            b.writeUUID(id); b.writeUUID(legionId); b.writeUtf(label, 80); b.writeUtf(type, 32); b.writeBlockPos(position);
            b.writeVarInt(soldiers); b.writeVarInt(maximum); b.writeVarInt(health); b.writeVarInt(morale);
            b.writeVarInt(endurance); b.writeVarInt(strength); b.writeVarInt(speed); b.writeVarInt(xp);
            b.writeUtf(gear, 32); writeId(b, armyId); writeId(b, settlementId);
        }
        public boolean available() { return soldiers > 0 && armyId == null && settlementId == null; }
    }
    public record RelationEntry(UUID id, String name, boolean war, boolean passageGranted, boolean passageReceived, boolean peaceOffered,
                                boolean ai, UUID surrenderToken, String surrenderTerms) {
        public RelationEntry(UUID id, String name, boolean war, boolean granted, boolean received, boolean peace) { this(id, name, war, granted, received, peace, false, null, ""); }
        RelationEntry(RegistryFriendlyByteBuf b) { this(b.readUUID(), b.readUtf(80), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean(), readId(b), b.readUtf(1024)); }
        void write(RegistryFriendlyByteBuf b) { b.writeUUID(id); b.writeUtf(name, 80); b.writeBoolean(war); b.writeBoolean(passageGranted); b.writeBoolean(passageReceived); b.writeBoolean(peaceOffered); b.writeBoolean(ai); writeId(b, surrenderToken); b.writeUtf(surrenderTerms, 1024); }
    }
    /** Cohort-owned reserves/fatigue are private; base endurance remains in CohortEntry. */
    public record CohortSupplyEntry(UUID cohortId, String status, String reason, String sourceName,
            UUID sourceId, UUID campId, double cost, double reserveDays, int fatigue, int effectiveEndurance) {
        public CohortSupplyEntry {
            status = status == null ? "" : status;
            reason = reason == null ? "" : reason;
            sourceName = sourceName == null ? "" : sourceName;
        }
        CohortSupplyEntry(RegistryFriendlyByteBuf b) {
            this(b.readUUID(), b.readUtf(40), b.readUtf(160), b.readUtf(80), readId(b), readId(b),
                    b.readDouble(), b.readDouble(), b.readVarInt(), b.readVarInt());
        }
        void write(RegistryFriendlyByteBuf b) {
            b.writeUUID(cohortId); b.writeUtf(status, 40); b.writeUtf(reason, 160); b.writeUtf(sourceName, 80);
            writeId(b, sourceId); writeId(b, campId); b.writeDouble(cost); b.writeDouble(reserveDays);
            b.writeVarInt(fatigue); b.writeVarInt(effectiveEndurance);
        }
    }
    public record CampEntry(UUID id, BlockPos position, long stock, long capacity, boolean connected) {
        CampEntry(RegistryFriendlyByteBuf b) { this(b.readUUID(), b.readBlockPos(), b.readVarLong(), b.readVarLong(), b.readBoolean()); }
        void write(RegistryFriendlyByteBuf b) { b.writeUUID(id); b.writeBlockPos(position); b.writeVarLong(stock); b.writeVarLong(capacity); b.writeBoolean(connected); }
    }
    /** Only one owned selection has detailed geometry; the bound keeps refresh packets predictable. */
    public record SelectedSupplyRoute(UUID targetId, List<BlockPos> points, boolean truncated) {
        public static final int MAX_POINTS = 2048;
        public SelectedSupplyRoute {
            points = points.stream().map(BlockPos::immutable).toList();
            if (points.size() > MAX_POINTS) throw new IllegalArgumentException("Supply route too long");
        }
        SelectedSupplyRoute(RegistryFriendlyByteBuf b) { this(readId(b), readPoints(b), b.readBoolean()); }
        private static List<BlockPos> readPoints(RegistryFriendlyByteBuf b) {
            int count = b.readVarInt();
            if (count < 0 || count > MAX_POINTS) throw new IllegalArgumentException("Invalid supply route count");
            List<BlockPos> points = new ArrayList<>(count);
            for (int i = 0; i < count; i++) points.add(b.readBlockPos());
            return points;
        }
        void write(RegistryFriendlyByteBuf b) {
            writeId(b, targetId); b.writeVarInt(points.size()); points.forEach(b::writeBlockPos); b.writeBoolean(truncated);
        }
        public static SelectedSupplyRoute empty() { return new SelectedSupplyRoute(null, List.of(), false); }
    }
    /** News travels on demand, independently of the recurring management/army snapshot. */
    public record NewsRequest(long requestId, long before, long through, WorldNews.Filter filter, boolean ownOnly) implements CustomPacketPayload {
        public static final Type<NewsRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "request_news"));
        public static final StreamCodec<RegistryFriendlyByteBuf, NewsRequest> STREAM_CODEC = StreamCodec.ofMember(NewsRequest::write, NewsRequest::new);
        public NewsRequest {
            if (requestId < 1 || before < 0 || through < 0 || filter == null || before != 0 && (through == 0 || before > through))
                throw new IllegalArgumentException("Invalid news cursor");
        }
        public NewsRequest(RegistryFriendlyByteBuf b) { this(b.readLong(), b.readLong(), b.readLong(), b.readEnum(WorldNews.Filter.class), b.readBoolean()); }
        private void write(RegistryFriendlyByteBuf b) { b.writeLong(requestId); b.writeLong(before); b.writeLong(through); b.writeEnum(filter); b.writeBoolean(ownOnly); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record NewsEntry(UUID id, long sequence, long time, WorldNews.Kind kind, String headline, UUID settlement, BlockPos location) {
        public NewsEntry {
            if (id == null || sequence < 1 || time < 0 || kind == null || headline == null || headline.length() > 512 || (settlement == null) != (location == null))
                throw new IllegalArgumentException("Invalid news entry");
        }
        private NewsEntry(RegistryFriendlyByteBuf b) {
            this(b.readUUID(), b.readLong(), b.readLong(), b.readEnum(WorldNews.Kind.class), b.readUtf(512), readId(b), b.readBoolean() ? b.readBlockPos() : null);
        }
        private void write(RegistryFriendlyByteBuf b) {
            b.writeUUID(id); b.writeLong(sequence); b.writeLong(time); b.writeEnum(kind); b.writeUtf(headline, 512);
            writeId(b, settlement); b.writeBoolean(location != null); if (location != null) b.writeBlockPos(location);
        }
    }
    public record NewsPage(long requestId, UUID viewer, long before, long through, WorldNews.Filter filter, boolean ownOnly,
                           boolean hasOlder, boolean trimmed, long gameTime, List<NewsEntry> entries) implements CustomPacketPayload {
        public static final Type<NewsPage> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "news_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, NewsPage> STREAM_CODEC = StreamCodec.ofMember(NewsPage::write, NewsPage::new);
        public NewsPage {
            entries = List.copyOf(entries);
            if (requestId < 1 || before < 0 || through < 0 || gameTime < 0 || filter == null || entries.size() > WorldNews.MAX_PAGE || viewer == null && !entries.isEmpty())
                throw new IllegalArgumentException("Invalid news page");
            long previous = before == 0 ? Long.MAX_VALUE : before;
            for (var entry : entries) {
                if (entry.sequence() >= previous || entry.sequence() > through) throw new IllegalArgumentException("Unordered news page");
                previous = entry.sequence();
            }
            if (hasOlder && entries.isEmpty()) throw new IllegalArgumentException("Empty older news cursor");
        }
        public NewsPage(RegistryFriendlyByteBuf b) {
            this(b.readLong(), readId(b), b.readLong(), b.readLong(), b.readEnum(WorldNews.Filter.class), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readLong(), readNews(b));
        }
        private static List<NewsEntry> readNews(RegistryFriendlyByteBuf b) {
            int count = b.readVarInt(); if (count < 0 || count > WorldNews.MAX_PAGE) throw new IllegalArgumentException("Invalid news page size");
            List<NewsEntry> entries = new ArrayList<>(count); for (int i = 0; i < count; i++) entries.add(new NewsEntry(b)); return entries;
        }
        private void write(RegistryFriendlyByteBuf b) {
            b.writeLong(requestId); writeId(b, viewer); b.writeLong(before); b.writeLong(through); b.writeEnum(filter); b.writeBoolean(ownOnly);
            b.writeBoolean(hasOlder); b.writeBoolean(trimmed); b.writeLong(gameTime); b.writeVarInt(entries.size()); entries.forEach(e -> e.write(b));
        }
        public long nextBefore() { return entries.isEmpty() ? 0 : entries.getLast().sequence(); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    private static UUID readId(RegistryFriendlyByteBuf b) { return b.readBoolean() ? b.readUUID() : null; }
    private static void writeId(RegistryFriendlyByteBuf b, UUID id) { b.writeBoolean(id != null); if (id != null) b.writeUUID(id); }
    private static <T> List<T> readList(RegistryFriendlyByteBuf b, Function<RegistryFriendlyByteBuf, T> reader) {
        int count = b.readVarInt();
        if (count < 0 || count > 8192) throw new IllegalArgumentException("Invalid management list size");
        List<T> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) entries.add(reader.apply(b));
        return entries;
    }
    public static ManagementDataPayload empty() {
        return new ManagementDataPayload(null, "", "", 0, 0, 0, 0, false, false, List.of(), List.of(), List.of(), List.of());
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
