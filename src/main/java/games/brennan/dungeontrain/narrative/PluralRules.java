package games.brennan.dungeontrain.narrative;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;

/**
 * Which grammatical-number form a language wants for a given count — the localization seam that lets a
 * translator whose language declines numerals say so, without any burden on the languages that don't.
 *
 * <p>Dungeon Train used to pick a count-dependent lang key with {@code n == 1 ? ".one" : ".other"}: the
 * English two-form rule, applied to every language DT ships. Russian has three forms and so was forced
 * into a wrong answer — {@code 5 раза} where it must read {@code 5 раз} — with no key to put the third
 * form in. This class replaces that ternary. Given the reader's locale and the count, it names a
 * <a href="https://cldr.unicode.org/index/cldr-spec/plural-rules">CLDR</a> plural category, and the
 * count-dependent key gains that category as its suffix:</p>
 *
 * <pre>
 *   chat.dungeontrain.familiar_book.times.one     ru: "%s раз"    (1, 21, 101)
 *   chat.dungeontrain.familiar_book.times.few     ru: "%s раза"   (2, 3, 4, 22)
 *   chat.dungeontrain.familiar_book.times.many    ru: "%s раз"    (5, 11, 50)
 * </pre>
 *
 * <p><b>Nothing changes for a language without the distinction.</b> The category a locale can produce is
 * fixed by its rule family, so English still only ever asks for {@code .one}/{@code .other} and a German
 * translator writes exactly the two lines they write today; Japanese asks only for {@code .other} and
 * writes one. Only ru and pl carry {@code .few}/{@code .many} keys at all —
 * {@code scripts/localization/validate-locale.py} derives each locale's legal key set from the same rule
 * table, so a locale is required to have exactly the forms its own grammar can reach and no others.</p>
 *
 * <p><b>The table is data, not code.</b> {@code assets/dungeontrain/plural_rules.json} maps locale to
 * rule family and is read by this class and by the validator, so Java and CI cannot drift. Adding a
 * locale is a one-line data edit; adding a <em>family</em> needs both implementations.</p>
 *
 * <p><b>Whose locale.</b> These lines are built on the server and rendered on the client, so the category
 * must be chosen against the RECIPIENT's language, not the server's — callers pass
 * {@code WorldInfoReporter.clientLanguage(player)}. An unknown locale (empty string, a language DT ships
 * no rule for) falls back to {@code one_other}, which is what the code did for everyone before.</p>
 */
public final class PluralRules {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RESOURCE = "/assets/dungeontrain/plural_rules.json";

    /** CLDR plural categories DT uses. Ordinary lang-key suffixes — no other value is ever produced. */
    public static final String ONE = "one";
    public static final String FEW = "few";
    public static final String MANY = "many";
    public static final String OTHER = "other";

    /**
     * The rule families, each naming the categories its languages can reach. Implemented identically in
     * {@code validate-locale.py}; a new constant here needs a new branch there.
     */
    private enum Family {
        /** {@code one} at exactly 1. English, German, Dutch, Spanish, Italian. */
        ONE_OTHER,
        /** {@code one} at 0 and 1 — French and Portuguese count zero as singular. */
        ZERO_ONE_OTHER,
        /** Russian and its relatives: one / few (2-4) / many, all modulo the teens exception. */
        EAST_SLAVIC,
        /** Polish: like {@link #EAST_SLAVIC} but only a bare 1 is {@code one} (21 is {@code many}). */
        POLISH,
        /** No inflection for number at all — Japanese, Korean, Chinese, Thai, Vietnamese, Malayic, Filipino. */
        SINGLE
    }

    /** Locale prefix → family, loaded once from {@link #RESOURCE}. Never null; empty if the file is lost. */
    private static final Map<String, Family> FAMILIES = load();

    private PluralRules() {}

    /**
     * The plural category {@code localeCode} wants for {@code n} — one of {@link #ONE}, {@link #FEW},
     * {@link #MANY}, {@link #OTHER}, ready to use as a lang-key suffix.
     *
     * <p>Negative counts are read by magnitude: a language's rule for "minus five" is its rule for five.
     * An unknown or empty locale gets the English {@code one_other} rule.</p>
     */
    public static String category(String localeCode, long n) {
        long abs = Math.abs(n);
        return switch (familyOf(localeCode)) {
            case SINGLE -> OTHER;
            case ONE_OTHER -> abs == 1L ? ONE : OTHER;
            case ZERO_ONE_OTHER -> abs == 0L || abs == 1L ? ONE : OTHER;
            case EAST_SLAVIC -> slavic(abs, abs % 10L == 1L && abs % 100L != 11L);
            case POLISH -> slavic(abs, abs == 1L);
        };
    }

    /**
     * The shared Slavic tail: {@code few} for a 2-4 final digit outside the teens, {@code many} otherwise.
     * {@code isOne} is the only thing East Slavic and Polish disagree on (Russian 21 is {@code one},
     * Polish 21 is {@code many}), so it is decided by the caller.
     */
    private static String slavic(long abs, boolean isOne) {
        if (isOne) return ONE;
        long lastDigit = abs % 10L, lastTwo = abs % 100L;
        boolean few = lastDigit >= 2L && lastDigit <= 4L && (lastTwo < 12L || lastTwo > 14L);
        return few ? FEW : MANY;
    }

    /**
     * The count-dependent line {@code baseKey} in the form {@code localeCode} wants for {@code n}, with
     * {@code n} as its single argument — {@code clause(locale, "…familiar_book.times", 5)} resolves the
     * key {@code …familiar_book.times.many}. The one call every count-dependent site makes.
     */
    public static MutableComponent clause(String localeCode, String baseKey, long n) {
        return Component.translatable(baseKey + "." + category(localeCode, n), n);
    }

    /**
     * Every category {@code localeCode}'s rules can produce. Not used at runtime — it is the seam a test
     * asserts the lang files against, and the shape the validator reimplements.
     */
    public static java.util.List<String> categoriesOf(String localeCode) {
        return switch (familyOf(localeCode)) {
            case SINGLE -> java.util.List.of(OTHER);
            case ONE_OTHER, ZERO_ONE_OTHER -> java.util.List.of(ONE, OTHER);
            case EAST_SLAVIC, POLISH -> java.util.List.of(ONE, FEW, MANY);
        };
    }

    /**
     * The family for {@code localeCode}, keyed on its two-letter prefix (so {@code ru_ru} and a bare
     * {@code ru} agree). Defaults to {@link Family#ONE_OTHER} for anything unlisted — the pre-existing
     * behaviour, and the safe one: those locales' lang files carry {@code .one}/{@code .other}.
     */
    private static Family familyOf(String localeCode) {
        if (localeCode == null || localeCode.isEmpty()) return Family.ONE_OTHER;
        String lower = localeCode.toLowerCase(Locale.ROOT);
        String base = lower.length() >= 2 ? lower.substring(0, 2) : lower;
        return FAMILIES.getOrDefault(base, Family.ONE_OTHER);
    }

    /**
     * Read the locale→family table. A missing or malformed file is logged and leaves the table empty,
     * which degrades every locale to {@code one_other} — wrong for Russian, but exactly the behaviour
     * that shipped before this class existed, so no line ever fails to render.
     */
    private static Map<String, Family> load() {
        Map<String, Family> out = new java.util.HashMap<>();
        try (var in = PluralRules.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("[DungeonTrain] plural rules resource {} missing; every locale falls back to"
                        + " one/other", RESOURCE);
                return Map.of();
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : root.getAsJsonObject("locales").entrySet()) {
                String name = e.getValue().getAsString().toUpperCase(Locale.ROOT);
                try {
                    out.put(e.getKey().toLowerCase(Locale.ROOT), Family.valueOf(name));
                } catch (IllegalArgumentException bad) {
                    LOGGER.warn("[DungeonTrain] plural rules: locale '{}' names unknown family '{}'",
                            e.getKey(), e.getValue().getAsString());
                }
            }
            return Map.copyOf(out);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] plural rules failed to load; every locale falls back to one/other:"
                    + " {}", t.toString());
            return Map.of();
        }
    }
}
