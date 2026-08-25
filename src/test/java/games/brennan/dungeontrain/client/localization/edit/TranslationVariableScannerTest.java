package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The placeholder scanner behind the editor's underlines.
 *
 * <p>The numbering is the load-bearing part: the tooltip promises a translator that {@code %2$s}
 * holds a particular thing, and it can only promise that if the scanner numbers slots the way
 * {@link java.util.Formatter} does at runtime.</p>
 */
class TranslationVariableScannerTest {

    private static final TranslationVariableScanner.Lookup NONE = (key, slot) -> null;

    @Test
    @DisplayName("bare %s placeholders are numbered in the order they appear")
    void bareArgumentsCountUp() {
        List<TranslationVariable> found =
            TranslationVariableScanner.scan("k", "%s was kept as %s in the config folder.", NONE);
        assertEquals(2, found.size());
        assertEquals(1, found.get(0).slot());
        assertEquals(2, found.get(1).slot());
        assertEquals("%s", found.get(0).token());
    }

    @Test
    @DisplayName("an explicit index names its own slot and does not advance the bare counter")
    void positionalArgumentsKeepTheirIndex() {
        List<TranslationVariable> found = TranslationVariableScanner.scan(
            "k", "%2$s held it, and %1$s read it. Then %s.", NONE);
        assertEquals(3, found.size());
        assertEquals(2, found.get(0).slot());
        assertEquals(1, found.get(1).slot());
        // The bare %s takes slot 1 — Formatter's ordinary index is untouched by %N$s before it.
        assertEquals(1, found.get(2).slot());
    }

    @Test
    @DisplayName("%% is a literal percent sign, not a variable")
    void literalPercentIsNotAVariable() {
        List<TranslationVariable> found =
            TranslationVariableScanner.scan("k", "100%% done, %s left", NONE);
        assertEquals(1, found.size());
        assertEquals(1, found.get(0).slot());
        // ...and the one real variable is still found at its own offsets, not the literal's.
        assertEquals("%s", found.get(0).token());
    }

    @Test
    @DisplayName("offsets land exactly on the token, so the styled run replaces nothing else")
    void offsetsBoundTheToken() {
        String text = "Reply from %1$s.";
        TranslationVariable variable = TranslationVariableScanner.scan("k", text, NONE).get(0);
        assertEquals("%1$s", text.substring(variable.start(), variable.end()));
    }

    @Test
    @DisplayName("a string with no placeholders, and empty input, yield nothing")
    void plainTextHasNoVariables() {
        assertTrue(TranslationVariableScanner.scan("k", "Just words.", NONE).isEmpty());
        assertTrue(TranslationVariableScanner.scan("k", "", NONE).isEmpty());
        assertTrue(TranslationVariableScanner.scan("k", null, NONE).isEmpty());
    }

    @Test
    @DisplayName("curated label and examples are attached to the slot they belong to")
    void lookupDecoratesEachSlot() {
        Map<Integer, TranslationVariableExamples.Entry> curated = Map.of(
            2, new TranslationVariableExamples.Entry("a duration", List.of("3 minutes")));
        List<TranslationVariable> found = TranslationVariableScanner.scan(
            "k", "%1$s held it; the longest reading ran %2$s.",
            (key, slot) -> curated.get(slot));
        assertFalse(found.get(0).hasLabel());
        assertFalse(found.get(0).hasExamples());
        assertEquals("a duration", found.get(1).label());
        assertEquals(List.of("3 minutes"), found.get(1).examples());
    }

    @Test
    @DisplayName("slotCount reports the highest slot a string uses")
    void slotCountCountsSlotsNotTokens() {
        assertEquals(0, TranslationVariableScanner.slotCount("Just words."));
        assertEquals(2, TranslationVariableScanner.slotCount("%s (%s)"));
        // Two tokens, one slot: the same argument printed twice.
        assertEquals(1, TranslationVariableScanner.slotCount("%1$s and %1$s"));
        assertEquals(3, TranslationVariableScanner.slotCount("%1$s, page %2$s — %3$s there"));
    }
}
