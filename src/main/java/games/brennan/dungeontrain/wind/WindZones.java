package games.brennan.dungeontrain.wind;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.SpheresProgressionConfig;
import games.brennan.dungeontrain.worldgen.NetherBandBiomes;
import games.brennan.dungeontrain.worldgen.SpheresSky;
import games.brennan.dungeontrain.worldgen.SunkZone;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
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
 *   <li><b>Over the spheres band's void</b> — no wind, whatever the sky: it fades out as the terrain dissolves
 *       across the entry fade and back in over the closing void, so it is fully back where the world returns
 *       ({@link #spheresVoidAt}).</li>
 *   <li><b>Under a Nether sky</b> — no wind ({@link #NETHER_FACTOR}), fading with the sky; where any is left
 *       the streaks turn orange.</li>
 *   <li><b>Riding high</b> over the sunk approach to the Amplified band and the Amplified band itself — 1.5x
 *       wind ({@link #HIGH_UP_FACTOR}).</li>
 *   <li><b>Over the sky islands</b> (Caves of Chaos, Skylands, Floating) — calmer, slower, gappier wind
 *       ({@link #SKY_STYLE}).</li>
 *   <li><b>In the caves leaving the Nether</b> (deep dark, lush, dripstone and sulfur caves) — no wind
 *       ({@link #CAVE_FACTOR}); deep dark streaks would be dark blue if it is ever turned back on.</li>
 * </ul>
 * Dimensional carriages (portal twin space) have no wind at all; that check lives with each side's own twin
 * space test ({@code PortalTwinSpace#isInside} / {@code ClientUpsideDownBand#isInPortalTwinSpace}).
 *
 * <p>The End-sky value is the same one the client sky renderer uses ({@code ClientVoidBand#endSkyIntensityAt}),
 * computed from config and the cycle alone so the server can use it too.</p>
 */
public final class WindZones {

    /** Wind left under a full Nether sky (0 = none; fades with the sky). */
    static final double NETHER_FACTOR = 0.0;
    /** Wind left in the caves on the way out of the Nether (0 = none). */
    static final double CAVE_FACTOR = 0.0;
    /** Wind where the train rides high over sunk terrain: the approach to the Amplified band and the band itself. */
    static final double HIGH_UP_FACTOR = 1.5;

    private WindZones() {}

    /**
     * Wind multiplier from the band at {@code worldX}: 0 under a full End sky, a full Nether sky or over the
     * spheres band's void (fading between), {@link #HIGH_UP_FACTOR} where the train rides high over the sunk
     * approach to the Amplified band and the band.
     */
    public static double bandFactor(double worldX) {
        int x = (int) Math.floor(worldX);
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        double end = endSky(cycle, x);
        double nether = nether(cycle, x);
        double spheres = spheresVoid(cycle, x);
        double high = isHighUp(cycle, x) ? HIGH_UP_FACTOR : 1.0;
        return (1.0 - end) * (1.0 - (1.0 - NETHER_FACTOR) * nether) * (1.0 - spheres) * high;
    }

    /**
     * The sky-island eras right after Far Lands, with open void under them (Caves of Chaos, Skylands, Floating):
     * half the gusts at half the strength, a steady wind that swings three times as far and at least ±180% (so
     * its low end is calm: wind-free gaps of about 15 s), and slower changes of strength (18x) and direction (3x).
     */
    public static final WindField.Style SKY_STYLE = new WindField.Style(0.5, 0.5, 3.0, 18.0, 3.0, 1.8);

    /** How the band at {@code worldX} reshapes the wind ({@link #SKY_STYLE} over the sky islands). */
    public static WindField.Style styleAt(double worldX) {
        return isSky(WorldGenCycle.fromConfig(), (int) Math.floor(worldX)) ? SKY_STYLE : WindField.Style.NORMAL;
    }

    public static boolean isSkyAt(double worldX) {
        return isSky(WorldGenCycle.fromConfig(), (int) Math.floor(worldX));
    }

    private static boolean isSky(WorldGenCycle cycle, int x) {
        return LegacyBands.isInSlot(cycle, LegacyBandKind.CAVES_OF_CHAOS, x)
                || LegacyBands.isInSlot(cycle, LegacyBandKind.SKYLANDS, x)
                || LegacyBands.isInSlot(cycle, LegacyBandKind.FLOATING, x);
    }

    /** The train rides high over sunk terrain here ({@link SunkZone}: the sunk approach and the Amplified band). */
    public static boolean isHighUpAt(double worldX) {
        return isHighUp(WorldGenCycle.fromConfig(), (int) Math.floor(worldX));
    }

    private static boolean isHighUp(WorldGenCycle cycle, int x) {
        return SunkZone.contains(cycle, x);
    }

    /** Nether-sky intensity {@code 0..1} at {@code worldX} (drives the orange tint). */
    public static double netherAt(double worldX) {
        return nether(WorldGenCycle.fromConfig(), (int) Math.floor(worldX));
    }

    /** End-sky intensity {@code 0..1} at {@code worldX}. */
    public static double endSkyAt(double worldX) {
        return endSky(WorldGenCycle.fromConfig(), (int) Math.floor(worldX));
    }

    /**
     * Spheres-band void {@code 0..1} at {@code worldX}: rising as the terrain dissolves across the entry fade,
     * 1 through the band, easing back to 0 over the closing empty void ({@code spheresExitVoidBlocks}) so the
     * wind is fully back where the world returns.
     */
    public static double spheresVoidAt(double worldX) {
        return spheresVoid(WorldGenCycle.fromConfig(), (int) Math.floor(worldX));
    }

    public static boolean isDeepDark(Level level, BlockPos pos) {
        return level.getBiome(pos).is(Biomes.DEEP_DARK);
    }

    /** One of the cave biomes the train runs through leaving the Nether (deep dark, lush, dripstone, sulfur). */
    public static boolean isCave(Level level, BlockPos pos) {
        var biome = level.getBiome(pos);
        for (var key : NetherBandBiomes.CAVE_POST) {
            if (biome.is(key)) return true;
        }
        return false;
    }

    /** {@link #CAVE_FACTOR} in those caves, else 1. */
    public static double deepDarkFactor(Level level, BlockPos pos) {
        return isCave(level, pos) ? CAVE_FACTOR : 1.0;
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

    private static double spheresVoid(WorldGenCycle cycle, int x) {
        if (!DungeonTrainCommonConfig.isSpheresEnabled()) return 0.0;
        return clamp(cycle.spheresVoidReturnRamp(x, SpheresProgressionConfig.exitVoidBlocks()));
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
