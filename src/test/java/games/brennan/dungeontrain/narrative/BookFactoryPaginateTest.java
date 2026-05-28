package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down {@link BookFactory#paginate} behaviour:
 * <ul>
 *   <li>{@code \n\n\n+} (three or more consecutive newlines) is a hard page break.</li>
 *   <li>{@code \n\n} is a soft paragraph separator — multiple paragraphs whose
 *       combined length fits {@link BookFactory#MAX_CHARS_PER_PAGE} pack onto one page.</li>
 *   <li>{@code \n} preserved as a line break within a page.</li>
 *   <li>Oversize sections still fall through to sentence / word splitting.</li>
 * </ul>
 */
final class BookFactoryPaginateTest {

    @Test
    @DisplayName("Backward compat: \\n\\n soft-pack — two short paragraphs fit on one page")
    void softPackTwoShortParagraphs() {
        List<String> pages = BookFactory.paginate("First short paragraph.\n\nSecond short paragraph.");
        assertEquals(1, pages.size(), "Both fit under 256 chars → greedy pack onto one page");
        assertEquals("First short paragraph.\n\nSecond short paragraph.", pages.get(0));
    }

    @Test
    @DisplayName("Hard break: \\n\\n\\n forces a page break even when content fits on one page")
    void hardBreakSplitsShortContent() {
        List<String> pages = BookFactory.paginate("First.\n\n\nSecond.");
        assertEquals(2, pages.size());
        assertEquals("First.", pages.get(0));
        assertEquals("Second.", pages.get(1));
    }

    @Test
    @DisplayName("Mixed: soft and hard breaks coexist — packs within section, hard-breaks between")
    void mixedSoftAndHardBreaks() {
        List<String> pages = BookFactory.paginate("A\n\nB\n\n\nC\n\nD");
        assertEquals(2, pages.size(), "soft-pack within each hard-break section");
        assertEquals("A\n\nB", pages.get(0));
        assertEquals("C\n\nD", pages.get(1));
    }

    @Test
    @DisplayName("Four+ consecutive newlines = one hard break (no empty pages)")
    void fourNewlinesIsOneHardBreak() {
        List<String> pages = BookFactory.paginate("A\n\n\n\nB");
        assertEquals(2, pages.size(), "4 newlines collapse to one hard break — no empty page");
        assertEquals("A", pages.get(0));
        assertEquals("B", pages.get(1));
    }

    @Test
    @DisplayName("Wren shape: five short paragraphs with hard breaks → 5 pages even when total < 256 chars")
    void wrenLetterShape() {
        String body = "Faulthurst.\n\n\n"
            + "I read your books.\nAll of them.\n\n\n"
            + "Cute.\n\n\n"
            + "It's procedural.\n\n\n"
            + "— Wren";
        List<String> pages = BookFactory.paginate(body);
        assertEquals(5, pages.size(), "hard breaks force one page per beat");
        assertEquals("Faulthurst.", pages.get(0));
        assertEquals("I read your books.\nAll of them.", pages.get(1));
        assertEquals("Cute.", pages.get(2));
        assertEquals("It's procedural.", pages.get(3));
        assertEquals("— Wren", pages.get(4));
    }

    @Test
    @DisplayName("Oversize section still spills through sentence/word fallback")
    void oversizeSectionStillSpills() {
        // Build an oversize single paragraph (> 256 chars) inside one hard-break section.
        StringBuilder oversize = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            oversize.append("Sentence ").append(i).append(" lorem ipsum dolor sit amet. ");
        }
        String body = oversize.toString().trim() + "\n\n\nTail.";
        List<String> pages = BookFactory.paginate(body);
        assertTrue(pages.size() >= 3, "oversize section spills, then hard break, then tail");
        assertEquals("Tail.", pages.get(pages.size() - 1));
        for (String page : pages) {
            assertTrue(page.length() <= BookFactory.MAX_CHARS_PER_PAGE,
                "page over budget: '" + page + "' (" + page.length() + " chars)");
        }
    }

    @Test
    @DisplayName("Single hard break at start/end is harmless — produces no empty pages")
    void leadingAndTrailingHardBreaksHarmless() {
        List<String> pages = BookFactory.paginate("\n\n\nMiddle.\n\n\n");
        assertEquals(1, pages.size(), "leading/trailing empty sections are skipped");
        assertEquals("Middle.", pages.get(0));
    }
}
