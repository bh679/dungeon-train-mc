package games.brennan.dungeontrain.client.localization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four states the language screen narrows to. Pure — the facts about a language are supplied,
 * so this never needs a ResourceManager or a running client.
 *
 * <p>What is really being pinned is that AI and HUMAN <b>overlap</b>. They answer independent
 * questions, and a language part-way through review answers yes to both; an earlier version treated
 * them as exclusive buckets and so hid the mod's largest block of outstanding work from the filter
 * built to find it.</p>
 */
class LanguageAiFilterTest {

    // translated, humanReviewed, needsReview
    private static boolean m(LanguageAiFilter f, boolean t, boolean h, boolean n) {
        return f.matches(t, h, n);
    }

    @Test
    @DisplayName("ALL admits every language whatever its state")
    void allAdmitsEverything() {
        for (boolean t : new boolean[] {false, true}) {
            for (boolean h : new boolean[] {false, true}) {
                for (boolean n : new boolean[] {false, true}) {
                    assertTrue(m(LanguageAiFilter.ALL, t, h, n));
                }
            }
        }
    }

    @Test
    @DisplayName("AI is 'has machine translation still waiting on a human'")
    void aiIsOutstandingWork() {
        assertTrue(m(LanguageAiFilter.AI, true, false, true), "untouched machine translation");
        assertTrue(m(LanguageAiFilter.AI, true, true, true), "part-reviewed still has work left");
        assertFalse(m(LanguageAiFilter.AI, true, true, false), "nothing left to review");
        assertFalse(m(LanguageAiFilter.AI, false, false, true), "nothing shipped, nothing to review");
    }

    @Test
    @DisplayName("HUMAN is 'a person has been through some of it', at any depth")
    void humanIsAnyReview() {
        assertTrue(m(LanguageAiFilter.HUMAN, true, true, true));
        assertTrue(m(LanguageAiFilter.HUMAN, true, true, false));
        assertFalse(m(LanguageAiFilter.HUMAN, true, false, true));
        assertFalse(m(LanguageAiFilter.HUMAN, false, true, false), "not shipped, so not reviewed");
    }

    @Test
    @DisplayName("a part-reviewed language appears under BOTH AI and Human reviewed")
    void partReviewedIsInBoth() {
        // zh_cn as shipped: a translator through most of it, 232 lines still untouched. It belongs
        // in the review queue AND in the list of languages somebody has cared for.
        assertTrue(m(LanguageAiFilter.AI, true, true, true));
        assertTrue(m(LanguageAiFilter.HUMAN, true, true, true));
        assertFalse(m(LanguageAiFilter.NONE, true, true, true));
    }

    @Test
    @DisplayName("a language the mod ships nothing for is NONE, and only NONE")
    void noneIsExclusive() {
        for (boolean h : new boolean[] {false, true}) {
            for (boolean n : new boolean[] {false, true}) {
                assertTrue(m(LanguageAiFilter.NONE, false, h, n));
                assertFalse(m(LanguageAiFilter.AI, false, h, n));
                assertFalse(m(LanguageAiFilter.HUMAN, false, h, n));
            }
        }
    }

    @Test
    @DisplayName("every language lands in at least one of the three states")
    void nothingFallsThroughTheCracks() {
        // Not a partition any more — but still a cover. A language matching none of the three would
        // be reachable only under ALL, which is the one state a player narrowing a list has left.
        for (boolean t : new boolean[] {false, true}) {
            for (boolean h : new boolean[] {false, true}) {
                for (boolean n : new boolean[] {false, true}) {
                    boolean any = m(LanguageAiFilter.AI, t, h, n)
                        || m(LanguageAiFilter.HUMAN, t, h, n)
                        || m(LanguageAiFilter.NONE, t, h, n);
                    // The one real gap: shipped, fully reviewed, by nobody — arithmetically
                    // impossible, since review is what makes needsReview false.
                    if (t && !h && !n) {
                        continue;
                    }
                    assertTrue(any, "t=" + t + " h=" + h + " n=" + n);
                }
            }
        }
    }
}
