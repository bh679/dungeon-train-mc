package games.brennan.dungeontrain.client.localization;

import games.brennan.dungeontrain.narrative.HumanOnlyProse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanOnlyTranslationsTest {

    @AfterEach
    void clearProse() {
        HumanOnlyProse.clear();
    }

    @Test
    void fallbacksTakeOnlyFlaggedKeysAcrossNamespaces() {
        Map<String, Map<String, String>> english = Map.of(
            "dungeontrain", Map.of("a", "Alpha", "b", "Bravo"),
            "playermob", Map.of("c", "Charlie"));
        Set<String> flagged = Set.of("dungeontrain:a", "playermob:c");

        Map<String, String> out = HumanOnlyTranslations.fallbacks(english,
            (namespace, key) -> flagged.contains(namespace + ":" + key));

        assertEquals(Map.of("a", "Alpha", "c", "Charlie"), out);
    }

    @Test
    void humanOverridesWinOverEnglishFallbacks() {
        Map<String, String> layered = HumanOnlyTranslations.layer(
            Map.of("a", "Alpha", "b", "Bravo"),
            Map.of("b", "Брaво (approved)", "z", "own edit"));

        assertEquals(Map.of("a", "Alpha", "b", "Брaво (approved)", "z", "own edit"), layered);
    }

    @Test
    void noFallbacksLeavesOverridesUntouched() {
        Map<String, String> overrides = Map.of("k", "v");
        assertEquals(overrides, HumanOnlyTranslations.layer(Map.of(), overrides));
    }

    @Test
    void proseSnapshotOnlyAppliesToItsOwnLocale() {
        HumanOnlyProse.set("ru_ru", path -> path.startsWith("random_books/"), true);

        assertTrue(HumanOnlyProse.skipsBook("ru_ru", "random_books/deathnote"));
        assertFalse(HumanOnlyProse.skipsBook("ru_ru", "stories/questions"));
        assertFalse(HumanOnlyProse.skipsBook("de_de", "random_books/deathnote"));
        assertEquals("", HumanOnlyProse.itemNameLocale("ru_ru"));
        assertEquals("de_de", HumanOnlyProse.itemNameLocale("de_de"));
    }

    @Test
    void clearedProseSnapshotSkipsNothing() {
        assertFalse(HumanOnlyProse.skipsBook("ru_ru", "random_books/deathnote"));
        assertEquals("ru_ru", HumanOnlyProse.itemNameLocale("ru_ru"));
    }
}
