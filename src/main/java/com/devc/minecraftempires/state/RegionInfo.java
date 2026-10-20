package com.devc.minecraftempires.state;

import com.devc.minecraftempires.Config;
import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.network.GeographyPreviewService;
import com.devc.minecraftempires.territory.ClaimManager;
import com.devc.minecraftempires.territory.SettlementData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Seeded planning plus bounded initialization journals. Existing managers remain the live domain owners. */
/*
This class handles regional generation information.
It manages NPC-controlled states, independent settlements, and strategic regions based on a seeded random generation system. 
The class includes methods for creating and managing AI states, ensuring that they adhere to specific rules regarding spacing, biome restrictions, and progression requirements.
These regions are initialized in a way that allows for consistent and reproducible world generation, while also providing mechanisms for recovery and management of AI states.
*/
public final class RegionInfo {
    public static final int VERSION = 2, REGION_BLOCKS = ClaimManager.STRATEGIC_REGION_CHUNKS * 16;
    public static final int MAX_PENDING = 8, MAX_JOURNALS = 256;
    private static final long JOURNAL_BYTES = 2 * 1024 * 1024, DOMAIN_BYTES = 64 * 1024 * 1024;
    //default names for generated states, to avoid the need for a name database or human input
    private static final String[] FIRST = {"Ash", "Briar", "Cairn", "Dawn", "Elder", "Frost", "Golden", "High", "Iron", "Oak", "Raven", "Silver"};
    private static final String[] LAST = {"brook", "crest", "fall", "ford", "haven", "march", "mere", "reach", "vale", "watch", "wood", "wyck"};
    public record Manifest(int version, long seed, int density, int spawnX, int spawnZ) {
        public Manifest {
            if ((version != 1 && version != VERSION) || density < 0 || density > 100) throw new IllegalArgumentException("Unsupported AI generation manifest");
            if (version >= 2) density = Math.min(60, density);
        }
        public boolean neutral(int x, int z) { return Math.abs((long) x - spawnX) <= 1 && Math.abs((long) z - spawnZ) <= 1; }
        public CompoundTag toNBT() {
            CompoundTag tag = new CompoundTag(); tag.putInt("Version", version); tag.putLong("Seed", seed); tag.putInt("Density", density);
            tag.putInt("SpawnX", spawnX); tag.putInt("SpawnZ", spawnZ); return tag;
        }
        public static Manifest fromNBT(CompoundTag tag) {
            return new Manifest(tag.getInt("Version").orElseThrow(), tag.getLong("Seed").orElseThrow(), tag.getInt("Density").orElseThrow(),
                    tag.getInt("SpawnX").orElseThrow(), tag.getInt("SpawnZ").orElseThrow());
        }
    }
    public record Seed(UUID id, long worldSeed, int x, int z, int width, StateTier tier, int version) {
        public Seed(UUID id, long worldSeed, int x, int z, int width, StateTier tier) { this(id, worldSeed, x, z, width, tier, 1); }
        public Seed {
            Objects.requireNonNull(id); Objects.requireNonNull(tier);
            if ((version != 1 && version != VERSION) || (width != 2 && width != 4 && width != 8 && width != 16) || Math.floorMod(x, width) != 0 || Math.floorMod(z, width) != 0
                    || (long) x * REGION_BLOCKS < -30_000_000L || (long) z * REGION_BLOCKS < -30_000_000L
                    || ((long) x + width) * REGION_BLOCKS > 30_000_000L || ((long) z + width) * REGION_BLOCKS > 30_000_000L
                    || width == 2 && tier != StateTier.COUNTY && tier != StateTier.DUCHY || width == 4 && tier != StateTier.CITY_STATE
                    || width == 8 && tier != StateTier.KINGDOM || width == 16 && !tier.isPermanent() && !(version >= 2 && tier == StateTier.STATE)
                    || !id.equals(stableId(version, worldSeed, "nation", x, z, width))) throw new IllegalArgumentException("Invalid seeded district");
        }
        public UUID assetId(String kind, int index) { return stableId(version, worldSeed, kind + index, x, z, width); }
        public String name() {
            long hash = mix(worldSeed ^ new ChunkPos(x, z).pack());
            return FIRST[(int) Long.remainderUnsigned(hash, FIRST.length)] + LAST[(int) Long.remainderUnsigned(mix(hash), LAST.length)];
        }
        public CompoundTag toNBT() {
            CompoundTag tag = new CompoundTag(); tag.putString("Id", id.toString()); tag.putLong("Seed", worldSeed);
            tag.putInt("Version", version); tag.putInt("X", x); tag.putInt("Z", z); tag.putInt("Width", width); tag.putString("Tier", tier.name()); return tag;
        }
        public static Seed fromNBT(CompoundTag tag) {
            return new Seed(UUID.fromString(tag.getString("Id").orElseThrow()), tag.getLong("Seed").orElseThrow(),
                    tag.getInt("X").orElseThrow(), tag.getInt("Z").orElseThrow(), tag.getInt("Width").orElseThrow(), StateTier.valueOf(tag.getString("Tier").orElseThrow()), tag.getInt("Version").orElse(1));
        }
    }
    public record Site(UUID id, String name, BlockPos position, int population, int tier, SettlementData.Kind kind, boolean independent) {
        public Site(UUID id, String name, BlockPos position, int population, int tier) { this(id, name, position, population, tier, tier >= 2 ? SettlementData.Kind.CITY : SettlementData.Kind.TOWN, false); }
        public Site {
            Objects.requireNonNull(id); Objects.requireNonNull(kind); position = position.immutable(); name = StateManager.cleanName(name);
            if (population < 1 || population > 1_000_000 || tier < 1 || tier > 2 || (kind == SettlementData.Kind.CITY) != (tier == 2)) throw new IllegalArgumentException("Invalid seeded settlement");
        }
    }

    //compact record of a proposed AI state, including its seed, tier, treasury, settlements, strategic regions, and forces.
    // Validates constraints on the number of sites, regions, treasury amount, and forces based on the seed version and tier.
    public record Plan(Seed seed, StateTier tier, long treasury, List<Site> sites, List<ClaimManager.StrategicRegion> regions, int forces) {
        public Plan {
            sites = List.copyOf(sites); regions = List.copyOf(regions);
            if (sites.isEmpty() || sites.size() > (seed.version() == 1 ? 5 : 128) || regions.size() != seed.width() * seed.width() || treasury < 0 || treasury > 1_000_000_000L
                    || forces < (tier == StateTier.NOMADIC ? 0 : 1) || forces > (seed.version() == 1 ? Math.min(4, tier.getMaxLegions()) : tier.getMaxLegions())) throw new IllegalArgumentException("Invalid starting profile");
            Map<Long, ClaimManager.StrategicRegion> keys = new HashMap<>(); Set<UUID> ids = new HashSet<>();
            for (var region : regions) if (!seed.id().equals(region.owner()) || region.revision() != 0 || keys.putIfAbsent(region.key(), region) != null
                    || region.x() < seed.x() || region.x() >= seed.x() + seed.width() || region.z() < seed.z() || region.z() >= seed.z() + seed.width())
                throw new IllegalArgumentException("Invalid starting region");
            for (int i = 0; i < sites.size(); i++) {
                Site site = sites.get(i); long key = ClaimManager.regionKey(ChunkPos.containing(site.position()));
                if (!ids.add(site.id()) || !site.id().equals(seed.assetId("settlement", i)) || !keys.containsKey(key)
                        || !site.independent() && !keys.get(key).isLand(ChunkPos.containing(site.position()))) throw new IllegalArgumentException("Invalid starting site");
            }
            int population = sites.stream().filter(site -> !site.independent()).mapToInt(Site::population).sum(); long cities = sites.stream().filter(site -> !site.independent() && site.kind() == SettlementData.Kind.CITY).count();
            long towns = sites.stream().filter(site -> !site.independent() && site.kind() == SettlementData.Kind.TOWN).count();
            long area = regions.stream().mapToLong(ClaimManager.StrategicRegion::landChunks).sum();
            if (seed.version() >= 2 && tier == StateTier.NOMADIC && (forces != 0 || treasury != 0 || sites.stream().anyMatch(site -> !site.independent()) || area != 0)
                    || tier.ordinal() > seed.tier().ordinal() || tier.isPermanent() && tier != seed.tier() || area < tier.getMinChunks()
                    || sites.stream().anyMatch(site -> site.tier() == 2 && site.population() < StateBalance.CITY_POPULATION)
                    || tier == StateTier.DUCHY && cities < 1 || tier == StateTier.CITY_STATE && (cities < 1 || towns < 2)
                    || tier == StateTier.KINGDOM && population <= 5000 || tier.ordinal() >= StateTier.STATE.ordinal() && (cities < 5 || population <= 15000))
                throw new IllegalArgumentException("Starting profile does not meet its progression requirements");
        }
        public CompoundTag toNBT() {
            CompoundTag tag = new CompoundTag(); tag.put("Seed", seed.toNBT()); tag.putString("Tier", tier.name()); tag.putLong("Treasury", treasury); tag.putInt("Forces", forces);
            ListTag places = new ListTag(), land = new ListTag();
            for (Site site : sites) {
                CompoundTag item = new CompoundTag(); item.putString("Id", site.id().toString()); item.putString("Name", site.name()); item.putLong("Position", site.position().asLong());
                item.putInt("Population", site.population()); item.putInt("Tier", site.tier()); item.putString("Kind", site.kind().name()); item.putBoolean("Independent", site.independent()); places.add(item);
            }
            regions.forEach(region -> land.add(region.toNBT())); tag.put("Sites", places); tag.put("Regions", land); return tag;
        }
        public static Plan fromNBT(CompoundTag tag) {
            List<Site> sites = new ArrayList<>(); List<ClaimManager.StrategicRegion> regions = new ArrayList<>();
            if (tag.getListOrEmpty("Sites").size() > 128 || tag.getListOrEmpty("Regions").size() > 256) throw new IllegalArgumentException("Oversized initialization plan");
            for (var value : tag.getListOrEmpty("Sites")) {
                CompoundTag item = (CompoundTag) value; sites.add(new Site(UUID.fromString(item.getString("Id").orElseThrow()), item.getString("Name").orElseThrow(),
                        BlockPos.of(item.getLong("Position").orElseThrow()), item.getInt("Population").orElseThrow(), item.getInt("Tier").orElseThrow(), SettlementData.Kind.valueOf(item.getString("Kind").orElse(item.getInt("Tier").orElse(1) >= 2 ? "CITY" : "TOWN")), item.getBoolean("Independent").orElse(false)));
            }
            for (var value : tag.getListOrEmpty("Regions")) regions.add(ClaimManager.StrategicRegion.fromNBT((CompoundTag) value));
            return new Plan(Seed.fromNBT(tag.getCompound("Seed").orElseThrow()), StateTier.valueOf(tag.getString("Tier").orElseThrow()), tag.getLong("Treasury").orElseThrow(),
                    sites, regions, tag.getInt("Forces").orElseThrow());
        }
    }
    private record Journal(Seed seed, Plan plan) { boolean complete() { return plan == null; } }
    private record Bootstrap(Manifest manifest, List<Journal> journals) {}
    private final StateManager states;
    private final GeographyPreviewService geography = new GeographyPreviewService();
    private final Deque<Seed> pending = new ArrayDeque<>();
    private final Set<UUID> queued = new HashSet<>(), sealed = new HashSet<>();
    private final LinkedHashSet<UUID> unsuitable = new LinkedHashSet<>();
    private final Deque<Plan> recovery = new ArrayDeque<>();
    private Manifest manifest;
    private Path directory;
    private Builder builder;
    private Plan applying;
    private CompletableFuture<?> io;
    private String phase = "IDLE", failure = "";
    private boolean started, closed, checkedDisabled;
    private int playerCursor;

    public RegionInfo(StateManager states) { this.states = Objects.requireNonNull(states); }
    public Manifest manifest() { return manifest; }
    public int initializedDistricts() { return sealed.size(); }
    public int queuedDistricts() { return pending.size(); }
    public int cachedSamples() { return geography.cachedSamples(); }
    public String status() { return phase + (failure.isEmpty() ? "" : ": " + failure) + "; initialized=" + sealed.size() + ", queued=" + pending.size() + ", cached samples=" + geography.cachedSamples(); }

    public static long mix(long value) {
        value = (value ^ value >>> 30) * 0xbf58476d1ce4e5b9L; value = (value ^ value >>> 27) * 0x94d049bb133111ebL; return value ^ value >>> 31;
    }
    private static boolean foreignFootprint(BlockPos position, UUID owner, ClaimManager claims) {
        ChunkPos center = ChunkPos.containing(position); int radius = (StateBalance.SETTLEMENT_RADIUS + 15) / 16;
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            ChunkPos chunk = new ChunkPos(center.x() + dx, center.z() + dz); UUID existing = claims.getStrategicOwner(chunk);
            if (StateManager.isInSettlementFootprint(position, chunk, StateBalance.SETTLEMENT_RADIUS) && existing != null && !existing.equals(owner)) return true;
        }
        return false;
    }
    private static UUID stableId(long seed, String kind, int x, int z, int width) {
        return stableId(1, seed, kind, x, z, width);
    }
    private static UUID stableId(int version, long seed, String kind, int x, int z, int width) {
        return UUID.nameUUIDFromBytes(("minecraftempires:ai:" + version + ":" + seed + ":" + kind + ":" + x + ":" + z + ":" + width).getBytes(StandardCharsets.UTF_8));
    }
    /** Hierarchical seeded districts allow large states to span technical regions without exploration-order randomness. */
    //seed-based npc state generation algorithm
    /*
     * This algorithm generates a seed for each region based on the manifest and the region's coordinates.
     * The seed is used to create a stable ID for the AI state, ensuring consistency across different runs. (aka the same seed will always generate the same AI state for a given region)
     */
    public static Seed seedForRegion(Manifest manifest, int x, int z) {
        if (manifest.version() >= 2) return modernSeed(manifest, x, z);
        int width = 16, ax = Math.floorDiv(x, width) * width, az = Math.floorDiv(z, width) * width;
        long hash = mix(manifest.seed() ^ new ChunkPos(ax, az).pack() ^ 0x632be59bd9b4e019L);
        StateTier tier;
        if (Long.remainderUnsigned(hash, 100) < 20) tier = (hash & 256) == 0 ? StateTier.EMPIRE : StateTier.REPUBLIC;
        else {
            width = 8; ax = Math.floorDiv(x, width) * width; az = Math.floorDiv(z, width) * width;
            hash = mix(manifest.seed() ^ new ChunkPos(ax, az).pack() ^ 0x9e3779b97f4a7c15L);
            if (Long.remainderUnsigned(hash, 100) < 30) tier = StateTier.KINGDOM;
            else {
                width = 4; ax = Math.floorDiv(x, width) * width; az = Math.floorDiv(z, width) * width;
                hash = mix(manifest.seed() ^ new ChunkPos(ax, az).pack() ^ 0x94d049bb133111ebL);
                if (Long.remainderUnsigned(hash, 100) < 40) tier = StateTier.CITY_STATE;
                else {
                    width = 2; ax = Math.floorDiv(x, width) * width; az = Math.floorDiv(z, width) * width;
                    hash = mix(manifest.seed() ^ new ChunkPos(ax, az).pack()); tier = (hash & 1) == 0 ? StateTier.COUNTY : StateTier.DUCHY;
                }
            }
        }
        if (Long.remainderUnsigned(mix(hash ^ 0xd1b54a32d192ed03L), 100) >= manifest.density()) return null;
        try { return new Seed(stableId(manifest.seed(), "nation", ax, az, width), manifest.seed(), ax, az, width, tier); }
        catch (IllegalArgumentException outsideWorld) { return null; }
    }

    /** Area-weighted branching yields the requested nation COUNT mix despite very different footprints. */
    //this method generates a seed for modern AI states based on the manifest and the region's coordinates, using a weighted random selection process to determine the tier of the state.
    //the weights are county/duchy/city-state/kingdom/state/republic/empire distribution for 100 states in the same order: 25/20/20/15/15/3/2
    private static Seed modernSeed(Manifest manifest, int x, int z) {
        if (manifest.density() == 0) return null;
        int[] widths = {16, 8, 4, 2};
        int[] weights = {5120, 960, 320, 180};
        int remaining = 6580;
        for (int i = 0; i < widths.length; i++) {
            int width = widths[i], ax = Math.floorDiv(x, width) * width, az = Math.floorDiv(z, width) * width;
            long hash = mix(manifest.seed() ^ new ChunkPos(ax, az).pack() ^ (0x632be59bd9b4e019L * width));
            if (Long.remainderUnsigned(hash, remaining) < weights[i]) {
                StateTier tier = switch (width) {
                    case 16 -> { long roll = Long.remainderUnsigned(mix(hash), 20); yield roll < 15 ? StateTier.STATE : roll < 18 ? StateTier.REPUBLIC : StateTier.EMPIRE; } case 8 -> StateTier.KINGDOM; case 4 -> StateTier.CITY_STATE;
                    default -> Long.remainderUnsigned(mix(hash), 45) < 25 ? StateTier.COUNTY : StateTier.DUCHY;
                };
                try { return new Seed(stableId(manifest.version(), manifest.seed(), "nation", ax, az, width), manifest.seed(), ax, az, width, tier, manifest.version()); }
                catch (IllegalArgumentException outsideWorld) { return null; }
            }
            remaining -= weights[i];
        }
        return null;
    }
    public enum BiomeRule { FORBIDDEN, INDEPENDENT_ONLY, STATE_ALLOWED }
    //biome restriction rule for npc and settlement generation, to prevent AI/npc states from being generated in biomes that are unsuitable for habitation or development.
    /** Exact registry keys, rather than substring matching that could accidentally ban unrelated mod biomes. */
    public static BiomeRule biomeRule(String biome) {
        return switch (biome) {
            case "minecraft:jungle", "minecraft:sparse_jungle", "minecraft:bamboo_jungle", "minecraft:dark_forest", "minecraft:swamp", "minecraft:mangrove_swamp", "minecraft:pale_garden" -> BiomeRule.FORBIDDEN;
            case "minecraft:snowy_plains", "minecraft:snowy_taiga", "minecraft:windswept_hills", "minecraft:windswept_gravelly_hills", "minecraft:windswept_forest", "minecraft:badlands", "minecraft:wooded_badlands" -> BiomeRule.INDEPENDENT_ONLY;
            // Oceans, rivers, caves, peaks and unknown/modded biomes do not invent inhabited surface land.
            case "minecraft:plains", "minecraft:sunflower_plains", "minecraft:forest", "minecraft:flower_forest", "minecraft:birch_forest", "minecraft:old_growth_birch_forest", "minecraft:taiga", "minecraft:old_growth_pine_taiga", "minecraft:old_growth_spruce_taiga", "minecraft:desert", "minecraft:savanna", "minecraft:savanna_plateau", "minecraft:meadow", "minecraft:cherry_grove", "minecraft:beach", "minecraft:stony_shore" -> BiomeRule.STATE_ALLOWED;
            default -> BiomeRule.FORBIDDEN;
        };
    }
    public static int minimumSpacing(SettlementData.Kind a, SettlementData.Kind b) {
        if (a == SettlementData.Kind.OUTPOST || b == SettlementData.Kind.OUTPOST) return a == b ? 256 : 384;
        if (a == SettlementData.Kind.CITY || b == SettlementData.Kind.CITY) return a == b ? 1536 : 768;
        return 512;
    }
    private record Candidate(int cellX, int cellZ, BlockPos position, SettlementData.Kind kind, boolean required, long priority) {}
    /** A global jittered lattice and local priority exclusion make spacing independent of discovery order. */
    private static Candidate candidate(Manifest manifest, int cellX, int cellZ) {
        long hash = mix(manifest.seed() ^ new ChunkPos(cellX, cellZ).pack() ^ 0x3c79ac492ba7b653L);
        int x = cellX * 512 + 128 + ((hash & 1) == 0 ? 0 : 256), z = cellZ * 512 + 128 + ((hash & 2) == 0 ? 0 : 256);
        Seed district = seedForRegion(manifest, Math.floorDiv(x, REGION_BLOCKS), Math.floorDiv(z, REGION_BLOCKS));
        int roll = (int) Long.remainderUnsigned(mix(hash), 100);
        SettlementData.Kind kind = roll < 30 ? SettlementData.Kind.VILLAGE : roll < 60 ? SettlementData.Kind.TOWN : roll < 65 ? SettlementData.Kind.CITY : SettlementData.Kind.OUTPOST;
        boolean required = false;
        if (district != null) {
            int side = district.width() * 2, index = Math.floorMod(cellZ, side) * side + Math.floorMod(cellX, side);
            int offset = (int) Long.remainderUnsigned(mix(district.id().getLeastSignificantBits()), side * side);
            int ordinal = side * side;
            for (int n = 0; n < 7; n++) if (index == Math.floorMod(offset + n * (side * 2 + 3), side * side)) { ordinal = n; break; }
            int cities = district.tier().ordinal() >= StateTier.STATE.ordinal() ? 5 : district.tier() == StateTier.COUNTY ? 0 : 1;
            if (ordinal < cities) { kind = SettlementData.Kind.CITY; required = true; }
            else if (ordinal < cities + (district.tier() == StateTier.CITY_STATE ? 2 : 1)) { kind = SettlementData.Kind.TOWN; required = true; }
            if (district.tier() == StateTier.COUNTY && kind == SettlementData.Kind.CITY) kind = SettlementData.Kind.TOWN;
        }
        return new Candidate(cellX, cellZ, new BlockPos(x, 0, z), kind, required, hash);
    }
    private static boolean spaced(Manifest manifest, Candidate a) {
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            if (dx == 0 && dz == 0) continue;
            Candidate b = candidate(manifest, a.cellX() + dx, a.cellZ() + dz);
            int compare = Boolean.compare(b.required(), a.required());
            if (compare == 0) compare = Long.compareUnsigned(a.priority(), b.priority());
            if (compare > 0 && a.position().distSqr(b.position()) < (long) minimumSpacing(a.kind(), b.kind()) * minimumSpacing(a.kind(), b.kind())) return false;
        }
        return true;
    }

    /*
     * A builder for creating Region instances.
     * This builder is used to construct region objects with the necessary data and configurations.
     * It provides an API for setting the various properties of the region.
     */
    public static final class Builder {
        private final Manifest manifest;
        private final Seed seed;
        private final List<BitSet> land = new ArrayList<>();
        private final List<BlockPos> candidates = new ArrayList<>();
        private final List<BitSet> eligible = new ArrayList<>();
        private final GeographyPreviewService.Sample[] columns;
        private final List<BitSet> habitable = new ArrayList<>();
        private final List<ClaimManager.StrategicRegion> modernRegions = new ArrayList<>();
        private final List<Candidate> modernOptions = new ArrayList<>();
        private final List<Site> modernSites = new ArrayList<>();
        private long modernArea;
        private int siteCursor;
        private boolean optionsSorted;
        private int cursor;
        public Builder(Manifest manifest, Seed seed) {
            this.manifest = manifest; this.seed = seed;
            if (manifest.version() != seed.version()) throw new IllegalArgumentException("Mismatched generation versions");
            columns = new GeographyPreviewService.Sample[seed.width() * seed.width() * 16];
            for (int i = 0; i < seed.width() * seed.width(); i++) { land.add(new BitSet(4096)); eligible.add(new BitSet(4096)); habitable.add(new BitSet(4096)); }
        }
        public Seed seed() { return seed; }
        public int completedSamples() { return cursor; }
        public int totalSamples() { return seed.width() * seed.width() * (seed.version() == 1 ? 16 : 4112); }
        /** Each call attempts at most one column. Null samples retain progress and mean pending, never verified ground. */
        public boolean advance(GeographyPreviewService.Sampler sampler) { return advance(sampler, (x, y, z) -> { var value = sampler.sample(x, z); return value == null ? null : value.biome(); }); }
        public boolean advance(GeographyPreviewService.Sampler sampler, GeographyPreviewService.BiomeSampler biomes) {
            if (seed.version() >= 2) return advanceModern(sampler, biomes);
            if (cursor == totalSamples()) return true;
            int region = cursor / 16, local = cursor % 16, rx = seed.x() + region % seed.width(), rz = seed.z() + region / seed.width();
            if (manifest.neutral(rx, rz)) { cursor = Math.min(totalSamples(), (region + 1) * 16); return cursor == totalSamples(); }
            int sx = local % 4, sz = local / 4, x = rx * REGION_BLOCKS + sx * GeographyPreviewService.SAMPLE_SPACING + 128,
                    z = rz * REGION_BLOCKS + sz * GeographyPreviewService.SAMPLE_SPACING + 128;
            GeographyPreviewService.Sample sample = sampler.sample(x, z); if (sample == null) return false;
            if (sample.land()) {
                BitSet mask = land.get(region);
                for (int row = 0; row < 16; row++) mask.set((sz * 16 + row) * 64 + sx * 16, (sz * 16 + row) * 64 + sx * 16 + 16);
                candidates.add(new BlockPos(x, sample.height(), z));
            }
            cursor++; return cursor == totalSamples();
        }
        private boolean advanceModern(GeographyPreviewService.Sampler sampler, GeographyPreviewService.BiomeSampler biomes) {
            if (cursor == totalSamples()) return true;
            int region = cursor / 4112, local = cursor % 4112, rx = seed.x() + region % seed.width(), rz = seed.z() + region / seed.width();
            if (local < 16) {
                int sx = local % 4, sz = local / 4;
                var sample = sampler.sample(rx * REGION_BLOCKS + sx * 256 + 128, rz * REGION_BLOCKS + sz * 256 + 128);
                if (sample == null) return false;
                columns[region * 16 + local] = sample;
                if (sample.land()) for (int row = 0; row < 16; row++) land.get(region).set((sz * 16 + row) * 64 + sx * 16, (sz * 16 + row) * 64 + sx * 16 + 16);
            } else {
                int tile = local - 16, tx = tile % 64, tz = tile / 64;
                var column = columns[region * 16 + tz / 16 * 4 + tx / 16];
                if (column.land()) {
                    String biome = biomes.sample(rx * REGION_BLOCKS + tx * 16 + 8, column.height(), rz * REGION_BLOCKS + tz * 16 + 8);
                    if (biome == null) return false;
                    if (biomeRule(biome) != BiomeRule.FORBIDDEN) habitable.get(region).set(tz * 64 + tx);
                    if (!manifest.neutral(rx, rz) && biomeRule(column.biome()) == BiomeRule.STATE_ALLOWED && biomeRule(biome) == BiomeRule.STATE_ALLOWED)
                        eligible.get(region).set(tz * 64 + tx);
                }
            }
            cursor++; return cursor == totalSamples();
        }
        /** One provisional planning unit. The tick worker shares its deadline across sampling and these units. */
        public boolean prepare(StateManager states, ClaimManager claims) {
            if (seed.version() == 1) return true;
            if (cursor != totalSamples()) throw new IllegalStateException("Geography is pending");
            if (modernRegions.size() < land.size()) {
                int i = modernRegions.size();
                BitSet mask = new BitSet(4096); int chosen = 0; int quota = land.get(i).cardinality() * manifest.density() / 100;
                int rx = seed.x() + i % seed.width(), rz = seed.z() + i / seed.width();
                // Required seats get a small reserved core, still inside this region's shared area budget.
                for (int cx = rx * 2; cx < rx * 2 + 2; cx++) for (int cz = rz * 2; cz < rz * 2 + 2; cz++) {
                    Candidate c = candidate(manifest, cx, cz); int bit = (Math.floorMod(c.position().getZ(), REGION_BLOCKS) / 16) * 64 + Math.floorMod(c.position().getX(), REGION_BLOCKS) / 16;
                    if (c.required() && eligible.get(i).get(bit) && chosen < quota && !mask.get(bit)) { mask.set(bit); chosen++; }
                }
                // A shifted contiguous sweep avoids a random per-chunk checkerboard. Excluded land still counts in quota.
                int start = (int) (mix(seed.worldSeed() ^ new ChunkPos(rx, rz).pack()) & 4095);
                for (int n = 0; n < 4096 && chosen < quota; n++) { int bit = (start + n) & 4095; if (eligible.get(i).get(bit) && !mask.get(bit)) { mask.set(bit); chosen++; } }
                var region = new ClaimManager.StrategicRegion(rx, rz, seed.id(), mask.toLongArray(), 0, null);
                modernRegions.add(region); modernArea += claims.getInitialRegionalArea(region); return false;
            }
            int side = seed.width() * 2;
            if (modernOptions.size() < side * side) {
                int i = modernOptions.size(); modernOptions.add(candidate(manifest, seed.x() * 2 + i / side, seed.z() * 2 + i % side)); return false;
            }
            if (!optionsSorted) {
                modernOptions.sort(Comparator.comparing(Candidate::required).reversed().thenComparing((a, b) -> Long.compareUnsigned(a.priority(), b.priority())));
                optionsSorted = true; return false;
            }
            if (siteCursor < modernOptions.size() && modernSites.size() < 128) {
                Candidate c = modernOptions.get(siteCursor++);
                BlockPos flat = c.position(); int rx = Math.floorDiv(flat.getX(), REGION_BLOCKS), rz = Math.floorDiv(flat.getZ(), REGION_BLOCKS);
                int index = (rz - seed.z()) * seed.width() + rx - seed.x();
                int sx = Math.floorMod(flat.getX(), REGION_BLOCKS) / 256, sz = Math.floorMod(flat.getZ(), REGION_BLOCKS) / 256;
                var column = columns[index * 16 + sz * 4 + sx];
                int siteBit = Math.floorMod(flat.getZ(), REGION_BLOCKS) / 16 * 64 + Math.floorMod(flat.getX(), REGION_BLOCKS) / 16;
                if (!column.land() || !habitable.get(index).get(siteBit) || biomeRule(column.biome()) == BiomeRule.FORBIDDEN || !spaced(manifest, c)) return false;
                BlockPos pos = new BlockPos(flat.getX(), column.height(), flat.getZ());
                if (claims.getStrategicOwner(ChunkPos.containing(pos)) != null || foreignFootprint(pos, seed.id(), claims)
                        || states.getAllSettlements().stream().anyMatch(other -> {
                            long spacing = Math.max(minimumSpacing(c.kind(), other.getKind()), other.getProtectiveRadius() + StateBalance.SETTLEMENT_RADIUS);
                            long dx = (long) pos.getX() - other.getCenterAltarPos().getX(), dz = (long) pos.getZ() - other.getCenterAltarPos().getZ();
                            return dx * dx + dz * dz < spacing * spacing;
                        })) return false;
                boolean independent = biomeRule(column.biome()) != BiomeRule.STATE_ALLOWED || !modernRegions.get(index).isLand(ChunkPos.containing(pos));
                long hash = mix(c.priority() ^ 0x1c69b3f74ac4ae35L);
                int population = switch (c.kind()) {
                    case VILLAGE -> 1 + (int) Long.remainderUnsigned(hash, 50);
                    case TOWN -> 51 + (int) Long.remainderUnsigned(hash, StateBalance.CITY_POPULATION - 51);
                    case OUTPOST -> 10 + (int) Long.remainderUnsigned(hash, 41);
                    case CITY -> independent ? 500 + (int) Long.remainderUnsigned(hash, 1000)
                            : seed.tier().ordinal() >= StateTier.STATE.ordinal() ? 3001 + (int) Long.remainderUnsigned(hash, 3000)
                            : seed.tier() == StateTier.KINGDOM ? 5001 + (int) Long.remainderUnsigned(hash, 3000) : 500 + (int) Long.remainderUnsigned(hash, 1500);
                };
                int number = modernSites.size(); modernSites.add(new Site(seed.assetId("settlement", number), seed.name() + " " + c.kind().name().toLowerCase(Locale.ROOT) + " " + (number + 1), pos, population, c.kind() == SettlementData.Kind.CITY ? 2 : 1, c.kind(), independent));
                return false;
            }
            return true;
        }
        private Plan finishModern(StateManager states, ClaimManager claims) {
            while (!prepare(states, claims)) { /* Pure callers may complete synchronously; the live worker prepares incrementally. */ }
            List<Site> sites = new ArrayList<>(modernSites);
            List<ClaimManager.StrategicRegion> regions = new ArrayList<>(modernRegions);
            long area = modernArea;
            if (sites.isEmpty()) return null;
            long cities = sites.stream().filter(site -> !site.independent() && site.kind() == SettlementData.Kind.CITY).count();
            long towns = sites.stream().filter(site -> !site.independent() && site.kind() == SettlementData.Kind.TOWN).count();
            int population = sites.stream().filter(site -> !site.independent()).mapToInt(Site::population).sum();
            StateTier tier = seed.tier();
            if (tier.isPermanent() && (area < tier.getMinChunks() || cities < 5 || population <= 15000)) tier = StateTier.STATE;
            if (tier == StateTier.STATE && (area < tier.getMinChunks() || cities < 5 || population <= 15000)) tier = StateTier.KINGDOM;
            if (tier == StateTier.KINGDOM && (area < tier.getMinChunks() || population <= 5000)) tier = StateTier.CITY_STATE;
            if (tier == StateTier.CITY_STATE && (area < tier.getMinChunks() || cities < 1 || towns < 2)) tier = StateTier.DUCHY;
            if (tier == StateTier.DUCHY && cities < 1) tier = StateTier.COUNTY;
            if (population == 0 || area < 256 || sites.stream().noneMatch(site -> !site.independent() && site.kind() != SettlementData.Kind.OUTPOST)) {
                tier = StateTier.NOMADIC; regions = regions.stream().map(r -> new ClaimManager.StrategicRegion(r.x(), r.z(), seed.id(), new long[0], 0, null)).toList();
                List<Site> independent = new ArrayList<>();
                for (Site site : sites) independent.add(new Site(site.id(), site.name(), site.position(), site.population(), site.tier(), site.kind(), true));
                sites = independent;
            }
            int rank = tier.ordinal(); long random = mix(seed.id().getMostSignificantBits());
            int maxForces = Math.max(Math.min(2, tier.getMaxLegions()), tier.getMaxLegions() * rank / 8);
            int forces = tier == StateTier.NOMADIC ? 0 : 1 + (int) Long.remainderUnsigned(random, maxForces);
            long upkeep = forces * 550L + sites.stream().filter(site -> !site.independent()).mapToLong(site -> site.tier() * StateBalance.SETTLEMENT_UPKEEP_PER_TIER).sum();
            long treasury = tier == StateTier.NOMADIC ? 0 : forces * (long) ArmyManager.LEGION_RECRUIT_COST + upkeep * (8 + Long.remainderUnsigned(mix(random), 13)) + rank * rank * rank * (1000 + Long.remainderUnsigned(mix(~random), 1001));
            return new Plan(seed, tier, treasury, sites, regions, forces);
        }
        public Plan finish(StateManager states, ClaimManager claims) {
            if (seed.version() >= 2) return finishModern(states, claims);
            if (cursor != totalSamples()) throw new IllegalStateException("Geography is pending");
            List<ClaimManager.StrategicRegion> regions = new ArrayList<>(); long area = 0;
            for (int i = 0; i < land.size(); i++) {
                var region = new ClaimManager.StrategicRegion(seed.x() + i % seed.width(), seed.z() + i / seed.width(), seed.id(), land.get(i).toLongArray(), 0, null);
                regions.add(region); area += claims.getInitialRegionalArea(region);
            }
            // Stable candidate ranking, capped collision checks; existing players' settlements and concrete land win.
            candidates.sort(Comparator.comparingLong(pos -> mix(pos.asLong() ^ seed.worldSeed())));
            List<BlockPos> centers = new ArrayList<>(); int desired = seed.width() == 16 ? 5 : seed.width() >= 4 ? 3 : 1;
            for (int i = 0; i < Math.min(64, candidates.size()) && centers.size() < desired; i++) {
                BlockPos pos = candidates.get(i); UUID owner = claims.getStrategicOwner(ChunkPos.containing(pos));
                if (owner != null || foreignFootprint(pos, seed.id(), claims) || centers.stream().anyMatch(other -> StateManager.settlementFootprintsOverlap(other, StateBalance.SETTLEMENT_RADIUS, pos, StateBalance.SETTLEMENT_RADIUS))
                        || states.getAllSettlements().stream().anyMatch(other -> StateManager.settlementFootprintsOverlap(other.getCenterAltarPos(), other.getProtectiveRadius(), pos, StateBalance.SETTLEMENT_RADIUS))) continue;
                centers.add(pos);
            }
            if (centers.isEmpty() || area < 256) return null;
            StateTier tier = seed.tier();
            if (tier.isPermanent() && (area < StateTier.REPUBLIC.getMinChunks() || centers.size() < 5)) tier = StateTier.STATE;
            if (tier == StateTier.STATE && (area < StateTier.STATE.getMinChunks() || centers.size() < 5)) tier = StateTier.KINGDOM;
            if (tier == StateTier.KINGDOM && (area < StateTier.KINGDOM.getMinChunks() || centers.size() < 3)) tier = StateTier.CITY_STATE;
            if (tier == StateTier.CITY_STATE && (area < StateTier.CITY_STATE.getMinChunks() || centers.size() < 3)) tier = StateTier.DUCHY;
            int forces = Math.min(4, Math.max(1, tier.getMaxLegions() / 3));
            List<Site> sites = new ArrayList<>(); long upkeep = (long) forces * 550;
            for (int i = 0; i < centers.size(); i++) {
                int population = tier.ordinal() >= StateTier.STATE.ordinal() ? 3400 : tier == StateTier.KINGDOM ? 2100 : i == 0 && tier != StateTier.COUNTY ? 600 : 100;
                int settlementTier = tier.ordinal() >= StateTier.KINGDOM.ordinal() || i == 0 && tier != StateTier.COUNTY ? 2 : 1;
                sites.add(new Site(seed.assetId("settlement", i), seed.name() + (i == 0 ? " Capital" : " " + (i + 1)), centers.get(i), population, settlementTier));
                upkeep += (long) settlementTier * StateBalance.SETTLEMENT_UPKEEP_PER_TIER;
            }
            return new Plan(seed, tier, forces * (long) ArmyManager.LEGION_RECRUIT_COST + upkeep * 4, sites, regions, forces);
        }
    }
    //end of region struct

    /** Retry only unfinished initialization. Receipts preserve previously committed funds, ownership and troops. */
    public static void apply(Plan plan, StateManager states, ClaimManager claims, ArmyManager armies) {
        UUID id = plan.seed().id(); StateData existing = states.getState(id);
        if (plan.tier() != StateTier.NOMADIC && existing == null && states.hasAIInitialization(id)) throw new IllegalStateException("Committed initialization has no surviving state; refusing to recreate it");
        if (existing != null && (!existing.isAI() || existing.getGenerationVersion() != plan.seed().version() || existing.getGenerationSeed() != plan.seed().worldSeed()
                || !existing.isInitializationPending() && !states.hasAIInitialization(id)))
            throw new IllegalStateException("Existing state conflicts with an unfinished seed");
        for (var region : plan.regions()) {
            var before = claims.getStrategicRegions().get(region.key());
            if (!claims.hasAIInitialization(id) && before != null && !id.equals(before.owner())) throw new IllegalStateException("Existing region conflicts with an unfinished seed");
        }
        for (Site site : plan.sites()) {
            SettlementData before = states.getSettlement(site.id());
            if (!states.hasAIInitialization(id) && before != null && !(site.independent() ? SettlementData.INDEPENDENT_OWNER : id).equals(before.getOwningStateId())) throw new IllegalStateException("Existing site conflicts with an unfinished seed");
            if (!states.hasAIInitialization(id) && before == null && (foreignFootprint(site.position(), id, claims)
                    || states.getAllSettlements().stream().anyMatch(other -> !other.getOwningStateId().equals(id)
                    && StateManager.settlementFootprintsOverlap(other.getCenterAltarPos(), other.getProtectiveRadius(), site.position(), StateBalance.SETTLEMENT_RADIUS))))
                throw new IllegalStateException("A foreign holding now occupies the planned settlement footprint");
        }
        if (!armies.hasAIInitialization(id)) for (int i = 0; i < plan.forces(); i++) {
            UUID forceId = plan.seed().assetId("force", i);
            if (armies.getLegion(forceId) != null || armies.getStrategicForces().stream().anyMatch(force -> force.id().equals(forceId) && !force.owner().equals(id)))
                throw new IllegalStateException("Existing force conflicts with an unfinished seed");
        }
        if (!states.hasAIInitialization(id)) {
            if (existing == null && plan.tier() != StateTier.NOMADIC) {
                existing = StateData.createAI(id, plan.seed().name(), plan.tier(), plan.seed().worldSeed(), plan.seed().version());
                existing.setInitializationPending(true); existing.addFunds(plan.treasury()); states.createState(existing);
            }
            for (Site site : plan.sites()) {
                SettlementData settlement = new SettlementData(site.id(), site.independent() ? SettlementData.INDEPENDENT_OWNER : id, site.name(), site.position());
                settlement.initializeAbstract(ClaimManager.regionKey(ChunkPos.containing(site.position())), null);
                settlement.setLocalPopulation(site.population()); settlement.setGeneratedKind(site.kind()); states.registerSettlement(site.id(), settlement);
            }
            if (existing != null) {
                existing.setCapitalSettlementId(plan.sites().stream().filter(site -> !site.independent() && site.kind() != SettlementData.Kind.OUTPOST).sorted(Comparator.comparingInt(Site::tier).reversed()).map(Site::id).findFirst().orElse(null));
                existing.setTotalPopulation(plan.sites().stream().filter(site -> !site.independent()).mapToInt(Site::population).sum());
            }
            states.recordAIInitialization(id);
        }
        if (!claims.hasAIInitialization(id)) {
            plan.regions().stream().filter(region -> region.landChunks() > 0).forEach(claims::registerStrategicRegion); claims.recordAIInitialization(id);
        }
        if (!armies.hasAIInitialization(id)) {
            for (int i = 0; i < plan.forces(); i++) {
                List<Site> owned = plan.sites().stream().filter(site -> !site.independent()).toList();
                Site home = owned.get(i % owned.size());
                armies.registerStrategicForce(new ArmyManager.StrategicForce(plan.seed().assetId("force", i), id, home.id(), home.position()));
            }
            armies.recordAIInitialization(id);
        }
    }

    /** Only real Overworld player locations call this; map viewports and preview requests have no path here. */
    //for explored areas, this method checks if the exploration of a given position should trigger the generation of a new AI state. It does this by checking the manifest and the pending queue, and if conditions are met, it adds a new seed to the pending queue for processing.
    public boolean observeExploration(BlockPos position) {
        if (manifest == null || pending.size() >= MAX_PENDING) return false;
        int rx = Math.floorDiv(position.getX(), REGION_BLOCKS), rz = Math.floorDiv(position.getZ(), REGION_BLOCKS);
        int[][] offsets = {{0,0},{-1,0},{1,0},{0,-1},{0,1},{-1,-1},{1,-1},{-1,1},{1,1}};
        for (int[] offset : offsets) {
            Seed seed = seedForRegion(manifest, rx + offset[0], rz + offset[1]);
            if (seed == null || sealed.contains(seed.id()) || unsuitable.contains(seed.id()) || queued.contains(seed.id())) continue;
            queued.add(seed.id()); pending.addLast(seed); return true;
        }
        return false;
    }

    public void tick(ServerLevel level) {
        if (closed || !failure.isEmpty()) return;
        boolean enabled = Config.AI_GENERATION_ENABLED.get();
        try {
            if (!started) {
                if (!enabled && checkedDisabled) return;
                directory = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/minecraftempires_ai");
                if (!enabled && !Files.exists(directory.resolve("manifest.dat"))) { checkedDisabled = true; phase = "DISABLED"; return; }
                started = true; phase = "OPENING";
                BlockPos spawn = level.getRespawnData().pos();
                Manifest proposed = new Manifest(VERSION, level.getSeed(), Config.AI_NATION_DENSITY.get(), Math.floorDiv(spawn.getX(), REGION_BLOCKS), Math.floorDiv(spawn.getZ(), REGION_BLOCKS));
                Tag generator = ChunkGenerator.CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), level.getChunkSource().getGenerator()).getOrThrow();
                io = CompletableFuture.supplyAsync(() -> open(directory, proposed, generator)); return;
            }
            if (io != null) {
                if (!io.isDone()) return;
                Object result = io.join(); io = null;
                switch (phase) {
                    case "OPENING" -> {
                        Bootstrap data = (Bootstrap) result; manifest = data.manifest();
                        for (Journal journal : data.journals()) if (journal.complete()) {
                            sealed.add(journal.seed().id()); StateData state = states.getState(journal.seed().id());
                            if (state != null) {
                                if (!state.isAI() || state.getGenerationVersion() != journal.seed().version() || state.getGenerationSeed() != journal.seed().worldSeed()) throw new IllegalStateException("Completed AI journal conflicts with an existing state");
                                if (state.isInitializationPending()) { state.setInitializationPending(false); states.setDirty(); }
                            }
                        } else { recovery.addLast(journal.plan()); queued.add(journal.seed().id()); }
                        phase = "IDLE";
                    }
                    case "PREPARING" -> install(level);
                    case "SAVING" -> {
                        Plan plan = applying; phase = "VERIFYING";
                        Path data = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(),
                                level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data");
                        io = CompletableFuture.runAsync(() -> {
                            verifyReceipt(data, StateManager.TYPE.id().withSuffix(".dat").resolveAgainst(data), plan.seed().id());
                            verifyReceipt(data, ClaimManager.TYPE.id().withSuffix(".dat").resolveAgainst(data), plan.seed().id());
                            verifyReceipt(data, ArmyManager.TYPE.id().withSuffix(".dat").resolveAgainst(data), plan.seed().id());
                            writeJournal(directory, plan, true);
                        }); return;
                    }
                    case "VERIFYING" -> {
                        UUID id = applying.seed().id(); sealed.add(id); queued.remove(id);
                        StateData state = states.getState(id); if (state != null) { state.setInitializationPending(false); states.setDirty(); }
                        applying = null; phase = "IDLE";
                    }
                    default -> throw new IllegalStateException("Unexpected initialization phase");
                }
                return;
            }
            if (!recovery.isEmpty()) { applying = recovery.removeFirst(); install(level); return; }
            if (!enabled) { phase = "DISABLED"; return; }
            if (level.getServer().getTickCount() % 20 == 0) {
                var players = level.players().stream().filter(player -> !player.isSpectator()).sorted(Comparator.comparing(player -> player.getUUID())).toList();
                if (!players.isEmpty()) observeExploration(players.get(Math.floorMod(playerCursor++, players.size())).blockPosition());
            }
            if (builder == null) {
                if (pending.isEmpty()) { phase = "IDLE"; return; }
                if (sealed.size() >= Config.AI_MAX_NATIONS.get() || sealed.size() >= MAX_JOURNALS) { phase = "CAPACITY"; return; }
                int available = Config.AI_MAX_REGIONS.get() - ClaimManager.get(level).getStrategicRegions().size();
                Seed next = null;
                // A large waiting district must not block a different player's smaller admissible frontier.
                for (int checked = 0, size = pending.size(); checked < size; checked++) {
                    Seed candidate = pending.removeFirst();
                    if (sealed.contains(candidate.id()) || unsuitable.contains(candidate.id())) { queued.remove(candidate.id()); continue; }
                    if (candidate.width() * candidate.width() > available) { pending.addLast(candidate); continue; }
                    next = candidate; break;
                }
                if (next == null) { phase = "CAPACITY"; return; }
                builder = new Builder(manifest, next);
            }
            if (geography.isUnsupported()) { phase = "UNSUPPORTED_GENERATOR"; return; }
            phase = "SAMPLING"; geography.beginTick(level.getGameTime(), Config.AI_GEOGRAPHY_SAMPLES_PER_TICK.get());
            // Neutral regions can finish without a column, but even those visits are bounded.
            long workDeadline = System.nanoTime() + GeographyPreviewService.WORK_NANOS;
            for (int visits = 0; visits < (manifest.version() == 1 ? 16 : 4096); visits++) {
                if (visits > 0 && System.nanoTime() >= workDeadline) return;
                int before = builder.completedSamples(); boolean done = builder.advance((x, z) -> level.getWorldBorder().isWithinBounds(new BlockPos(x, 0, z))
                        ? geography.request(level, x, z) : new GeographyPreviewService.Sample(level.getMinY(), false), (x, y, z) -> GeographyPreviewService.biome(level, x, y, z));
                if (geography.isUnsupported()) { phase = "UNSUPPORTED_GENERATOR"; return; }
                if (done) {
                    if (!builder.prepare(states, ClaimManager.get(level))) continue;
                    Plan plan = builder.finish(states, ClaimManager.get(level)); Seed seed = builder.seed(); builder = null;
                    if (plan == null) {
                        queued.remove(seed.id()); unsuitable.add(seed.id()); if (unsuitable.size() > 256) unsuitable.remove(unsuitable.iterator().next());
                        phase = "IDLE"; return;
                    }
                    applying = plan; phase = "PREPARING"; io = CompletableFuture.runAsync(() -> writeJournal(directory, plan, false)); return;
                }
                if (before == builder.completedSamples()) return;
            }
        } catch (RuntimeException error) { fail(error); }
    }

    private void install(ServerLevel level) {
        apply(applying, states, ClaimManager.get(level), ArmyManager.get(level));
        phase = "SAVING"; io = level.getDataStorage().scheduleSave();
    }
    private void fail(RuntimeException error) {
        failure = error.getCause() == null ? error.toString() : error.getCause().toString(); phase = "BLOCKED";
        System.getLogger(RegionInfo.class.getName()).log(System.Logger.Level.ERROR, "AI initialization stopped safely: " + failure);
    }
    /** Futures contain immutable tags/plans only, so late completion cannot mutate a closed world. */
    public void close() {
        closed = true;
        // Finish the one admitted I/O job before this world's directory can be opened by another session.
        if (io != null) try { io.join(); } catch (RuntimeException error) { fail(error); }
        io = null; pending.clear(); queued.clear(); unsuitable.clear(); recovery.clear(); builder = null; applying = null; geography.clear();
    }

    private static Bootstrap open(Path directory, Manifest proposed, Tag generator) {
        try {
            Files.createDirectories(directory); Path file = directory.resolve("manifest.dat"); Manifest manifest;
            if (Files.exists(file)) {
                CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.create(JOURNAL_BYTES)); manifest = Manifest.fromNBT(tag);
                if (manifest.seed() != proposed.seed() || !generator.equals(tag.get("Generator"))) throw new IOException("Saved AI seed/generator does not match this world");
            } else {
                try (var files = Files.newDirectoryStream(directory, "nation-*.dat")) {
                    if (files.iterator().hasNext()) throw new IOException("AI manifest is missing beside existing initialization records");
                }
                manifest = proposed; CompoundTag tag = manifest.toNBT(); tag.put("Generator", generator.copy()); writeAtomic(file, tag);
            }
            List<Journal> journals = new ArrayList<>();
            try (var files = Files.newDirectoryStream(directory, "nation-*.dat")) {
                for (Path path : files) {
                    if (journals.size() == MAX_JOURNALS) throw new IOException("AI journal residency limit exceeded");
                    CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.create(JOURNAL_BYTES)); Seed seed = Seed.fromNBT(tag.getCompound("Seed").orElseThrow());
                    if (seed.worldSeed() != manifest.seed() || !seed.equals(seedForRegion(manifest, seed.x(), seed.z()))
                            || !path.getFileName().toString().equals("nation-" + seed.id() + ".dat")) throw new IOException("Conflicting AI journal identity");
                    Plan plan = tag.getBoolean("Complete").orElse(false) ? null : Plan.fromNBT(tag.getCompound("Plan").orElseThrow());
                    if (plan != null && !plan.seed().equals(seed)) throw new IOException("Initialization snapshot identity does not match its journal");
                    journals.add(new Journal(seed, plan));
                }
            }
            journals.sort(Comparator.comparing(journal -> journal.seed().id())); return new Bootstrap(manifest, List.copyOf(journals));
        } catch (IOException | RuntimeException error) { throw new CompletionException(error); }
    }
    private static void writeJournal(Path directory, Plan plan, boolean complete) {
        CompoundTag tag = new CompoundTag(); tag.put("Seed", plan.seed().toNBT()); tag.putBoolean("Complete", complete);
        if (!complete) tag.put("Plan", plan.toNBT());
        try { writeAtomic(directory.resolve("nation-" + plan.seed().id() + ".dat"), tag); }
        catch (IOException error) { throw new CompletionException(error); }
    }
    private static void writeAtomic(Path file, CompoundTag tag) throws IOException {
        AIJournal.write(file, tag);
    }
    private static void verifyReceipt(Path data, Path file, UUID id) {
        if (!file.normalize().startsWith(data.normalize())) throw new IllegalArgumentException("Invalid AI receipt path");
        try {
            CompoundTag saved = NbtIo.readCompressed(file, NbtAccounter.create(DOMAIN_BYTES)).getCompound("data").orElseThrow();
            boolean found = false;
            for (var value : saved.getListOrEmpty("AIInitializations")) if (value.asString().orElse("").equals(id.toString())) { found = true; break; }
            if (!found) throw new IOException("Initialization receipt not durable in " + file.getFileName());
        } catch (IOException | RuntimeException error) { throw new CompletionException(error); }
    }
}
