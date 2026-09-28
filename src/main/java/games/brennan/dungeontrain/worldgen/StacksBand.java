package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side helper for the <b>stacks</b> band — the fifth looping phase of the {@link WorldGenCycle},
 * appended after the chuncks band with a long plain-overworld lead-in. Across the band's X-range the
 * world is mostly <b>void</b>; scattered chunks each hold a <b>vertical stack</b>:
 *
 * <ul>
 *   <li>{@link Kind#TERRAIN} — normal overworld (only in the entry fade, where void ramps in).</li>
 *   <li>{@link Kind#VOID} — empty; {@code NoiseBasedChunkGeneratorMixin} hands back an all-air chunk
 *       (the same fast path the End void and chuncks bands use). The floating track bed still crosses.</li>
 *   <li>{@link Kind#STACK} — also generated as all-air, then {@code StacksFeature} stamps one vanilla
 *       structure piece repeatedly from the world floor to near build height — a tower of one thing.</li>
 * </ul>
 *
 * <p>The per-chunk kind is a pure, seed-stable function of the chunk coordinates (see {@link #hash01}),
 * so it is identical across reloads and on every worldgen worker. Chunks whose Z-range touches the train's
 * tunnel corridor are never {@link Kind#STACK} — the track bed always passes <em>between</em> towers.
 * Layout/positioning lives in {@link WorldGenCycle}; this class only classifies chunks.</p>
 *
 * <p>Thread-safety: reads only the memoised {@link WorldGenCycle#fromConfig()}, the volatile
 * {@link DungeonTrainCommonConfig}, and per-world {@link DungeonTrainWorldData} — the same access pattern
 * {@link ChuncksBand#kindOf} already uses from C2ME worldgen workers.</p>
 */
public final class StacksBand {

    /** Returned by {@link #startX} when the stacks band is disabled or the world has no train. */
    public static final long OFF = Long.MAX_VALUE;

    /** Per-chunk classification within the band (+ entry fade). */
    public enum Kind { TERRAIN, VOID, STACK }

    private StacksBand() {}

    /**
     * World-X where the cycle is anchored (shared with the other bands via {@link WorldGenCycle}), or
     * {@link #OFF} when the stacks band is disabled, the world has no train, or the band has no length.
     * Independent of the other bands' enable flags.
     */
    public static long startX(ServerLevel overworld) {
        if (!DungeonTrainCommonConfig.isStacksEnabled()) return OFF;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return OFF;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.period() <= 0L || cycle.stacksLen() <= 0L) return OFF;
        return cycle.startX();
    }

    /** True if the column at {@code worldX} lies in the stacks band core. */
    public static boolean isInBand(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return false;
        return WorldGenCycle.fromConfig().isInStacksBand(worldX);
    }

    /**
     * True if the column at {@code worldX} lies in the stacks band OR anywhere in the plain-overworld
     * run-up to it (the lead gap after the chuncks band, the entry fade) — see
     * {@link WorldGenCycle#isInStacksApproachOrBand}. The advancement gate for
     * {@code reached_overworld_again} uses this so "Re-Over-World" is held back until the overworld that
     * follows the band. False when the band is disabled or the world has no train.
     */
    public static boolean isInApproachOrBand(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return false;
        return WorldGenCycle.fromConfig().isInStacksApproachOrBand(worldX);
    }

    /**
     * Classify the chunk at {@code (chunkX, chunkZ)}. Uses the position-driven void ramp
     * ({@link WorldGenCycle#stacksVoidRampAt}) so the entry fade zone naturally produces sparse void that
     * thickens toward the core. A ramp {@code <= 0} — outside the band + fade, or when the band is off —
     * always returns {@link Kind#TERRAIN} (normal terrain, never void).
     *
     * <p>Ordering is deliberate for the runtime hot path (the fluid veto queries this on every water/lava
     * spread in the overworld): the cheap gate — config flag + memoised cycle + pure offset math — runs
     * <b>before</b> any {@code DungeonTrainWorldData} data-storage lookup or noise hash, so out-of-band
     * spreads (the overwhelming majority) fold to a few comparisons and touch neither. Only genuinely
     * in-band chunks reach the per-world seed lookup, and those are memoised (see {@link #cachedKind}).</p>
     */
    public static Kind kindOf(ServerLevel overworld, int chunkX, int chunkZ) {
        if (!DungeonTrainCommonConfig.isStacksEnabled()) return Kind.TERRAIN;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.stacksLen() <= 0L) return Kind.TERRAIN;
        double voidRamp = cycle.stacksVoidRampAt(chunkX << 4);
        if (voidRamp <= 0.0) return Kind.TERRAIN;                    // outside the band + fade → normal terrain
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return Kind.TERRAIN;            // no train → no bands
        boolean corridor = touchesCorridor(data, chunkZ);
        return cachedKind(data.getGenerationSeed(), chunkX, chunkZ, voidRamp, cycle.stacksDensity(), corridor);
    }

    /**
     * True if the chunk row {@code chunkZ} overlaps the train's tunnel corridor (walls included) — the
     * Z-range {@code TunnelGeometry.wallMinZ()..wallMaxZ()}. Pure data: derived from the world's carriage
     * dims and train Y, no level access. A corridor chunk can be void but never holds a stack, so the
     * floating track bed is never cut by a tower.
     */
    static boolean touchesCorridor(DungeonTrainWorldData data, int chunkZ) {
        TunnelGeometry tg = TunnelGeometry.from(TrackGeometry.from(data.dims(), data.getTrainY()));
        return touchesCorridor(chunkZ, tg.wallMinZ(), tg.wallMaxZ());
    }

    /** Pure Z-overlap test: chunk row {@code chunkZ} spans {@code [chunkZ·16, chunkZ·16 + 15]}. */
    static boolean touchesCorridor(int chunkZ, int wallMinZ, int wallMaxZ) {
        int minZ = chunkZ << 4;
        int maxZ = minZ + 15;
        return minZ <= wallMaxZ && maxZ >= wallMinZ;
    }

    /**
     * Pure per-chunk classification (no Minecraft types) — a chunk turns to void with probability
     * {@code voidRamp}; a void chunk holds a stack with probability {@code stackDensity} unless it touches
     * the corridor. Deterministic in {@code (seed, chunkX, chunkZ)} and uniform, so both fractions track
     * their knob linearly. Package-private for unit testing; {@link #kindOf} is the world-facing entry point.
     */
    static Kind classify(long seed, int chunkX, int chunkZ, double voidRamp, double stackDensity,
                         boolean corridor) {
        if (hash01(seed, chunkX, chunkZ, VOID_SALT) >= voidRamp) return Kind.TERRAIN;
        if (corridor) return Kind.VOID;
        return hash01(seed, chunkX, chunkZ, STACK_SALT) < stackDensity ? Kind.STACK : Kind.VOID;
    }

    /**
     * Pure, seed-stable pick of an index in {@code [0, n)} for the stack in chunk {@code (chunkX, chunkZ)}.
     * {@code attempt} salts the roll so a rejected pick (a template that does not fit the chunk) rerolls
     * deterministically instead of falling back to a fixed neighbour. {@code n <= 0} returns {@code -1}.
     */
    static int pickIndex(long seed, int chunkX, int chunkZ, int attempt, int n) {
        if (n <= 0) return -1;
        double r = hash01(seed, chunkX, chunkZ, PICK_SALT + attempt);
        return Math.min(n - 1, (int) Math.floor(r * n));
    }

    /** Pure, seed-stable rotation for the stack in chunk {@code (chunkX, chunkZ)} — the same for every layer. */
    public static Rotation rotationFor(long seed, int chunkX, int chunkZ) {
        double r = hash01(seed, chunkX, chunkZ, ROTATION_SALT);
        Rotation[] all = Rotation.values();
        return all[Math.min(all.length - 1, (int) Math.floor(r * all.length))];
    }

    // ---- per-chunk classification cache -------------------------------------
    // In-band kinds are stable within a (seed, config) epoch, so memoise them by chunk key: the fluid
    // veto queries the same boundary chunks every fluid tick, and the fill / decoration / bedrock gen
    // passes each re-derive the kind per chunk. ConcurrentHashMap: worldgen workers + the server thread
    // both hit it. Seed-keyed (a fresh world clears it) and cleared on COMMON config reload via
    // #invalidateCache (wired next to WorldGenCycle#invalidateCache). Only in-band chunks are cached
    // (kindOf's ramp gate returns TERRAIN before reaching here), so no out-of-band entries accumulate.
    private static final ConcurrentHashMap<Long, Kind> KIND_CACHE = new ConcurrentHashMap<>();
    private static volatile long cacheSeed = Long.MIN_VALUE;
    private static final int MAX_CACHE = 1 << 18;                     // ~262k chunks — crude memory bound

    private static Kind cachedKind(long seed, int chunkX, int chunkZ, double voidRamp, double density,
                                   boolean corridor) {
        if (seed != cacheSeed) {                                      // new world (or first use) → drop stale kinds
            KIND_CACHE.clear();
            cacheSeed = seed;
        }
        long key = ChunkPos.asLong(chunkX, chunkZ);
        Kind hit = KIND_CACHE.get(key);
        if (hit != null) return hit;
        Kind k = classify(seed, chunkX, chunkZ, voidRamp, density, corridor);
        if (KIND_CACHE.size() >= MAX_CACHE) KIND_CACHE.clear();       // bound memory; recompute is cheap
        KIND_CACHE.put(key, k);
        return k;
    }

    /**
     * Drop the memoised per-chunk kinds. Called on COMMON {@code ModConfigEvent} (the band's position,
     * fade, or density may have changed), alongside {@link WorldGenCycle#invalidateCache()}.
     */
    public static void invalidateCache() {
        KIND_CACHE.clear();
        cacheSeed = Long.MIN_VALUE;
    }

    /**
     * True if the chunk at {@code (chunkMinX, chunkMinZ)} generates as all-air — a {@link Kind#VOID} or
     * {@link Kind#STACK} chunk (the stack is stamped into the empty chunk afterwards by the feature).
     * Takes any block coordinate in the chunk (floored to chunk coords). Routes through {@link #kindOf},
     * which gates and fast-outs internally.
     */
    public static boolean isVoidOrStackChunk(ServerLevel overworld, int chunkMinX, int chunkMinZ) {
        return kindOf(overworld, chunkMinX >> 4, chunkMinZ >> 4) != Kind.TERRAIN;
    }

    /**
     * True if the chunk at {@code (chunkMinX, chunkMinZ)} is an empty {@link Kind#VOID} chunk — the fluid
     * veto uses this so liquid inside a tower cannot pour out into the bottomless void beside it.
     */
    public static boolean isVoidChunk(ServerLevel overworld, int chunkMinX, int chunkMinZ) {
        return kindOf(overworld, chunkMinX >> 4, chunkMinZ >> 4) == Kind.VOID;
    }

    // ---- per-chunk uniform hash ---------------------------------------------
    // A splitmix64-style finaliser (same idiom as ChuncksBand#hash01) giving a uniform [0,1) value per
    // (seed, chunkX, chunkZ, salt). Uniform, so the void/stack fractions track the config knobs linearly,
    // and per-chunk independent, so towers scatter instead of clumping. Distinct salts decorrelate the
    // void / stack / pick / rotation rolls; the pick salt is offset by the reroll attempt.
    private static final int VOID_SALT = 11;
    private static final int STACK_SALT = 12;
    private static final int ROTATION_SALT = 13;
    private static final int PICK_SALT = 100;

    private static double hash01(long seed, int a, int b, int salt) {
        long h = seed * 0x9E3779B97F4A7C15L + salt * 0xD1B54A32D192ED03L;
        h ^= (long) a * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h ^= (long) b * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return (h >>> 11) * 0x1.0p-53;
    }
}
