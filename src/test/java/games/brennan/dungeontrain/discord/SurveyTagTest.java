package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The shape of the feedback header tag. */
class SurveyTagTest {

    @Test
    @DisplayName("dot, version, language, mods, games — in that order")
    void fullTag() {
        assertEquals("🟠 DT 0.1093.0 · ru_ru · Mods: 87 · Games 12",
                SurveyTag.format(VersionFreshness.MONTH, "0.1093.0", "ru_ru", 87, 12L));
    }

    @Test
    @DisplayName("an unknown language is left out, not shown blank")
    void blankLanguageIsSkipped() {
        assertEquals("🟢 DT 0.1104.0 · Mods: 60 · Games 0",
                SurveyTag.format(VersionFreshness.LATEST, "0.1104.0", "", 60, 0L));
        assertEquals("🟢 DT 0.1104.0 · Mods: 60 · Games 0",
                SurveyTag.format(VersionFreshness.LATEST, "0.1104.0", null, 60, 0L));
    }

    @Test
    @DisplayName("a client language that is not a locale code never reaches Discord")
    void hostileLanguageIsDropped() {
        assertEquals("🔴 DT 0.900.0 · Mods: 3 · Games 5",
                SurveyTag.format(VersionFreshness.OLDER, "0.900.0", "@everyone", 3, 5L));
        assertEquals("🔴 DT 0.900.0 · Mods: 3 · Games 5",
                SurveyTag.format(VersionFreshness.OLDER, "0.900.0", "[x](http://a.b)", 3, 5L));
    }

    @Test
    @DisplayName("a padded locale code is trimmed")
    void languageIsTrimmed() {
        assertEquals("🟡 DT 0.1100.0 · zh_cn · Mods: 1 · Games 1",
                SurveyTag.format(VersionFreshness.WEEK, "0.1100.0", " zh_cn ", 1, 1L));
    }
}
