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
    @DisplayName("strips formatting, control, bidi and zero-width characters")
    void stripsHostileCharacters() {
        assertEquals("akb", BookSafeText.sanitize("a" + SECTION + "kb", false));
        assertEquals("abcd", BookSafeText.sanitize("a" + NUL + "b" + DEL + "c" + C1 + "d", false));
        assertEquals("abcde", BookSafeText.sanitize("a" + RLO + "b" + ZWSP + "c" + BOM + "d" + LRI + "e", false));
        assertEquals("ab", BookSafeText.sanitize("a" + HIGH + "b", false));
        assertEquals("", BookSafeText.sanitize(null, false));
    }

    @Test
    @DisplayName("keeps astral characters whole")
    void keepsAstralText() {
        assertEquals("a" + EMOJI + "b", BookSafeText.sanitize("a" + EMOJI + "b", false));
        assertFalse(hasLoneSurrogate(BookSafeText.sanitize("a" + EMOJI + "b", false)));
    }

    @Test
    @DisplayName("newlines survive in pages, never in titles or authors")
    void newlinePolicy() {
        assertEquals("a\nb", BookSafeText.sanitize("a\nb", true));
        assertEquals("ab", BookSafeText.sanitize("a\nb", false));
        assertEquals("a\nb", BookSafeText.sanitize("a\r\nb", true)); // CR dropped, LF kept
    }

    @Test
    @DisplayName("collapses Zalgo combining-mark stacks, keeps ordinary accents")
    void collapsesCombiningRuns() {
        String zalgo = "e" + ACUTE.repeat(200);
        assertEquals(1 + BookSafeText.MAX_COMBINING_RUN, BookSafeText.sanitize(zalgo, false).length());
        assertEquals("e" + ACUTE, BookSafeText.sanitize("e" + ACUTE, false));
        assertEquals("e" + ACUTE.repeat(3), BookSafeText.sanitize("e" + ACUTE.repeat(3), false));
        // The run counter resets on the next base character — a second accented letter is untouched.
        assertEquals("e" + ACUTE + "a" + ACUTE, BookSafeText.sanitize("e" + ACUTE + "a" + ACUTE, false));
    }

    @Test
    @DisplayName("is idempotent, so applying it in more than one place is safe")
    void idempotent() {
        String hostile = "a" + SECTION + "k " + RLO + EMOJI + "e" + ACUTE.repeat(50) + HIGH;
        assertEquals(BookSafeText.sanitize(hostile, true), BookSafeText.sanitize(BookSafeText.sanitize(hostile, true), true));
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
    @DisplayName("sanitizeAndClamp trims before clamping so whitespace can't eat the budget")
    void sanitizeAndClampTrims() {
        assertEquals("hi", BookSafeText.sanitizeAndClamp("   hi   ", 8, false));
        assertEquals("abcd", BookSafeText.sanitizeAndClamp("  a" + SECTION + "bcdefg", 4, false));
        assertFalse(hasLoneSurrogate(BookSafeText.sanitizeAndClamp("x".repeat(127) + EMOJI, 128, false)));
    }
}
