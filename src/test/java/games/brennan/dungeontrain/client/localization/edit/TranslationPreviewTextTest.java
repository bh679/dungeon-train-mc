package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The text and arithmetic behind the translation previews. */
class TranslationPreviewTextTest {

    private static final TranslationVariableScanner.Lookup NO_EXAMPLES = (key, slot) -> null;

    private static List<TranslationVariable> scan(String text) {
        return TranslationVariableScanner.scan("k", text, NO_EXAMPLES);
    }

    @Test
    @DisplayName("placeholders are filled in order and %% prints as one percent sign")
    void fills() {
        String text = "%s hat %s Wagen geschafft (100%%)";
        String filled = TranslationPreviewText.fill(text, scan(text),
            v -> v.slot() == 1 ? "Steve" : "42");
        assertEquals("Steve hat 42 Wagen geschafft (100%)", filled);
    }

    @Test
    @DisplayName("a slot with no example keeps its token, never vanishes")
    void keepsUnknownTokens() {
        String text = "Bonjour %s";
        assertEquals("Bonjour %s", TranslationPreviewText.fill(text, scan(text), v -> null));
    }

    @Test
    @DisplayName("text with no placeholders comes back as typed")
    void plain() {
        assertEquals("Abfahren", TranslationPreviewText.fill("Abfahren", scan("Abfahren"), v -> "x"));
        assertEquals("", TranslationPreviewText.fill(null, List.of(), v -> "x"));
    }

    @Test
    @DisplayName("a label fits while it is no wider than the button less vanilla's 2px margins")
    void overflow() {
        assertTrue(TranslationPreviewText.buttonOverflow(196, 200) <= 0);
        assertEquals(1, TranslationPreviewText.buttonOverflow(197, 200));
        assertEquals(50, TranslationPreviewText.buttonOverflow(196, 150));
    }

    @Test
    @DisplayName("the small-screen frame is the default 854x480 window at GUI scale Auto")
    void smallScreen() {
        assertEquals(854, TranslationPreviewText.SMALL_SCREEN_WIDTH * TranslationPreviewText.SMALL_SCREEN_GUI_SCALE);
        assertEquals(480, TranslationPreviewText.SMALL_SCREEN_HEIGHT * TranslationPreviewText.SMALL_SCREEN_GUI_SCALE);
    }
}
