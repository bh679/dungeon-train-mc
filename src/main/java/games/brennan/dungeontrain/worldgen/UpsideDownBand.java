package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-side helper for the upside-down band — the third looping phase (alongside the nether and
 * disintegration/End bands), positioned after the End band in the shared {@link WorldGenCycle}. Past
 * {@link #startX(ServerLevel)} the cycle repeats forever along +X; before it is plain overworld.
 *
 * <p>Unlike the nether/End bands (which reshape terrain via density/biome hooks), the upside-down
 * band is realised purely as a post-process vertical mirror in {@code WorldUpsideDownEvents}. This
 * class only answers "is this column in the band?" (binary — the mirror is all-or-nothing per
 * column) and exposes the client-atmosphere ramp. Shared by that reflection handler,
 * {@link TrainPhase#phaseAt}, and the band mob-spawn rule.</p>
 */
public final class UpsideDownBand {

    /** Returned by {@link #startX} when the band is disabled or the world has no train. */
    public static final long OFF = Long.MAX_VALUE;

    private UpsideDownBand() {}

    /**
     * World-X where the cycle is anchored (shared with the nether/End phases via
     * {@link WorldGenCycle}), or {@link #OFF} if the upside-down band is disabled or this world has
     * no train. Past this X the cycle repeats forever; before it is plain overworld.
     */
    public static long startX(ServerLevel overworld) {
        if (!DungeonTrainCommonConfig.isUpsideDownEnabled()) return OFF;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return OFF;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.period() <= 0L || cycle.upsideDownLen() <= 0L) return OFF;
        return cycle.startX();
    }

    /**
     * True iff the column at {@code worldX} lies anywhere in the upside-down band (fade edges
     * included). False when the band is off. This binary membership — not {@link #rampAt} — gates the
     * terrain reflection, since a vertical mirror is all-or-nothing per column.
     */
    public static boolean isInBand(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return false;
        return WorldGenCycle.fromConfig().isInUpsideDownBand(worldX);
    }

    /**
     * Upside-down atmosphere ramp {@code 0..1} at a world-X (0 outside the band / when disabled) —
     * drives the sky/light crossfade and the mob-spawn gate on the server side.
     */
    public static double rampAt(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return 0.0;
        return WorldGenCycle.fromConfig().upsideDownRamp(worldX);
    }
}
