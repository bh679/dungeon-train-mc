package games.brennan.dungeontrain.client.localization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four buckets the language screen narrows to. Pure — the two facts about a language are
 * supplied, so this never needs a ResourceManager or a running client.
 *
 * <p>What is really being asserted is that the buckets <b>partition</b>: every language lands in
 * exactly one of AI / HUMAN / NONE. A row draws one badge, so a filter that could admit a language
 * to two of them would be a list disagreeing with the thing it is listing.</p>
 */
class LanguageAiFilterTest {

    @Test
    @DisplayName("ALL admits every language whatever its state")
    void allAdmitsEverything() {
        assertTrue(LanguageAiFilter.ALL.matches(false, false));
        assertTrue(LanguageAiFilter.ALL.matches(true, false));
        assertTrue(LanguageAiFilter.ALL.matches(true, true));
    }

    @Test
    @DisplayName("machine-translated and unreviewed is AI, and only AI")
    void aiBucket() {
        assertTrue(LanguageAiFilter.AI.matches(true, false));
        assertFalse(LanguageAiFilter.HUMAN.matches(true, false));
        assertFalse(LanguageAiFilter.NONE.matches(true, false));
    }

    @Test
    @DisplayName("reviewed is HUMAN, and leaves the AI queue")
    void humanBucket() {
        assertTrue(LanguageAiFilter.HUMAN.matches(true, true));
        assertFalse(LanguageAiFilter.AI.matches(true, true));
        assertFalse(LanguageAiFilter.NONE.matches(true, true));
    }

    @Test
    @DisplayName("a language the mod ships nothing for is NONE regardless of the review flag")
    void noneBucket() {
        for (boolean reviewed : new boolean[] {false, true}) {
            assertTrue(LanguageAiFilter.NONE.matches(false, reviewed));
            assertFalse(LanguageAiFilter.AI.matches(false, reviewed),
                "untranslated is not machine-translated — there is nothing to have translated");
            assertFalse(LanguageAiFilter.HUMAN.matches(false, reviewed));
        }
    }

    @Test
    @DisplayName("the three states partition every language exactly once")
    void statesPartition() {
        for (boolean translated : new boolean[] {false, true}) {
            for (boolean reviewed : new boolean[] {false, true}) {
                long hits = java.util.stream.Stream
                    .of(LanguageAiFilter.AI, LanguageAiFilter.HUMAN, LanguageAiFilter.NONE)
                    .filter(f -> f.matches(translated, reviewed))
                    .count();
                assertEquals(1, hits,
                    "translated=" + translated + " reviewed=" + reviewed);
            }
        }
    }
}
