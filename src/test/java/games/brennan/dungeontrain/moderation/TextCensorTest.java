package games.brennan.dungeontrain.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The censor masks listed words however they are spelled, and leaves innocent words alone. */
final class TextCensorTest {

    /** A list in the shape of the baked one, with made-up terms so the test file carries no slurs. */
    private static final CompiledWords WORDS = new CompiledWords(
        List.of("zorp", "quib"),          // whole word only
        List.of("blarnex"),               // distinctive: whole word + collapsed
        List.of("炽焰狼"),                 // CJK substring
        List.of("red flannel"),           // phrase
        List.of("blarnexia", "zorpington")); // allow

    @Test
    @DisplayName("a whole-word term is masked; the rest of the line is untouched")
    void wholeWord() {
        TextCensor.Result r = TextCensor.censor("you absolute zorp, go away", WORDS);
        assertTrue(r.changed());
        assertEquals("you absolute ****, go away", r.text());
        assertEquals(List.of("zorp"), r.hits());
    }

    @Test
    @DisplayName("case, punctuation and trailing symbols do not hide a whole-word term")
    void wholeWordVariants() {
        assertEquals("****!", TextCensor.censor("ZORP!", WORDS).text());
        assertEquals("(****)", TextCensor.censor("(Quib)", WORDS).text());
        assertEquals("**** ****", TextCensor.censor("zorp quib", WORDS).text());
    }

    @Test
    @DisplayName("a term inside a longer ordinary word is not a hit")
    void substringsOfOrdinaryWords() {
        assertFalse(TextCensor.censor("zorpington is a town", WORDS).changed());
        assertFalse(TextCensor.censor("the quibble was settled", WORDS).changed());
        assertFalse(TextCensor.censor("blarnexia is lovely in spring", WORDS).changed());
    }

    @Test
    @DisplayName("a distinctive term survives separators, repeats and leetspeak")
    void collapseCatchesEvasion() {
        assertEquals("****", TextCensor.censor("b l a r n e x", WORDS).text());
        assertEquals("**** here", TextCensor.censor("b.l.a.r.n.e.x here", WORDS).text());
        assertEquals("****", TextCensor.censor("blaaarnexxx", WORDS).text());
        assertEquals("****", TextCensor.censor("bl4rn3x", WORDS).text());
        assertEquals("****", TextCensor.censor("BlArNeX", WORDS).text());
        assertEquals("****", TextCensor.censor("bla​rnex", WORDS).text());
    }

    @Test
    @DisplayName("a distinctive term's letters inside a longer word, or spread over real words, are not a hit")
    void collapseDoesNotOverreach() {
        // one word that merely contains the squeezed letters
        assertFalse(TextCensor.censor("blarnexing along", WORDS).changed());
        // spread across words longer than three letters
        assertFalse(TextCensor.censor("blar nexus", WORDS).changed());
        assertFalse(TextCensor.censor("in blarnexia", WORDS).changed());
    }

    @Test
    @DisplayName("a CJK term is masked even with separators between the characters")
    void cjk() {
        assertEquals("你是****吗", TextCensor.censor("你是炽焰狼吗", WORDS).text());
        assertEquals("****", TextCensor.censor("炽 焰 狼", WORDS).text());
    }

    @Test
    @DisplayName("a multi-word phrase is masked as one span")
    void phrase() {
        assertEquals("wearing **** today", TextCensor.censor("wearing red flannel today", WORDS).text());
        assertEquals("wearing **** today", TextCensor.censor("wearing Red   Flannel today", WORDS).text());
        assertFalse(TextCensor.censor("red paint and flannel", WORDS).changed());
    }

    @Test
    @DisplayName("censoring twice changes nothing more")
    void idempotent() {
        TextCensor.Result once = TextCensor.censor("zorp and b l a r n e x and 炽焰狼", WORDS);
        TextCensor.Result twice = TextCensor.censor(once.text(), WORDS);
        assertFalse(twice.changed());
        assertEquals(once.text(), twice.text());
    }

    @Test
    @DisplayName("while typing, a whole-word term is masked only once a boundary follows it")
    void tailWaitsForTheWordToEnd() {
        assertFalse(TextCensor.censorTail("you zorp", WORDS).changed());
        assertEquals("you **** ", TextCensor.censorTail("you zorp ", WORDS).text());
        assertEquals("you ****,", TextCensor.censorTail("you zorp,", WORDS).text());
        // the distinctive pass acts at once
        assertEquals("****", TextCensor.censorTail("blarnex", WORDS).text());
    }

    @Test
    @DisplayName("empty input, null input and an empty list are no-ops")
    void edges() {
        assertFalse(TextCensor.censor("", WORDS).changed());
        assertEquals("", TextCensor.censor(null, WORDS).text());
        assertFalse(TextCensor.censor("zorp", CompiledWords.EMPTY).changed());
        assertFalse(TextCensor.censor("hello there general", WORDS).changed());
    }

    @Test
    @DisplayName("a surrogate-pair emoji next to a term neither hides it nor gets split")
    void emojiNeighbours() {
        TextCensor.Result r = TextCensor.censor("zorp😀", WORDS);
        assertEquals("****😀", r.text());
    }
}
