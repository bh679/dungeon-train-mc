package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link PoliticalFilterMirror#defaultForLocale} — what the server assumes about a player whose
 * client has not (yet) synced a Political Filter answer.
 *
 * <p>This is the fail-safe, and it is the opposite of {@code NetworkConsentMirror}'s plain
 * fail-closed: {@code false} here means "show them everything", which is the wrong assumption for
 * exactly the players the filter exists for. It must agree with what the client resolves {@code UNSET}
 * to ({@code PoliticalFilterPrefs.isEnabled}), or a player's first moments in a world would behave
 * differently from every moment after the login packet lands.</p>
 */
final class PoliticalFilterMirrorTest {

    @Test
    @DisplayName("an unsynced Chinese-language client defaults to FILTERED, across the whole zh family")
    void chineseLocalesDefaultToFiltered() {
        assertTrue(PoliticalFilterMirror.defaultForLocale("zh_cn"));
        assertTrue(PoliticalFilterMirror.defaultForLocale("zh_tw"));
        assertTrue(PoliticalFilterMirror.defaultForLocale("zh_hk"));
        assertTrue(PoliticalFilterMirror.defaultForLocale("lzh"), "Classical Chinese collapses to zh");
    }

    @Test
    @DisplayName("every other client defaults to UNFILTERED — nobody is filtered by a question they were never asked")
    void otherLocalesDefaultToUnfiltered() {
        assertFalse(PoliticalFilterMirror.defaultForLocale("en_us"));
        assertFalse(PoliticalFilterMirror.defaultForLocale("ja_jp"));
        assertFalse(PoliticalFilterMirror.defaultForLocale("ko_kr"));
    }

    @Test
    @DisplayName("an unknown or missing locale defaults to UNFILTERED rather than throwing")
    void unknownLocaleIsUnfiltered() {
        // A blank locale is what WorldInfoReporter.clientLanguage returns when it cannot read one.
        // LanguageFamily would collapse it to the English default, so this must not read as Chinese.
        assertFalse(PoliticalFilterMirror.defaultForLocale(""));
        assertFalse(PoliticalFilterMirror.defaultForLocale("   "));
        assertFalse(PoliticalFilterMirror.defaultForLocale(null));
        assertFalse(PoliticalFilterMirror.defaultForLocale("not-a-locale"));
    }
}
