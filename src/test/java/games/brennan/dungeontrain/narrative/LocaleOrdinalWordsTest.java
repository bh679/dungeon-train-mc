package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import games.brennan.dungeontrain.narrative.LocaleOrdinalWords.Gender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Locks down {@link LocaleOrdinalWords}. The regression it guards is specific: the death-screen epitaph
 * used to build its ordinal by appending a suffix to a spelled-out CARDINAL, so English read "the twoth
 * to fall" and Russian read "два-й" — "two-th" — where it must read "второй".
 */
final class LocaleOrdinalWordsTest {

    private static void check(String loc, long n, Gender g, String expected) {
        assertEquals(expected, LocaleOrdinalWords.forLocale(loc, n, g), loc + " " + n + " " + g);
    }

    @Test @DisplayName("English spells 1-20, then falls to st/nd/rd/th digits")
    void english() {
        check("en_us", 1, Gender.MASCULINE, "first");
        check("en_us", 2, Gender.MASCULINE, "second");   // never "twoth"
        check("en_us", 3, Gender.MASCULINE, "third");
        check("en_us", 5, Gender.MASCULINE, "fifth");
        check("en_us", 12, Gender.MASCULINE, "twelfth");
        check("en_us", 20, Gender.MASCULINE, "twentieth");
        check("en_us", 21, Gender.MASCULINE, "21st");
        check("en_us", 22, Gender.MASCULINE, "22nd");
        check("en_us", 23, Gender.MASCULINE, "23rd");
        check("en_us", 24, Gender.MASCULINE, "24th");
        check("en_us", 111, Gender.MASCULINE, "111th");  // the teens exception survives the hundreds
        check("en_us", 112, Gender.MASCULINE, "112th");
        check("en_us", 101, Gender.MASCULINE, "101st");
    }

    @Test @DisplayName("Russian spells 1-20 and agrees in gender")
    void russian() {
        check("ru_ru", 1, Gender.MASCULINE, "первый");
        check("ru_ru", 2, Gender.MASCULINE, "второй");   // the bug: was rendering "два-й"
        check("ru_ru", 2, Gender.FEMININE, "вторая");
        check("ru_ru", 2, Gender.NEUTER, "второе");
        check("ru_ru", 3, Gender.MASCULINE, "третий");
        check("ru_ru", 3, Gender.FEMININE, "третья");
        check("ru_ru", 5, Gender.MASCULINE, "пятый");
        check("ru_ru", 20, Gender.MASCULINE, "двадцатый");
        // Above the spelled range Russian writes the digit with the same adjective ending.
        check("ru_ru", 37, Gender.MASCULINE, "37-й");
        check("ru_ru", 37, Gender.FEMININE, "37-я");
        check("ru_ru", 37, Gender.NEUTER, "37-е");
    }

    @Test @DisplayName("Every other shipped locale gets its own written digit ordinal")
    void digitForms() {
        check("de_de", 5, Gender.MASCULINE, "5.");
        check("pl_pl", 5, Gender.MASCULINE, "5.");
        check("fr_fr", 5, Gender.MASCULINE, "5e");
        check("nl_nl", 5, Gender.MASCULINE, "5e");
        check("it_it", 5, Gender.MASCULINE, "5°");
        check("es_es", 5, Gender.MASCULINE, "5.º");
        check("es_es", 5, Gender.FEMININE, "5.ª");
        check("pt_br", 5, Gender.MASCULINE, "5.º");
        check("ja_jp", 5, Gender.MASCULINE, "第5");
        check("zh_cn", 5, Gender.MASCULINE, "第5");
        check("zh_tw", 5, Gender.MASCULINE, "第5");
        check("ko_kr", 5, Gender.MASCULINE, "5번째");
        check("th_th", 5, Gender.MASCULINE, "ที่ 5");
        check("vi_vn", 5, Gender.MASCULINE, "thứ 5");
        check("id_id", 5, Gender.MASCULINE, "ke-5");
        check("ms_my", 5, Gender.MASCULINE, "ke-5");
        check("fil_ph", 5, Gender.MASCULINE, "ika-5");
    }

    @Test @DisplayName("Absent, unknown and non-positive inputs return something printable, never null")
    void edges() {
        check(null, 2, Gender.MASCULINE, "second");
        check("", 2, Gender.MASCULINE, "second");
        check("xx_yy", 22, Gender.MASCULINE, "22nd");
        check("en_us", 0, Gender.MASCULINE, "0");
        check("ru_ru", 0, Gender.MASCULINE, "0");
        check("ru_ru", -3, Gender.MASCULINE, "-3");
        for (String loc : new String[] {"en_us", "ru_ru", "ja_jp", "xx"}) {
            for (long n : new long[] {0, 1, 2, 20, 21, 1000}) {
                assertFalse(LocaleOrdinalWords.forLocale(loc, n, Gender.MASCULINE).isEmpty());
            }
        }
    }
}
