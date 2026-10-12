package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The shape of the feedback header tag. */
class SurveyTagTest {

    @Test
    @DisplayName("dot, version, language, mods, games, times answered — in that order")
    void fullTag() {
        assertEquals("🟠 DT 0.1093.0 · Russian · Mods: 87 · Games 12 · (3)",
                SurveyTag.format(VersionFreshness.MONTH, "0.1093.0", "ru_ru", 87, 12L, 3));
    }

    @Test
    @DisplayName("an unknown answer count leaves the bracket off")
    void unknownCountIsSkipped() {
        assertEquals("🟠 DT 0.1093.0 · Russian · Mods: 87 · Games 12",
                SurveyTag.format(VersionFreshness.MONTH, "0.1093.0", "ru_ru", 87, 12L, 0));
        assertEquals("🟠 DT 0.1093.0 · Russian · Mods: 87 · Games 12",
                SurveyTag.format(VersionFreshness.MONTH, "0.1093.0", "ru_ru", 87, 12L, -1));
    }

    @Test
    @DisplayName("an unknown language is left out, not shown blank")
    void blankLanguageIsSkipped() {
        assertEquals("🟢 DT 0.1104.0 · Mods: 60 · Games 0 · (1)",
                SurveyTag.format(VersionFreshness.LATEST, "0.1104.0", "", 60, 0L, 1));
        assertEquals("🟢 DT 0.1104.0 · Mods: 60 · Games 0 · (1)",
                SurveyTag.format(VersionFreshness.LATEST, "0.1104.0", null, 60, 0L, 1));
    }

    @Test
    @DisplayName("a client language that is not a locale code never reaches Discord")
    void hostileLanguageIsDropped() {
        assertEquals("🔴 DT 0.900.0 · Mods: 3 · Games 5 · (2)",
                SurveyTag.format(VersionFreshness.OLDER, "0.900.0", "@everyone", 3, 5L, 2));
        assertEquals("🔴 DT 0.900.0 · Mods: 3 · Games 5 · (2)",
                SurveyTag.format(VersionFreshness.OLDER, "0.900.0", "[x](http://a.b)", 3, 5L, 2));
    }

    @Test
    @DisplayName("a locale code reads as the language's English name, without its region")
    void languageIsNamedInEnglish() {
        assertEquals("English", SurveyTag.languageName("en_us"));
        assertEquals("English", SurveyTag.languageName("en_gb"));
        assertEquals("Russian", SurveyTag.languageName("ru_ru"));
        assertEquals("Chinese", SurveyTag.languageName("zh_cn"));
        assertEquals("Chinese", SurveyTag.languageName("zh_tw"));
        assertEquals("Portuguese", SurveyTag.languageName("pt_br"));
        assertEquals("German", SurveyTag.languageName("de_de"));
        assertEquals("Japanese", SurveyTag.languageName("ja_jp"));
        assertEquals("Filipino", SurveyTag.languageName("fil_ph"));
    }

    @Test
    @DisplayName("a padded or oddly cased code still resolves")
    void languageCodeIsNormalised() {
        assertEquals("Chinese", SurveyTag.languageName(" zh_cn "));
        assertEquals("Russian", SurveyTag.languageName("RU_RU"));
    }

    @Test
    @DisplayName("a code with no English name is shown as it came")
    void unnamedCodeFallsBackToTheCode() {
        assertEquals("qqq_xx", SurveyTag.languageName("qqq_xx"));
    }

    @Test
    @DisplayName("the game's joke languages are not mistaken for the real ones whose codes they borrow")
    void jokeLanguagesAreNamed() {
        // ISO's "lol" is Mongo, and "en_pt" would otherwise read as plain English.
        assertEquals("LOLCAT", SurveyTag.languageName("lol_us"));
        assertEquals("Pirate Speak", SurveyTag.languageName("en_pt"));
        assertEquals("Upside-down English", SurveyTag.languageName("en_ud"));
        assertEquals("Anglish", SurveyTag.languageName("enp"));
        assertEquals("Shakespearean English", SurveyTag.languageName("enws"));
    }
}
