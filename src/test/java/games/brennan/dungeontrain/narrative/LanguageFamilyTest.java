package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LanguageFamily} must agree with the relay's {@code langfamily.js} — the relay family-scopes the
 * book pool with those rules, and the loot selector re-derives "is this MY language?" with these. A drift
 * between the two would bucket a book the relay considers in-family as foreign. These cases mirror the
 * relay module's documented behaviour case-for-case.
 */
final class LanguageFamilyTest {

    @Test
    @DisplayName("English variants (incl. joke locales) all collapse to 'en'")
    void englishVariants() {
        assertEquals("en", LanguageFamily.of("en_us"));
        assertEquals("en", LanguageFamily.of("en_gb"));
        assertEquals("en", LanguageFamily.of("en_au"));
        assertEquals("en", LanguageFamily.of("en_pt"), "Pirate is en_-prefixed");
        assertEquals("en", LanguageFamily.of("en_ud"), "Upside-down is en_-prefixed");
        assertEquals("en", LanguageFamily.of("lol"), "LOLCAT override");
        assertEquals("en", LanguageFamily.of("lol_us"), "LOLCAT full-code override");
        assertEquals("en", LanguageFamily.of("enws"), "Shakespearean override");
    }

    @Test
    @DisplayName("Chinese variants (incl. Classical) collapse to 'zh'")
    void chineseVariants() {
        assertEquals("zh", LanguageFamily.of("zh_cn"));
        assertEquals("zh", LanguageFamily.of("zh_tw"));
        assertEquals("zh", LanguageFamily.of("zh_hk"));
        assertEquals("zh", LanguageFamily.of("lzh"), "Classical Chinese override");
    }

    @Test
    @DisplayName("everything else falls back to its ISO subtag")
    void isoSubtagFallback() {
        assertEquals("pt", LanguageFamily.of("pt_br"));
        assertEquals("de", LanguageFamily.of("de_de"));
        assertEquals("es", LanguageFamily.of("es_es"));
        assertEquals("es", LanguageFamily.of("es_mx"));
        assertEquals("ja", LanguageFamily.of("ja_jp"));
        assertEquals("fr", LanguageFamily.of("fr_fr"));
    }

    @Test
    @DisplayName("blank / null / unusable → the English default family")
    void defaultsToEnglish() {
        assertEquals("en", LanguageFamily.of(null), "untagged legacy book");
        assertEquals("en", LanguageFamily.of(""));
        assertEquals("en", LanguageFamily.of("   "), "charset-clean leaves nothing");
        assertEquals("en", LanguageFamily.of("!!!"), "charset-clean leaves nothing");
    }

    @Test
    @DisplayName("case and stray charset are normalised before matching")
    void normalisation() {
        assertEquals("en", LanguageFamily.of("EN_US"));
        assertEquals("zh", LanguageFamily.of("ZH_cn"));
        // Hyphenated BCP-47 is NOT a Minecraft locale shape; the charset clean strips the hyphen leaving
        // no separator, so it degrades to the whole string. Asserted to pin the behaviour we SHARE with
        // the relay (langfamily.js does the identical strip-then-split), not because it is desirable.
        assertEquals("eses", LanguageFamily.of("es-ES"));
    }

    @Test
    @DisplayName("sameFamily: variants match, distinct languages don't, untagged reads as English")
    void sameFamily() {
        assertTrue(LanguageFamily.sameFamily("en_us", "en_gb"));
        assertTrue(LanguageFamily.sameFamily("zh_cn", "lzh"));
        assertTrue(LanguageFamily.sameFamily(null, "en_us"), "untagged book is English to an English player");
        assertFalse(LanguageFamily.sameFamily("es_es", "en_us"));
        assertFalse(LanguageFamily.sameFamily("ja_jp", "fr_fr"));
    }
}
