package games.brennan.dungeontrain.client.perf;

import org.jetbrains.annotations.Nullable;

/**
 * What a lag prompt's [Yes with info] hands to the bug form it opens: the freeze it was about.
 *
 * <p>{@link LagReportPrompt} arms it and sends {@code /bug}; the server answers by opening DP's survey.
 * Two one-shot reads follow: {@code SurveyScreenLagPreselectMixin} takes the pre-select when the form
 * first lays out (so it opens with Lag picked), and {@code BugLogReporter} takes the freeze line when a
 * Lag answer is submitted (so the upload names the freeze, as a plain [Yes] does). Both expire after
 * {@link #TTL_MS} and are dropped on logout, so a later manual {@code /bug} inherits nothing.</p>
 *
 * <p>Client thread only. The {@code now} arguments keep it testable.</p>
 */
public final class LagReportFormHandoff {

    /** How long after the click the form and its submit may still use it. */
    static final long TTL_MS = 10L * 60L * 1000L;
    /** How long after the click the form may open and take the pre-select (the server round trip). */
    static final long OPEN_WINDOW_MS = 60_000L;
    private static final long NONE = -1;

    private static long armedAt = NONE;
    private static long freezeMs;
    private static boolean preselectTaken;

    private LagReportFormHandoff() {}

    /** [Yes with info] was clicked for a freeze of {@code freezeMs}. Replaces any earlier one. */
    static void arm(long freezeMs, long now) {
        LagReportFormHandoff.freezeMs = freezeMs;
        armedAt = now;
        preselectTaken = false;
    }

    /** Whether the bug form opening now should start on Lag. True at most once per arm. */
    public static boolean takePreselect(long now) {
        if (armedAt == NONE || preselectTaken || now - armedAt > OPEN_WINDOW_MS) return false;
        preselectTaken = true;
        return true;
    }

    /**
     * The freeze line for a Lag answer submitted now, or null. Ends the handoff: one report per click.
     * Only a form that took the pre-select may use it, so a manual {@code /bug} never picks it up.
     */
    public static @Nullable String takeFreezeLine(long now) {
        if (armedAt == NONE || !preselectTaken || now - armedAt > TTL_MS) return null;
        clear();
        return freezeLine(freezeMs);
    }

    static void clear() {
        armedAt = NONE;
        preselectTaken = false;
    }

    /** The spec's first line for a prompted report: shared with the plain [Yes]. */
    static String freezeLine(long freezeMs) {
        return "Detected client freeze: ~" + freezeMs + " ms\n";
    }
}
