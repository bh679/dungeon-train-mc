package games.brennan.dungeontrain.client;

import net.minecraft.util.Mth;

/**
 * Single shared timeline behind the whole loading sequence — the themed
 * {@code LevelLoadingScreenThemeMixin} (world-load) screen, then
 * {@link CinematicLoadingScreen} (placing on the train → chunk stream-in) —
 * so the two screens read as one continuous animation instead of each
 * restarting its own progress bar and clock at the handoff.
 *
 * <p>Each screen still computes its own "local" 0..1 progress using whatever
 * phase model makes sense for it ({@code LevelLoadingScreenThemeMixin}'s
 * world-gen/bootstrap blend, {@link CinematicPreloadGate}'s placing/chunks
 * blend); it reports that local fraction here and gets back the fraction to
 * actually render. Overall progress only ever goes up — a screen that starts
 * with more of the bar already spent than its own local model implies (e.g.
 * a very fast world load) just jumps forward, it never re-empties.</p>
 */
public final class LoadingSequenceProgress {

    /** Share of the overall bar spent on the world-load screen; the rest is the gate. */
    private static final double WORLD_LOAD_SHARE = 0.55;

    private static long startNanos = -1L;
    private static double displayFraction = 0.0;

    private LoadingSequenceProgress() {}

    private static long ensureStarted() {
        long now = System.nanoTime();
        if (startNanos < 0) {
            startNanos = now;
        }
        return now;
    }

    /** Report the world-load (first) screen's own 0..1 local progress; returns the overall fraction to render. */
    public static double reportWorldLoad(double local) {
        ensureStarted();
        double overall = WORLD_LOAD_SHARE * Mth.clamp(local, 0.0, 1.0);
        displayFraction = Math.max(displayFraction, overall);
        return displayFraction;
    }

    /** Report the gate's (second screen) own 0..1 local progress; returns the overall fraction to render. */
    public static double reportGate(double local) {
        ensureStarted();
        double overall = WORLD_LOAD_SHARE + (1.0 - WORLD_LOAD_SHARE) * Mth.clamp(local, 0.0, 1.0);
        displayFraction = Math.max(displayFraction, Math.min(overall, 0.999));
        return displayFraction;
    }

    /** Shared animation clock (smoke drift, ∞ pulse) — continuous across both screens. */
    public static long animNanos() {
        ensureStarted();
        return System.nanoTime() - startNanos;
    }

    /** Called on logout so the next login starts a fresh timeline. */
    public static void reset() {
        startNanos = -1L;
        displayFraction = 0.0;
    }
}
