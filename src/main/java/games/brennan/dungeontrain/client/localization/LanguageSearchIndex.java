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
 * Every name a language can be found by that vanilla's own row does not already carry: what the
 * language is called in other languages, and the countries that speak it.
 *
 * <p>Vanilla writes each row in the language itself -- {@code Nederlands (Nederland)},
 * {@code 日本語 (日本)} -- which is right for somebody who can already read that row and useless
 * for anybody else. Someone hunting for their language knows it as "Dutch", or "Niederländisch",
 * or by the country they are in; they may not know Minecraft files Swiss German under
 * {@code de_ch}, or which of India's languages the list carries. This is what makes "French",
 * "Französisch", "Germany" and "Allemagne" all work.</p>
 *
 * <p>Only the ISO country codes are data here. Every <b>name</b> -- of a language and of a country
 * alike -- comes from the JDK's own CLDR tables at runtime, in the player's language. That is the
 * whole reason this is one small file and not several thousand translated strings: a hand-kept
 * table of language and country names in nineteen languages would be a large, drifting duplicate
 * of something already sitting in the JVM, and it would have gone in the lang files, inflating
 * every locale's key count and putting a few hundred name lists in front of translators as work to
 * do.</p>
 *
 * <p>Matched in the player's language <em>and</em> in English, because English place names are what
 * people type into search boxes regardless of what they are reading the game in.</p>
 */
public final class LanguageSearchIndex {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation FILE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "language_search/countries.json");

    /** Separates names in a haystack, so a needle cannot match across two of them. */
    private static final char SEPARATOR = '\n';

    /**
     * Locales whose code CLDR knows as an unrelated real language, so asking it for the name gives
     * a confidently wrong one.
     *
     * <p>{@code lol} is ISO 639 for Mongo, spoken in the Congo; Minecraft means LOLCAT. Everything
     * else in the list either resolves correctly -- CLDR carries Toki Pona, Lojban, Klingon,
     * Bavarian and Literary Chinese -- or has no name at all and simply contributes nothing.</p>
     */
    private static final java.util.Set<String> MISNAMED_BY_CLDR = java.util.Set.of("lol_us");

    /** Minecraft locale code to the ISO 3166 countries that speak it. Empty until a reload runs. */
    private static Map<String, List<String>> countries = Map.of();

    /** The UI language {@link #haystacks} was built for, so a language switch rebuilds it. */
    private static String haystackLang = "";
    /** Minecraft locale code to every name it can be found by, lowercased and newline-joined. */
    private static Map<String, String> haystacks = new LinkedHashMap<>();

    private LanguageSearchIndex() {}

    /**
     * Whether {@code localeCode} is known by any name containing {@code needle} -- its language's
     * name, or that of a country where it is spoken.
     *
     * @param needle   already lowercased and trimmed by the caller
     * @param uiLocale the Minecraft locale the player is reading in, e.g. {@code fr_fr}
     */
    public static synchronized boolean matches(String localeCode, String needle, String uiLocale) {
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
        Locale reading = readingLocale(uiLocale);
        StringBuilder out = new StringBuilder();

        Locale target = MISNAMED_BY_CLDR.contains(localeCode) ? null : toJavaLocale(localeCode);
        if (target != null) {
            append(out, target.getDisplayLanguage(reading));
            // English as well as their own language, here and below: search boxes get English
            // names typed into them by people who are not reading anything else in English.
            append(out, target.getDisplayLanguage(Locale.ENGLISH));
        }

        for (String country : countries.getOrDefault(localeCode, List.of())) {
            Locale region = new Locale.Builder().setRegion(country).build();
            append(out, region.getDisplayCountry(reading));
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
     * A Minecraft locale code as a Java one -- {@code pt_br} becomes {@code pt-BR} -- or null when
     * it is not one.
     *
     * <p>Null rather than a fallback, because the two callers want opposite things from a code
     * they cannot parse: the language being NAMED must contribute nothing (a wrong name is worse
     * than none), while the language being READ IN can perfectly well fall back to English, which
     * is what the other half of every haystack is anyway. {@link #readingLocale} is that half.</p>
     */
    static Locale toJavaLocale(String localeCode) {
        if (localeCode == null || localeCode.isBlank()) {
            return null;
        }
        String[] parts = localeCode.split("_");
        try {
            Locale.Builder builder = new Locale.Builder().setLanguage(parts[0]);
            if (parts.length > 1 && parts[1].length() == 2) {
                builder.setRegion(parts[1]);
            }
            return builder.build();
        } catch (Exception e) {
            return null; // a pack's own invention, or a code Java will not accept as one
        }
    }

    /** The locale to render names IN, which always has an answer. */
    static Locale readingLocale(String localeCode) {
        Locale parsed = toJavaLocale(localeCode);
        return parsed == null ? Locale.ENGLISH : parsed;
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
