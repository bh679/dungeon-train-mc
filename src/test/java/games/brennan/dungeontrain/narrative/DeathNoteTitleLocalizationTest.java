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
 * Verifies the pure helpers of {@link DeathNoteTitleLocalization}, which derives each
 * {@link NoteKind}'s trigger words from its instruction books' titles (a single source of truth so
 * the shown instructions and the trigger can never drift): {@code titleFrom} normalizes a book's
 * title, ignores the always-accepted English form and placeholders, and never throws.
 */
class DeathNoteTitleLocalizationTest {

    /** A minimal instruction-book JSON body with the given title. */
    private static String book(String titleJson) {
        return "{\"id\":\"deathnote\",\"title\":" + titleJson
                + ",\"author\":\"匿名\",\"weight\":1,\"variants\":[\"...\"]}";
    }

    /** {@code titleFrom} for a Death Note instruction book — the common case in these tests. */
    private static String death(String titleJson) {
        return DeathNoteTitleLocalization.titleFrom(new StringReader(book(titleJson)), NoteKind.DEATH);
    }

    /** {@code titleFrom} for a Love Note instruction book. */
    private static String love(String titleJson) {
        return DeathNoteTitleLocalization.titleFrom(new StringReader(book(titleJson)), NoteKind.LOVE);
    }

    @Test
    void derivesTheNormalisedTitleFromALocalisedBook() {
        assertEquals("死亡笔记", death("\"死亡笔记\""));
        // Whitespace is stripped, matching how a signed title is normalized.
        assertEquals("notademuerte", death("\"Nota de Muerte\""));
        assertEquals("notadeamor", love("\"Nota de Amor\""));
    }

    @Test
    void skipsTitlesTooLongForAPlayerToType() {
        // Vanilla caps the book-title field at 15 chars; "Cuaderno de la Muerte" (21) is untypeable,
        // so no trigger is derived from it (a dead trigger would be worse than English-only).
        assertTrue(death("\"Cuaderno de la Muerte\"").isEmpty());
        // Exactly at the limit (15 chars) is still usable.
        assertEquals("cuadernomortal", death("\"Cuaderno Mortal\""));
    }

    @Test
    void englishBaseTitleAddsNothing() {
        // "Deathnote" (and "Death Note") reduce to the always-accepted English form → no locale entry.
        assertTrue(death("\"Deathnote\"").isEmpty());
        assertTrue(death("\"Death Note\"").isEmpty());
        assertTrue(love("\"Lovenote\"").isEmpty());
        assertTrue(love("\"Love Note\"").isEmpty());
    }

    @Test
    void eachKindOnlyFoldsAwayItsOwnEnglishName() {
        // A book carrying the OTHER kind's English name still registers as a (colliding) trigger
        // rather than vanishing silently. NoteKind.of resolves such a collision by testing DEATH
        // first; the shipped translations are checked for exactly this below.
        assertEquals("lovenote", death("\"Love Note\""));
        assertEquals("deathnote", love("\"Death Note\""));
    }

    @Test
    void missingBlankOrPlaceholderTitleYieldsEmpty() {
        assertTrue(death("\"\"").isEmpty());
        assertTrue(death("\"   \"").isEmpty());
        assertTrue(death("\"Untitled\"").isEmpty());
        // title field absent entirely
        assertTrue(DeathNoteTitleLocalization.titleFrom(
                new StringReader("{\"id\":\"deathnote\",\"variants\":[\"x\"]}"), NoteKind.DEATH).isEmpty());
        // title present but not a string
        assertTrue(death("42").isEmpty());
    }

    @Test
    void malformedOrUnexpectedJsonYieldsEmptyWithoutThrowing() {
        assertTrue(DeathNoteTitleLocalization.titleFrom(
                new StringReader("not json"), NoteKind.DEATH).isEmpty());
        assertTrue(DeathNoteTitleLocalization.titleFrom(
                new StringReader("[\"an array\"]"), NoteKind.DEATH).isEmpty());
        assertTrue(DeathNoteTitleLocalization.titleFrom(
                new StringReader(""), NoteKind.DEATH).isEmpty());
    }

    @Test
    void everyShippedLocalizedInstructionBookTitleIsTypeable() throws IOException {
        // Guard against a translator shipping a note book whose title a player can't type (vanilla
        // caps the title field at 15 chars). Such a title teaches an unusable trigger word, so every
        // bundled narrative_localizations/<locale>/random_books/<kind>.json title must fit.
        Path overlays = Path.of("src/main/resources/data/dungeontrain/narrative_localizations");
        if (!Files.isDirectory(overlays)) return; // running outside the repo tree — nothing to check
        for (NoteKind kind : NoteKind.values()) {
            try (Stream<Path> tree = Files.walk(overlays)) {
                List<Path> books = tree
                        .filter(p -> p.toString().endsWith(kind.instructionBookSuffix()))
                        .toList();
                assertFalse(books.isEmpty(),
                        "expected at least one localized " + kind.id() + " instruction book");
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
    }

    @Test
    void noLocaleGivesBothKindsTheSameTrigger() throws IOException {
        // A translation that titled both books the same word would make one mechanic unreachable:
        // NoteKind.of tests DEATH first, so a Love Note signed with that title would arm a curse.
        Path overlays = Path.of("src/main/resources/data/dungeontrain/narrative_localizations");
        if (!Files.isDirectory(overlays)) return;
        for (Path locale : localeDirs(overlays)) {
            String deathTitle = normalizedTitleIn(locale, NoteKind.DEATH);
            String loveTitle = normalizedTitleIn(locale, NoteKind.LOVE);
            if (deathTitle.isEmpty() || loveTitle.isEmpty()) continue; // one side not translated yet
            assertFalse(deathTitle.equals(loveTitle),
                    locale.getFileName() + " gives the Death Note and the Love Note the same trigger \""
                            + deathTitle + "\" — one of the two mechanics would be unreachable");
        }
    }

    private static List<Path> localeDirs(Path overlays) throws IOException {
        try (Stream<Path> tree = Files.list(overlays)) {
            return tree.filter(Files::isDirectory).toList();
        }
    }

    /** The normalized trigger title of {@code kind}'s book in {@code locale}, or "" if absent/unusable. */
    private static String normalizedTitleIn(Path locale, NoteKind kind) throws IOException {
        Path book = locale.resolve("random_books").resolve(kind.englishTitle() + ".json");
        if (!Files.isRegularFile(book)) return "";
        try (var reader = Files.newBufferedReader(book, StandardCharsets.UTF_8)) {
            return DeathNoteTitleLocalization.titleFrom(reader, kind);
        }
    }

    @Test
    void allIsEmptyUntilLoaded() {
        // Nothing loaded in a unit context → each kind's set is empty (English matching lives in
        // NoteKind / DeathNoteTitle, not here).
        assertTrue(DeathNoteTitleLocalization.all(NoteKind.DEATH).isEmpty());
        assertTrue(DeathNoteTitleLocalization.all(NoteKind.LOVE).isEmpty());
    }
}
