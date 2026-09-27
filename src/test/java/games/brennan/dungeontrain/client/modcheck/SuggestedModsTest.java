package games.brennan.dungeontrain.client.modcheck;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The "already suggested" marker file's parse/serialise round trip. Pure. */
class SuggestedModsTest {

    @Test
    @DisplayName("toJson round-trips through parse, lowercased and validated")
    void roundTrips() {
        Set<String> ids = Set.of("coolmod", "another_mod");
        assertEquals(ids, SuggestedMods.parse(SuggestedMods.toJson(ids)));
        assertEquals(Set.of("coolmod"), SuggestedMods.parse("{\"suggested\":[\"CoolMod\",\"bad id!\",3]}"));
    }

    @Test
    @DisplayName("A malformed file reads as empty rather than throwing")
    void malformedIsEmpty() {
        for (String body : new String[]{"", "nope", "[]", "{\"suggested\":\"x\"}"}) {
            assertTrue(SuggestedMods.parse(body).isEmpty(), body);
        }
    }
}
