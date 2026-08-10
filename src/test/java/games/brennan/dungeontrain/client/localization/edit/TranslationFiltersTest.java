package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the translation editor's "Needs a human" queue does and does not ask a volunteer to work on.
 * Pure — no Minecraft bootstrap, which is the reason the predicate lives outside the screen.
 */
class TranslationFiltersTest {

    private static TranslationUnit langUnit(String key, boolean aiUnreviewed) {
        return new TranslationUnit(TranslationUnit.Type.LANG, "dungeontrain", key,
            "Depart", "Abfahren", aiUnreviewed);
    }

    private static TranslationUnit bookUnit(String id, boolean aiUnreviewed) {
        return new TranslationUnit(TranslationUnit.Type.BOOK, "dungeontrain", id,
            "The Lost Conductor", "Der verlorene Schaffner", aiUnreviewed);
    }

    private static TranslationEdits approvedLang(String key, String value) {
        return new TranslationEdits("de_de", Map.of(key, value), Map.of());
    }

    @Test
    @DisplayName("machine translation nobody has reviewed still needs a human")
    void unreviewedNeedsHuman() {
        assertTrue(TranslationFilters.needsHuman(langUnit("gui.a", true),
            TranslationEdits.empty("de_de")));
    }

    @Test
    @DisplayName("a string the relay has approved has HAD its human review, so it leaves the queue")
    void approvedLeavesTheQueue() {
        // The whole point: provenance still says "AI" because that was true when the jar was built,
        // but an operator has since released a player's fix for this exact key.
        TranslationUnit unit = langUnit("gui.a", true);
        assertTrue(unit.aiUnreviewed());
        assertFalse(TranslationFilters.needsHuman(unit, approvedLang("gui.a", "Abfahren!")));
    }

    @Test
    @DisplayName("an approval for a DIFFERENT key does not excuse this one")
    void approvalIsPerKey() {
        assertTrue(TranslationFilters.needsHuman(langUnit("gui.a", true),
            approvedLang("gui.b", "Ankommen")));
    }

    @Test
    @DisplayName("a human-translated string was never in the queue, approved or not")
    void humanTranslatedIsNeverQueued() {
        assertFalse(TranslationFilters.needsHuman(langUnit("gui.a", false),
            TranslationEdits.empty("de_de")));
        assertFalse(TranslationFilters.needsHuman(langUnit("gui.a", false),
            approvedLang("gui.a", "Abfahren!")));
    }

    @Test
    @DisplayName("book fields are matched against the book body, not the lang one")
    void bookFieldsUseTheirOwnBody() {
        TranslationUnit unit = bookUnit("random_books/lost#title", true);
        // Same id sitting in the lang map must not count — the two bodies are separate namespaces.
        assertTrue(TranslationFilters.needsHuman(unit,
            new TranslationEdits("de_de", Map.of("random_books/lost#title", "x"), Map.of())));
        assertFalse(TranslationFilters.needsHuman(unit,
            new TranslationEdits("de_de", Map.of(), Map.of("random_books/lost#title", "x"))));
    }

    @Test
    @DisplayName("a missing layer is treated as no approvals rather than throwing")
    void nullsAreSafe() {
        assertTrue(TranslationFilters.needsHuman(langUnit("gui.a", true), null));
        assertFalse(TranslationFilters.needsHuman(null, TranslationEdits.empty("de_de")));
        assertNull(TranslationFilters.overrideOf(null, TranslationEdits.empty("de_de")));
        assertNull(TranslationFilters.overrideOf(langUnit("gui.a", true), null));
    }
}
