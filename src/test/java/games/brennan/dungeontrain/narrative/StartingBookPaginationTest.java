package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down {@link StartingBookFactory#paginateExplicit} behaviour:
 * <ul>
 *   <li>A line holding exactly {@code %PAGE%} is the one and only page break.</li>
 *   <li>Newlines are content — any number of blank lines, anywhere on the page,
 *       including at the top to push the text down. This is why the marker is
 *       not made of newlines: every "N newlines break the page" rule caps how
 *       many blank lines an author can write.</li>
 *   <li>Two markers in a row insert a blank page between content pages.</li>
 *   <li>Oversize chunks fall back to {@link BookFactory#paginate}.</li>
 *   <li>Leading + trailing blank pages are trimmed; internal blanks survive.</li>
 * </ul>
 */
final class StartingBookPaginationTest {

    @Test
    @DisplayName("Single-page body — no marker → one page")
    void singlePage() {
        List<String> pages = StartingBookFactory.paginateExplicit("Welcome traveler.\nSit down.");
        assertEquals(1, pages.size(), "no %PAGE% marker → 1 page");
        assertEquals("Welcome traveler.\nSit down.", pages.get(0));
    }

    @Test
    @DisplayName("A %PAGE% line breaks the page")
    void markerBreaksThePage() {
        List<String> pages = StartingBookFactory.paginateExplicit("Page one body.\n%PAGE%\nPage two body.");
        assertEquals(2, pages.size());
        assertEquals("Page one body.", pages.get(0));
        assertEquals("Page two body.", pages.get(1));
    }

    @Test
    @DisplayName("Blank lines are content — any number of them, kept verbatim")
    void blankLinesAreContent() {
        // The point of the whole design: three blank lines in a row stay on the page.
        String body = "Line one.\n\n\n\nLine two.";
        List<String> pages = StartingBookFactory.paginateExplicit(body);
        assertEquals(1, pages.size(), "newlines must never break the page on their own");
        assertEquals(body, pages.get(0), "and they must survive verbatim");
    }

    @Test
    @DisplayName("Leading blank lines survive, pushing the page's text down")
    void leadingBlankLinesSurvive() {
        List<String> pages = StartingBookFactory.paginateExplicit("A.\n%PAGE%\n\n\nLower down.");
        assertEquals(2, pages.size());
        assertEquals("\n\nLower down.", pages.get(1), "one newline belongs to the marker, the rest are the author's");
    }

    @Test
    @DisplayName("Two markers in a row insert a blank page between content")
    void doubledMarkerInsertsBlankPage() {
        List<String> pages = StartingBookFactory.paginateExplicit("First.\n%PAGE%\n%PAGE%\nSecond.");
        assertEquals(3, pages.size(), "content / blank / content");
        assertEquals("First.", pages.get(0));
        assertEquals("", pages.get(1));
        assertEquals("Second.", pages.get(2));
    }

    @Test
    @DisplayName("Whitespace-only chunk between two markers is a blank page too")
    void whitespaceChunkBecomesBlankPage() {
        List<String> pages = StartingBookFactory.paginateExplicit("Alpha.\n%PAGE%\n \n%PAGE%\nBeta.");
        assertEquals(3, pages.size(), "whitespace-only middle chunk → blank page slot");
        assertEquals("Alpha.", pages.get(0));
        assertTrue(pages.get(1).isBlank());
        assertEquals("Beta.", pages.get(2));
    }

    @Test
    @DisplayName("Stray horizontal whitespace around the marker still breaks the page")
    void markerToleratesSurroundingSpaces() {
        List<String> pages = StartingBookFactory.paginateExplicit("A.\n   %PAGE%  \nB.");
        assertEquals(2, pages.size(), "a trailing space should not silently un-break a book");
        assertEquals("A.", pages.get(0));
        assertEquals("B.", pages.get(1));
    }

    @Test
    @DisplayName("Single newlines stay as line breaks within a page")
    void preserveSingleNewlines() {
        List<String> pages = StartingBookFactory.paginateExplicit("Line one\nLine two\nLine three");
        assertEquals(1, pages.size());
        assertEquals("Line one\nLine two\nLine three", pages.get(0));
    }

    @Test
    @DisplayName("Leading blank pages are trimmed (don't open a book on a blank page)")
    void trimLeadingBlankPages() {
        List<String> pages = StartingBookFactory.paginateExplicit("%PAGE%\n%PAGE%\nFirst real page.");
        assertEquals(1, pages.size(), "leading blanks should drop");
        assertEquals("First real page.", pages.get(0));
    }

    @Test
    @DisplayName("Trailing blank pages are trimmed (no dead pages at the end)")
    void trimTrailingBlankPages() {
        List<String> pages = StartingBookFactory.paginateExplicit("Last real page.\n%PAGE%\n%PAGE%\n%PAGE%");
        assertEquals(1, pages.size(), "trailing blanks should drop");
        assertEquals("Last real page.", pages.get(0));
    }

    @Test
    @DisplayName("Internal blank pages survive trim — only leading + trailing get dropped")
    void preserveInternalBlanksTrimEdges() {
        List<String> pages = StartingBookFactory.paginateExplicit(
            "%PAGE%\nA\n%PAGE%\n%PAGE%\nB\n%PAGE%\n%PAGE%\nC\n%PAGE%");
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
        assertEquals(List.of(), StartingBookFactory.paginateExplicit("   \n%PAGE%\n   "));
    }
}
