package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the pure parsing helpers of {@link DeathNoteTitleLocalization}: the per-locale title file
 * parses from either shape, entries are normalized, and malformed input degrades to an empty list
 * (never throws) so a bad datapack file can't break signing.
 */
class DeathNoteTitleLocalizationTest {

    @Test
    void parsesAJsonArrayOfTitlesAndNormalisesThem() {
        List<String> titles = DeathNoteTitleLocalization.parseTitles(
                new StringReader("[\"死亡笔记\", \"  Death  Note  \"]"), "test");
        // Normalized: whitespace stripped, English lowercased.
        assertEquals(List.of("死亡笔记", "deathnote"), titles);
    }

    @Test
    void parsesTheTitlesObjectShape() {
        List<String> titles = DeathNoteTitleLocalization.parseTitles(
                new StringReader("{\"titles\": [\"死亡笔记\"]}"), "test");
        assertEquals(List.of("死亡笔记"), titles);
    }

    @Test
    void skipsBlankAndNonStringEntries() {
        List<String> titles = DeathNoteTitleLocalization.parseTitles(
                new StringReader("[\"死亡笔记\", \"  \", 42, null, {}]"), "test");
        assertEquals(List.of("死亡笔记"), titles);
    }

    @Test
    void malformedOrUnexpectedJsonYieldsEmptyWithoutThrowing() {
        assertTrue(DeathNoteTitleLocalization.parseTitles(new StringReader("not json"), "test").isEmpty());
        assertTrue(DeathNoteTitleLocalization.parseTitles(new StringReader("{\"other\": 1}"), "test").isEmpty());
        assertTrue(DeathNoteTitleLocalization.parseTitles(new StringReader("\"a string\""), "test").isEmpty());
        assertTrue(DeathNoteTitleLocalization.parseTitles(new StringReader(""), "test").isEmpty());
    }

    @Test
    void localeOfExtractsTheFileBasename() {
        assertEquals("zh_cn", DeathNoteTitleLocalization.localeOf("deathnote_titles/zh_cn.json"));
        assertEquals("es_es", DeathNoteTitleLocalization.localeOf("es_es.json"));
        assertEquals("", DeathNoteTitleLocalization.localeOf("deathnote_titles/notjson.txt"));
    }

    @Test
    void unknownOrEnglishLocaleHasNoLocalizedTitles() {
        // Nothing loaded in a unit context → every lookup is empty (English matching lives in
        // DeathNoteTitle, not here).
        assertTrue(DeathNoteTitleLocalization.titlesFor("").isEmpty());
        assertTrue(DeathNoteTitleLocalization.titlesFor(null).isEmpty());
        assertTrue(DeathNoteTitleLocalization.titlesFor("en_us").isEmpty());
    }
}
