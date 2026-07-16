package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
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
        assertEquals("notademuerte",
                DeathNoteTitleLocalization.titleFrom(new StringReader(book("\"Nota de Muerte\""))));
    }

    @Test
    void skipsTitlesTooLongForAPlayerToType() {
        // Vanilla caps the book-title field at 15 chars; "Cuaderno de la Muerte" (21) is untypeable,
        // so no trigger is derived from it (a dead trigger would be worse than English-only).
        assertTrue(DeathNoteTitleLocalization.titleFrom(
                new StringReader(book("\"Cuaderno de la Muerte\""))).isEmpty());
        // Exactly at the limit (15 chars) is still usable.
        assertEquals("cuadernomortal",
                DeathNoteTitleLocalization.titleFrom(new StringReader(book("\"Cuaderno Mortal\""))));
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
    void everyShippedLocalizedInstructionBookTitleIsTypeable() throws IOException {
        // Guard against a translator shipping a Death Note book whose title a player can't type
        // (vanilla caps the title field at 15 chars). Such a title teaches an unusable trigger word,
        // so every bundled narrative_localizations/<locale>/random_books/deathnote.json title must fit.
        Path overlays = Path.of("src/main/resources/data/dungeontrain/narrative_localizations");
        if (!Files.isDirectory(overlays)) return; // running outside the repo tree — nothing to check
        try (Stream<Path> tree = Files.walk(overlays)) {
            List<Path> books = tree
                    .filter(p -> p.toString().endsWith("/random_books/deathnote.json"))
                    .toList();
            assertFalse(books.isEmpty(), "expected at least one localized deathnote instruction book");
            for (Path book : books) {
                String json = Files.readString(book, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                String title = obj.get("title").getAsString();
                assertTrue(title.strip().length() <= DeathNoteTitleLocalization.VANILLA_MAX_TITLE_CHARS,
                        book + " title \"" + title + "\" is " + title.strip().length()
                                + " chars — a player cannot type more than "
                                + DeathNoteTitleLocalization.VANILLA_MAX_TITLE_CHARS);
            }
        }
    }

    @Test
    void allIsEmptyUntilLoaded() {
        // Nothing loaded in a unit context → the set is empty (English matching lives in
        // DeathNoteTitle, not here).
        assertTrue(DeathNoteTitleLocalization.all().isEmpty());
    }
}
