package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.brennan.dungeontrain.RepoPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The death-screen epitaph's ordinal. The regression is a shipped one: the template read
 * {@code "the {deaths}th to fall"} and {@code {deaths}} resolves through {@code DeathLoreStore.words}
 * to a spelled-out CARDINAL, so English rendered "the twoth to fall" and the Russian mirror
 * {@code "{deaths}-<char>"} rendered "\u0434\u0432\u0430-\u0439" \u2014 literally "two-th".
 *
 * <p>Two halves have to hold together: the ordinal has to be FORMED right, and the templates have to
 * ASK for it. A correct formatter nothing calls fixes nothing, so the shipped templates are asserted
 * here too.</p>
 */
final class DeathLoreOrdinalTest {

    /** The sentinels the death screen parses to colour the figure white (see DeathLoreStore.num). */
    private static final char OPEN = '\u0001';
    private static final char CLOSE = '\u0002';

    @AfterEach
    void resetLocale() {
        NarrativeContentLocale.set("");
    }

    private static String ord(String locale, long n) {
        NarrativeContentLocale.set(locale);
        String out = DeathLoreStore.ord(n);
        assertTrue(out.charAt(0) == OPEN && out.charAt(out.length() - 1) == CLOSE,
                "figure sentinels lost: " + out);
        return out.substring(1, out.length() - 1);
    }

    @Test @DisplayName("The ordinal follows the prose language, wrapped in the white-figure sentinels")
    void ordinalFollowsTheProseLanguage() {
        assertEquals("second", ord("", 2));            // no locale set = the English base
        assertEquals("second", ord("en_us", 2));
        assertEquals("\u0432\u0442\u043e\u0440\u043e\u0439", ord("ru_ru", 2));   // was "\u0434\u0432\u0430-\u0439"
        assertEquals("\u043f\u044f\u0442\u044b\u0439", ord("ru_ru", 5));
        assertEquals("\u043f\u0435\u0440\u0432\u044b\u0439", ord("ru_ru", 1));
        assertEquals("37-\u0439", ord("ru_ru", 37));
        assertEquals("21st", ord("en_us", 21));
        assertEquals("\u7b2c2", ord("zh_cn", 2));
    }

    @Test @DisplayName("The shipped templates ask for the ordinal, and none still glue a suffix on")
    void shippedTemplatesUseTheOrdinalPlaceholder() throws IOException {
        Path root = RepoPaths.root().resolve("src/main/resources/data/dungeontrain");
        String en = Files.readString(root.resolve("death_lore/default.json"), StandardCharsets.UTF_8);
        String ru = Files.readString(
                root.resolve("narrative_localizations/ru_ru/death_lore/default.json"),
                StandardCharsets.UTF_8);
        assertTrue(en.contains("{deaths_nth}"), "English epitaphs no longer name the ordinal");
        assertTrue(ru.contains("{deaths_nth}"), "Russian epitaphs no longer name the ordinal");
        assertFalse(en.contains("{deaths}th"), "English still glues 'th' onto a spelled-out cardinal");
        assertFalse(ru.contains("{deaths}-"), "Russian still glues an adjective ending onto a cardinal");
    }
}
