package games.brennan.dungeontrain.client.localization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding a language by the country that speaks it. Pure — the table is injected, and the names
 * come from the JVM's own CLDR data, so this needs no ResourceManager and no running client.
 *
 * <p>These double as the check that the JDK really carries country names for the languages the mod
 * ships. If a JVM ever shipped without them, {@code getDisplayCountry} returns the bare code and
 * the localized half of every assertion here fails rather than silently degrading in-game.</p>
 */
class LanguageSearchIndexTest {

    private static void table() {
        LanguageSearchIndex.setCountriesForTest(Map.of(
            "de_de", List.of("DE", "AT", "CH", "LI", "LU", "BE"),
            "fr_fr", List.of("FR", "BE", "CH", "CA"),
            "it_it", List.of("IT", "CH", "SM", "VA"),
            "pt_br", List.of("BR"),
            "es_es", List.of("ES", "GQ"),
            "es_ar", List.of("AR"),
            "tok", List.of(),
            "lol_us", List.of()));
    }

    @Test
    @DisplayName("an English place name finds the language whatever the player is reading in")
    void englishAlwaysWorks() {
        table();
        for (String ui : new String[] {"en_us", "fr_fr", "ja_jp", "th_th", "zh_tw"}) {
            assertTrue(LanguageSearchIndex.matches("de_de", "germany", ui), ui);
            assertTrue(LanguageSearchIndex.matches("pt_br", "brazil", ui), ui);
        }
    }

    @Test
    @DisplayName("and so does the place name in the player's own language")
    void localizedNamesWork() {
        table();
        assertTrue(LanguageSearchIndex.matches("de_de", "allemagne", "fr_fr"));
        assertTrue(LanguageSearchIndex.matches("de_de", "alemania", "es_es"));
        assertTrue(LanguageSearchIndex.matches("de_de", "germania", "it_it"));
        assertTrue(LanguageSearchIndex.matches("pt_br", "brasilien", "de_de"));
        // A French player is not offered the German name, and should not be.
        assertFalse(LanguageSearchIndex.matches("pt_br", "brasilien", "fr_fr"));
    }

    @Test
    @DisplayName("one country reaches every language spoken there")
    void countryReachesEveryLanguage() {
        table();
        // The case the whole feature exists for: a Swiss player knows the country, not which of
        // its three languages the list files them under.
        assertTrue(LanguageSearchIndex.matches("de_de", "switzerland", "en_us"));
        assertTrue(LanguageSearchIndex.matches("fr_fr", "switzerland", "en_us"));
        assertTrue(LanguageSearchIndex.matches("it_it", "switzerland", "en_us"));
        assertFalse(LanguageSearchIndex.matches("pt_br", "switzerland", "en_us"));
    }

    @Test
    @DisplayName("a country that speaks a language without lending it its name still finds it")
    void borrowedCountriesCount() {
        table();
        // Vanilla's own row says "Español (España)", so Argentina is reachable only through this.
        assertTrue(LanguageSearchIndex.matches("es_ar", "argentina", "en_us"));
        assertTrue(LanguageSearchIndex.matches("es_es", "equatorial guinea", "en_us"));
    }

    @Test
    @DisplayName("a constructed language has no country, and matches nothing")
    void constructedLanguagesMatchNothing() {
        table();
        assertFalse(LanguageSearchIndex.matches("tok", "germany", "en_us"));
        assertFalse(LanguageSearchIndex.matches("unknown_xx", "germany", "en_us"));
    }

    @Test
    @DisplayName("an empty needle never matches — a blank query is the list's job, not this one's")
    void emptyNeedleDoesNotMatch() {
        table();
        assertFalse(LanguageSearchIndex.matches("de_de", "", "en_us"));
        assertFalse(LanguageSearchIndex.matches(null, "germany", "en_us"));
    }

    @Test
    @DisplayName("a name cannot be matched across two countries' worth of text")
    void namesDoNotRunTogether() {
        table();
        // "de_de" carries Germany then Austria; without a separator "germanyaustria" would hit.
        assertFalse(LanguageSearchIndex.matches("de_de", "germanyaustria", "en_us"));
    }

    @Test
    @DisplayName("Minecraft locale codes become Java ones; an unusable one becomes null")
    void localeParsing() {
        assertEquals(Locale.forLanguageTag("pt-BR"), LanguageSearchIndex.toJavaLocale("pt_br"));
        assertEquals(Locale.forLanguageTag("zh-TW"), LanguageSearchIndex.toJavaLocale("zh_tw"));
        assertEquals(Locale.forLanguageTag("fil-PH"), LanguageSearchIndex.toJavaLocale("fil_ph"),
            "a three-letter language code is still a language code");
        assertNull(LanguageSearchIndex.toJavaLocale(null));
        assertNull(LanguageSearchIndex.toJavaLocale("  "));
        // The language being READ IN always has an answer, even when the code is unusable.
        assertEquals(Locale.ENGLISH, LanguageSearchIndex.readingLocale(null));
        assertEquals(Locale.ENGLISH, LanguageSearchIndex.readingLocale("  "));
    }

    // ---- the language's own name, in every language ----------------------------------------------

    @Test
    @DisplayName("a language is found by its English name whatever the player is reading in")
    void englishLanguageNames() {
        table();
        // Vanilla's row says "Français (France)" — "French" appears nowhere on it.
        for (String ui : new String[] {"en_us", "de_de", "ja_jp", "th_th"}) {
            assertTrue(LanguageSearchIndex.matches("fr_fr", "french", ui), ui);
            assertTrue(LanguageSearchIndex.matches("nl_nl", "dutch", ui), ui);
        }
    }

    @Test
    @DisplayName("and by its name in the player's own language")
    void localizedLanguageNames() {
        table();
        assertTrue(LanguageSearchIndex.matches("fr_fr", "franz\u00f6sisch", "de_de"));
        assertTrue(LanguageSearchIndex.matches("nl_nl", "niederl\u00e4ndisch", "de_de"));
        assertTrue(LanguageSearchIndex.matches("de_de", "allemand", "fr_fr"));
        assertFalse(LanguageSearchIndex.matches("fr_fr", "franz\u00f6sisch", "en_us"),
            "an English player is not offered the German name, and should not be");
    }

    @Test
    @DisplayName("a language name works with no country entry at all")
    void languageNameWithoutCountries() {
        table();
        // tok has an empty country list; CLDR still knows the language.
        assertTrue(LanguageSearchIndex.matches("tok", "toki pona", "en_us"));
    }

    @Test
    @DisplayName("LOLCAT is not the Mongo language, whatever ISO 639 says about the code")
    void misnamedCodesContributeNothing() {
        table();
        // `lol` really is ISO 639 for Mongo, spoken in the Congo. Asking CLDR would put a
        // confidently wrong language name on Minecraft's joke locale.
        assertFalse(LanguageSearchIndex.matches("lol_us", "mongo", "en_us"));
    }
}
