package games.brennan.dungeontrain.client.localization;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped country table itself, read from the resource tree.
 *
 * <p>Every entry is a promise the JVM has to be able to keep: an ISO 3166 code the CLDR tables
 * carry a name for. A typo would not throw -- {@code getDisplayCountry} hands back the bare code --
 * so a wrong entry would ship as a language findable only by typing "GQ", which nobody does.</p>
 */
class LanguageCountriesResourceTest {

    private static final Path FILE = RepoPaths.root()
        .resolve("src/main/resources/assets/dungeontrain/language_search/countries.json");

    private static JsonObject table() throws Exception {
        return JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8))
            .getAsJsonObject();
    }

    @Test
    @DisplayName("every country code is a real one the JVM can name")
    void codesAreRealAndNameable() throws Exception {
        Set<String> iso = new HashSet<>(Arrays.asList(Locale.getISOCountries()));
        List<String> bad = new ArrayList<>();
        for (var entry : table().entrySet()) {
            for (JsonElement el : entry.getValue().getAsJsonArray()) {
                String code = el.getAsString();
                if (!iso.contains(code)) {
                    bad.add(entry.getKey() + " -> " + code + " (not ISO 3166)");
                    continue;
                }
                Locale region = new Locale.Builder().setRegion(code).build();
                // English is the fallback half of every haystack, so it is the one that must work.
                if (region.getDisplayCountry(Locale.ENGLISH).equals(code)) {
                    bad.add(entry.getKey() + " -> " + code + " (no CLDR name)");
                }
            }
        }
        assertTrue(bad.isEmpty(), "unusable country codes: " + bad);
    }

    @Test
    @DisplayName("the locales the mod itself ships are all findable by country")
    void shippedLocalesAreCovered() throws Exception {
        JsonObject table = table();
        List<String> missing = new ArrayList<>();
        for (String locale : new String[] {
            "de_de", "es_es", "es_mx", "fil_ph", "fr_fr", "id_id", "it_it", "ja_jp", "ko_kr",
            "ms_my", "nl_nl", "pl_pl", "pt_br", "pt_pt", "ro_ro", "ru_ru", "th_th", "vi_vn",
            "zh_cn", "zh_tw"
        }) {
            if (!table.has(locale) || table.getAsJsonArray(locale).isEmpty()) {
                missing.add(locale);
            }
        }
        assertTrue(missing.isEmpty(), "shipped locales with no countries: " + missing);
    }

    @Test
    @DisplayName("no entry is empty — a locale with nothing to say should be left out instead")
    void noEmptyEntries() throws Exception {
        List<String> empty = new ArrayList<>();
        for (var entry : table().entrySet()) {
            if (entry.getValue().getAsJsonArray().isEmpty()) {
                empty.add(entry.getKey());
            }
        }
        assertTrue(empty.isEmpty(), "empty entries: " + empty);
    }

    @Test
    @DisplayName("the constructed languages are absent, not listed with a country")
    void constructedLanguagesAreAbsent() throws Exception {
        JsonObject table = table();
        for (String made : new String[] {"tok", "jbo_en", "tlh_aa", "qya_aa", "lol_us", "en_ud"}) {
            assertFalse(table.has(made), made + " is not spoken anywhere");
        }
    }
}
