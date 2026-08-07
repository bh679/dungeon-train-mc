package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.net.ModRecommendPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape of the {@code /telemetry/mod-rec} body. The load-bearing assertion is the absence of the
 * comment: the relay stores game-generated metadata verbatim but never player free-text, so a
 * regression that let the comment through would put unmoderated text into a stored telemetry row.
 */
class ModRecommendPayloadTest {

    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    @Test
    @DisplayName("a recommendation carries the modId and the flag — and no comment")
    void recommendation() {
        JsonObject out = ModRecommendReporter.buildPayload(UUID,
                new ModRecommendPacket("jei", "Just Enough Items", "saves me so much time", false));
        assertEquals(UUID, out.get("uuid").getAsString());
        assertEquals("jei", out.get("modId").getAsString());
        assertFalse(out.get("requested").getAsBoolean());
        assertFalse(out.has("comment"));
        assertFalse(out.has("displayName"));
        assertFalse(out.toString().contains("saves me"));
    }

    @Test
    @DisplayName("a request sends no id at all — the typed name is player text and stays in Discord")
    void request() {
        JsonObject out = ModRecommendReporter.buildPayload(UUID,
                new ModRecommendPacket("", "Create", "would suit the train perfectly", true));
        assertEquals("", out.get("modId").getAsString());
        assertTrue(out.get("requested").getAsBoolean());
        assertFalse(out.toString().contains("Create"));
        assertFalse(out.toString().contains("would suit"));
    }

    @Test
    @DisplayName("null fields degrade to empty strings rather than JSON nulls")
    void nullsAreSafe() {
        JsonObject out = ModRecommendReporter.buildPayload(UUID,
                new ModRecommendPacket(null, null, null, false));
        assertEquals("", out.get("modId").getAsString());
    }
}
