package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.brennan.dungeontrain.net.relay.BookStatsClient;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

/**
 * Verifies the author-facing "a familiar book…" line ({@link FamiliarBookMessage}): variant eligibility
 * (a stat that is 0 is never named), held-count grammar (singular/plural key selection), the 1-based page
 * number, and human duration formatting.
 *
 * <p>The line is a {@link Component#translatable} tree rendered on the client, so these tests inspect the
 * translation <em>key and args</em> the server chose — not a rendered English string (which, with no
 * language loaded in the test JVM, would just be the raw key). This is what actually matters: the server
 * picks the right variant and the right plural sub-key + numeric args; the client turns that into text in
 * its own language.</p>
 */
class FamiliarBookMessageTest {

    private static final String KEY = "chat.dungeontrain.familiar_book.";

    private static BookStatsClient.Stats stats(int held, int completers, int opens,
                                               long longestReadMs, long longestPageMs, int longestPageIndex,
                                               int pageTurns, int rereads) {
        return new BookStatsClient.Stats(true, held, completers, opens,
                longestReadMs, longestPageMs, longestPageIndex, pageTurns, rereads);
    }

    /** The top-level translatable contents of the built line for {@code s} at {@code seed}. */
    private static TranslatableContents contents(BookStatsClient.Stats s, long seed) {
        Component c = FamiliarBookMessage.build(s, RandomSource.create(seed));
        return (TranslatableContents) c.getContents();
    }

    /** The translatable contents of a nested Component arg (e.g. the held-count or duration sub-phrase). */
    private static TranslatableContents subContents(Object arg) {
        return (TranslatableContents) ((Component) arg).getContents();
    }

    /** Stringified value of an arg (Integer/Long → its decimal digits), for count/page/duration checks. */
    private static String argStr(Object arg) {
        return String.valueOf(arg);
    }

    @Test
    void heldOnlyStats_alwaysFallsBackToHeldCountLine() {
        // Every other stat 0 → only the held-count variant (#10) is eligible, so the key is deterministic.
        BookStatsClient.Stats s = stats(3, 0, 0, 0, 0, 0, 0, 0);
        for (long seed = 0; seed < 50; seed++) {
            TranslatableContents tc = contents(s, seed);
            assertEquals(KEY + "10", tc.getKey(), "held-only → fallback variant");
            TranslatableContents held = subContents(tc.getArgs()[0]);
            assertEquals(KEY + "held.other", held.getKey(), "3 holders → plural held key");
            assertEquals("3", argStr(held.getArgs()[0]), "held clause names the count");
        }
    }

    @Test
    void heldCountGrammar_singularVsPlural() {
        TranslatableContents one = subContents(contents(stats(1, 0, 0, 0, 0, 0, 0, 0), 1L).getArgs()[0]);
        assertEquals(KEY + "held.one", one.getKey(), "one holder → singular held key");
        assertEquals("1", argStr(one.getArgs()[0]));

        TranslatableContents two = subContents(contents(stats(2, 0, 0, 0, 0, 0, 0, 0), 1L).getArgs()[0]);
        assertEquals(KEY + "held.other", two.getKey(), "two holders → plural held key");
        assertEquals("2", argStr(two.getArgs()[0]));
    }

    @Test
    void neverEmpty_andAlwaysNamesHeldCount() {
        BookStatsClient.Stats rich = stats(12, 5, 31, 254000, 100000, 2, 88, 19);
        for (long seed = 0; seed < 200; seed++) {
            TranslatableContents tc = contents(rich, seed);
            assertTrue(tc.getKey().matches("\\Q" + KEY + "\\E([1-9]|10)"), "a numbered variant, got: " + tc.getKey());
            for (Object arg : tc.getArgs()) assertNotNull(arg, "no null args in: " + tc.getKey());
            // The held count is always the first arg's clause.
            TranslatableContents held = subContents(tc.getArgs()[0]);
            assertEquals("12", argStr(held.getArgs()[0]), "line always names the held count");
        }
    }

    @Test
    void durationVariant_formatsMinutesAndSeconds() {
        // held + longestRead only → eligible = the two longest-read variants (#3/#4) + the fallback (#10).
        // A duration variant carries the duration as arg[1]: 72_000ms → 1m 12s → dur.ms(1, 12).
        BookStatsClient.Stats s = stats(4, 0, 0, 72_000L, 0, 0, 0, 0);
        boolean sawDuration = false;
        for (long seed = 0; seed < 200; seed++) {
            TranslatableContents tc = contents(s, seed);
            if (tc.getKey().equals(KEY + "10")) continue; // the fallback
            TranslatableContents dur = subContents(tc.getArgs()[1]);
            assertEquals(KEY + "dur.ms", dur.getKey(), "72_000ms → minutes+seconds");
            assertEquals("1", argStr(dur.getArgs()[0]));
            assertEquals("12", argStr(dur.getArgs()[1]));
            sawDuration = true;
        }
        assertTrue(sawDuration, "a longest-read variant should surface across 200 seeds");
    }

    @Test
    void pageVariant_reportsOneBasedPageNumber() {
        // held + longestPage only (index 2 → page 3). Non-fallback variants (#5/#6) carry page as arg[1]
        // and the dwell duration as arg[2]: 100_000ms → 1m 40s → dur.ms(1, 40).
        BookStatsClient.Stats s = stats(6, 0, 0, 0, 100_000L, 2, 0, 0);
        boolean sawPage = false;
        for (long seed = 0; seed < 200; seed++) {
            TranslatableContents tc = contents(s, seed);
            if (tc.getKey().equals(KEY + "10")) continue;
            assertEquals("3", argStr(tc.getArgs()[1]), "0-based index 2 → page 3");
            TranslatableContents dur = subContents(tc.getArgs()[2]);
            assertEquals(KEY + "dur.ms", dur.getKey());
            assertEquals("1", argStr(dur.getArgs()[0]));
            assertEquals("40", argStr(dur.getArgs()[1]));
            sawPage = true;
        }
        assertTrue(sawPage, "a longest-page variant should surface across 200 seeds");
    }

    @Test
    void completionStat_zero_neverClaimsReaders() {
        // A book opened but never finished must never pick a completion variant (#1/#2).
        BookStatsClient.Stats s = stats(8, 0, 20, 0, 0, 0, 0, 0);
        for (long seed = 0; seed < 200; seed++) {
            String key = contents(s, seed).getKey();
            assertNotEquals(KEY + "1", key, "no completion line when completers=0");
            assertNotEquals(KEY + "2", key, "no completion line when completers=0");
        }
    }
}
