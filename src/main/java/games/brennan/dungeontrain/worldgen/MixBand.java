package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/**
 * The <b>mix zone</b>: a hard-edged {@link CycleLayout.Type#MIX} slot where every chunk, with equal odds,
 * generates as one of the bands the run has already passed — any overworld style, Nether, BetterNether,
 * End, BetterEnd, upside-down, spheres, or a legacy era.
 *
 * <p>It works by handing each chunk a {@link WorldGenCycle#shifted shifted} cycle. The chunk rolls a
 * {@link Candidate} and a representative chunk inside that candidate's core (same doubling run), and gets
 * the cycle moved so its own X reads as the representative's X. Every band helper then answers for the
 * picked band without knowing the zone exists. The shift is a whole number of chunks, so a chunk keeps
 * its {@code x & 15} layout and its Z; noise, the per-chunk rolls and the train corridor stay on the real
 * chunk. Resolve the cycle through {@link #cycleAt} / {@link #cycleFor} <em>inside</em> each per-chunk
 * helper, never at a call site, so every pass for a chunk sees the same cycle.</p>
 *
 * <p>Progression — {@link TrainPhase}, advancements, the client sky — stays on the real X: the zone reads
 * as chuncks there.</p>
 *
 * <p>Thread-safety: pure functions of (seed, chunk, immutable cycle); memos are {@link ConcurrentHashMap}s
 * cleared on a new seed or a COMMON config reload ({@link #invalidateCache}). No thread-locals, so it is
 * safe on worldgen workers.</p>
 */
public final class MixBand {

    private MixBand() {}

    /**
     * One band the zone can pick. {@code token} is the {@code mixExclude} name; {@code lo..hi} is its core
     * window in base (run-0) coordinates, already trimmed by a margin so a representative never sits on a
     * fade.
     */
    public record Candidate(String token, CycleLayout.Type type, CycleLayout.Style style, LegacyBandKind era,
                            long lo, long hi) {}

    /** A chunk's pick: the candidate and the shift {@code dx} (a multiple of 16) onto its representative chunk. */
    public record Pick(Candidate candidate, long dx) {}

    /** Scan step (blocks) when measuring a candidate's core window. */
    private static final int SCAN_STEP = 16;
    /** Largest margin trimmed off each end of a core window. */
    private static final long MAX_MARGIN = 128L;

    // ---- candidate table ----------------------------------------------------------------

    /**
     * Every band behind the first mix slot of {@code cycle}'s layout, minus {@code exclude} (tokens). Each
     * distinct band once: overworld styles keep their longest gap, Nether / End keep one slot per style,
     * each legacy era its own core. Chuncks, stacks and later mix slots are never candidates. Empty without
     * a layout or a mix slot.
     */
    public static List<Candidate> candidates(WorldGenCycle cycle, Set<String> exclude) {
        List<Candidate> out = new ArrayList<>();
        CycleLayout layout = cycle.layout();
        if (layout == null) return out;
        int mix = layout.firstIndexOf(CycleLayout.Type.MIX);
        if (mix < 0) return out;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < mix; i++) {
            CycleLayout.Slot slot = layout.slot(i);
            final int slotIndex = i;
            switch (slot.type()) {
                case OVERWORLD -> {
                    String token = overworldToken(slot.style());
                    int longest = longestOverworld(layout, mix, slot.style());
                    if (longest != i || !seen.add(token)) continue;
                    add(out, exclude, token, slot, null, window(cycle, layout, i, x -> cycle.slotIndexAt(x) == slotIndex));
                }
                case NETHER -> {
                    String token = slot.style() == CycleLayout.Style.BETTER ? "nether_better" : "nether";
                    if (!seen.add(token)) continue;
                    add(out, exclude, token, slot, null, window(cycle, layout, i,
                            x -> cycle.slotIndexAt(x) == slotIndex && cycle.isNetherCore(x)));
                }
                case END -> {
                    String token = slot.style() == CycleLayout.Style.BETTER ? "end_better" : "end";
                    if (!seen.add(token)) continue;
                    add(out, exclude, token, slot, null, window(cycle, layout, i,
                            x -> cycle.slotIndexAt(x) == slotIndex && cycle.isEndCore(x)));
                }
                case UPSIDE_DOWN -> {
                    if (!seen.add("upside_down")) continue;
                    add(out, exclude, "upside_down", slot, null, window(cycle, layout, i,
                            x -> cycle.slotIndexAt(x) == slotIndex && cycle.isInUpsideDownBand(x)));
                }
                case SPHERES -> {
                    if (!seen.add("spheres")) continue;
                    add(out, exclude, "spheres", slot, null, window(cycle, layout, i,
                            x -> cycle.slotIndexAt(x) == slotIndex && cycle.isInSpheresBand(x)));
                }
                case LEGACY_RUN -> {
                    for (LegacySpan era : layout.eras(i)) {
                        LegacyBandKind kind = era.kind();
                        // Lost City is plain overworld terrain; its cities are multi-chunk structures a lone
                        // mix chunk can't carry, so as a pick it would only repeat "ow".
                        if (kind.usesVanillaTerrain()) continue;
                        if (!seen.add(kind.token())) continue;
                        add(out, exclude, kind.token(), slot, kind, window(cycle, layout, i,
                                x -> cycle.slotIndexAt(x) == slotIndex && cycle.isInLegacyBand(kind, x)));
                    }
                }
                case CHUNCKS, STACKS, MIX -> { }                   // never picked: the zone's own neighbours
            }
        }
        return out;
    }

    private static void add(List<Candidate> out, Set<String> exclude, String token, CycleLayout.Slot slot,
                            LegacyBandKind era, long[] window) {
        if (window == null || exclude.contains(token)) return;
        out.add(new Candidate(token, slot.type(), slot.style(), era, window[0], window[1]));
    }

    static String overworldToken(CycleLayout.Style style) {
        return style == CycleLayout.Style.VANILLA ? "ow" : "ow_" + style.name().toLowerCase(Locale.ROOT);
    }

    /** Index of the longest overworld slot of {@code style} before {@code end}, or {@code -1}. */
    private static int longestOverworld(CycleLayout layout, int end, CycleLayout.Style style) {
        int best = -1;
        for (int i = 0; i < end; i++) {
            CycleLayout.Slot s = layout.slot(i);
            if (s.type() != CycleLayout.Type.OVERWORLD || s.style() != style) continue;
            if (best < 0 || layout.length(i) > layout.length(best)) best = i;
        }
        return best;
    }

    /**
     * The longest run of run-0 base coordinates inside slot {@code i} where {@code core} holds, trimmed by
     * {@code min(128, len / 4)} at each end; {@code null} when the core is empty.
     */
    private static long[] window(WorldGenCycle cycle, CycleLayout layout, int i, IntPredicate core) {
        long start = layout.start(i);
        long end = start + layout.length(i);
        long bestLo = -1L;
        long bestLen = 0L;
        long runLo = -1L;
        for (long u = start; u < end; u += SCAN_STEP) {
            boolean in = core.test((int) cycle.worldXOfBase(0, u));
            if (in && runLo < 0L) runLo = u;
            if ((!in || u + SCAN_STEP >= end) && runLo >= 0L) {
                long runHi = in ? Math.min(end, u + SCAN_STEP) : u;
                if (runHi - runLo > bestLen) {
                    bestLen = runHi - runLo;
                    bestLo = runLo;
                }
                runLo = -1L;
            }
        }
        if (bestLen <= 0L) return null;
        long margin = Math.min(MAX_MARGIN, bestLen / 4L);
        return new long[] {bestLo + margin, bestLo + bestLen - margin};
    }

    // ---- the pick -----------------------------------------------------------------------

    /**
     * The pick for chunk {@code (chunkX, chunkZ)}, or {@code null} where the chunk keeps the base cycle:
     * outside the mix zone, with no candidates, in the stacks fade after the zone when stacks claims the
     * chunk, or when the chunk is one of the zone's void chunks ({@link #keeps}). Pure and deterministic
     * in (seed, chunk, cycle, candidates).
     */
    public static Pick pickAt(WorldGenCycle base, List<Candidate> candidates, long seed, int chunkX, int chunkZ) {
        int x = chunkX << 4;
        if (candidates.isEmpty() || !base.mixPicksAt(x)) return null;
        if (!base.isInMixZone(x) && stacksClaims(base, seed, chunkX, chunkZ, x)) return null;
        if (!keeps(base, seed, chunkX, chunkZ)) return null;
        int n = candidates.size();
        Candidate c = candidates.get(Math.min(n - 1, (int) (hash01(seed, chunkX, chunkZ, PICK_SALT) * n)));
        int k = base.runIndexAt(x);
        long lo = Math.floorDiv(base.worldXOfBase(k, c.lo()) + 15L, 16L);    // first whole chunk inside
        long hi = Math.floorDiv(base.worldXOfBase(k, c.hi()), 16L);           // exclusive
        if (hi <= lo) hi = lo + 1L;
        long rep = lo + Math.min(hi - lo - 1L, (long) (hash01(seed, chunkX, chunkZ, REP_SALT) * (hi - lo)));
        return new Pick(c, (rep - chunkX) * 16L);
    }

    /**
     * The zone is as sparse as the chuncks core: a chunk keeps terrain with the chuncks keep density, and
     * only a kept chunk becomes a band — the rest stay chuncks void. Pure.
     */
    static boolean keeps(WorldGenCycle base, long seed, int chunkX, int chunkZ) {
        return hash01(seed, chunkX, chunkZ, KEEP_SALT) < base.chuncksKeepDensity();
    }

    /**
     * True if chunk {@code (chunkX, chunkZ)} of {@code level} is one of the mix zone's void chunks (in the
     * zone, or in the stacks fade after it without stacks claiming it, and not kept). {@link ChuncksBand}
     * voids these, so the zone keeps the chuncks band's density.
     */
    public static boolean isVoidAt(ServerLevel level, int chunkX, int chunkZ) {
        WorldGenCycle base = WorldGenCycle.fromConfig();
        int x = chunkX << 4;
        if (!base.mixPicksAt(x)) return false;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (!data.startsWithTrain()) return false;
        long seed = data.getGenerationSeed();
        if (!base.isInMixZone(x) && stacksClaims(base, seed, chunkX, chunkZ, x)) return false;
        return !keeps(base, seed, chunkX, chunkZ);
    }

    /** In the stacks fade after the zone, stacks keeps any chunk its own void roll claims. */
    private static boolean stacksClaims(WorldGenCycle base, long seed, int chunkX, int chunkZ, int x) {
        double ramp = base.stacksVoidRampAt(x);
        return StacksBand.classify(seed, chunkX, chunkZ, ramp, base.stacksDensity(), false) != StacksBand.Kind.TERRAIN;
    }

    /** {@code base}, or the cycle shifted onto chunk {@code (chunkX, chunkZ)}'s pick. Pure given the seed. */
    public static WorldGenCycle cycleFor(WorldGenCycle base, long seed, int chunkX, int chunkZ) {
        if (!base.mixPicksAt(chunkX << 4)) return base;               // cheap gate — no table, no hash
        Pick p = cachedPick(base, seed, chunkX, chunkZ);
        return p == null ? base : shiftedCached(base, p.dx());
    }

    /**
     * The cycle chunk {@code (chunkX, chunkZ)} of {@code level} generates with: the live config cycle, or
     * its mix-shifted copy inside the zone. The base cycle off the overworld and in a world without a train.
     */
    public static WorldGenCycle cycleAt(ServerLevel level, int chunkX, int chunkZ) {
        WorldGenCycle base = WorldGenCycle.fromConfig();
        if (!base.mixPicksAt(chunkX << 4)) return base;
        if (!level.dimension().equals(Level.OVERWORLD)) return base;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (!data.startsWithTrain()) return base;
        return cycleFor(base, data.getGenerationSeed(), chunkX, chunkZ);
    }

    /** Sentinel Z for the band helpers' X-only overloads: read the base cycle (progression, the real X). */
    public static final int NO_Z = Integer.MIN_VALUE;

    /** {@link #cycleAtBlock}, or the base cycle when {@code blockZ} is {@link #NO_Z}. */
    public static WorldGenCycle cycleAtColumn(ServerLevel level, int blockX, int blockZ) {
        return blockZ == NO_Z ? WorldGenCycle.fromConfig() : cycleAtBlock(level, blockX, blockZ);
    }

    /** {@link #cycleAt} for the chunk holding block {@code (blockX, blockZ)}. */
    public static WorldGenCycle cycleAtBlock(ServerLevel level, int blockX, int blockZ) {
        return cycleAt(level, blockX >> 4, blockZ >> 4);
    }

    /** The pick for a chunk of {@code level}, or {@code null} (for the debug command). */
    public static Pick pickAt(ServerLevel level, int chunkX, int chunkZ) {
        WorldGenCycle base = WorldGenCycle.fromConfig();
        if (!base.mixPicksAt(chunkX << 4)) return null;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (!data.startsWithTrain()) return null;
        return cachedPick(base, data.getGenerationSeed(), chunkX, chunkZ);
    }

    /** The live candidate table (config exclusions applied). */
    public static List<Candidate> candidates(WorldGenCycle base) {
        Table t = table;
        if (t == null || t.cycle() != base) {
            t = new Table(base, List.copyOf(candidates(base, parseExclude(DungeonTrainCommonConfig.getMixExclude()))));
            table = t;                                                 // benign race — same-value replace
        }
        return t.candidates();
    }

    static Set<String> parseExclude(String raw) {
        Set<String> out = new HashSet<>();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ---- memos --------------------------------------------------------------------------

    private record Table(WorldGenCycle cycle, List<Candidate> candidates) {}

    private static final Object NONE = new Object();
    private static final int MAX_CACHE = 1 << 18;
    private static volatile Table table;
    private static final ConcurrentHashMap<Long, Object> PICK_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, WorldGenCycle> SHIFTED = new ConcurrentHashMap<>();
    private static volatile long cacheSeed = Long.MIN_VALUE;
    private static volatile WorldGenCycle cacheCycle;

    private static Pick cachedPick(WorldGenCycle base, long seed, int chunkX, int chunkZ) {
        if (seed != cacheSeed || base != cacheCycle) {                // new world or config → drop stale picks
            PICK_CACHE.clear();
            SHIFTED.clear();
            cacheSeed = seed;
            cacheCycle = base;
        }
        long key = ChunkPos.asLong(chunkX, chunkZ);
        Object hit = PICK_CACHE.get(key);
        if (hit != null) return hit == NONE ? null : (Pick) hit;
        Pick p = pickAt(base, candidates(base), seed, chunkX, chunkZ);
        if (PICK_CACHE.size() >= MAX_CACHE) PICK_CACHE.clear();
        PICK_CACHE.put(key, p == null ? NONE : p);
        return p;
    }

    private static WorldGenCycle shiftedCached(WorldGenCycle base, long dx) {
        if (SHIFTED.size() >= MAX_CACHE) SHIFTED.clear();
        return SHIFTED.computeIfAbsent(dx, base::shifted);
    }

    /** Drop memoised picks and the candidate table — COMMON config reload may have moved the bands. */
    public static void invalidateCache() {
        PICK_CACHE.clear();
        SHIFTED.clear();
        table = null;
        cacheCycle = null;
    }

    // ---- per-chunk uniform hash (the ChuncksBand idiom) ----------------------------------
    private static final int PICK_SALT = 41;
    private static final int REP_SALT = 42;
    private static final int KEEP_SALT = 43;

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
