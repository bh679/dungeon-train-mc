package games.brennan.dungeontrain.worldgen;

/**
 * The spheres band's <b>progression</b>: where, counted in blocks into the band core (from the end of
 * the entry fade), the sky changes, other dimensions join the sphere mix and structures run denser.
 * With the defaults ({@code SpheresProgressionConfig}) the 14000-block band reads:
 *
 * <pre>
 *   0 ─ 3000   overworld sky   overworld spheres
 *   3000 ─ 5000   End sky         overworld spheres
 *   5000 ─ 6000   End sky         overworld / Nether
 *   6000 ─ 9000   End sky         overworld / Nether / End
 *   9000 ─ 12000  End sky         overworld / Nether / End, structures ×5
 *   12000 ─ end   Nether sky      overworld / Nether / End
 * </pre>
 *
 * <p>Pure and immutable; build it with {@link #of}, which clamps the offsets monotonic.</p>
 */
public record SpheresSegments(long endSkyStart, long netherMixStart, long endMixStart,
                              long structureBoostStart, long structureBoostEnd, long netherSkyStart,
                              double structureChance, double structureBoostMultiplier,
                              int overworldWeight, int netherWeight, int endWeight) {

    /** Build from raw (config) values, clamping every offset and weight non-negative and in order. */
    public static SpheresSegments of(long endSkyStart, long netherMixStart, long endMixStart,
                                     long structureBoostStart, long structureBoostEnd, long netherSkyStart,
                                     double structureChance, double structureBoostMultiplier,
                                     int overworldWeight, int netherWeight, int endWeight) {
        long endSky = Math.max(0L, endSkyStart);
        long netherMix = Math.max(0L, netherMixStart);
        long boostStart = Math.max(0L, structureBoostStart);
        return new SpheresSegments(endSky, netherMix, Math.max(netherMix, endMixStart),
                boostStart, Math.max(boostStart, structureBoostEnd), Math.max(endSky, netherSkyStart),
                clamp01(structureChance), Math.max(0.0, structureBoostMultiplier),
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

    /** Chance {@code 0..1} a sphere centred {@code offset} blocks into the core is built around a structure. */
    public double structureChanceAt(long offset) {
        if (offset < 0L) return 0.0;
        boolean boosted = offset >= structureBoostStart && offset < structureBoostEnd;
        return boosted ? clamp01(structureChance * structureBoostMultiplier) : structureChance;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
