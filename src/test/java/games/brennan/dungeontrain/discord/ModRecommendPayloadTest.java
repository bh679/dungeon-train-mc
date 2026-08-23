package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.net.ModRecommendPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape of the {@code /telemetry/mod-rec} body.
 *
 * <p>The body carries the display name and the comment as well as the id (relay v0.128.0 splits them
 * on arrival — text-free analytics event, prose in its own capped admin-only table). The assertions
 * that matter are that a REQUEST still sends no {@code modId} — its typed name is not an id and must
 * never be stored as one — and that the id/name/comment fields stay distinct, since the explorer
 * reads {@code name} as the mod's own name.</p>
 */
class ModRecommendPayloadTest {

    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    @Test
    @DisplayName("a recommendation carries the modId, the flag, the mod's name and the reason")
    void recommendation() {
        JsonObject out = ModRecommendReporter.buildPayload(UUID,
                new ModRecommendPacket("jei", "Just Enough Items", "saves me so much time", false, false));
        assertEquals(UUID, out.get("uuid").getAsString());
        assertEquals("jei", out.get("modId").getAsString());
        assertFalse(out.get("requested").getAsBoolean());
        assertEquals("Just Enough Items", out.get("name").getAsString());
        assertEquals("saves me so much time", out.get("comment").getAsString());
        assertFalse(out.get("hack").getAsBoolean());
    }

    @Test
    @DisplayName("a request sends its typed name but still NO id — the name is not an identifier")
    void request() {
        JsonObject out = ModRecommendReporter.buildPayload(UUID,
                new ModRecommendPacket("", "Create", "would suit the train perfectly", true, false));
        assertEquals("", out.get("modId").getAsString());
        assertTrue(out.get("requested").getAsBoolean());
        assertEquals("Create", out.get("name").getAsString());
        assertEquals("would suit the train perfectly", out.get("comment").getAsString());
        assertFalse(out.get("hack").getAsBoolean());
    }

    @Test
    @DisplayName("a hack report is a recommendation's mirror: real id, hack set, requested clear")
    void hackReport() {
        JsonObject out = ModRecommendReporter.buildPayload(UUID,
                new ModRecommendPacket("xray", "X-Ray", "see ores through stone", false, true));
        assertEquals("xray", out.get("modId").getAsString());
        assertTrue(out.get("hack").getAsBoolean());
        assertFalse(out.get("requested").getAsBoolean());
        assertEquals("X-Ray", out.get("name").getAsString());
        assertEquals("see ores through stone", out.get("comment").getAsString());
    }

    @Test
    @DisplayName("an installed mod with no display name falls back to its id, never to a placeholder")
    void nameFallsBackToId() {
        JsonObject out = ModRecommendReporter.buildPayload(UUID,
                new ModRecommendPacket("jei", "  ", "good", false, false));
        assertEquals("jei", out.get("name").getAsString());
    }

    @Test
    @DisplayName("null fields degrade to empty strings rather than JSON nulls")
    void nullsAreSafe() {
        JsonObject out = ModRecommendReporter.buildPayload(UUID,
                new ModRecommendPacket(null, null, null, false, false));
        assertEquals("", out.get("modId").getAsString());
        assertEquals("", out.get("name").getAsString());
        assertEquals("", out.get("comment").getAsString());
    }
}
