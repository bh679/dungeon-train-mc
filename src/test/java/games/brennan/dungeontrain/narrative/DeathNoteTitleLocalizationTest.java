package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

/**
 * Verifies the pure helpers of {@link DeathNoteTitleLocalization}, which derives the Death Note
 * trigger words from the instruction books' titles (a single source of truth so the shown
 * instructions and the trigger can never drift): {@code titleFrom} normalizes a book's title,
 * ignores the always-accepted English form and placeholders, and never throws.
 */
class DeathNoteTitleLocalizationTest {

    /** A minimal instruction-book JSON body with the given title. */
    private static String book(String titleJson) {
        return "{\"id\":\"deathnote\",\"title\":" + titleJson
                + ",\"author\":\"匿名\",\"weight\":1,\"variants\":[\"...\"]}";
    }

    @Test
    void derivesTheNormalisedTitleFromALocalisedBook() {
        assertEquals("死亡笔记",
                DeathNoteTitleLocalization.titleFrom(new StringReader(book("\"死亡笔记\""))));
        // Whitespace is stripped, matching how a signed title is normalized.
        assertEquals("cahierdelamort",
                DeathNoteTitleLocalization.titleFrom(new StringReader(book("\"Cahier de la Mort\""))));
    }

    @Test
    void englishBaseTitleAddsNothing() {
        // "Deathnote" (and "Death Note") reduce to the always-accepted English form → no locale entry.
        assertTrue(DeathNoteTitleLocalization.titleFrom(new StringReader(book("\"Deathnote\""))).isEmpty());
        assertTrue(DeathNoteTitleLocalization.titleFrom(new StringReader(book("\"Death Note\""))).isEmpty());
    }

    @Test
    void missingBlankOrPlaceholderTitleYieldsEmpty() {
        assertTrue(DeathNoteTitleLocalization.titleFrom(new StringReader(book("\"\""))).isEmpty());
        assertTrue(DeathNoteTitleLocalization.titleFrom(new StringReader(book("\"   \""))).isEmpty());
        assertTrue(DeathNoteTitleLocalization.titleFrom(new StringReader(book("\"Untitled\""))).isEmpty());
        // title field absent entirely
        assertTrue(DeathNoteTitleLocalization.titleFrom(
                new StringReader("{\"id\":\"deathnote\",\"variants\":[\"x\"]}")).isEmpty());
        // title present but not a string
        assertTrue(DeathNoteTitleLocalization.titleFrom(new StringReader(book("42"))).isEmpty());
    }

    @Test
    void malformedOrUnexpectedJsonYieldsEmptyWithoutThrowing() {
        assertTrue(DeathNoteTitleLocalization.titleFrom(new StringReader("not json")).isEmpty());
        assertTrue(DeathNoteTitleLocalization.titleFrom(new StringReader("[\"an array\"]")).isEmpty());
        assertTrue(DeathNoteTitleLocalization.titleFrom(new StringReader("")).isEmpty());
    }

    @Test
    void allIsEmptyUntilLoaded() {
        // Nothing loaded in a unit context → the set is empty (English matching lives in
        // DeathNoteTitle, not here).
        assertTrue(DeathNoteTitleLocalization.all().isEmpty());
    }
}
