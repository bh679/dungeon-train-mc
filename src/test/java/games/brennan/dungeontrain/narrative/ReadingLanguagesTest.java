package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class ReadingLanguagesTest {

    @Test
    @DisplayName("parse: locale + weight, clamped; missing weight = 1; junk = null")
    void parse() {
        assertEquals(new ReadingLanguages.Entry("uk_ua", 3), ReadingLanguages.parse("uk_ua:3"));
        assertEquals(new ReadingLanguages.Entry("uk_ua", 5), ReadingLanguages.parse(" UK_UA : 9"));
        assertEquals(new ReadingLanguages.Entry("es_mx", 1), ReadingLanguages.parse("es_mx:0"));
        assertEquals(new ReadingLanguages.Entry("fr_fr", 1), ReadingLanguages.parse("fr_fr"));
        assertNull(ReadingLanguages.parse("fr_fr:lots"));
        assertNull(ReadingLanguages.parse(":2"));
        assertNull(ReadingLanguages.parse(null));
    }

    @Test
    @DisplayName("clean: one per family, never the game language's, at most MAX_EXTRA")
    void clean() {
        List<String> raw = new ArrayList<>(List.of("en_gb:2", "uk_ua:2", "es_es:1", "es_mx:4", "bad:x"));
        for (int i = 0; i < 20; i++) raw.add("l" + i + "_xx:1");
        List<ReadingLanguages.Entry> out = ReadingLanguages.parseAll(raw, "en_us");
        assertEquals(ReadingLanguages.MAX_EXTRA, out.size());
        assertEquals(new ReadingLanguages.Entry("uk_ua", 2), out.get(0));
        assertEquals(new ReadingLanguages.Entry("es_es", 1), out.get(1));
        assertEquals("l0", out.get(2).family());
    }

    @Test
    @DisplayName("families: game language first with its weight, then the extras")
    void families() {
        Map<String, Integer> f = ReadingLanguages.families("uk_ua", 3,
                List.of(new ReadingLanguages.Entry("en_us", 1), new ReadingLanguages.Entry("uk_ua", 5)));
        assertEquals(List.of("uk", "en"), List.copyOf(f.keySet()));
        assertEquals(3, f.get("uk"));
        assertEquals(1, f.get("en"));
    }

    @Test
    @DisplayName("bookLocale: only a reading language is honoured, by its stored locale")
    void bookLocale() {
        List<ReadingLanguages.Entry> extras = List.of(new ReadingLanguages.Entry("uk_ua", 1));
        assertEquals("uk_ua", ReadingLanguages.bookLocale("uk_ua", "en_us", extras));
        assertEquals("en_us", ReadingLanguages.bookLocale("en_gb", "en_us", extras));
        assertEquals("en_us", ReadingLanguages.bookLocale("fr_fr", "en_us", extras));
        assertEquals("en_us", ReadingLanguages.bookLocale(null, "en_us", extras));
        assertEquals("en_us", ReadingLanguages.bookLocale("uk_ua", "en_us", List.of()));
    }
}
