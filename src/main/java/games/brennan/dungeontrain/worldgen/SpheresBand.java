package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.SpheresProgressionConfig;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side helper for the <b>spheres</b> band — the fifth looping phase of the
 * {@link WorldGenCycle}, appended after the chuncks band behind a plain-overworld lead gap. Across
 * the band's X-range the world is open <b>void</b> scattered with floating spheres of natural
 * overworld terrain, each lifted to its own height (the {@link SphereField}):
 *
 * <ul>
 *   <li>A chunk no sphere touches generates as pure void — {@code NoiseBasedChunkGeneratorMixin}
 *       hands back an all-air chunk (the End/chuncks fast path). The floating track bed still
 *       crosses.</li>
 *   <li>A chunk a sphere touches generates vanilla terrain, then {@code WorldSpheresEvents} keeps
 *       only the blocks inside spheres (lifted to their display height) and erases the rest.</li>
 *   <li>Across the entry fade the natural terrain outside the spheres dissolves gradually
 *       ({@link #voidRamp} 0 → 1) instead of ending at a wall.</li>
 * </ul>
 *
 * <p>Layout/positioning lives in {@link WorldGenCycle}; sphere geometry in {@link SphereField};
 * this class gates on the per-world train state and memoises the per-chunk sphere lists.
 * Thread-safety mirrors {@link ChuncksBand}: reads only the memoised cycle, the volatile config
 * and per-world {@link DungeonTrainWorldData}; the caches are concurrent maps.</p>
 */
public final class SpheresBand {

    /** Returned by {@link #startX} when the spheres band is disabled or the world has no train. */
    public static final long OFF = Long.MAX_VALUE;

    private SpheresBand() {}

    /**
     * World-X where the cycle is anchored (shared with the other bands via {@link WorldGenCycle}), or
     * {@link #OFF} when the spheres band is disabled, the world has no train, or the band has no length.
     */
    public static long startX(ServerLevel overworld) {
        if (!DungeonTrainCommonConfig.isSpheresEnabled()) return OFF;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return OFF;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.period() <= 0L || cycle.spheresLen() <= 0L) return OFF;
        return cycle.startX();
    }

    /** True if the column at {@code worldX} lies in the spheres band core (not the entry fade). */
    public static boolean isInBand(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return false;
        return WorldGenCycle.fromConfig().isInSpheresBand(worldX);
    }

    /**
     * True if the column at {@code worldX} lies in the spheres band OR anywhere in the plain-overworld
     * run-up to it (the lead gap, the entry fade) — see {@link WorldGenCycle#isInSpheresApproachOrBand}.
     * The {@code reached_overworld_again} advancement gate uses this alongside the chuncks predicate.
     */
    public static boolean isInApproachOrBand(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return false;
        return WorldGenCycle.fromConfig().isInSpheresApproachOrBand(worldX);
    }

    /**
     * Void ramp {@code 0..1} at a column: 0 outside the band, rising across the entry fade, 1 in the
     * core. Cheap gate first (config flag + memoised cycle), the per-world lookup only when in range.
     */
    public static double voidRamp(ServerLevel overworld, int worldX) {
        if (!DungeonTrainCommonConfig.isSpheresEnabled()) return 0.0;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.spheresLen() <= 0L) return 0.0;
        double ramp = cycle.spheresVoidRamp(worldX);
        if (ramp <= 0.0) return 0.0;
        if (!DungeonTrainWorldData.get(overworld).startsWithTrain()) return 0.0;
        return ramp;
    }

    /** True if any column of the chunk starting at {@code chunkMinX} has a non-zero {@link #voidRamp}. */
    public static boolean chunkTouchesBand(ServerLevel overworld, int chunkMinX) {
        if (!DungeonTrainCommonConfig.isSpheresEnabled()) return false;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.spheresLen() <= 0L) return false;
        // The ramp is monotone non-decreasing across fade+core and 0 after, so the two chunk edges plus
        // a mid sample cover every phase edge a 16-wide chunk can straddle.
        if (cycle.spheresVoidRamp(chunkMinX) <= 0.0 && cycle.spheresVoidRamp(chunkMinX + 15) <= 0.0
                && cycle.spheresVoidRamp(chunkMinX + 8) <= 0.0) {
            return false;
        }
        return DungeonTrainWorldData.get(overworld).startsWithTrain();
    }

    /**
     * The spheres touching the chunk at {@code (chunkX, chunkZ)} — empty out of band or when the chunk
     * sits over open void. Only spheres lying <em>entirely</em> inside the band's fade + core X-range
     * are placed ({@link #insideBand}), so none is sliced flat by the zone's leading or trailing edge.
     * Memoised per chunk; only in-band chunks are cached.
     */
    public static List<SphereField.Sphere> candidates(ServerLevel overworld, int chunkX, int chunkZ) {
        if (!chunkTouchesBand(overworld, chunkX << 4)) return List.of();
        SphereField field = fieldFor(overworld);
        long key = ChunkPos.asLong(chunkX, chunkZ);
        List<SphereField.Sphere> hit = CANDIDATE_CACHE.get(key);
        if (hit != null) return hit;
        List<SphereField.Sphere> list = insideBand(WorldGenCycle.fromConfig(), field.candidatesFor(chunkX, chunkZ));
        if (CANDIDATE_CACHE.size() >= MAX_CACHE) CANDIDATE_CACHE.clear();
        CANDIDATE_CACHE.put(key, list);
        return list;
    }

    /**
     * Keep only the spheres whose full X extent {@code [cx − r, cx + r]} has a non-zero void ramp —
     * i.e. sits inside one repeat's entry fade + core. A sphere straddling the fade's first column
     * (untouched overworld before it) or the core's hard far edge would otherwise be carved on one side
     * and cut off flat on the other. Pure in the cycle, so unit-testable; a sphere can't span two
     * repeats (its diameter is orders of magnitude below the period).
     */
    static List<SphereField.Sphere> insideBand(WorldGenCycle cycle, List<SphereField.Sphere> spheres) {
        if (spheres.isEmpty()) return spheres;
        List<SphereField.Sphere> kept = null;
        for (SphereField.Sphere s : spheres) {
            boolean in = cycle.spheresVoidRamp(s.cx() - s.r()) > 0.0 && cycle.spheresVoidRamp(s.cx() + s.r()) > 0.0;
            if (in) {
                if (kept == null) kept = new java.util.ArrayList<>(spheres.size());
                kept.add(s);
            }
        }
        return kept == null ? List.of() : java.util.Collections.unmodifiableList(kept);
    }

    /**
     * True if the chunk at {@code (chunkMinX, chunkMinZ)} generates as pure void: every column is in
     * the band <b>core</b> (ramp 1 — the fade keeps dissolving terrain) and no sphere touches it. The
     * gate the fill / surface / decoration skips and the portal site check share.
     */
    public static boolean isVoidChunk(ServerLevel overworld, int chunkMinX, int chunkMinZ) {
        if (!DungeonTrainCommonConfig.isSpheresEnabled()) return false;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.spheresLen() <= 0L) return false;
        if (cycle.spheresVoidRamp(chunkMinX) < 1.0 || cycle.spheresVoidRamp(chunkMinX + 15) < 1.0) return false;
        if (!DungeonTrainWorldData.get(overworld).startsWithTrain()) return false;
        return candidates(overworld, chunkMinX >> 4, chunkMinZ >> 4).isEmpty();
    }

    /**
     * True if the world position is <b>void space</b> in the band — anywhere the void ramp is non-zero
     * (entry fade or core) and inside no sphere. Per-block, for the fluid veto, so liquid can't pour
     * off a sphere's underside. The fade counts too: its crumbling seabeds and lake floors open holes
     * under worldgen water, and without the veto that water cascades to bedrock as an ever-spreading
     * sheet of flowing fluid (the runaway tick load the chuncks band hit) — a headless probe found
     * full-height water columns across the whole fade. Liquid there stays put as static blocks.
     */
    public static boolean isVoidSpace(ServerLevel overworld, int blockX, int blockY, int blockZ) {
        if (!DungeonTrainCommonConfig.isSpheresEnabled()) return false;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.spheresLen() <= 0L) return false;
        if (cycle.spheresVoidRamp(blockX) <= 0.0) return false;
        if (!DungeonTrainWorldData.get(overworld).startsWithTrain()) return false;
        return SphereField.bestAt(candidates(overworld, blockX >> 4, blockZ >> 4), blockX, blockY, blockZ) == null;
    }

    // ---- field + per-chunk candidate cache ---------------------------------
    // The field is keyed on (seed, config epoch): a fresh world or a COMMON config reload rebuilds it,
    // which also drops its per-cell memo. The candidate cache is the same shape as ChuncksBand's kind
    // cache: worldgen workers + the server thread both read it; bounded; cleared on reload / new seed.
    private static final ConcurrentHashMap<Long, List<SphereField.Sphere>> CANDIDATE_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_CACHE = 1 << 16;
    private static volatile SphereField cachedField;
    private static volatile long cachedSeed = Long.MIN_VALUE;

    private static SphereField fieldFor(ServerLevel overworld) {
        long seed = DungeonTrainWorldData.get(overworld).getGenerationSeed();
        SphereField f = cachedField;
        if (f != null && seed == cachedSeed) return f;
        synchronized (SpheresBand.class) {
            f = cachedField;
            if (f != null && seed == cachedSeed) return f;
            CANDIDATE_CACHE.clear();
            f = new SphereField(paramsFromConfig(seed), surfaceSampler(overworld), mixer(overworld));
            cachedSeed = seed;
            cachedField = f;
            return f;
        }
    }

    private static SphereField.Params paramsFromConfig(long seed) {
        return new SphereField.Params(seed,
                DungeonTrainCommonConfig.getSpheresCellBlocks(),
                DungeonTrainCommonConfig.getSpheresDensity(),
                DungeonTrainCommonConfig.getSpheresMinRadius(),
                DungeonTrainCommonConfig.getSpheresMaxRadius(),
                DungeonTrainCommonConfig.getSpheresCenterMinY(),
                DungeonTrainCommonConfig.getSpheresCenterMaxY(),
                DungeonTrainCommonConfig.getSpheresSurfaceBias());
    }

    /**
     * The natural surface at a column straight from the chunk generator's noise — pure in the seed,
     * no chunk needed, and the same {@code getBaseHeight} path vanilla structure placement takes from
     * worldgen workers. {@code WORLD_SURFACE_WG} counts water as surface, so an ocean sphere caps at
     * the waterline (its water stays inside the sphere).
     */
    private static SphereField.SurfaceSampler surfaceSampler(ServerLevel overworld) {
        var source = overworld.getChunkSource();
        var generator = source.getGenerator();
        var randomState = source.randomState();
        return (x, z) -> generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, overworld, randomState);
    }

    /**
     * The band's dimension mix ({@link SpheresSegments}, from {@code SpheresProgressionConfig}): which
     * dimension a sphere is cut from and how likely it is to hold a structure both follow where its
     * centre sits along the band core. Snapshotted with the field, so a config reload
     * ({@link #invalidateCache}) rebuilds both together. End spheres anchor to the End generator's island
     * surface; a column over the End void reports {@link SphereField#NO_SURFACE}.
     */
    private static SphereField.Mixer mixer(ServerLevel overworld) {
        SpheresSegments seg = SpheresProgressionConfig.segments();
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        ServerLevel end = overworld.getServer().getLevel(Level.END);
        return new SphereField.Mixer() {
            @Override
            public SphereSource sourceAt(int cx, double u) {
                return seg.sourceAt(cycle.spheresCoreOffset(cx), u);
            }

            @Override
            public double structureChanceAt(int cx) {
                return seg.structureChanceAt(cycle.spheresCoreOffset(cx));
            }

            @Override
            public double taperAt(int cx) {
                return SpheresSegments.exitTaper(cycle.spheresCoreOffset(cx), cycle.spheresLen(),
                        SpheresProgressionConfig.exitTaperBlocks(), SpheresProgressionConfig.exitVoidBlocks());
            }

            @Override
            public int endSurfaceY(int x, int z) {
                if (end == null) return SphereField.NO_SURFACE;
                var source = end.getChunkSource();
                int y = source.getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, end,
                        source.randomState());
                return y <= end.getMinBuildHeight() ? SphereField.NO_SURFACE : y;
            }
        };
    }

    /**
     * Drop the memoised field + per-chunk candidates. Called on COMMON {@code ModConfigEvent} (the band's
     * position or sphere geometry may have changed), alongside {@link WorldGenCycle#invalidateCache()}.
     */
    public static void invalidateCache() {
        synchronized (SpheresBand.class) {
            CANDIDATE_CACHE.clear();
            cachedField = null;
            cachedSeed = Long.MIN_VALUE;
        }
    }
}
