package games.brennan.dungeontrain.client.localization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding a language by the country that speaks it. Pure — the table is injected, and the names
 * come from the JVM's own CLDR data, so this needs no ResourceManager and no running client.
 *
 * <p>These double as the check that the JDK really carries country names for the languages the mod
 * ships. If a JVM ever shipped without them, {@code getDisplayCountry} returns the bare code and
 * the localized half of every assertion here fails rather than silently degrading in-game.</p>
 */
class LanguageCountryIndexTest {

    private static void table() {
        LanguageCountryIndex.setCountriesForTest(Map.of(
            "de_de", List.of("DE", "AT", "CH", "LI", "LU", "BE"),
            "fr_fr", List.of("FR", "BE", "CH", "CA"),
            "it_it", List.of("IT", "CH", "SM", "VA"),
            "pt_br", List.of("BR"),
            "es_es", List.of("ES", "GQ"),
            "es_ar", List.of("AR"),
            "tok", List.of()));
    }

    @Test
    @DisplayName("an English place name finds the language whatever the player is reading in")
    void englishAlwaysWorks() {
        table();
        for (String ui : new String[] {"en_us", "fr_fr", "ja_jp", "th_th", "zh_tw"}) {
            assertTrue(LanguageCountryIndex.matchesCountry("de_de", "germany", ui), ui);
            assertTrue(LanguageCountryIndex.matchesCountry("pt_br", "brazil", ui), ui);
        }
    }

    @Test
    @DisplayName("and so does the place name in the player's own language")
    void localizedNamesWork() {
        table();
        assertTrue(LanguageCountryIndex.matchesCountry("de_de", "allemagne", "fr_fr"));
        assertTrue(LanguageCountryIndex.matchesCountry("de_de", "alemania", "es_es"));
        assertTrue(LanguageCountryIndex.matchesCountry("de_de", "germania", "it_it"));
        assertTrue(LanguageCountryIndex.matchesCountry("pt_br", "brasilien", "de_de"));
        // A French player is not offered the German name, and should not be.
        assertFalse(LanguageCountryIndex.matchesCountry("pt_br", "brasilien", "fr_fr"));
    }

    @Test
    @DisplayName("one country reaches every language spoken there")
    void countryReachesEveryLanguage() {
        table();
        // The case the whole feature exists for: a Swiss player knows the country, not which of
        // its three languages the list files them under.
        assertTrue(LanguageCountryIndex.matchesCountry("de_de", "switzerland", "en_us"));
        assertTrue(LanguageCountryIndex.matchesCountry("fr_fr", "switzerland", "en_us"));
        assertTrue(LanguageCountryIndex.matchesCountry("it_it", "switzerland", "en_us"));
        assertFalse(LanguageCountryIndex.matchesCountry("pt_br", "switzerland", "en_us"));
    }

    @Test
    @DisplayName("a country that speaks a language without lending it its name still finds it")
    void borrowedCountriesCount() {
        table();
        // Vanilla's own row says "Español (España)", so Argentina is reachable only through this.
        assertTrue(LanguageCountryIndex.matchesCountry("es_ar", "argentina", "en_us"));
        assertTrue(LanguageCountryIndex.matchesCountry("es_es", "equatorial guinea", "en_us"));
    }

    @Test
    @DisplayName("a constructed language has no country, and matches nothing")
    void constructedLanguagesMatchNothing() {
        table();
        assertFalse(LanguageCountryIndex.matchesCountry("tok", "germany", "en_us"));
        assertFalse(LanguageCountryIndex.matchesCountry("unknown_xx", "germany", "en_us"));
    }

    @Test
    @DisplayName("an empty needle never matches — a blank query is the list's job, not this one's")
    void emptyNeedleDoesNotMatch() {
        table();
        assertFalse(LanguageCountryIndex.matchesCountry("de_de", "", "en_us"));
        assertFalse(LanguageCountryIndex.matchesCountry(null, "germany", "en_us"));
    }

    @Test
    @DisplayName("a name cannot be matched across two countries' worth of text")
    void namesDoNotRunTogether() {
        table();
        // "de_de" carries Germany then Austria; without a separator "germanyaustria" would hit.
        assertFalse(LanguageCountryIndex.matchesCountry("de_de", "germanyaustria", "en_us"));
    }

    @Test
    @DisplayName("Minecraft locale codes become Java ones, and nonsense falls back to English")
    void localeParsing() {
        assertEquals(Locale.forLanguageTag("pt-BR"), LanguageCountryIndex.toJavaLocale("pt_br"));
        assertEquals(Locale.forLanguageTag("zh-TW"), LanguageCountryIndex.toJavaLocale("zh_tw"));
        assertEquals(Locale.forLanguageTag("fil-PH"), LanguageCountryIndex.toJavaLocale("fil_ph"),
            "a three-letter language code is still a language code");
        assertEquals(Locale.ENGLISH, LanguageCountryIndex.toJavaLocale(null));
        assertEquals(Locale.ENGLISH, LanguageCountryIndex.toJavaLocale("  "));
    }
}
