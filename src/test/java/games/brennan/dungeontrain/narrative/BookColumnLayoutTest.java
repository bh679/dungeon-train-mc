package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layout tests for {@link BookColumnLayout}. Pure arithmetic over a fixed glyph table — no
 * Minecraft bootstrap, which is the whole reason the table exists rather than a call to
 * {@code Font#width}.
 *
 * <p>An entry now spends one line on the name and the next on the score, so the two things measured
 * here are "does a name fit its own line" and "does a value land on the margin" — not the old
 * name-and-number-on-one-line row.
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
    @DisplayName("a name line never exceeds the page width, for any name")
    void nameLineNeverOverflows() {
        for (String name : new String[]{
            "Ada", "MMMMMMMMMMMMMMMM", "iiiiiiiiiiiiiiii", "\u65e5\u672c\u8a9e\u306e\u30d7\u30ec\u30a4\u30e4\u30fc", "", "x".repeat(64),
        }) {
            String line = BookColumnLayout.truncate("1. " + name, W);
            assertTrue(BookColumnLayout.width(line) <= W,
                "'" + name + "' laid out to " + BookColumnLayout.width(line) + "px, over the " + W + "px margin");
        }
    }

    @Test
    @DisplayName("a name gets the whole line now it no longer shares one with a score")
    void nameKeepsTheWholeLine() {
        String line = "1. FirstClassGhost";
        // Fits alone at 91px, and would NOT have fitted beside a five-figure score: that shared a
        // line, so the name only ever had W minus the score minus a space to live in.
        int sharedRoom = W - BookColumnLayout.width("61200m") - 4;
        assertTrue(BookColumnLayout.width(line) > sharedRoom, "this example no longer proves anything");
        assertEquals(line, BookColumnLayout.truncate(line, W), "a name this long must survive on its own line");
    }

    @Test
    @DisplayName("a right-aligned value lands within one space of the margin")
    void valueSitsAtTheMargin() {
        for (String value : new String[]{"1234", "37d 12h", "$500", "61200m", "7"}) {
            String line = BookColumnLayout.rightAlign(value, W);
            int px = BookColumnLayout.width(line);
            assertTrue(px > W - 4 && px <= W, "'" + value + "' landed at " + px + "px, expected 0-3px short of " + W);
            assertTrue(line.endsWith(value), "the value must survive alignment: " + line);
        }
    }

    @Test
    @DisplayName("a value wider than the page is left alone rather than padded onto a second line")
    void oversizeValueIsNotPadded() {
        String huge = "9".repeat(40);
        assertEquals(huge, BookColumnLayout.rightAlign(huge, W));
        assertEquals("", BookColumnLayout.rightAlign("", W));
        assertEquals("", BookColumnLayout.rightAlign(null, W));
    }

    @Test
    @DisplayName("truncate returns empty rather than a lone ellipsis when there is no room at all")
    void truncateGivesUpCleanly() {
        assertEquals("", BookColumnLayout.truncate("Ada", 3));
        assertEquals("", BookColumnLayout.truncate("", 100));
    }

}
