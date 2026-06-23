package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.feature.MountainNoise;

/**
 * Pure layout math for the nether-transition band's <b>terrain-noise mountains</b> — the
 * per-(x,z) target top the density router raises the overworld surface to, and the gate for
 * which columns it applies to. Lifted from the old post-process
 * {@code NetherTransitionFeature.fillMountainColumn} (lines ~244-250) so the same shape is now
 * produced in terrain noise instead of being stamped on top after surface/trees/structures.
 *
 * <p>No Minecraft types — depends only on {@link WorldGenCycle} and {@link MountainNoise}, both
 * pure — so it is unit-testable without a NeoForge bootstrap (same convention as
 * {@link WorldGenCycle}). The density wrapper ({@code NetherBandTerrainDensityFunction}) calls
 * these and turns {@link #targetTop} into a {@code max(child, k·(T − y))} density raise.</p>
 */
public final class NetherMountainTerrain {

    private NetherMountainTerrain() {}

    /**
     * True where the terrain-noise raise applies at this world-X: inside the nether band's
     * mountain stages, but NOT the leading beach span (which stays natural overworld, or becomes
     * the post-process sand shore over ocean) and NOT where the End band owns the column (the End
     * erosion would delete it anyway — mirrors the old feature's {@code middleRampAt > 0} skip).
     */
    public static boolean raises(WorldGenCycle cycle, int worldX) {
        return cycle.netherHeightRamp(worldX) > 0.0
                && !cycle.isNetherBeachStage(worldX)
                && cycle.endMiddleRamp(worldX) <= 0.0;
    }

    /**
     * Per-column mountain target top (world-Y): the ridged {@link MountainNoise} relief scaled by
     * the stage multiplier ({@link WorldGenCycle#netherMountainMultiplier}), tapered toward the
     * nether-core height as the real-Nether core approaches ({@link WorldGenCycle#netherRamp}) so
     * the mountain sinks into the nether instead of capping it with stone. Clamped to
     * {@code [seaLevel, worldCeiling]}.
     *
     * <p>Natural terrain isn't added in here (the old {@code natural01} term): the density wrapper
     * {@code max}es this raise against the real terrain, so naturally-higher ground shows through
     * for free without a heightmap probe.</p>
     *
     * <p>The above-sea height is finally scaled by {@link WorldGenCycle#netherMountainFeather} so the
     * added height eases from 0 at each band edge — the mountains grow out of the natural world
     * gradually instead of stepping up as a cliff.</p>
     */
    public static double targetTop(WorldGenCycle cycle, long seed, int worldX, int worldZ,
                                   int seaLevel, int worldCeiling, int netherTop, int baseRelief) {
        double mult = cycle.netherMountainMultiplier(worldX);
        double relief01 = MountainNoise.height01(seed, worldX, worldZ);   // [0,1]
        double amplified = seaLevel + relief01 * baseRelief * mult;
        double n = cycle.netherRamp(worldX);                              // 0..1 toward the nether core
        double top = amplified * (1.0 - n) + netherTop * n;
        // Feather the ADDED (above-sea) height to 0 exactly at each band edge so the mountain grows
        // out of the natural terrain instead of stepping up as a cliff: at the edge top == seaLevel,
        // so the density wrapper's max() keeps the natural surface (over land) and the ocean shore's
        // sea-level handoff (over water); 1 across the interior leaves the core/crossfade untouched.
        double feather = cycle.netherMountainFeather(worldX);
        top = seaLevel + (top - seaLevel) * feather;
        if (top < seaLevel) return seaLevel;
        if (top > worldCeiling) return worldCeiling;
        return top;
    }
}
