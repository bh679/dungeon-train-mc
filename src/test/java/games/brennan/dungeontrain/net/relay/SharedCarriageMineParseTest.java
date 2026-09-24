package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.builder.relay.BuilderProfileCap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the {@code cap} half of the {@code /carriages/mine} reply: the owner's own profile cap, which
 * the upload guard measures against. A relay that predates the field must read as the old default,
 * never as zero — a zero cap would refuse every save as "full".
 */
final class SharedCarriageMineParseTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    @DisplayName("a reported per-player cap is kept")
    void reportedCap() {
        SharedCarriageClient.Mine mine = SharedCarriageClient.parseMine(json(
                "{\"ok\":true,\"count\":1,\"cap\":2000,\"carriages\":[{\"id\":7,\"visibility\":\"profile\"}]}"));
        assertNotNull(mine);
        assertEquals(2000, mine.cap());
        assertEquals(1, mine.builds().size());
        assertEquals(1, BuilderProfileCap.used(mine.builds()));
    }

    @Test
    @DisplayName("an older relay with no cap falls back to the default")
    void missingCap() {
        SharedCarriageClient.Mine mine = SharedCarriageClient.parseMine(json(
                "{\"ok\":true,\"count\":0,\"carriages\":[]}"));
        assertNotNull(mine);
        assertEquals(BuilderProfileCap.DEFAULT_PROFILE_BUILDS, mine.cap());
    }

    @Test
    @DisplayName("a zero, negative or non-numeric cap falls back to the default")
    void unusableCap() {
        for (String cap : new String[] {"0", "-5", "\"lots\"", "null"}) {
            SharedCarriageClient.Mine mine = SharedCarriageClient.parseMine(json(
                    "{\"ok\":true,\"cap\":" + cap + ",\"carriages\":[]}"));
            assertNotNull(mine, cap);
            assertEquals(BuilderProfileCap.DEFAULT_PROFILE_BUILDS, mine.cap(), cap);
        }
    }

    @Test
    @DisplayName("no carriages array is an unusable reply, not an empty profile")
    void unusableReply() {
        assertNull(SharedCarriageClient.parseMine(json("{\"ok\":true}")));
        assertNull(SharedCarriageClient.parseMine(null));
    }
}
