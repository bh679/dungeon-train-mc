package games.brennan.dungeontrain.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Folding keeps one slot per original character so a hit can be masked where it was typed. */
final class TextNormalizerTest {

    @Test
    @DisplayName("lowercase, fullwidth and accented letters fold to plain ASCII letters")
    void folds() {
        assertEquals("hello", TextNormalizer.normalizeTerm("HeLLo"));
        assertEquals("hello", TextNormalizer.normalizeTerm("ｈｅｌｌｏ"));
        assertEquals("cafe", TextNormalizer.normalizeTerm("café"));
    }

    @Test
    @DisplayName("Cyrillic and Greek look-alikes read as the Latin letters they imitate")
    void confusables() {
        assertEquals("paco", TextNormalizer.normalizeTerm("расо")); // Cyrillic р а с о
        assertEquals("tok", TextNormalizer.normalizeTerm("τοκ"));
    }

    @Test
    @DisplayName("leet digits and symbols become letters only between letters")
    void leetOnlyInsideWords() {
        assertEquals("nice", TextNormalizer.normalizeTerm("n1ce"));
        assertEquals("hello!", TextNormalizer.normalizeTerm("hello!"));
        assertEquals("2024", TextNormalizer.normalizeTerm("2024"));
        assertEquals("a1", TextNormalizer.normalizeTerm("a1"));
    }

    @Test
    @DisplayName("zero-width and combining characters vanish without splitting the word")
    void transparentCharacters() {
        char[] f = TextNormalizer.fold("a​b̈c");
        assertEquals(5, f.length);
        assertEquals('a', f[0]);
        assertEquals(TextNormalizer.TRANSPARENT, f[1]);
        assertEquals('b', f[2]);
        assertEquals(TextNormalizer.TRANSPARENT, f[3]);
        assertEquals('c', f[4]);
        assertEquals("abc", TextNormalizer.normalizeTerm("a​b̈c"));
    }

    @Test
    @DisplayName("collapse keeps letters only, squeezes repeats and remembers where each came from")
    void collapseWithOrigins() {
        TextNormalizer.Mapped m = TextNormalizer.collapse(TextNormalizer.fold("b l-aa r"));
        assertEquals("blar", m.text());
        assertArrayEquals(new int[] {0, 2, 4, 7}, m.origin());
    }

    @Test
    @DisplayName("the CJK collapse drops separators but keeps ideographs and Latin letters")
    void cjkCollapse() {
        assertEquals("操你妈ab", TextNormalizer.cjkCollapse(TextNormalizer.fold("操 你.妈 a b")).text());
    }

    @Test
    @DisplayName("a surrogate pair occupies two slots, the second transparent")
    void surrogatePairs() {
        char[] f = TextNormalizer.fold("a😀b");
        assertEquals(4, f.length);
        assertEquals(TextNormalizer.TRANSPARENT, f[2]);
        assertEquals('b', f[3]);
    }
}
