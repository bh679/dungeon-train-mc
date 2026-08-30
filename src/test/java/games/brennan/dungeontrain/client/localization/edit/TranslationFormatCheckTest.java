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

    // ---- books: {braces}, not printf --------------------------------------------------------

    /** The English epitaph the ru_ru import nearly broke. */
    private static final String EPITAPH = "the {deaths_nth} to fall. perhaps not the last.";

    @Test
    @DisplayName("A book keeping its figure is fine wherever the grammar puts it")
    void bookKeepingItsFigureIsFine() {
        assertNull(TranslationFormatCheck.checkBook(EPITAPH, "{deaths_nth}, кто пал."));
        assertNull(TranslationFormatCheck.checkBook(EPITAPH, "павший {deaths_nth}."));
    }

    @Test
    @DisplayName("A book that drops its figure names the one it lost")
    void bookDroppingItsFigureIsBlocked() {
        TranslationFormatCheck.Problem problem =
                TranslationFormatCheck.checkBook(EPITAPH, "кто пал. и похоже, не последний.");
        assertNotNull(problem);
        assertEquals(TranslationFormatCheck.MISSING_VARS, problem.messageKey());
        assertEquals("{deaths_nth}", problem.tokens());
    }

    @Test
    @DisplayName("The Russian ordinal regression: {deaths_nth} swapped for a suffixed {deaths}")
    void theOrdinalRegressionIsBlocked() {
        TranslationFormatCheck.Problem problem =
                TranslationFormatCheck.checkBook(EPITAPH, "{deaths}й, кто пал.");
        assertNotNull(problem);
        assertEquals(TranslationFormatCheck.MISSING_VARS, problem.messageKey());
    }

    @Test
    @DisplayName("A book that invents a figure is blocked too — nothing would fill it")
    void bookInventingAFigureIsBlocked() {
        TranslationFormatCheck.Problem problem =
                TranslationFormatCheck.checkBook(EPITAPH, "{deaths_nth} из {mobs}");
        assertNotNull(problem);
        assertEquals(TranslationFormatCheck.EXTRA_VARS, problem.messageKey());
        assertEquals("{mobs}", problem.tokens());
    }

    @Test
    @DisplayName("Naming the figure once where English names it twice is the translator's call")
    void sayingTheFigureFewerTimesIsAllowed() {
        // ru_ru's live rendering: "столько же раз" — "as many times" — for the second {deaths}.
        String english = "{deaths} times the dark took you, and {deaths} times you boarded again.";
        assertNull(TranslationFormatCheck.checkBook(
                english, "{deaths} раз тьма забирала тебя, и столько же раз ты садился вновь."));
    }

    @Test
    @DisplayName("A percent in a story is just a percent — books never reach the format parser")
    void aPercentInAStoryIsNotAnError() {
        assertNull(TranslationFormatCheck.checkBook("100% of the line", "100% линии"));
    }

    @Test
    @DisplayName("Blank means 'no override' for a book too")
    void blankBookReverts() {
        assertNull(TranslationFormatCheck.checkBook(EPITAPH, ""));
        assertNull(TranslationFormatCheck.checkBook(EPITAPH, null));
    }

    @Test
    @DisplayName("Prose braces that are not figures are left alone")
    void proseBracesAreNotFigures() {
        assertNull(TranslationFormatCheck.checkBook("a { and a }", "ein { und ein }"));
    }
}
