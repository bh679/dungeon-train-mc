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
    @DisplayName("a string the player marked good as is leaves the queue too")
    void dismissedLeavesTheQueue() {
        // A dismissal is the review the queue was asking for — a speaker of the language read the
        // machine translation and decided it needed nothing. Unlike an approval it binds only this
        // translator's own list, which is why it is a predicate rather than an override layer.
        TranslationUnit unit = langUnit("gui.a", true);
        TranslationEdits none = TranslationEdits.empty("de_de");
        assertTrue(TranslationFilters.needsHuman(unit, none, (u) -> false));
        assertFalse(TranslationFilters.needsHuman(unit, none, (u) -> true));
        // The unfiltered question is unchanged — ALL still shows a dismissed string.
        assertTrue(TranslationFilters.needsHuman(unit, none));
    }

    @Test
    @DisplayName("dismissing one string does not excuse another")
    void dismissalIsPerUnit() {
        TranslationEdits none = TranslationEdits.empty("de_de");
        assertTrue(TranslationFilters.needsHuman(langUnit("gui.b", true), none,
            (u) -> u.id().equals("gui.a")));
        assertFalse(TranslationFilters.needsHuman(langUnit("gui.a", true), none,
            (u) -> u.id().equals("gui.a")));
    }

    @Test
    @DisplayName("a missing layer is treated as no approvals rather than throwing")
    void nullsAreSafe() {
        assertTrue(TranslationFilters.needsHuman(langUnit("gui.a", true), null));
        assertFalse(TranslationFilters.needsHuman(null, TranslationEdits.empty("de_de")));
        assertNull(TranslationFilters.overrideOf(null, TranslationEdits.empty("de_de")));
        assertNull(TranslationFilters.overrideOf(langUnit("gui.a", true), null));
        // No dismissal set at all asks the plain question rather than throwing.
        assertTrue(TranslationFilters.needsHuman(langUnit("gui.a", true), null, null));
    }
}
