package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down {@link StartingBookFactory#paginateExplicit} behaviour:
 * <ul>
 *   <li>Every {@code \n\n} in the source is exactly one page break.</li>
 *   <li>Single newlines preserved as line breaks within a page.</li>
 *   <li>Doubled-up {@code \n\n}s insert blank pages between content pages.</li>
 *   <li>Oversize chunks fall back to {@link BookFactory#paginate}.</li>
 *   <li>Leading + trailing blank pages are trimmed; internal blanks survive.</li>
 * </ul>
 */
final class StartingBookPaginationTest {

    @Test
    @DisplayName("Single-page body — one chunk → one page")
    void singlePage() {
        List<String> pages = StartingBookFactory.paginateExplicit("Welcome traveler.\nSit down.");
        assertEquals(1, pages.size(), "no \\n\\n breaks → 1 page");
        assertEquals("Welcome traveler.\nSit down.", pages.get(0));
    }

    @Test
    @DisplayName("Two chunks separated by \\n\\n → 2 pages")
    void twoPagesBlankLine() {
        List<String> pages = StartingBookFactory.paginateExplicit("Page one body.\n\nPage two body.");
        assertEquals(2, pages.size());
        assertEquals("Page one body.", pages.get(0));
        assertEquals("Page two body.", pages.get(1));
    }

    @Test
    @DisplayName("Doubled-up break \\n\\n\\n\\n inserts a blank page between content")
    void doubledBreakInsertsBlankPage() {
        List<String> pages = StartingBookFactory.paginateExplicit("First.\n\n\n\nSecond.");
        assertEquals(3, pages.size(), "two \\n\\n in a row → content / blank / content");
        assertEquals("First.", pages.get(0));
        assertEquals("", pages.get(1));
        assertEquals("Second.", pages.get(2));
    }

    @Test
    @DisplayName("Whitespace-only chunk between \\n\\n pairs becomes a blank page")
    void whitespaceChunkBecomesBlankPage() {
        // "A\n\n \n\nB" → split on \n\n yields ["A", " ", "B"]; the " "
        // strips to "" → one blank page between A and B.
        List<String> pages = StartingBookFactory.paginateExplicit("Alpha.\n\n \n\nBeta.");
        assertEquals(3, pages.size(), "whitespace-only middle chunk → blank page slot");
        assertEquals("Alpha.", pages.get(0));
        assertEquals("", pages.get(1));
        assertEquals("Beta.", pages.get(2));
    }

    @Test
    @DisplayName("Single newlines stay as line breaks within a page")
    void preserveSingleNewlines() {
        List<String> pages = StartingBookFactory.paginateExplicit("Line one\nLine two\nLine three");
        assertEquals(1, pages.size());
        assertEquals("Line one\nLine two\nLine three", pages.get(0));
    }

    @Test
    @DisplayName("Newline + whitespace + newline (only one \\n on each side) stays in the same page")
    void singleNewlinePlusWhitespaceStaysInPage() {
        // Only \n\n is a page break — \n   \n is just whitespace within the page.
        List<String> pages = StartingBookFactory.paginateExplicit("Alpha.\n   \nBeta.");
        assertEquals(1, pages.size(), "only \\n\\n triggers a page break, not whitespace-padded single newlines");
        // Strip happens at the page level, not per-line, so internal whitespace is preserved.
        assertEquals("Alpha.\n   \nBeta.", pages.get(0));
    }

    @Test
    @DisplayName("Leading + trailing whitespace on each page is trimmed")
    void trimPageWhitespace() {
        List<String> pages = StartingBookFactory.paginateExplicit("   Padded.   \n\n  Also padded.  ");
        assertEquals(2, pages.size());
        assertEquals("Padded.", pages.get(0));
        assertEquals("Also padded.", pages.get(1));
    }

    @Test
    @DisplayName("Leading blank pages are trimmed (don't open a book on a blank page)")
    void trimLeadingBlankPages() {
        List<String> pages = StartingBookFactory.paginateExplicit("\n\n\n\nFirst real page.");
        assertEquals(1, pages.size(), "leading blanks should drop");
        assertEquals("First real page.", pages.get(0));
    }

    @Test
    @DisplayName("Trailing blank pages are trimmed (no dead pages at the end)")
    void trimTrailingBlankPages() {
        List<String> pages = StartingBookFactory.paginateExplicit("Last real page.\n\n\n\n\n\n");
        assertEquals(1, pages.size(), "trailing blanks should drop");
        assertEquals("Last real page.", pages.get(0));
    }

    @Test
    @DisplayName("Internal blank pages survive trim — only leading + trailing get dropped")
    void preserveInternalBlanksTrimEdges() {
        List<String> pages = StartingBookFactory.paginateExplicit("\n\nA\n\n\n\nB\n\n\n\nC\n\n");
        assertEquals(5, pages.size(), "leading/trailing blanks gone, A/blank/B/blank/C remains");
        assertEquals("A", pages.get(0));
        assertEquals("", pages.get(1));
        assertEquals("B", pages.get(2));
        assertEquals("", pages.get(3));
        assertEquals("C", pages.get(4));
    }

    @Test
    @DisplayName("Oversize chunk falls back to auto-pagination — long paragraph splits into multiple pages")
    void oversizeChunkFallsBack() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("This is sentence ").append(i).append(" with some filler content. ");
        }
        String body = sb.toString();
        assertTrue(body.length() > 256, "test premise: oversize chunk");

        List<String> pages = StartingBookFactory.paginateExplicit(body);
        assertTrue(pages.size() > 1, "oversize chunk should produce > 1 page via fallback");
        for (String p : pages) {
            assertTrue(p.length() <= BookFactory.MAX_CHARS_PER_PAGE,
                "fallback should keep each page within the soft limit (page len " + p.length() + ")");
        }
    }

    @Test
    @DisplayName("Empty body → empty page list (caller substitutes one blank page)")
    void emptyBody() {
        assertEquals(List.of(), StartingBookFactory.paginateExplicit(""));
        assertEquals(List.of(), StartingBookFactory.paginateExplicit("   \n\n   "));
    }
}
