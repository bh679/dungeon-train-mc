package games.brennan.dungeontrain.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEBUG diagnostics for {@link FramerateThrottle}, in the same {@code [tag]} style as
 * {@code [freeze]} / {@code [mspt]} / {@code [gen.timing]} and on the same
 * {@code games.brennan.dungeontrain.jitter} logger.
 *
 * <p>Logs the <em>measured</em> {@code Minecraft#getFps()} next to the throttle state, so the log
 * shows whether the cap actually took effect rather than merely that the code path ran. That
 * distinction matters: the mixin applying cleanly proves nothing about the frame rate, and the
 * frame rate is only observable on screen otherwise.</p>
 *
 * <p>Emits immediately on a state transition and then at most every {@link #STEADY_PERIOD_MS}
 * while the state holds, so a 30fps idle costs one line per 5s rather than 30 per second.
 * Everything short-circuits on {@code isDebugEnabled()}, so a normal INFO-level install pays only
 * a boolean check per frame.</p>
 */
public final class FramerateThrottleLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    /** Steady-state cadence. Transitions bypass this and log at once. */
    private static final long STEADY_PERIOD_MS = 5_000L;

    /** Vanilla's "Unlimited" sentinel — rendered as a word so the line reads unambiguously. */
    private static final int UNLIMITED_SENTINEL = 260;

    private static volatile boolean lastThrottled;
    private static volatile boolean primed;
    private static volatile long lastLogMs;

    private FramerateThrottleLog() {}

    /**
     * Sample the throttle once per frame. Called from the {@code getFramerateLimit} mixin, which
     * vanilla invokes exactly once per {@code runTick}.
     */
    public static void sample(boolean throttled, int fps, int cap, int vanillaLimit,
                              boolean paused, boolean focused, boolean vr) {
        if (!LOGGER.isDebugEnabled()) return;

        long now = System.currentTimeMillis();
        boolean transition = !primed || throttled != lastThrottled;
        if (!transition && now - lastLogMs < STEADY_PERIOD_MS) return;

        primed = true;
        lastThrottled = throttled;
        lastLogMs = now;

        LOGGER.debug("[fps] throttled={} measuredFps={} cap={} yourLimit={} paused={} focused={} vr={}{}",
                throttled,
                fps,
                cap,
                vanillaLimit >= UNLIMITED_SENTINEL ? "unlimited" : Integer.toString(vanillaLimit),
                paused,
                focused,
                vr,
                transition ? "  <-- transition" : "");
    }
}
