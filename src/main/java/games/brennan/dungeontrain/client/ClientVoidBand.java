package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.SpheresProgressionConfig;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.worldgen.SpheresSky;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;

/**
 * Client-side cache of the per-world data needed to evaluate the disintegration
 * band's "void intensity" at a world-X, so the sky/fog can fade toward the End
 * look as the player crosses the band.
 *
 * <p>The band start-X depends on the per-world carriage length (server-only
 * {@code DungeonTrainWorldData.dims()}), so that single value plus the per-world
 * {@code startsWithTrain} flag are synced once on join via {@code VoidBandSyncPacket}.
 * Everything else — the enabled flag and the fade/core spans — is COMMON config,
 * readable directly on the client, so config tuning takes effect without a resync.</p>
 *
 * <p>Pure-logic only (no rendering imports); the band math is shared with the
 * server via {@link Disintegration}.</p>
 */
public final class ClientVoidBand {

    private static volatile int carriageLength = CarriageDims.DEFAULT_LENGTH;
    private static volatile boolean startsWithTrain = false;

    private ClientVoidBand() {}

    /** Apply a server sync (carriage length + whether this world has the train system). */
    public static void update(int length, boolean starts) {
        carriageLength = Math.max(1, length);
        startsWithTrain = starts;
        games.brennan.dungeontrain.worldgen.VanillaBiomeTwins.setClientWorldHasTrain(starts);
    }

    /** Whether the server said this world has the train system (false until synced, and after disconnect). */
    public static boolean startsWithTrain() {
        return startsWithTrain;
    }

    /** Reset to safe defaults on disconnect so a band never leaks into the next world. */
    public static void reset() {
        carriageLength = CarriageDims.DEFAULT_LENGTH;
        startsWithTrain = false;
    }

    /**
     * End-sky intensity {@code t} in {@code [0, 1]} at a world-X: 0 outside a band
     * phase (or when disintegration is disabled / this world has no train), ramping to
     * 1 across the whole void+End+void middle. Repeats every cycle, so the End
     * atmosphere returns each time the world breaks apart. Drives the sky crossfade,
     * the fog darkening, and cloud suppression.
     *
     * <p>Uses {@link WorldGenCycle#endSkyRamp} (not {@code endMiddleRamp}), so the sky lags the
     * terrain by {@code disintegrationSkyFadeOffsetBlocks} on each edge: it stays overworld
     * while the ground first crumbles on entry and returns to overworld before the terrain
     * has fully reformed on exit. Offset 0 reproduces the terrain-synced behaviour.</p>
     */
    public static double endSkyIntensityAt(double worldX) {
        if (!startsWithTrain) return 0.0;
        int x = (int) Math.floor(worldX);
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        double end = DungeonTrainCommonConfig.isDisintegrationEnabled()
                ? cycle.endSkyRamp(x, DungeonTrainCommonConfig.getDisintegrationSkyFadeOffsetBlocks())
                : 0.0;
        // The two bands never overlap, so max() just picks whichever one the camera is in.
        return Math.max(end, spheresEndSkyIntensity(cycle, x));
    }

    /**
     * End-sky intensity over the spheres band's End-sky window ({@link SpheresSky#endSky}), so the whole
     * End atmosphere keyed off {@link #endSkyIntensityAt} — sky, fog, lighting, clouds, music — takes
     * over {@code spheresEndSkyStartBlocks} into the spheres and fades back to the overworld sky over the
     * {@code spheresEndSkyExitFadeBlocks} before its closing void.
     */
    private static double spheresEndSkyIntensity(WorldGenCycle cycle, int worldX) {
        if (!DungeonTrainCommonConfig.isSpheresEnabled() || !DungeonTrainCommonConfig.isSpheresEndSkyEnabled()) {
            return 0.0;
        }
        return SpheresSky.endSky(cycle, SpheresProgressionConfig.segments(), worldX,
                DungeonTrainCommonConfig.getSpheresEndSkyFadeBlocks(), SpheresProgressionConfig.endSkyExitFadeBlocks(),
                SpheresProgressionConfig.exitVoidBlocks());
    }

    /** Sample spacing for {@link #isSpheresZoneWithin}; the entry fade alone is far wider than this. */
    private static final int SPHERES_ZONE_SAMPLE_STEP = 64;

    /**
     * True if any world-X within {@code margin} blocks of {@code worldX} lies in the spheres band's entry
     * fade or core. Used to decide when Distant Horizons must stop drawing (see
     * {@code DistantHorizonsSuppression}); the margin covers the approach, since the band sits inside DH's
     * draw distance well before the camera reaches it. Immediate {@code false} when the band is off or
     * this world has no train, so it is cheap enough for a per-frame call.
     */
    public static boolean isSpheresZoneWithin(int worldX, int margin) {
        if (!startsWithTrain || !DungeonTrainCommonConfig.isSpheresEnabled()) return false;
        return spheresZoneWithin(WorldGenCycle.fromConfig(), worldX, margin);
    }

    /** Pure core of {@link #isSpheresZoneWithin}: same 64-block sampling as the upside-down flip zone. */
    static boolean spheresZoneWithin(WorldGenCycle cycle, int worldX, int margin) {
        if (cycle.spheresLen() <= 0L) return false;
        int span = Math.max(0, margin);
        for (int x = worldX - span; x < worldX + span; x += SPHERES_ZONE_SAMPLE_STEP) {
            if (cycle.spheresVoidRamp(x) > 0.0) return true;
        }
        // The step can overshoot the far end, so the last block of the window is always tested.
        return cycle.spheresVoidRamp(worldX + span) > 0.0;
    }

    /** End-sky intensity {@code t} crosses this point: below it the Overworld track plays, above it the End track. */
    public static final double MUSIC_CROSSOVER = 0.5;

    /**
     * Position-driven music volume factor in {@code [0, 1]} at a world-X — a V-curve over the
     * band's End-sky intensity {@code t}: {@code 1} in the Overworld ({@code t=0}) and across the
     * End core ({@code t=1}), dipping to {@code 0} at the {@link #MUSIC_CROSSOVER} ({@code t=0.5}).
     *
     * <p>Combined with the track flip at the crossover, this fades the outgoing track down to
     * silence as the player approaches the handoff and the incoming track back up afterwards —
     * Overworld→End on the way in, End→Overworld on the way out. {@code 1.0} outside any band, so
     * scaling music volume by it is a no-op away from the disintegration.</p>
     */
    public static double musicVolumeFactor(double worldX) {
        double t = endSkyIntensityAt(worldX);
        if (t <= 0.0) return 1.0;
        return Math.abs(2.0 * t - 1.0);
    }
}
