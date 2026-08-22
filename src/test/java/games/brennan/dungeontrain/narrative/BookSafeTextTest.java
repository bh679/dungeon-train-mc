package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mod's own guard on untrusted book text — relay pool books and client-signed books alike.
 *
 * <p>Mirrors the relay's {@code books.js} sanitizer case for case. The two implementations are
 * written from one spec and have to agree; these tests and their JS twins are what keep them in
 * step. Both are strictly reducing, so divergence would be confusing rather than unsafe.</p>
 *
 * <p>Every hostile character is built with {@code (char)} / {@code Character.toChars} rather than
 * written literally: a raw control or bidi character in a source file is invisible in review and in
 * a diff, which is the very property that makes it worth stripping.</p>
 */
class BookSafeTextTest {

    private static final String NUL = String.valueOf((char) 0x00);
    private static final String DEL = String.valueOf((char) 0x7f);
    private static final String C1 = String.valueOf((char) 0x85);
    private static final String RLO = String.valueOf((char) 0x202e);  // right-to-left override
    private static final String ZWSP = String.valueOf((char) 0x200b);
    private static final String BOM = String.valueOf((char) 0xfeff);
    private static final String LRI = String.valueOf((char) 0x2066);  // bidi isolate
    private static final String LRM = String.valueOf((char) 0x200e);  // left-to-right mark
    private static final String RLM = String.valueOf((char) 0x200f);  // right-to-left mark
    private static final String ZWNJ = String.valueOf((char) 0x200c);
    private static final String ZWJ = String.valueOf((char) 0x200d);
    private static final String ACUTE = String.valueOf((char) 0x301); // combining acute accent
    private static final String HIGH = String.valueOf((char) 0xd800); // unpaired high surrogate
    private static final String SECTION = String.valueOf((char) 0xa7);
    /** U+1F600 — one code point, TWO chars, so it straddles a cap by design. */
    private static final String EMOJI = new String(Character.toChars(0x1f600));

    /** A lone surrogate is what throws when book text reaches NBT's modified-UTF-8 encoder. */
    private static boolean hasLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return true;
                i++; // valid pair — skip its low half
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("strips control characters, lone surrogates and invisible padding")
    void stripsHostileCharacters() {
        assertEquals("abcd", BookSafeText.sanitizeName("a" + NUL + "b" + DEL + "c" + C1 + "d"));
        assertEquals("ab", BookSafeText.sanitizeName("a" + HIGH + "b"));   // lone surrogate
        assertEquals("abc", BookSafeText.sanitizeName("a" + ZWSP + "b" + BOM + "c"));
        assertEquals("", BookSafeText.sanitizeName(null));
    }

    @Test
    @DisplayName("§ styles a page body, but never a title or an author")
    void formattingAllowedOnPagesOnly() {
        // Styling your own page is a feature. A title or author name reaches item names, chat and
        // tooltips, so § there would restyle UI that does not belong to the book.
        assertEquals("a" + SECTION + "lpage", BookSafeText.sanitizePage("a" + SECTION + "lpage"));
        assertEquals("alpage", BookSafeText.sanitizeName("a" + SECTION + "lpage"));
    }

    @Test
    @DisplayName("right-to-left text is left alone — bidi marks, overrides and isolates all survive")
    void bidiIsPreserved() {
        // Stripping these mangles legitimate Arabic and Hebrew, which DT ships locales for. A book
        // that reverses its own display is cosmetic and cannot escape the book.
        for (String bidi : new String[] { RLO, LRI, LRM, RLM }) {
            assertEquals("a" + bidi + "b", BookSafeText.sanitizePage("a" + bidi + "b"));
            assertEquals("a" + bidi + "b", BookSafeText.sanitizeName("a" + bidi + "b"));
        }
    }

    @Test
    @DisplayName("ZWNJ and ZWJ survive — Indic/Persian scripts and emoji sequences need them")
    void joinersArePreserved() {
        assertEquals("a" + ZWNJ + "b", BookSafeText.sanitizePage("a" + ZWNJ + "b"));
        // A ZWJ emoji sequence must come through whole, or every family emoji breaks.
        String family = EMOJI + ZWJ + EMOJI;
        assertEquals(family, BookSafeText.sanitizePage(family));
    }

    @Test
    @DisplayName("newlines survive in pages, never in names")
    void newlinePolicy() {
        assertEquals("a\nb", BookSafeText.sanitizePage("a\nb"));
        assertEquals("ab", BookSafeText.sanitizeName("a\nb"));
        assertEquals("a\nb", BookSafeText.sanitizePage("a\r\nb")); // CR dropped, LF kept
    }

    @Test
    @DisplayName("collapses Zalgo combining-mark stacks, keeps ordinary accents")
    void collapsesCombiningRuns() {
        String zalgo = "e" + ACUTE.repeat(200);
        assertEquals(1 + BookSafeText.MAX_COMBINING_RUN, BookSafeText.sanitizePage(zalgo).length());
        assertEquals("e" + ACUTE, BookSafeText.sanitizePage("e" + ACUTE));
        assertEquals("e" + ACUTE.repeat(3), BookSafeText.sanitizePage("e" + ACUTE.repeat(3)));
        // The run counter resets on the next base character.
        assertEquals("e" + ACUTE + "a" + ACUTE, BookSafeText.sanitizePage("e" + ACUTE + "a" + ACUTE));
    }

    @Test
    @DisplayName("keeps astral characters whole")
    void keepsAstralText() {
        assertEquals("a" + EMOJI + "b", BookSafeText.sanitizePage("a" + EMOJI + "b"));
        assertFalse(hasLoneSurrogate(BookSafeText.sanitizePage("a" + EMOJI + "b")));
    }

    @Test
    @DisplayName("is idempotent, so applying it in more than one place is safe")
    void idempotent() {
        String hostile = "a" + SECTION + "k " + RLO + EMOJI + "e" + ACUTE.repeat(50) + HIGH + ZWSP;
        assertEquals(BookSafeText.sanitizePage(hostile), BookSafeText.sanitizePage(BookSafeText.sanitizePage(hostile)));
        assertEquals(BookSafeText.sanitizeName(hostile), BookSafeText.sanitizeName(BookSafeText.sanitizeName(hostile)));
    }

    @Test
    @DisplayName("clampCp never splits a surrogate pair")
    void clampNeverSplitsPairs() {
        String t = "x".repeat(127) + EMOJI;
        assertEquals(127, BookSafeText.clampCp(t, 128).length()); // stepped back rather than orphan the high half
        assertFalse(hasLoneSurrogate(BookSafeText.clampCp(t, 128)));
        assertTrue(BookSafeText.clampCp(t, 129).endsWith(EMOJI)); // room for both halves, so both kept
        assertEquals("abc", BookSafeText.clampCp("abc", 10));     // under the cap, unchanged
        assertEquals("", BookSafeText.clampCp("abc", 0));
        assertEquals("", BookSafeText.clampCp(null, 5));
    }

    @Test
    @DisplayName("sanitizeAndClampName trims before clamping so whitespace can't eat the budget")
    void sanitizeAndClampTrims() {
        assertEquals("hi", BookSafeText.sanitizeAndClampName("   hi   ", 8));
        assertEquals("abcd", BookSafeText.sanitizeAndClampName("  a" + SECTION + "bcdefg", 4));
        assertFalse(hasLoneSurrogate(BookSafeText.sanitizeAndClampName("x".repeat(127) + EMOJI, 128)));
    }
}
