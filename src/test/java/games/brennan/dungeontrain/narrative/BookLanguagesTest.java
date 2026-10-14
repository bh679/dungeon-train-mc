package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.narrative.BookLanguages.FamilyTotal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class BookLanguagesTest {

    @Test
    @DisplayName("parses families, largest first; malformed → empty")
    void parse() {
        List<FamilyTotal> got = BookLanguages.parse(
                "{\"ok\":true,\"families\":[{\"family\":\"uk\",\"total\":46},{\"family\":\"en\",\"total\":900},{\"total\":3}]}");
        assertEquals(List.of(new FamilyTotal("en", 900), new FamilyTotal("uk", 46)), got);
        assertEquals(List.of(), BookLanguages.parse("{\"ok\":false}"));
        assertEquals(List.of(), BookLanguages.parse("[]"));
    }

    @Test
    @DisplayName("non-English player → English; English player → biggest other family")
    void fallbackFamily() {
        List<FamilyTotal> totals = List.of(new FamilyTotal("en", 900), new FamilyTotal("de", 80), new FamilyTotal("uk", 46));
        assertEquals("en", BookLanguages.fallbackFamilyFor("uk_ua", totals));
        assertEquals("en", BookLanguages.fallbackFamilyFor("uk_ua", List.of()), "English needs no list");
        assertEquals("de", BookLanguages.fallbackFamilyFor("en_gb", totals));
        assertNull(BookLanguages.fallbackFamilyFor("en_us", List.of()), "unknown yet → no fallback");
        assertNull(BookLanguages.fallbackFamilyFor("en_us", List.of(new FamilyTotal("en", 5))));
    }
}
