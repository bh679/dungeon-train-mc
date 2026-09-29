package games.brennan.dungeontrain.narrative;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BookFactory#buildPlainBook} at the ITEM level — the real {@link WrittenBookContent} and
 * {@link ItemStack}, not just the string handling around them.
 *
 * <p>Every other book test in this package is pure-logic, which leaves the construction step itself
 * uncovered: a book could sanitize and paginate perfectly and still fail to build. Since every relay
 * book and every player-signed book goes through this one method, a regression here breaks all of
 * them at once, so it is worth the headless bootstrap that {@link ItemStack} and the data-component
 * registry need.</p>
 *
 * <p>The first test is the one that matters most: an ordinary clean book must come out exactly as it
 * did before the sanitization work. The rest pin the hostile-input behaviour end to end.</p>
 */
class BookFactoryBuildTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final String SECTION = String.valueOf((char) 0xa7);
    private static final String NUL = String.valueOf((char) 0x00);
    private static final String RLO = String.valueOf((char) 0x202e);
    private static final String ZWSP = String.valueOf((char) 0x200b);
    private static final String EMOJI = new String(Character.toChars(0x1f600));

    private static WrittenBookContent contentOf(ItemStack stack) {
        assertEquals(Items.WRITTEN_BOOK, stack.getItem(), "must be a written book");
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        assertNotNull(content, "WRITTEN_BOOK_CONTENT component must be present");
        return content;
    }

    private static List<String> pageTexts(WrittenBookContent content) {
        List<String> out = new ArrayList<>();
        for (Filterable<net.minecraft.network.chat.Component> page : content.pages()) {
            out.add(page.raw().getString());
        }
        return out;
    }

    private static boolean hasLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return true;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("An ordinary book is completely unaffected — the regression guard")
    void ordinaryBookIsUnchanged() {
        ItemStack stack = BookFactory.buildPlainBook(
            "A Traveller's Note", "Steve",
            List.of("The train came through at dusk.", "I did not board it."));
        WrittenBookContent content = contentOf(stack);
        assertEquals("A Traveller's Note", content.title().raw());
        assertEquals("Steve", content.author());
        assertEquals(0, content.generation());
        assertTrue(content.resolved());
        assertEquals(List.of("The train came through at dusk.", "I did not board it."), pageTexts(content));
    }

    @Test
    @DisplayName("Blank and null title/author still fall back to Untitled/Anonymous")
    void blankFallbacksSurvive() {
        WrittenBookContent blank = contentOf(BookFactory.buildPlainBook("   ", "", List.of("body")));
        assertEquals("Untitled", blank.title().raw());
        assertEquals("Anonymous", blank.author());
        WrittenBookContent nulls = contentOf(BookFactory.buildPlainBook(null, null, List.of("body")));
        assertEquals("Untitled", nulls.title().raw());
        assertEquals("Anonymous", nulls.author());
    }

    @Test
    @DisplayName("A title of nothing but control characters reads as Untitled, not as an empty name")
    void allControlCharTitleFallsBack() {
        // Sanitizing runs BEFORE the blank check for exactly this case: these characters pass
        // isBlank() on the raw string, so without that ordering the book would ship a nameless title.
        WrittenBookContent content = contentOf(
            BookFactory.buildPlainBook(NUL + ZWSP, NUL, List.of("body")));
        assertEquals("Untitled", content.title().raw());
        assertEquals("Anonymous", content.author());
    }

    @Test
    @DisplayName("Section signs style a page, but are stripped from the title and author")
    void sectionSignsAllowedOnPagesOnly() {
        // Styling your own page is a feature. A title or author reaches item names, chat lines and
        // tooltips, where § would restyle UI that does not belong to the book.
        WrittenBookContent content = contentOf(BookFactory.buildPlainBook(
            "My" + SECTION + "kBook", "St" + SECTION + "leve", List.of("a" + SECTION + "lpage")));
        assertEquals("MykBook", content.title().raw());
        assertEquals("Stleve", content.author());
        assertFalse(content.title().raw().contains(SECTION));
        assertEquals(List.of("a" + SECTION + "lpage"), pageTexts(content), "page keeps its formatting");
    }

    @Test
    @DisplayName("Right-to-left text survives the build intact")
    void bidiSurvives() {
        // Stripping bidi would mangle legitimate Arabic and Hebrew, which DT ships locales for.
        WrittenBookContent content = contentOf(BookFactory.buildPlainBook(
            "Title" + RLO, "Auth" + RLO + "or", List.of("a page" + RLO + " with bidi")));
        assertTrue(content.title().raw().contains(RLO));
        assertTrue(content.author().contains(RLO));
        assertTrue(pageTexts(content).get(0).contains(RLO));
    }

    @Test
    @DisplayName("An over-long title clamps without orphaning half a surrogate pair")
    void titleClampKeepsPairsIntact() {
        String title = "T".repeat(BookFactory.MAX_TITLE_CHARS - 1) + EMOJI + "overflow";
        WrittenBookContent content = contentOf(BookFactory.buildPlainBook(title, "Steve", List.of("body")));
        String raw = content.title().raw();
        assertTrue(raw.length() <= BookFactory.MAX_TITLE_CHARS);
        assertFalse(hasLoneSurrogate(raw), "clamped title must not end in a lone surrogate");
    }

    @Test
    @DisplayName("More pages than vanilla allows truncates to the cap and still builds")
    void pageCountIsCapped() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < BookFactory.MAX_PAGES + 40; i++) many.add("page " + i);
        WrittenBookContent content = contentOf(BookFactory.buildPlainBook("Long", "Steve", many));
        assertEquals(BookFactory.MAX_PAGES, content.pages().size());
    }

    @Test
    @DisplayName("A book whose pages all sanitize away still builds a valid stack")
    void emptyPagesStillBuild() {
        WrittenBookContent content = contentOf(
            BookFactory.buildPlainBook("Title", "Steve", List.of(NUL, ZWSP)));
        assertEquals(1, content.pages().size(), "an empty book gets one blank page, not zero");
        assertEquals("", pageTexts(content).get(0));
    }

    /**
     * A 13-line drawing, the shape of the one reported broken: centred with leading spaces, spacer
     * lines, a {@code \.} inside the art (which the story paginator took for a sentence end) and
     * well over the story paginator's 256-char page budget.
     */
    private static final String WORD_ART = String.join("\n",
        "      A Gift",
        "",
        "",
        "",
        "         /\\",
        "        /  \\",
        "       /    \\.",
        "      /______\\",
        "        |  |",
        "        |  |    for whoever",
        "        |__|    finds this.",
        "",
        "   It is not much. It was",
        "   all I had on the train.",
        "",
        "   ~~~~~~~~~~~~~~~~~~~~~~",
        "  ~  keep the train going  ~",
        "   ~~~~~~~~~~~~~~~~~~~~~~",
        "",
        "                   - P");

    @Test
    @DisplayName("A word-art page is served byte-for-byte, as one page")
    void wordArtPageIsByteIdentical() {
        assertTrue(WORD_ART.length() > 256, "fixture must exceed the story paginator's page budget");
        WrittenBookContent content = contentOf(BookFactory.buildPlainBook("Art", "P", List.of(WORD_ART)));
        assertEquals(List.of(WORD_ART), pageTexts(content));
    }

    @Test
    @DisplayName("Every written page is served once, in order, with its whitespace intact")
    void pageCountAndOrderPreserved() {
        List<String> pages = List.of(
            "     centred title",
            "\n\n\n",
            "",
            WORD_ART);
        WrittenBookContent content = contentOf(BookFactory.buildPlainBook("Four", "P", pages));
        assertEquals(pages, pageTexts(content), "a blank spacer page mid-book keeps its place");
    }

    @Test
    @DisplayName("Trailing blank pages are dropped, as vanilla's editor does")
    void trailingBlankPagesDropped() {
        WrittenBookContent content = contentOf(
            BookFactory.buildPlainBook("Tail", "P", List.of("", "body", "", "")));
        assertEquals(List.of("", "body"), pageTexts(content));
    }

    @Test
    @DisplayName("An over-long page clamps to the vanilla page cap without orphaning a surrogate")
    void overlongPageClampsWithoutSurrogateSplit() {
        String page = "x".repeat(BookFactory.MAX_PAGE_CHARS - 1) + EMOJI + "overflow";
        WrittenBookContent content = contentOf(BookFactory.buildPlainBook("Long", "P", List.of(page)));
        List<String> texts = pageTexts(content);
        assertEquals(1, texts.size(), "an over-long page is clamped, not split");
        assertTrue(texts.get(0).length() <= BookFactory.MAX_PAGE_CHARS);
        assertFalse(hasLoneSurrogate(texts.get(0)));
    }

    @Test
    @DisplayName("Astral characters survive intact through the whole build")
    void astralTextSurvives() {
        WrittenBookContent content = contentOf(BookFactory.buildPlainBook(
            "Note " + EMOJI, "Steve", List.of("a page with " + EMOJI + " in it")));
        assertEquals("Note " + EMOJI, content.title().raw());
        assertEquals(List.of("a page with " + EMOJI + " in it"), pageTexts(content));
        assertFalse(hasLoneSurrogate(content.title().raw()));
    }
}
