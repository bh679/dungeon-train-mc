package games.brennan.dungeontrain.client.lod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEBUG diagnostics for the far-carriage render LOD, in the same {@code [tag]} style as
 * {@code [freeze]} / {@code [mspt]} / {@code [gen.timing]} and on the same
 * {@code games.brennan.dungeontrain.jitter} logger.
 *
 * <p>Logs the tier split next to the baked-mesh count, so the log shows the LOD actually taking
 * effect rather than merely that the reconcile ran — the same distinction
 * {@link games.brennan.dungeontrain.client.FramerateThrottleLog} draws for the framerate cap. A
 * reconcile that reports {@code far=0} while a long train is on screen means the threshold or the
 * bake budget is wrong, and that is invisible from the frame rate alone.</p>
 *
 * <p>Emits on a transition in the near/far split and then at most every {@link #STEADY_PERIOD_TICKS}
 * while it holds. Everything short-circuits on {@code isDebugEnabled()}, so a normal INFO-level
 * install pays only a boolean check per tick.</p>
 */
public final class CarriageLodLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    /** Steady-state cadence, matching the {@code [freeze]} line's 40-tick period. */
    private static final int STEADY_PERIOD_TICKS = 40;

    private static int lastNear = -1;
    private static int lastFar = -1;
    private static int ticksSinceLog;

    private CarriageLodLog() {}

    /** Sample once per reconcile. */
    static void sample(int tracked, int near, int far) {
        if (!LOGGER.isDebugEnabled()) return;

        boolean transition = near != lastNear || far != lastFar;
        ticksSinceLog++;
        if (!transition && ticksSinceLog < STEADY_PERIOD_TICKS) return;

        lastNear = near;
        lastFar = far;
        ticksSinceLog = 0;

        LOGGER.debug("[lod] tracked={} near={} far={} candidates={} blocked={} baked={} maxDist={} threshold={}",
            tracked, near, far,
            CarriageLodController.lastCandidates(),
            CarriageLodController.lastBlocked(),
            CarriageMeshCache.size(),
            String.format("%.1f", CarriageLodController.lastMaxDistance()),
            CarriageLodController.farDistanceBlocks);
    }

    /** Forget the last sample so the next one always logs — used when the master switch flips. */
    static void reset() {
        lastNear = -1;
        lastFar = -1;
        ticksSinceLog = 0;
    }
}
