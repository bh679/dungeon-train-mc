package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.brennan.dungeontrain.net.relay.BookStatsClient;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

/**
 * Verifies the author-facing "a familiar book…" line ({@link FamiliarBookMessage}): variant eligibility
 * (a stat that is 0 is never named), held-count grammar (person/people, has/have), and human duration
 * formatting. Pure — no game bootstrap needed (plain literal components).
 */
class FamiliarBookMessageTest {

    private static BookStatsClient.Stats stats(int held, int completers, int opens,
                                               long longestReadMs, long longestPageMs, int longestPageIndex,
                                               int pageTurns, int rereads) {
        return new BookStatsClient.Stats(true, held, completers, opens,
                longestReadMs, longestPageMs, longestPageIndex, pageTurns, rereads);
    }

    private static String render(BookStatsClient.Stats s, long seed) {
        return FamiliarBookMessage.build(s, RandomSource.create(seed)).getString();
    }

    @Test
    void heldOnlyStats_alwaysFallsBackToHeldCountLine() {
        // Every other stat 0 → only the held-count variant is eligible, so the line is deterministic.
        BookStatsClient.Stats s = stats(3, 0, 0, 0, 0, 0, 0, 0);
        for (long seed = 0; seed < 50; seed++) {
            String line = render(s, seed);
            assertTrue(line.contains("3 people have held your words"), "held-only line, got: " + line);
        }
    }

    @Test
    void heldCountGrammar_singularVsPlural() {
        assertTrue(render(stats(1, 0, 0, 0, 0, 0, 0, 0), 1L).contains("1 person has held"),
                "one holder → singular person/has");
        assertTrue(render(stats(2, 0, 0, 0, 0, 0, 0, 0), 1L).contains("2 people have held"),
                "two holders → plural people/have");
    }

    @Test
    void neverEmpty_andAlwaysNamesHeldCount() {
        BookStatsClient.Stats rich = stats(12, 5, 31, 254000, 100000, 2, 88, 19);
        for (long seed = 0; seed < 200; seed++) {
            String line = render(rich, seed);
            assertFalse(line.isBlank(), "line must never be blank");
            assertTrue(line.contains("12"), "line always names the held count, got: " + line);
            assertFalse(line.contains("null"), "no null in: " + line);
        }
    }

    @Test
    void durationVariant_formatsMinutesAndSeconds() {
        // held + longestRead only → eligible = the two longest-read variants + the held-only fallback.
        // Across many seeds a duration variant is picked; when it is, it must show "1m 12s" (72_000ms).
        BookStatsClient.Stats s = stats(4, 0, 0, 72_000L, 0, 0, 0, 0);
        boolean sawDuration = false;
        for (long seed = 0; seed < 200; seed++) {
            String line = render(s, seed);
            if (!line.contains("held your words")) { // not the fallback → a longest-read variant
                assertTrue(line.contains("1m 12s"), "duration must format as 1m 12s, got: " + line);
                sawDuration = true;
            }
        }
        assertTrue(sawDuration, "a longest-read variant should surface across 200 seeds");
    }

    @Test
    void pageVariant_reportsOneBasedPageNumber() {
        // held + longestPage only (index 2 → page 3). Non-fallback lines must name page 3 and its dwell.
        BookStatsClient.Stats s = stats(6, 0, 0, 0, 100_000L, 2, 0, 0);
        boolean sawPage = false;
        for (long seed = 0; seed < 200; seed++) {
            String line = render(s, seed);
            if (!line.contains("held your words")) {
                assertTrue(line.contains("page 3"), "0-based index 2 → page 3, got: " + line);
                assertTrue(line.contains("1m 40s"), "100_000ms → 1m 40s, got: " + line);
                sawPage = true;
            }
        }
        assertTrue(sawPage, "a longest-page variant should surface across 200 seeds");
    }

    @Test
    void completionStat_zero_neverClaimsReaders() {
        // A book opened but never finished must never print a completion line.
        BookStatsClient.Stats s = stats(8, 0, 20, 0, 0, 0, 0, 0);
        for (long seed = 0; seed < 200; seed++) {
            String line = render(s, seed);
            assertFalse(line.contains("read it to the very end"), "no completion line when completers=0: " + line);
            assertFalse(line.contains("last page"), "no completion line when completers=0: " + line);
        }
    }
}
