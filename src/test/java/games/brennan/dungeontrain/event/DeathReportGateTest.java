package games.brennan.dungeontrain.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies the death-report gate ({@link RunStatsEvents#shouldReportDeath}): a death posts when the
 * feature is enabled AND the run is legit OR the build is dev/test. The dev-build exception lets
 * creative-mode dev testing (which flips the run to Free Play) still report to the dev channel, while
 * {@code main} (release) builds keep suppressing Free Play deaths so the live feed is unaffected.
 */
class DeathReportGateTest {

    @Test
    void legitRunReportsOnAnyBuild() {
        assertTrue(RunStatsEvents.shouldReportDeath(false, false, true), "legit run, release build");
        assertTrue(RunStatsEvents.shouldReportDeath(false, true, true), "legit run, dev build");
    }

    @Test
    void freePlayReportsOnlyOnDevBuilds() {
        // The fix: a Free Play (cheated) death now reports on a dev/test build…
        assertTrue(RunStatsEvents.shouldReportDeath(true, true, true), "Free Play on dev build must report");
        // …but a release build still suppresses it — the live community feed is unchanged.
        assertFalse(RunStatsEvents.shouldReportDeath(true, false, true), "Free Play on main must stay suppressed");
    }

    @Test
    void disabledNeverReports() {
        assertFalse(RunStatsEvents.shouldReportDeath(false, false, false));
        assertFalse(RunStatsEvents.shouldReportDeath(false, true, false));
        assertFalse(RunStatsEvents.shouldReportDeath(true, true, false), "dev build cannot override the off switch");
    }
}
