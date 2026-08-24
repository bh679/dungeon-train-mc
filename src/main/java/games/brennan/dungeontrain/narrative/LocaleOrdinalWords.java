package games.brennan.dungeontrain.narrative;

import java.util.Locale;

/**
 * Ordinal numbers ("second", "второй") for the death-ledger prose, the counterpart to
 * {@link LocaleNumberWords}'s cardinals.
 *
 * <p><b>Why this exists.</b> The epitaph template used to build its ordinal by gluing a suffix onto a
 * spelled-out CARDINAL — {@code "the {deaths}th to fall"} where {@code {deaths}} resolves to a word, so
 * English read "the twoth to fall" and the Russian mirror {@code "{deaths}-й"} read "два-й" ("two-th")
 * rather than "второй". A suffix is not a rule: English needs {@code first/second/third}, and Russian
 * ordinals are adjectives that agree in gender with the noun they attach to. So the ordinal is formed
 * here, per locale, and the template just names {@code {deaths_nth}}.</p>
 *
 * <p><b>What is spelled and what is not.</b> 1–20 are spelled in the languages that have a template
 * calling for one — English and Russian. Everything else, and every value above 20, uses that language's
 * DIGIT ordinal, which is the ordinary written form in all of them and needs no guesswork: "37th",
 * "37-й", "37.", "37e", "第37", "ke-37". A spelled ordinal above twenty reads worse than the digit in
 * every language here ("the one hundred and seventh to fall"), so the cutoff is a style choice, not
 * only a coverage one. A translator who wants {@code {deaths_nth}} in their own {@code death_lore}
 * gets the correct digit form today; spelling their 1–20 is a table added below, nothing more.</p>
 *
 * <p>Locale matching follows {@link LocaleNumberWords#forLocale}: the two-letter prefix, with {@code fi}
 * meaning Filipino ({@code fil_ph}) rather than Finnish, which Dungeon Train does not ship.</p>
 */
public final class LocaleOrdinalWords {

    /** The grammatical gender the ordinal must agree with. Ignored by languages that don't inflect. */
    public enum Gender { MASCULINE, FEMININE, NEUTER }

    /** Above this, every language uses its digit ordinal — see the class note. */
    private static final int MAX_SPELLED = 20;

    private LocaleOrdinalWords() {}

    private static final String[] EN = {
            "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
            "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth",
            "eighteenth", "nineteenth", "twentieth"
    };
    private static final String[] RU_M = {
            "первый", "второй", "третий", "четвёртый", "пятый", "шестой", "седьмой", "восьмой", "девятый",
            "десятый", "одиннадцатый", "двенадцатый", "тринадцатый", "четырнадцатый", "пятнадцатый",
            "шестнадцатый", "семнадцатый", "восемнадцатый", "девятнадцатый", "двадцатый"
    };
    private static final String[] RU_F = {
            "первая", "вторая", "третья", "четвёртая", "пятая", "шестая", "седьмая", "восьмая", "девятая",
            "десятая", "одиннадцатая", "двенадцатая", "тринадцатая", "четырнадцатая", "пятнадцатая",
            "шестнадцатая", "семнадцатая", "восемнадцатая", "девятнадцатая", "двадцатая"
    };
    private static final String[] RU_N = {
            "первое", "второе", "третье", "четвёртое", "пятое", "шестое", "седьмое", "восьмое", "девятое",
            "десятое", "одиннадцатое", "двенадцатое", "тринадцатое", "четырнадцатое", "пятнадцатое",
            "шестнадцатое", "семнадцатое", "восемнадцатое", "девятнадцатое", "двадцатое"
    };

    /**
     * {@code n} as an ordinal in {@code localeCode}'s language, agreeing with {@code gender} where the
     * language inflects. Never null and never empty: an unknown locale falls back to the English digit
     * form, and a non-positive {@code n} (no such thing as a zeroth death) returns its plain digits.
     */
    public static String forLocale(String localeCode, long n, Gender gender) {
        String base = base(localeCode);
        if (n <= 0L) return Long.toString(n);
        if (n <= MAX_SPELLED) {
            String spelled = spelled(base, (int) n, gender);
            if (spelled != null) return spelled;
        }
        return digits(base, n, gender);
    }

    /** The spelled ordinal for {@code n} in 1..{@link #MAX_SPELLED}, or null where no table exists. */
    private static String spelled(String base, int n, Gender gender) {
        return switch (base) {
            case "en" -> EN[n - 1];
            case "ru" -> switch (gender) {
                case FEMININE -> RU_F[n - 1];
                case NEUTER -> RU_N[n - 1];
                case MASCULINE -> RU_M[n - 1];
            };
            default -> null;
        };
    }

    /**
     * The language's written digit ordinal — the form a newspaper would print. This is the whole answer
     * above twenty and for every language without a spelled table, so it is the part that has to be right
     * in all of them.
     */
    private static String digits(String base, long n, Gender gender) {
        String d = Long.toString(n);
        return switch (base) {
            // Russian writes the adjective ending after a hyphen, and it still agrees: 37-й / 37-я / 37-е.
            case "ru" -> d + switch (gender) {
                case FEMININE -> "-я";
                case NEUTER -> "-е";
                case MASCULINE -> "-й";
            };
            case "de", "pl" -> d + ".";
            case "nl" -> d + "e";
            case "fr" -> d + "e";
            case "it" -> d + "°";
            case "es", "pt" -> d + (gender == Gender.FEMININE ? ".ª" : ".º");
            case "ja", "zh" -> "第" + d;
            case "ko" -> d + "번째";
            case "th" -> "ที่ " + d;
            case "vi" -> "thứ " + d;
            case "id", "ms" -> "ke-" + d;
            case "fi" -> "ika-" + d;              // Filipino (fil_ph), per LocaleNumberWords' key
            // English, and anything unlisted: 1st / 2nd / 3rd / 4th, with the teens all taking "th".
            default -> d + englishSuffix(n);
        };
    }

    /** "st" / "nd" / "rd" / "th" — the 11th/12th/13th exception is why this isn't a lookup on n%10. */
    private static String englishSuffix(long n) {
        long lastTwo = n % 100L;
        if (lastTwo >= 11L && lastTwo <= 13L) return "th";
        return switch ((int) (n % 10L)) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    /** The two-letter locale prefix, matching {@link LocaleNumberWords}'s own matching. */
    private static String base(String localeCode) {
        if (localeCode == null || localeCode.isEmpty()) return "en";
        String lower = localeCode.toLowerCase(Locale.ROOT);
        return lower.length() >= 2 ? lower.substring(0, 2) : lower;
    }
}
