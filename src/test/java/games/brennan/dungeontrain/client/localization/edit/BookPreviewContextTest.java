package games.brennan.dungeontrain.client.localization.edit;

import games.brennan.dungeontrain.client.localization.edit.BookPreviewContext.Preview;
import games.brennan.dungeontrain.client.localization.edit.BookPreviewContext.Row;
import games.brennan.dungeontrain.client.localization.edit.BookPreviewContext.Segment;
import games.brennan.dungeontrain.client.localization.edit.BookPreviewContext.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The translation preview's "Full Context": the whole page or book a string belongs to. */
class BookPreviewContextTest {

    private static final String SB = BookPreviewContext.STAT_BOOK_PREFIX;

    private static TranslationUnit book(String id) {
        return new TranslationUnit(TranslationUnit.Type.BOOK, "dungeontrain", id, "en", "xx", false, false);
    }

    private static TranslationUnit lang(String key) {
        return new TranslationUnit(TranslationUnit.Type.LANG, "dungeontrain", key, "en", "xx", false, false);
    }

    @Test
    @DisplayName("each string is matched to the book family that prints it")
    void sources() {
        assertEquals(Source.STAT_BOOK, BookPreviewContext.sourceOf(lang(SB + "tail.3")));
        assertEquals(Source.LEADERBOARD, BookPreviewContext.sourceOf(lang("dungeontrain.leaderboard.you")));
        assertEquals(Source.NARRATIVE, BookPreviewContext.sourceOf(book("random_books/deathnote#title")));
        assertEquals(Source.DEATH_LORE, BookPreviewContext.sourceOf(book("death_lore/default#4.narration")));
        // Story notes never reach the game, so there is nothing around them to show.
        assertEquals(Source.NONE, BookPreviewContext.sourceOf(book("stories/x#letters.0.notes.0.text")));
        assertEquals(Source.NONE, BookPreviewContext.sourceOf(lang("gui.dungeontrain.done")));
    }

    @Test
    @DisplayName("a stat book line sits on its one page between an opener and a tail, highlighted")
    void statBook() {
        Map<String, String> lang = new LinkedHashMap<>();
        lang.put(SB + "open.0", "%s, I see you.");
        lang.put(SB + "stat.carriage", "You've made it to carriage %s.");
        lang.put(SB + "tail.0", "Keep going.");
        Preview p = BookPreviewContext.statBook(SB + "tail.0", "Weiter so.", lang, "Ari", 7);

        assertEquals(1, p.pages().size());
        List<Segment> page = p.pages().get(0).segments();
        assertEquals("Ari, I see you.", page.get(0).text());
        assertTrue(page.get(1).text().startsWith("You've made it to carriage "));
        assertFalse(page.get(1).text().contains("%s"));
        assertEquals(new Segment("Weiter so.", true), page.get(2));
        assertFalse(page.get(0).highlighted() || page.get(1).highlighted());
        assertTrue(p.rolled());
    }

    @Test
    @DisplayName("a .one stat form reads with the number one")
    void statBookOneForm() {
        Map<String, String> lang = Map.of(SB + "open.0", "Hi %s.", SB + "tail.0", "Bye.");
        Preview p = BookPreviewContext.statBook(SB + "stat.chests.one", "%s Kiste.", lang, null, 1);
        assertEquals(new Segment("1 Kiste.", true), p.pages().get(0).segments().get(1));
        assertEquals("Hi Steve.", p.pages().get(0).segments().get(0).text());
    }

    @Test
    @DisplayName("a leaderboard heading wraps its header and closes on the reader's standing page")
    void leaderboard() {
        Map<String, String> lang = Map.of(
            "dungeontrain.leaderboard.scope.run", "%s In a single life.",
            "dungeontrain.leaderboard.you", "You are #%s, with %s.");
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rows.add(new Row("P" + i, Integer.toString(100 - i)));
        }
        Preview p = BookPreviewContext.leaderboard("dungeontrain.leaderboard.carriages.header",
            "Wer kam am weitesten?", lang, "Furthest Carriage", "dungeontrain.leaderboard.carriages.header",
            "dungeontrain.leaderboard.scope.run", rows, "dungeontrain.leaderboard.you", List.of("4", "88"));

        Segment heading = p.pages().get(0).segments().get(0);
        assertEquals(new Segment("Wer kam am weitesten? In a single life.", true), heading);
        assertTrue(p.pages().get(0).segments().get(1).text().startsWith("1. P0"));
        Segment closing = p.pages().get(p.pages().size() - 1).segments().get(0);
        assertEquals(new Segment("You are #4, with 88.", false), closing);
        assertEquals("Furthest Carriage", p.cover().title());
    }

    @Test
    @DisplayName("a book title shows on the cover over a rolled body; a body is its own book")
    void narrativeCover() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", "Death Note");
        fields.put("author", "");
        fields.put("variants.0", "First.");
        fields.put("variants.1", "Second.");

        Preview title = BookPreviewContext.narrative("random_books/deathnote", "title", "Todesnotiz", fields, 3);
        assertEquals("Todesnotiz", title.cover().title());
        assertTrue(title.cover().titleHighlighted());
        assertEquals("Anonymous", title.cover().author());
        assertTrue(title.rolled());

        Preview body = BookPreviewContext.narrative("random_books/deathnote", "variants.1", "Zweite.", fields, 3);
        assertEquals("Zweite.", body.pages().get(0).segments().get(0).text());
        assertFalse(body.rolled());
        assertFalse(body.cover().titleHighlighted());
    }

    @Test
    @DisplayName("an untitled story letter is named by its label")
    void storyLabel() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("character", "Mara");
        fields.put("story", "Untitled");
        fields.put("letters.0.label", "First letter");
        fields.put("letters.0.variants.0", "Dear you.");
        fields.put("letters.1.label", "Second letter");
        fields.put("letters.1.variants.0", "Again.");

        Preview p = BookPreviewContext.narrative("stories/mara", "letters.1.label", "Zweiter Brief", fields, 0);
        assertEquals("Zweiter Brief", p.cover().title());
        assertTrue(p.cover().titleHighlighted());
        assertEquals("Mara", p.cover().author());
        assertEquals("Again.", p.pages().get(0).segments().get(0).text());
    }

    @Test
    @DisplayName("starting books break pages on %PAGE%, like the game")
    void startingBookPages() {
        String body = "One.\n%PAGE%\nTwo.";
        assertEquals(List.of("One.", "Two."),
            BookPreviewContext.justThisPages(book("starting_books/welcome#variants.0"), body));
        Preview p = BookPreviewContext.narrative("starting_books/welcome", "variants.0", body,
            Map.of("title", "Welcome"), 0);
        assertEquals(2, p.pages().size());
    }

    @Test
    @DisplayName("a death lore line stands with its entry's other lines, in death-screen order")
    void deathLore() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("4.narration", "You fell.");
        fields.put("4.question", "How did it end?");
        fields.put("5.question", "Not this one.");
        Preview p = BookPreviewContext.deathLore("4.narration", "Du fielst.", fields);

        assertTrue(p.deathScreen());
        assertEquals(List.of(new Segment("How did it end?", false), new Segment("Du fielst.", true)),
            p.pages().get(0).segments());
    }

    @Test
    @DisplayName("placeholders fill in order or by position, and %% prints one percent sign")
    void fill() {
        assertEquals("a 1 b 2 100%", BookPreviewContext.fill("a %s b %s 100%%", "1", "2"));
        assertEquals("2 then 1", BookPreviewContext.fill("%2$s then %1$s", "1", "2"));
        assertEquals("missing %s", BookPreviewContext.fill("missing %s"));
    }
}
