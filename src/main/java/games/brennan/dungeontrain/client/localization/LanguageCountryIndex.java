package games.brennan.dungeontrain.client.localization;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which countries speak each of Minecraft's languages, so the language search finds one by the
 * place rather than only by the word.
 *
 * <p>Somebody looking for their language knows what country they are in. They may not know that
 * Minecraft files it under "Nederlands", that Swiss German is {@code de_ch}, or which of India's
 * languages the list actually carries. Vanilla does put the country on the row -- but in the
 * language's own words ({@code Deutschland}, {@code Brasil}), which only helps somebody who could
 * already read the row. This is what makes "Germany" and "Allemagne" work too.</p>
 *
 * <p>Only the ISO country codes are data here; the <b>names</b> come from the JDK's own CLDR
 * tables at runtime, in the player's language. That is the whole reason this is one small file and
 * not several thousand translated strings -- a hand-kept table of country names in nineteen
 * languages would be a large, drifting duplicate of something already sitting in the JVM, and it
 * would have gone in the lang files, inflating every locale's key count and putting a hundred and
 * thirty-odd country lists in front of translators as work to do.</p>
 *
 * <p>Matched in the player's language <em>and</em> in English, because English place names are what
 * people type into search boxes regardless of what they are reading the game in.</p>
 */
public final class LanguageCountryIndex {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation FILE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "language_search/countries.json");

    /** Separates names in a haystack, so a needle cannot match across two of them. */
    private static final char SEPARATOR = '\n';

    /** Minecraft locale code to the ISO 3166 countries that speak it. Empty until a reload runs. */
    private static Map<String, List<String>> countries = Map.of();

    /** The UI language {@link #haystacks} was built for, so a language switch rebuilds it. */
    private static String haystackLang = "";
    /** Minecraft locale code to every country name it can be found by, lowercased and joined. */
    private static Map<String, String> haystacks = new LinkedHashMap<>();

    private LanguageCountryIndex() {}

    /**
     * Whether {@code localeCode} is spoken somewhere whose name contains {@code needle}.
     *
     * @param needle   already lowercased and trimmed by the caller
     * @param uiLocale the Minecraft locale the player is reading in, e.g. {@code fr_fr}
     */
    public static synchronized boolean matchesCountry(String localeCode, String needle,
                                                      String uiLocale) {
        if (localeCode == null || needle == null || needle.isEmpty()) {
            return false;
        }
        return haystackFor(localeCode, uiLocale).contains(needle);
    }

    /**
     * Every name {@code localeCode} can be found by, built once per language and cached -- the
     * search runs this against every row on every keystroke, and resolving CLDR names for a
     * hundred and thirty languages that often would be felt.
     */
    private static String haystackFor(String localeCode, String uiLocale) {
        String lang = uiLocale == null ? "" : uiLocale;
        if (!lang.equals(haystackLang)) {
            haystackLang = lang;
            haystacks = new LinkedHashMap<>();
        }
        return haystacks.computeIfAbsent(localeCode.toLowerCase(Locale.ROOT),
            code -> buildHaystack(code, lang));
    }

    static String buildHaystack(String localeCode, String uiLocale) {
        List<String> codes = countries.get(localeCode);
        if (codes == null || codes.isEmpty()) {
            return "";
        }
        Locale reading = toJavaLocale(uiLocale);
        StringBuilder out = new StringBuilder();
        for (String country : codes) {
            Locale region = new Locale.Builder().setRegion(country).build();
            append(out, region.getDisplayCountry(reading));
            // English as well as their own language: search boxes get English place names typed
            // into them by people who are not reading anything else in English.
            append(out, region.getDisplayCountry(Locale.ENGLISH));
        }
        return out.toString();
    }

    private static void append(StringBuilder out, String name) {
        if (name != null && !name.isEmpty()) {
            out.append(name.toLowerCase(Locale.ROOT)).append(SEPARATOR);
        }
    }

    /**
     * A Minecraft locale code as a Java one -- {@code pt_br} becomes {@code pt-BR}. Anything
     * unparseable falls back to English, which is what the second half of every haystack is anyway.
     */
    static Locale toJavaLocale(String localeCode) {
        if (localeCode == null || localeCode.isBlank()) {
            return Locale.ENGLISH;
        }
        String[] parts = localeCode.split("_");
        try {
            Locale.Builder builder = new Locale.Builder().setLanguage(parts[0]);
            if (parts.length > 1 && parts[1].length() == 2) {
                builder.setRegion(parts[1]);
            }
            return builder.build();
        } catch (Exception e) {
            return Locale.ENGLISH; // a constructed locale like lol_us, or a pack's own invention
        }
    }

    /** Reload listener entry point -- see {@code LocalizationCreditsClientLoaders}. */
    public static synchronized void load(ResourceManager resourceManager) {
        Map<String, List<String>> next = new LinkedHashMap<>();
        // The whole stack, lowest first, so a resource pack can extend or correct the table.
        for (Resource resource : resourceManager.getResourceStack(FILE)) {
            try (var in = resource.open()) {
                JsonElement root =
                    JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                if (root.isJsonObject()) {
                    readInto(root.getAsJsonObject(), next);
                }
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] Language search: could not read {} -- {}",
                    FILE, e.toString());
            }
        }
        countries = Map.copyOf(next);
        haystackLang = "";
        haystacks = new LinkedHashMap<>();
    }

    /** Replace the table directly -- for tests, which have no ResourceManager. */
    static synchronized void setCountriesForTest(Map<String, List<String>> table) {
        countries = Map.copyOf(table);
        haystackLang = "";
        haystacks = new LinkedHashMap<>();
    }

    private static void readInto(JsonObject obj, Map<String, List<String>> out) {
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            if (!entry.getValue().isJsonArray()) {
                continue;
            }
            JsonArray arr = entry.getValue().getAsJsonArray();
            List<String> codes = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                    codes.add(el.getAsString().toUpperCase(Locale.ROOT));
                }
            }
            out.put(entry.getKey().toLowerCase(Locale.ROOT), List.copyOf(codes));
        }
    }
}
