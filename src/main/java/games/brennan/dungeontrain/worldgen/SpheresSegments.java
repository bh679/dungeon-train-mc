package games.brennan.dungeontrain.worldgen;

/**
 * The spheres band's <b>progression</b>: where, counted in blocks into the band core (from the end of
 * the entry fade), the sky changes, other dimensions join the sphere mix and structures run denser.
 * With the defaults ({@code SpheresProgressionConfig}) the 6550-block band reads:
 *
 * <pre>
 *   0 ─ 1000      overworld sky   overworld spheres
 *   1000 ─ 1750   End sky         overworld spheres
 *   1750 ─ 2500   End sky         overworld / Nether
 *   2500 ─ 3250   End sky         overworld / Nether / End
 *   3250 ─ 6250   End sky         overworld / Nether / End, structures ×5 → ×20 → ×5
 *   5900 ─ 6450   (taper)         spheres rarer + smaller, End sky fading out
 *   6450 ─ 6550   overworld sky   empty void
 * </pre>
 *
 * <p>Over the last {@code spheresExitTaperBlocks} before a closing {@code spheresExitVoidBlocks} of empty
 * void the spheres thin out and shrink ({@link #exitTaper}), and the End sky fades back to overworld
 * ({@link SpheresSky}).</p>
 *
 * <p>Pure and immutable; build it with {@link #of}, which clamps the offsets monotonic.</p>
 */
public record SpheresSegments(long endSkyStart, long netherMixStart, long endMixStart,
                              long structureBoostStart, long structureBoostEnd,
                              double structureChance, double structureBoostMultiplier,
                              double structureBoostPeakMultiplier,
                              int overworldWeight, int netherWeight, int endWeight) {

    /** Build from raw (config) values, clamping every offset and weight non-negative and in order. */
    public static SpheresSegments of(long endSkyStart, long netherMixStart, long endMixStart,
                                     long structureBoostStart, long structureBoostEnd,
                                     double structureChance, double structureBoostMultiplier,
                                     double structureBoostPeakMultiplier,
                                     int overworldWeight, int netherWeight, int endWeight) {
        long endSky = Math.max(0L, endSkyStart);
        long netherMix = Math.max(0L, netherMixStart);
        long boostStart = Math.max(0L, structureBoostStart);
        return new SpheresSegments(endSky, netherMix, Math.max(netherMix, endMixStart),
                boostStart, Math.max(boostStart, structureBoostEnd),
                clamp01(structureChance), Math.max(0.0, structureBoostMultiplier),
                Math.max(0.0, structureBoostPeakMultiplier),
                Math.max(0, overworldWeight), Math.max(0, netherWeight), Math.max(0, endWeight));
    }

    /**
     * The dimension a sphere centred {@code offset} blocks into the core is cut from, given a uniform
     * roll {@code u ∈ [0,1)}. Overworld only before {@link #netherMixStart}; overworld / Nether until
     * {@link #endMixStart}; all three after. A negative offset (entry fade) is overworld. When every
     * weight allowed at that offset is zero the sphere is overworld.
     */
    public SphereSource sourceAt(long offset, double u) {
        if (offset < netherMixStart) return SphereSource.OVERWORLD;
        boolean end = offset >= endMixStart;
        int ow = overworldWeight, ne = netherWeight, en = end ? endWeight : 0;
        int total = ow + ne + en;
        if (total <= 0) return SphereSource.OVERWORLD;
        double pick = u * total;
        if (pick < ow) return SphereSource.OVERWORLD;
        if (pick < ow + ne) return SphereSource.NETHER;
        return SphereSource.END;
    }

    /**
     * Chance {@code 0..1} a sphere centred {@code offset} blocks into the core is built around a structure.
     * Inside the boost window the base chance is multiplied by {@link #structureMultiplierAt}.
     */
    public double structureChanceAt(long offset) {
        if (offset < 0L) return 0.0;
        return clamp01(structureChance * structureMultiplierAt(offset));
    }

    /**
     * Structure-chance multiplier at {@code offset}: {@code 1} outside the boost window; inside it a
     * triangle — {@link #structureBoostMultiplier} at the window's start, climbing linearly to
     * {@link #structureBoostPeakMultiplier} at its midpoint, then back down to
     * {@link #structureBoostMultiplier} at its end.
     */
    public double structureMultiplierAt(long offset) {
        if (offset < structureBoostStart || offset >= structureBoostEnd) return 1.0;
        double len = structureBoostEnd - structureBoostStart;
        double t = (offset - structureBoostStart) / len;             // 0 → 1 across the window
        double rise = 1.0 - Math.abs(2.0 * t - 1.0);                 // 0 → 1 at the midpoint → 0
        return structureBoostMultiplier + (structureBoostPeakMultiplier - structureBoostMultiplier) * rise;
    }

    /**
     * Exit taper {@code 0..1} at {@code offset} blocks into a core {@code coreLen} long: {@code 1} until the
     * taper starts {@code taperBlocks + voidBlocks} before the core's end, falling linearly to {@code 0}
     * where the last {@code voidBlocks} (empty void) begin, and {@code 0} across them. Spheres get rarer and
     * smaller with it. Negative offsets (entry fade) and a zero-length core taper nothing.
     */
    public static double exitTaper(long offset, long coreLen, long taperBlocks, long voidBlocks) {
        if (offset < 0L || coreLen <= 0L) return 1.0;
        long voidLen = Math.max(0L, Math.min(voidBlocks, coreLen));
        long end = coreLen - voidLen;                                  // where the spheres run out
        if (offset >= end) return 0.0;
        long taper = Math.max(0L, Math.min(taperBlocks, end));
        if (taper == 0L) return 1.0;
        long start = end - taper;
        if (offset < start) return 1.0;
        return clamp01((double) (end - offset) / taper);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
