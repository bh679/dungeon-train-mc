package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.brennan.dungeontrain.RepoPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Locks down {@link PluralRules} — the rule that decides which grammatical-number form of a
 * count-dependent lang key a reader's language wants.
 *
 * <p>The Russian cases are the reason the class exists, so they are spelled out at the boundaries that
 * actually catch a wrong rule: the teens (11 is {@code many}, not {@code one}) and the second decade
 * (21 is {@code one} again, 22 is {@code few}). A rule written as a bare {@code n <= 4} passes 1..5 and
 * fails every one of those.</p>
 */
final class PluralRulesTest {

    private static void check(String loc, long n, String expected) {
        assertEquals(expected, PluralRules.category(loc, n), loc + " " + n);
    }

    @Test @DisplayName("Russian: one / few / many, including the teens and the second decade")
    void russian() {
        check("ru_ru", 1, PluralRules.ONE);       // один раз
        check("ru_ru", 2, PluralRules.FEW);       // два раза
        check("ru_ru", 3, PluralRules.FEW);
        check("ru_ru", 4, PluralRules.FEW);
        check("ru_ru", 5, PluralRules.MANY);      // пять раз
        check("ru_ru", 10, PluralRules.MANY);
        check("ru_ru", 11, PluralRules.MANY);     // одиннадцать раз — NOT one, despite ending in 1
        check("ru_ru", 12, PluralRules.MANY);
        check("ru_ru", 14, PluralRules.MANY);
        check("ru_ru", 20, PluralRules.MANY);
        check("ru_ru", 21, PluralRules.ONE);      // двадцать один раз
        check("ru_ru", 22, PluralRules.FEW);
        check("ru_ru", 25, PluralRules.MANY);
        check("ru_ru", 50, PluralRules.MANY);     // пятьдесят раз
        check("ru_ru", 101, PluralRules.ONE);
        check("ru_ru", 111, PluralRules.MANY);
        check("ru_ru", 0, PluralRules.MANY);      // ноль раз
    }

    @Test @DisplayName("Polish: like Russian, except a bare 1 is the only 'one'")
    void polish() {
        check("pl_pl", 1, PluralRules.ONE);
        check("pl_pl", 2, PluralRules.FEW);
        check("pl_pl", 5, PluralRules.MANY);
        check("pl_pl", 12, PluralRules.MANY);
        check("pl_pl", 22, PluralRules.FEW);
        check("pl_pl", 21, PluralRules.MANY);     // where Russian says one, Polish says many
        check("pl_pl", 101, PluralRules.MANY);
    }

    @Test @DisplayName("Romanian: few covers zero and every count ending 2-19, other starts at 20")
    void romanian() {
        check("ro_ro", 1, PluralRules.ONE);
        check("ro_ro", 0, PluralRules.FEW);       // zero vagoane reads like the few form
        check("ro_ro", 2, PluralRules.FEW);
        check("ro_ro", 19, PluralRules.FEW);
        check("ro_ro", 20, PluralRules.OTHER);    // 20 de vagoane — the "de" range
        check("ro_ro", 21, PluralRules.OTHER);
        check("ro_ro", 100, PluralRules.OTHER);
        check("ro_ro", 101, PluralRules.OTHER);   // ends in 01, but 1 alone is the only "one"
        check("ro_ro", 102, PluralRules.FEW);     // …102 ends in 02, back to few
        check("ro_ro", 119, PluralRules.FEW);
        check("ro_ro", 120, PluralRules.OTHER);
        check("ro_ro", -3, PluralRules.FEW);      // magnitude, like every other family
    }

    @Test @DisplayName("English and its family: one at exactly 1")
    void english() {
        check("en_us", 1, PluralRules.ONE);
        check("en_us", 0, PluralRules.OTHER);
        check("en_us", 2, PluralRules.OTHER);
        check("en_us", 21, PluralRules.OTHER);
        check("de_de", 1, PluralRules.ONE);
        check("nl_nl", 2, PluralRules.OTHER);
        check("es_es", 1, PluralRules.ONE);
        check("it_it", 3, PluralRules.OTHER);
    }

    @Test @DisplayName("French and Portuguese count zero as singular")
    void zeroIsSingular() {
        check("fr_fr", 0, PluralRules.ONE);
        check("fr_fr", 1, PluralRules.ONE);
        check("fr_fr", 2, PluralRules.OTHER);
        check("pt_br", 0, PluralRules.ONE);
        check("pt_pt", 5, PluralRules.OTHER);
    }

    @Test @DisplayName("Languages without number inflection only ever ask for .other")
    void singleForm() {
        for (String loc : List.of("ja_jp", "ko_kr", "zh_cn", "zh_tw", "th_th", "vi_vn", "id_id",
                "ms_my", "fil_ph")) {
            for (long n : new long[] {0, 1, 2, 5, 11, 21, 100}) {
                check(loc, n, PluralRules.OTHER);
            }
        }
    }

    @Test @DisplayName("An unknown or absent locale falls back to the English rule, never throws")
    void unknownLocale() {
        check("", 1, PluralRules.ONE);
        check("", 5, PluralRules.OTHER);
        check(null, 1, PluralRules.ONE);
        check("xx_yy", 1, PluralRules.ONE);
        check("q", 2, PluralRules.OTHER);
    }

    @Test @DisplayName("A negative count is read by magnitude")
    void negative() {
        check("ru_ru", -1, PluralRules.ONE);
        check("ru_ru", -5, PluralRules.MANY);
        check("en_us", -1, PluralRules.ONE);
    }

    @Test @DisplayName("clause() appends the category to the base key and passes the count")
    void clauseBuildsTheKey() {
        String base = "chat.dungeontrain.familiar_book.times";
        assertEquals(base + ".few",
                ((TranslatableContents) PluralRules.clause("ru_ru", base, 2).getContents()).getKey());
        assertEquals(base + ".many",
                ((TranslatableContents) PluralRules.clause("ru_ru", base, 5).getContents()).getKey());
        assertEquals(base + ".other",
                ((TranslatableContents) PluralRules.clause("ja_jp", base, 5).getContents()).getKey());
        assertEquals(5L,
                ((TranslatableContents) PluralRules.clause("ja_jp", base, 5).getContents()).getArgs()[0]);
    }

    /**
     * The data table and the code must agree on the set of families — a locale naming a family the
     * switch has no branch for would silently degrade that whole language to the English rule.
     */
    @Test @DisplayName("Every family named in plural_rules.json is one the code implements")
    void everyFamilyIsImplemented() throws IOException {
        Path file = RepoPaths.root().resolve("src/main/resources/assets/dungeontrain/plural_rules.json");
        assertTrue(Files.isRegularFile(file), "plural_rules.json missing at " + file);
        JsonObject root = JsonParser.parseString(
                Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        Set<String> known = Set.of("one_other", "zero_one_other", "east_slavic", "polish", "single");
        JsonObject locales = root.getAsJsonObject("locales");
        assertTrue(locales.size() >= 17, "expected every shipped locale prefix, got " + locales.size());
        for (var e : locales.entrySet()) {
            assertTrue(known.contains(e.getValue().getAsString()),
                    "locale '" + e.getKey() + "' names unimplemented family " + e.getValue());
        }
        // The categories a locale can reach must match what the lang files were built to carry.
        assertEquals(List.of("one", "few", "many"), PluralRules.categoriesOf("ru_ru"));
        assertEquals(List.of("one", "few", "many"), PluralRules.categoriesOf("pl_pl"));
        assertEquals(List.of("other"), PluralRules.categoriesOf("ja_jp"));
        assertEquals(List.of("one", "other"), PluralRules.categoriesOf("en_us"));
    }

    /**
     * End-to-end on the shipped Russian file: the category the rule picks, resolved against
     * {@code ru_ru.json}, is the text a Russian player reads. This is the case in the bug report —
     * "5 раз", not "5 раза" — asserted against the real translation rather than a stub.
     */
    @Test @DisplayName("Russian counts resolve to the right shipped text")
    void russianResolvesToShippedText() throws IOException {
        JsonObject ru = JsonParser.parseString(Files.readString(
                RepoPaths.root().resolve("src/main/resources/assets/dungeontrain/lang/ru_ru.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        String times = "chat.dungeontrain.familiar_book.times";
        assertEquals("%s раз", value(ru, times, 1));     // один раз
        assertEquals("%s раза", value(ru, times, 2));    // два раза
        assertEquals("%s раза", value(ru, times, 4));
        assertEquals("%s раз", value(ru, times, 5));     // пять раз
        assertEquals("%s раз", value(ru, times, 10));    // десять раз
        assertEquals("%s раз", value(ru, times, 11));    // the teens are 'many', not 'one'
        assertEquals("%s раза", value(ru, times, 22));
        assertEquals("%s раз", value(ru, times, 50));    // пятьдесят раз
        String minute = "chat.dungeontrain.time.minute";
        assertEquals("%s минуту", value(ru, minute, 21));
        assertEquals("%s минуты", value(ru, minute, 2));
        assertEquals("%s минут", value(ru, minute, 5));
    }

    private static String value(JsonObject lang, String base, long n) {
        String key = base + "." + PluralRules.category("ru_ru", n);
        assertTrue(lang.has(key), "ru_ru.json is missing " + key);
        return lang.get(key).getAsString();
    }

    /**
     * Every shipped lang file must carry exactly the forms its own rules can reach — no more, no fewer.
     * A missing form renders as the raw key in front of a player; a form the rules can never select is
     * a line a translator wrote that nobody will ever read. This is the same invariant
     * {@code validate-locale.py} enforces in CI, asserted here against the live rule table so the two
     * cannot drift apart silently.
     */
    @Test @DisplayName("Each locale's lang file carries exactly the forms its rules can reach")
    void langFilesCarryExactlyTheReachableForms() throws IOException {
        Path dir = RepoPaths.root().resolve("src/main/resources/assets/dungeontrain/lang");
        JsonObject en = JsonParser.parseString(Files.readString(
                dir.resolve("en_us.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        // A plural family is a base carrying BOTH .one and .other in the English source.
        Set<String> bases = new java.util.HashSet<>();
        for (String key : en.keySet()) {
            if (key.endsWith(".one") && en.has(key.substring(0, key.length() - 4) + ".other")) {
                bases.add(key.substring(0, key.length() - 4));
            }
        }
        assertTrue(bases.size() >= 12, "expected the count-dependent key families, found " + bases);
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String loc = file.getFileName().toString().replace(".json", "");
                JsonObject lang = JsonParser.parseString(
                        Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                List<String> cats = PluralRules.categoriesOf(loc);
                for (String base : bases) {
                    for (String cat : List.of("one", "few", "many", "other")) {
                        assertEquals(cats.contains(cat), lang.has(base + "." + cat),
                                loc + " " + base + "." + cat + ": reachable=" + cats.contains(cat)
                                        + " present=" + lang.has(base + "." + cat));
                    }
                }
            }
        }
    }
}
