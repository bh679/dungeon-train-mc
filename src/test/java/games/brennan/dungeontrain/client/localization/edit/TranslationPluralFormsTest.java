package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which plural forms the editor offers a translator. Pure — no Minecraft bootstrap.
 *
 * <p>The bug this pins down cost real work: the catalog offered every language English's own
 * plural family, so Russian translators were shown 27 {@code .other} rows their grammar never
 * selects, translated them, and had them approved on the relay — where they can never be
 * imported, because writing them would add a key the validator rejects.</p>
 */
class TranslationPluralFormsTest {

    /** A plural family, an ordinary key that merely ends in ".one", and a plain key. */
    private static final Set<String> ENGLISH = new LinkedHashSet<>(List.of(
        "chat.dungeontrain.time.day.one",
        "chat.dungeontrain.time.day.other",
        "gui.dungeontrain.pick.one",
        "gui.dungeontrain.title"));

    @Test
    @DisplayName("Russian is offered one/few/many and never .other")
    void russianGetsItsOwnForms() {
        Set<String> keys = TranslationPluralForms.project(ENGLISH, "ru_ru");
        assertTrue(keys.contains("chat.dungeontrain.time.day.few"));
        assertTrue(keys.contains("chat.dungeontrain.time.day.many"));
        assertTrue(keys.contains("chat.dungeontrain.time.day.one"));
        assertFalse(keys.contains("chat.dungeontrain.time.day.other"),
            "ru_ru can never select .other — offering it is what produced 27 dead approvals");
    }

    @Test
    @DisplayName("Polish matches Russian's category set")
    void polishGetsThreeForms() {
        Set<String> keys = TranslationPluralForms.project(ENGLISH, "pl_pl");
        assertTrue(keys.contains("chat.dungeontrain.time.day.many"));
        assertFalse(keys.contains("chat.dungeontrain.time.day.other"));
    }

    @Test
    @DisplayName("Romanian is offered one/few/other")
    void romanianGetsItsOwnForms() {
        Set<String> keys = TranslationPluralForms.project(ENGLISH, "ro_ro");
        assertTrue(keys.contains("chat.dungeontrain.time.day.few"));
        assertTrue(keys.contains("chat.dungeontrain.time.day.other"));
        assertFalse(keys.contains("chat.dungeontrain.time.day.many"));
    }

    @Test
    @DisplayName("Languages that do not inflect for number are offered .other alone")
    void singleFormLanguagesGetOneRow() {
        for (String locale : List.of("ja_jp", "zh_cn", "ko_kr", "th_th", "vi_vn")) {
            Set<String> keys = TranslationPluralForms.project(ENGLISH, locale);
            assertTrue(keys.contains("chat.dungeontrain.time.day.other"), locale);
            assertFalse(keys.contains("chat.dungeontrain.time.day.one"),
                locale + " has no singular its rules can reach");
        }
    }

    @Test
    @DisplayName("A language sharing English's rules sees the English set unchanged")
    void oneOtherLanguagesAreUntouched() {
        assertEquals(ENGLISH, TranslationPluralForms.project(ENGLISH, "de_de"));
    }

    @Test
    @DisplayName("A key that merely ends in .one is not a family and is never rewritten")
    void loneOneKeyIsLeftAlone() {
        Set<String> keys = TranslationPluralForms.project(ENGLISH, "ja_jp");
        assertTrue(keys.contains("gui.dungeontrain.pick.one"),
            "no .other twin means this is a label, not a plural form");
        assertTrue(keys.contains("gui.dungeontrain.title"));
    }

    @Test
    @DisplayName("English's order is kept — the sidecars and review CSVs depend on it")
    void orderFollowsEnglish() {
        assertEquals(List.of("chat.dungeontrain.time.day.one",
                             "chat.dungeontrain.time.day.few",
                             "chat.dungeontrain.time.day.many",
                             "gui.dungeontrain.pick.one",
                             "gui.dungeontrain.title"),
            List.copyOf(TranslationPluralForms.project(ENGLISH, "ru_ru")));
    }

    @Test
    @DisplayName("A key set with no plural family at all passes straight through")
    void noFamiliesIsANoOp() {
        Set<String> plain = new LinkedHashSet<>(List.of("a.key", "b.key"));
        assertEquals(plain, TranslationPluralForms.project(plain, "ru_ru"));
    }
}
