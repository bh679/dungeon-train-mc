package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SuggestionClientTest {

    @Test
    @DisplayName("the suggest body carries the build as an upload does, plus the comment and who sent it")
    void suggestBody() {
        JsonObject b = SuggestionClient.suggestBody("QUJD", 11, 7, 13, "{\"files\":{}}",
            "{\"gold_hoard\":\"{}\"}", "moved the chest", "Brennan");
        assertEquals("QUJD", b.get("blocks").getAsString());
        assertEquals(11, b.getAsJsonObject("dims").get("l").getAsInt());
        assertEquals(7, b.getAsJsonObject("dims").get("h").getAsInt());
        assertEquals(13, b.getAsJsonObject("dims").get("w").getAsInt());
        assertEquals("{\"files\":{}}", b.get("sidecars").getAsString());
        assertEquals("{}", b.getAsJsonObject("lootPrefabs").get("gold_hoard").getAsString());
        assertEquals("moved the chest", b.get("comment").getAsString());
        assertEquals("Brennan", b.get("by").getAsString());
        // No owner secret: the developer never holds one, and the admin cap is the authority.
        assertFalse(b.has("secret"));
    }

    @Test
    @DisplayName("blank sidecars, prefabs and sender are left out rather than sent empty")
    void suggestBodyOmitsBlanks() {
        JsonObject b = SuggestionClient.suggestBody("QUJD", 1, 1, 1, "", "", "c", " ");
        assertFalse(b.has("sidecars"));
        assertFalse(b.has("lootPrefabs"));
        assertFalse(b.has("by"));
    }

    @Test
    @DisplayName("the owner's calls carry the build id and its secret")
    void ownerBody() {
        JsonObject b = SuggestionClient.ownerBody(42, "s3cret");
        assertEquals(42, b.get("id").getAsInt());
        assertEquals("s3cret", b.get("secret").getAsString());
        assertEquals("", SuggestionClient.ownerBody(1, null).get("secret").getAsString());
    }
}
