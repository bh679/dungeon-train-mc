package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layout tests for {@link BookColumnLayout}. Pure arithmetic over a fixed glyph table — no
 * Minecraft bootstrap, which is the whole reason the table exists rather than a call to
 * {@code Font#width}.
 */
class BookColumnLayoutTest {

    private static final int W = BookColumnLayout.PAGE_WIDTH_PX;

    @Test
    @DisplayName("narrow glyphs are measured narrow — the reason a space-padded column cannot work")
    void narrowGlyphsMeasuredNarrow() {
        assertEquals(2, BookColumnLayout.charWidth('i'));
        assertEquals(3, BookColumnLayout.charWidth('l'));
        assertEquals(6, BookColumnLayout.charWidth('M'));
        assertEquals(4, BookColumnLayout.charWidth(' '));
        // Sixteen characters either way, wildly different widths.
        assertEquals(32, BookColumnLayout.width("iiiiiiiiiiiiiiii"));
        assertEquals(96, BookColumnLayout.width("MMMMMMMMMMMMMMMM"));
    }

    @Test
    @DisplayName("CJK names are measured at the wide cell, not the Latin default")
    void cjkMeasuredWide() {
        assertEquals(9, BookColumnLayout.charWidth('日'));
        assertEquals(27, BookColumnLayout.width("日本語"));
    }

    @Test
    @DisplayName("a row never exceeds the page width, for any name")
    void rowNeverOverflows() {
        for (String name : new String[]{
            "Ada", "MMMMMMMMMMMMMMMM", "iiiiiiiiiiiiiiii", "日本語のプレイヤー", "", "x".repeat(64),
        }) {
            String row = BookColumnLayout.row("1. " + name, "123456", W);
            assertTrue(BookColumnLayout.width(row) <= W,
                "'" + name + "' laid out to " + BookColumnLayout.width(row) + "px, over the " + W + "px margin");
        }
    }

    @Test
    @DisplayName("the value lands within one space of the margin — as close as space padding allows")
    void valueSitsAtTheMargin() {
        String row = BookColumnLayout.row("1. Ada", "1234", W);
        int px = BookColumnLayout.width(row);
        assertTrue(px > W - 4 && px <= W, "expected 0-3px short of " + W + ", got " + px);
    }

    @Test
    @DisplayName("a long name is truncated with an ellipsis rather than pushing the number off the line")
    void longNameTruncates() {
        String row = BookColumnLayout.row("100. ABCDEFGHIJKLMNOP", "999999", W);
        assertTrue(row.contains("…"), "expected an ellipsis in: " + row);
        assertTrue(row.endsWith("999999"), "the number must survive truncation: " + row);
        assertTrue(BookColumnLayout.width(row) <= W);
    }

    @Test
    @DisplayName("a name that fits is left exactly as written")
    void shortNameUntouched() {
        String row = BookColumnLayout.row("1. Ada", "7", W);
        assertTrue(row.startsWith("1. Ada "), row);
        assertTrue(!row.contains("…"));
    }

    @Test
    @DisplayName("truncate returns empty rather than a lone ellipsis when there is no room at all")
    void truncateGivesUpCleanly() {
        assertEquals("", BookColumnLayout.truncate("Ada", 3));
        assertEquals("", BookColumnLayout.truncate("", 100));
    }

    @Test
    @DisplayName("a value wider than the page keeps the number and drops the name")
    void oversizeValueWins() {
        String huge = "9".repeat(40);
        assertEquals(huge, BookColumnLayout.row("1. Ada", huge, W));
    }

    @Test
    @DisplayName("there is always at least one space between the columns")
    void alwaysAGap() {
        String row = BookColumnLayout.row("1. MMMMMMMMMMMMMMMMMM", "999999", W);
        assertTrue(row.contains(" 999999"), "columns must not run together: " + row);
    }
}
