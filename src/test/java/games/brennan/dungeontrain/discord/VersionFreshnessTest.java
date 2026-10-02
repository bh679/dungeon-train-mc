package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every boundary of the feedback header's freshness dot. */
class VersionFreshnessTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);
    private static final String LATEST = "v0.1104.0";

    private static VersionFreshness builtDaysAgo(int days) {
        return VersionFreshness.classify("0.1000.0", LATEST, TODAY.minusDays(days).toString(), TODAY);
    }

    @Test
    @DisplayName("the newest release is green however long ago it was built")
    void latestIsGreen() {
        assertEquals(VersionFreshness.LATEST,
                VersionFreshness.classify("0.1104.0", LATEST, "2026-01-01", TODAY));
        // The cascade's patch ticks are the same release track.
        assertEquals(VersionFreshness.LATEST,
                VersionFreshness.classify("0.1104.7", LATEST, "2026-01-01", TODAY));
    }

    @Test
    @DisplayName("a build ahead of the newest release is green")
    void aheadIsGreen() {
        assertEquals(VersionFreshness.LATEST,
                VersionFreshness.classify("0.1107.1", LATEST, "", TODAY));
    }

    @Test
    @DisplayName("behind the newest release, the dot follows the build's age")
    void behindFollowsAge() {
        assertEquals(VersionFreshness.RECENT, builtDaysAgo(0));
        assertEquals(VersionFreshness.RECENT, builtDaysAgo(1));
        assertEquals(VersionFreshness.WEEK, builtDaysAgo(2));
        assertEquals(VersionFreshness.WEEK, builtDaysAgo(7));
        assertEquals(VersionFreshness.MONTH, builtDaysAgo(8));
        assertEquals(VersionFreshness.MONTH, builtDaysAgo(30));
        assertEquals(VersionFreshness.OLDER, builtDaysAgo(31));
        assertEquals(VersionFreshness.OLDER, builtDaysAgo(400));
    }

    @Test
    @DisplayName("with no word on the newest release the dot is the age, never green")
    void unknownLatestFallsBackToAge() {
        assertEquals(VersionFreshness.RECENT,
                VersionFreshness.classify("0.1104.0", "", TODAY.toString(), TODAY));
        assertEquals(VersionFreshness.OLDER,
                VersionFreshness.classify("0.1104.0", null, "2026-01-01", TODAY));
    }

    @Test
    @DisplayName("an unreadable version is never mistaken for the newest")
    void unparseableVersionIsNotLatest() {
        assertEquals(VersionFreshness.WEEK,
                VersionFreshness.classify("?", LATEST, TODAY.minusDays(3).toString(), TODAY));
        assertEquals(VersionFreshness.WEEK,
                VersionFreshness.classify("0.1000.0", "nightly", TODAY.minusDays(3).toString(), TODAY));
    }

    @Test
    @DisplayName("nothing to go on is the white dot")
    void nothingKnownIsUnknown() {
        assertEquals(VersionFreshness.UNKNOWN, VersionFreshness.classify("0.1000.0", LATEST, "", TODAY));
        assertEquals(VersionFreshness.UNKNOWN, VersionFreshness.classify("0.1000.0", LATEST, "last week", TODAY));
        assertEquals(VersionFreshness.UNKNOWN, VersionFreshness.classify("?", "", null, TODAY));
    }

    @Test
    @DisplayName("a build dated tomorrow is a clock disagreement, not an old build")
    void futureBuildDateIsRecent() {
        assertEquals(VersionFreshness.RECENT, builtDaysAgo(-1));
    }

    @Test
    @DisplayName("each state has its own dot")
    void dots() {
        assertEquals("🟢", VersionFreshness.LATEST.dot());
        assertEquals("🔵", VersionFreshness.RECENT.dot());
        assertEquals("🟡", VersionFreshness.WEEK.dot());
        assertEquals("🟠", VersionFreshness.MONTH.dot());
        assertEquals("🔴", VersionFreshness.OLDER.dot());
        assertEquals("⚪", VersionFreshness.UNKNOWN.dot());
    }
}
