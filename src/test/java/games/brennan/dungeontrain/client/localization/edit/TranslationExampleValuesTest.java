package games.brennan.dungeontrain.client.localization.edit;

import games.brennan.dungeontrain.client.localization.edit.TranslationVariableExamples.Example;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rendering an example in the locale being edited, and in English, for the both-languages tooltip.
 *
 * <p>The fallback is the load-bearing case: a locale that has not translated the clause yet must
 * show the English once, not an English value dressed up as that locale's own wording.</p>
 */
class TranslationExampleValuesTest {

    private static final String TRAVELLERS =
        "chat.dungeontrain.shared_carriage.deaths.count.travellers.other";
    private static final String NOUN = "chat.dungeontrain.shared_carriage.noun.1";

    private static TranslationExampleValues values(Map<String, String> localized) {
        return new TranslationExampleValues(localized,
            Map.of(TRAVELLERS, "%s travellers", NOUN, "drifting carriage"));
    }

    @Test
    @DisplayName("a keyed example renders in the locale, with the English kept beside it")
    void localeAndEnglishBoth() {
        var rendered = values(Map.of(TRAVELLERS, "%s 名旅人"))
            .render(Example.keyed(TRAVELLERS, List.of("3")));
        assertEquals("3 名旅人", rendered.localized());
        assertEquals("3 travellers", rendered.english());
        assertTrue(rendered.differs());
    }

    @Test
    @DisplayName("a locale that has not translated the clause falls back to the English, once")
    void untranslatedFallsBackToEnglish() {
        // ru_ru genuinely has neither count key today — this is that case.
        var rendered = values(Map.of()).render(Example.keyed(TRAVELLERS, List.of("3")));
        assertEquals("3 travellers", rendered.localized());
        assertFalse(rendered.differs(), "nothing to show twice when the locale has no translation");
    }

    @Test
    @DisplayName("a key taking no arguments renders as it stands")
    void argumentlessKeys() {
        var rendered = values(Map.of(NOUN, "漂流车厢")).render(Example.keyed(NOUN, List.of()));
        assertEquals("漂流车厢", rendered.localized());
        assertEquals("drifting carriage", rendered.english());
    }

    @Test
    @DisplayName("a literal is itself in either language")
    void literalsPassThrough() {
        var rendered = values(Map.of()).render(Example.literal("Steve"));
        assertEquals("Steve", rendered.localized());
        assertEquals("Steve", rendered.english());
        assertFalse(rendered.differs());
    }

    @Test
    @DisplayName("a key nothing ships renders as the key rather than as a blank line")
    void unknownKeysAreVisible() {
        var rendered = values(Map.of()).render(Example.keyed("no.such.key", List.of()));
        assertEquals("no.such.key", rendered.localized());
    }

    @Test
    @DisplayName("arguments that do not fit the string leave it alone instead of throwing")
    void badArgumentsDegrade() {
        // A translation that dropped its %s must not take the tooltip — or the screen — down.
        var rendered = values(Map.of(TRAVELLERS, "no placeholder here"))
            .render(Example.keyed(TRAVELLERS, List.of("3")));
        assertEquals("no placeholder here", rendered.localized());
    }
}
