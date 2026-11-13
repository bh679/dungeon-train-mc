package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.SpheresProgressionConfig;

/**
 * Which sky box a column shows, as the two intensities the client sky renderers blend on: the End sky
 * (the End band's void, and the spheres band's End-sky window) and the Nether sky. Pure world-X maths over
 * COMMON config, so the client's sky ({@code ClientVoidBand}, {@code ClientNetherBand}), the wind
 * ({@code WindZones}) and server rules that care what sky a player stands under all read one answer.
 *
 * <p>No train-world check here: callers gate on their own copy of {@code startsWithTrain}.</p>
 */
public final class BandSky {

    private BandSky() {}

    /**
     * End-sky intensity at {@code worldX}, unclamped: the End band's sky ramp and the spheres band's End-sky
     * window. The two bands never overlap, so {@code max} just picks whichever one the column is in.
     */
    public static double endSkyIntensityAt(WorldGenCycle cycle, int worldX) {
        double end = DungeonTrainCommonConfig.isDisintegrationEnabled()
                ? cycle.endSkyRamp(worldX, DungeonTrainCommonConfig.getDisintegrationSkyFadeOffsetBlocks())
                : 0.0;
        return Math.max(end, spheresEndSkyIntensity(cycle, worldX));
    }

    /**
     * End-sky intensity over the spheres band's End-sky window ({@link SpheresSky#endSky}): it takes over
     * {@code spheresEndSkyStartBlocks} into the spheres and fades back to the overworld sky over the
     * {@code spheresEndSkyExitFadeBlocks} before its closing void.
     */
    public static double spheresEndSkyIntensity(WorldGenCycle cycle, int worldX) {
        if (!DungeonTrainCommonConfig.isSpheresEnabled() || !DungeonTrainCommonConfig.isSpheresEndSkyEnabled()) {
            return 0.0;
        }
        return SpheresSky.endSky(cycle, SpheresProgressionConfig.segments(), worldX,
                DungeonTrainCommonConfig.getSpheresEndSkyFadeBlocks(), SpheresProgressionConfig.endSkyExitFadeBlocks(),
                SpheresProgressionConfig.exitVoidBlocks());
    }

    /** Nether-sky intensity at {@code worldX}, unclamped: 0 outside a Nether core, rising to 1 across it. */
    public static double netherSkyIntensityAt(WorldGenCycle cycle, int worldX) {
        if (!DungeonTrainCommonConfig.isNetherTransitionEnabled()) return 0.0;
        return cycle.netherRamp(worldX);
    }
}
