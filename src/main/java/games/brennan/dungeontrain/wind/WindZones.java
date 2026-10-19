package games.brennan.dungeontrain.wind;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.SpheresProgressionConfig;
import games.brennan.dungeontrain.worldgen.SpheresSky;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;

/**
 * Where the band the train is in changes the wind — shared by the server push, the client push, the streaks
 * and the sound, so every side agrees:
 * <ul>
 *   <li><b>Under an End sky</b> (the End band and its void, and the spheres band's End-sky window) — no wind;
 *       it fades out as the sky turns and back in as it returns.</li>
 *   <li><b>Under a Nether sky</b> — half the wind, scaled with how far into the Nether look you are; the
 *       streaks turn orange.</li>
 *   <li><b>In the deep dark</b> (the {@code minecraft:deep_dark} biome) — half the wind; the streaks turn dark
 *       blue.</li>
 * </ul>
 * Dimensional carriages (portal twin space) have no wind at all; that check lives with each side's own twin
 * space test ({@code PortalTwinSpace#isInside} / {@code ClientUpsideDownBand#isInPortalTwinSpace}).
 *
 * <p>The End-sky value is the same one the client sky renderer uses ({@code ClientVoidBand#endSkyIntensityAt}),
 * computed from config and the cycle alone so the server can use it too.</p>
 */
public final class WindZones {

    static final double NETHER_FACTOR = 0.5;
    static final double DEEP_DARK_FACTOR = 0.5;

    private WindZones() {}

    /** Wind multiplier from the band at {@code worldX}: 0 under a full End sky, 0.5 under a full Nether sky. */
    public static double bandFactor(double worldX) {
        int x = (int) Math.floor(worldX);
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        double end = endSky(cycle, x);
        double nether = nether(cycle, x);
        return (1.0 - end) * (1.0 - (1.0 - NETHER_FACTOR) * nether);
    }

    /** Nether-sky intensity {@code 0..1} at {@code worldX} (drives the orange tint). */
    public static double netherAt(double worldX) {
        return nether(WorldGenCycle.fromConfig(), (int) Math.floor(worldX));
    }

    /** End-sky intensity {@code 0..1} at {@code worldX}. */
    public static double endSkyAt(double worldX) {
        return endSky(WorldGenCycle.fromConfig(), (int) Math.floor(worldX));
    }

    public static boolean isDeepDark(Level level, BlockPos pos) {
        return level.getBiome(pos).is(Biomes.DEEP_DARK);
    }

    /** {@link #DEEP_DARK_FACTOR} in the deep dark, else 1. */
    public static double deepDarkFactor(Level level, BlockPos pos) {
        return isDeepDark(level, pos) ? DEEP_DARK_FACTOR : 1.0;
    }

    private static double nether(WorldGenCycle cycle, int x) {
        if (!DungeonTrainCommonConfig.isNetherTransitionEnabled()) return 0.0;
        return clamp(cycle.netherRamp(x));
    }

    private static double endSky(WorldGenCycle cycle, int x) {
        double end = DungeonTrainCommonConfig.isDisintegrationEnabled()
                ? cycle.endSkyRamp(x, DungeonTrainCommonConfig.getDisintegrationSkyFadeOffsetBlocks())
                : 0.0;
        double spheres = 0.0;
        if (DungeonTrainCommonConfig.isSpheresEnabled() && DungeonTrainCommonConfig.isSpheresEndSkyEnabled()) {
            spheres = SpheresSky.endSky(cycle, SpheresProgressionConfig.segments(), x,
                    DungeonTrainCommonConfig.getSpheresEndSkyFadeBlocks(),
                    SpheresProgressionConfig.endSkyExitFadeBlocks(), SpheresProgressionConfig.exitVoidBlocks());
        }
        return clamp(Math.max(end, spheres));
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
