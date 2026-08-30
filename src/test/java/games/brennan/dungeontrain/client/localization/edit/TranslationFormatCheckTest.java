package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Whether a translator's text will render. Pure — no Minecraft bootstrap.
 *
 * <p>The case that prompted this is {@link #aLostPlaceholderIsCaught()}: an approved Russian
 * translation carried one {@code %s} where the English has two, and nothing noticed until the
 * weekly import failed on it, long after the translator had moved on.</p>
 */
class TranslationFormatCheckTest {

    private static final String TWO = "removes backups from:\n%s\nand\n%s";
    private static final String ONE = "Press %s to continue";
    private static final String NONE = "All Aboard!";

    @Test
    @DisplayName("A translation that keeps its placeholders is fine")
    void aGoodTranslationPasses() {
        assertNull(TranslationFormatCheck.check(ONE, "Нажмите %s, чтобы продолжить"));
        assertNull(TranslationFormatCheck.check(TWO, "удалит копии из:\n%s\nи\n%s"));
        assertNull(TranslationFormatCheck.check(NONE, "Все на борт!"));
    }

    @Test
    @DisplayName("A lost placeholder is caught, and named")
    void aLostPlaceholderIsCaught() {
        var problem = TranslationFormatCheck.check(TWO, "удалит все копии из:\n%s");
        assertNotNull(problem);
        assertEquals(TranslationFormatCheck.MISSING_VARS, problem.messageKey());
        assertEquals("%2$s", problem.tokens());
    }

    @Test
    @DisplayName("An invented placeholder is caught, and named")
    void anInventedPlaceholderIsCaught() {
        var problem = TranslationFormatCheck.check(ONE, "Нажмите %s или %s");
        assertNotNull(problem);
        assertEquals(TranslationFormatCheck.EXTRA_VARS, problem.messageKey());
        assertEquals("%2$s", problem.tokens());
    }

    @Test
    @DisplayName("A bare % is caught — it throws rather than merely reading wrong")
    void aBarePercentIsCaught() {
        var problem = TranslationFormatCheck.check(NONE, "Готово на 50%");
        assertNotNull(problem);
        assertEquals(TranslationFormatCheck.BARE_PERCENT, problem.messageKey());
    }

    @Test
    @DisplayName("A bare % is reported ahead of a slot mismatch")
    void theCrashIsReportedBeforeTheMisreading() {
        var problem = TranslationFormatCheck.check(ONE, "Готово на 50%");
        assertNotNull(problem);
        assertEquals(TranslationFormatCheck.BARE_PERCENT, problem.messageKey(),
            "a translator who typed 50% is not thinking about argument slots");
    }

    @Test
    @DisplayName("A doubled %% is a literal percent, not a placeholder")
    void aDoubledPercentIsFine() {
        assertNull(TranslationFormatCheck.check(NONE, "Готово на 50%%"));
        assertNull(TranslationFormatCheck.check(ONE, "Нажмите %s — 50%% готово"));
    }

    @Test
    @DisplayName("Reordering with positional forms is allowed — that is what they are for")
    void reorderingIsAllowed() {
        assertNull(TranslationFormatCheck.check(TWO, "из:\n%2$s\nи\n%1$s"));
    }

    @Test
    @DisplayName("Blank means 'no override' and is always allowed")
    void blankReverts() {
        assertNull(TranslationFormatCheck.check(TWO, ""));
        assertNull(TranslationFormatCheck.check(TWO, "   "));
        assertNull(TranslationFormatCheck.check(TWO, null));
    }

    @Test
    @DisplayName("%d is normalized to %s before judging, not scolded")
    void unsupportedConversionsAreNormalizedFirst() {
        assertNull(TranslationFormatCheck.checkTyped(ONE, "Нажмите %d, чтобы продолжить"));
    }

    @Test
    @DisplayName("A source with no placeholders accepts any prose")
    void proseIsUnconstrained() {
        assertNull(TranslationFormatCheck.check(NONE, "любой текст без подстановок"));
    }
}
