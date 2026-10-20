package com.devc.minecraftempires.network;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.DensityFunction;
import com.devc.minecraftempires.network.packet.MapDataPayload.*;
import com.devc.minecraftempires.network.packet.RequestMapDataPayload.OverviewRequest;

import java.util.*;

/** Seeded geography estimates, deliberately separate from surveyed terrain and navigation authority. */
public final class GeographyPreviewService {
    public static final int SAMPLE_SPACING = 256;
    public static final int CACHE_LIMIT = 2048;
    public static final long WORK_NANOS = 1_000_000L;
    public record Sample(int height, boolean land, String biome) {
        public Sample(int height, boolean land) { this(height, land, "minecraft:plains"); }
    }
    @FunctionalInterface public interface BiomeSampler { String sample(int x, int y, int z); }
    /** Seeded surface biome query; does not acquire a chunk or mark terrain as surveyed. */
    public static String biome(ServerLevel level, int x, int y, int z) {
        return level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv(y, 4), Math.floorDiv(z, 4), level.getChunkSource().randomState().sampler()).unwrapKey().map(key -> key.identifier().toString()).orElse("");
    }
    @FunctionalInterface public interface Sampler { Sample sample(int blockX, int blockZ); }
    private final Map<Long, Sample> cache = new LinkedHashMap<>(256, .75f, true);
    private long tick = Long.MIN_VALUE, deadline;
    private int remaining;
    private boolean unsupported;
    public static final int PREVIEW_TILE_LIMIT = 512, PREVIEW_VIEW_LIMIT = 64;
    public static final long PREVIEW_WORK_NANOS = 2_000_000L;
    private final Map<CoastKey, PreviewJob> previews = new LinkedHashMap<>(64, .75f, true);
    private final Map<UUID, PreviewView> views = new LinkedHashMap<>();
    private int previewCursor;
    private long previewTick = Long.MIN_VALUE, revision;
    @FunctionalInterface public interface CoastSampler { short sample(int x, int z, boolean detailed); }

    /** Coordinate packing is signed and never allocates a map from spawn to this position. */
    public static long sampleKey(int x, int z) { return (long) x << 32 | z & 0xffffffffL; }
    public void beginTick(long now, int maximum) {
        if (tick == now) return;
        tick = now; remaining = Math.clamp(maximum, 1, 16); deadline = System.nanoTime() + WORK_NANOS;
    }
    public int cachedSamples() { return cache.size(); }
    public boolean isUnsupported() { return unsupported; }

    /** Null means pending work, never sea or permission to found/deploy. Cached reads use no sampling budget. */
    public Sample request(int blockX, int blockZ, Sampler sampler) {
        long key = sampleKey(blockX, blockZ); Sample known = cache.get(key);
        if (known != null) return known;
        if (unsupported || remaining == 0 || System.nanoTime() >= deadline) return null;
        remaining--;
        Sample sampled = sampler.sample(blockX, blockZ);
        if (sampled == null) { unsupported = true; return null; }
        cache.put(key, sampled);
        if (cache.size() > CACHE_LIMIT) cache.remove(cache.keySet().iterator().next());
        return sampled;
    }

    /** Calls the generator's column query only. This path creates no chunks, tickets or terrain observations. */
    public Sample request(ServerLevel level, int blockX, int blockZ) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        // Custom generators need an explicit preview adapter; do not invent geography on their behalf.
        if (!(generator instanceof NoiseBasedChunkGenerator) && !(generator instanceof FlatLevelSource)) {
            unsupported = true; return null;
        }
        return request(blockX, blockZ, (x, z) -> {
            int height = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, level.getChunkSource().randomState());
            return new Sample(height, height > generator.getSeaLevel(), biome(level, x, height, z));
        });
    }
    public int cachedPreviewTiles() { return previews.size(); }
    public void forgetPreview(UUID player) { views.remove(player); }
    public static int previewStep(int span) { int step = 4; while (step * 128 < span) step *= 2; return step; }

    /** Requests only schedule work. The tick worker shares one bounded allowance across all frontiers. */
    public List<CoastTile> preview(UUID player, long time, OverviewRequest request) {
        int step = previewStep(request.span());
        Map<CoastKey, Long> known = new HashMap<>(); for (var stamp : request.known()) known.put(stamp.key(), stamp.version());
        LinkedHashMap<CoastKey, Boolean> wanted = new LinkedHashMap<>();
        addViewport(wanted, request, step * 4);
        addViewport(wanted, request, step);
        // A second, denser lattice is reserved for shores rather than spending it on featureless interiors.
        int shore = Math.max(4, step / 2);
        for (CoastKey key : List.copyOf(wanted.keySet())) if (key.step() == step) {
            PreviewJob job = previews.get(key);
            if (job == null || job.ready == null || !job.ready.coastal()) continue;
            int divisor = step / shore;
            for (int z = 0; z < divisor; z++) for (int x = 0; x < divisor; x++) {
                if (wanted.size() == 192 && !wanted.containsKey(new CoastKey(key.tileX() * divisor + x, key.tileZ() * divisor + z, shore))) continue;
                wanted.put(new CoastKey(key.tileX() * divisor + x, key.tileZ() * divisor + z, shore), true);
            }
        }
        // Retain visible detailed tiles reported by the client even after server cache eviction.
        for (var stamp : request.known()) if (stamp.key().step() == shore && visible(stamp.key(), request) && wanted.size() < 192) wanted.put(stamp.key(), true);
        views.put(player, new PreviewView(time, wanted, known));
        while (views.size() > PREVIEW_VIEW_LIMIT) views.remove(views.keySet().iterator().next());
        List<CoastTile> result = new ArrayList<>();
        for (var key : wanted.keySet()) {
            PreviewJob job = previews.get(key);
            if (job != null && job.ready != null && known.getOrDefault(key, 0L) != job.ready.version()) result.add(job.ready);
            if (result.size() == 12) break;
        }
        return List.copyOf(result);
    }
    private static boolean visible(CoastKey key, OverviewRequest request) {
        long radius = request.span() / 2L;
        return key.x() <= request.centerX() + radius && (long) key.x() + key.blocks() >= request.centerX() - radius
                && key.z() <= request.centerZ() + radius && (long) key.z() + key.blocks() >= request.centerZ() - radius;
    }
    private static void addViewport(Map<CoastKey, Boolean> wanted, OverviewRequest request, int step) {
        int blocks = step * CoastKey.CELLS, radius = request.span() / 2;
        List<CoastKey> keys = new ArrayList<>();
        for (int z = Math.floorDiv(Math.max(-29_999_984, request.centerZ() - radius), blocks); z <= Math.floorDiv(Math.min(29_999_984, request.centerZ() + radius), blocks); z++)
            for (int x = Math.floorDiv(Math.max(-29_999_984, request.centerX() - radius), blocks); x <= Math.floorDiv(Math.min(29_999_984, request.centerX() + radius), blocks); x++) keys.add(new CoastKey(x, z, step));
        keys.sort(Comparator.comparingDouble(key -> { double dx = key.x() + blocks / 2.0 - request.centerX(), dz = key.z() + blocks / 2.0 - request.centerZ(); return dx * dx + dz * dz; }));
        for (var key : keys) wanted.put(key, false);
    }
    public void tickPreview(ServerLevel level) {
        if (views.isEmpty()) return;
        var generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator) && !(generator instanceof FlatLevelSource)) { unsupported = true; return; }
        tickPreview(level.getGameTime(), coastSampler(level), PREVIEW_WORK_NANOS);
    }
    /** Pure seeded functions; no chunk requests, world mutation or player terrain knowledge. */
    public static CoastSampler coastSampler(ServerLevel level) {
        return coastSampler(level.getChunkSource().getGenerator(), level.getChunkSource().randomState(), level);
    }
    public static CoastSampler coastSampler(ChunkGenerator generator, net.minecraft.world.level.levelgen.RandomState random, net.minecraft.world.level.LevelHeightAccessor height) {
        int sea = generator.getSeaLevel();
        if (generator instanceof NoiseBasedChunkGenerator) return (x, z, detailed) -> {
            var context = new DensityFunction.SinglePointContext(x, sea, z);
            double value = (detailed ? random.router().finalDensity() : random.router().depth()).compute(context);
            return (short) Math.clamp(Math.round(value * 8192), -32767, 32767);
        };
        if (generator instanceof FlatLevelSource) return (x, z, detailed) -> (short) Math.clamp(generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, height, random) - sea, -32767, 32767);
        return (x, z, detailed) -> CoastTile.UNKNOWN;
    }
    public void tickPreview(long time, CoastSampler sampler, long allowance) {
        if (previewTick == time || unsupported) return;
        previewTick = time; views.values().removeIf(view -> time - view.time > 100);
        if (views.isEmpty()) return;
        List<PreviewView> active = List.copyOf(views.values()); long end = System.nanoTime() + Math.min(allowance, PREVIEW_WORK_NANOS);
        int idle = 0;
        for (int work = 0; work < 256 && System.nanoTime() < end && idle < active.size(); work++) {
            PreviewView view = active.get(Math.floorMod(previewCursor++, active.size()));
            PreviewJob selected = null;
            for (var entry : view.wanted.entrySet()) {
                PreviewJob job = previews.get(entry.getKey());
                if (job == null && view.known.containsKey(entry.getKey())) continue;
                if (job == null) { job = new PreviewJob(entry.getKey()); previews.put(entry.getKey(), job); }
                if (entry.getValue() && !job.detailed) { job.detailed = true; job.offset = 0; }
                if (job.ready == null || job.detailed && !job.ready.detailed()) { selected = job; break; }
            }
            if (selected == null) { idle++; continue; }
            idle = 0; int index = selected.offset++, side = CoastKey.SIDE;
            int x = selected.key.x() + index % side * selected.key.step(), z = selected.key.z() + index / side * selected.key.step();
            selected.values[index] = Math.abs((long) x) > 29_999_984 || Math.abs((long) z) > 29_999_984 ? CoastTile.UNKNOWN : sampler.sample(x, z, selected.detailed);
            if (selected.offset == CoastKey.SAMPLES) selected.ready = new CoastTile(selected.key, ++revision, selected.detailed, selected.values);
            while (previews.size() > PREVIEW_TILE_LIMIT) previews.remove(previews.keySet().iterator().next());
        }
    }
    private record PreviewView(long time, LinkedHashMap<CoastKey, Boolean> wanted, Map<CoastKey, Long> known) {}
    private static final class PreviewJob {
        final CoastKey key; final short[] values = new short[CoastKey.SAMPLES];
        int offset; boolean detailed; CoastTile ready;
        PreviewJob(CoastKey key) { this.key = key; }
    }
    public void clear() { cache.clear(); previews.clear(); views.clear(); tick = previewTick = Long.MIN_VALUE; remaining = 0; unsupported = false; }
}
