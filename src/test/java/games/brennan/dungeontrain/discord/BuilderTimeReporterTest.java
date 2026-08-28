package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Assembly tests for {@link BuilderTimeReporter#buildPayload}. The relay reads {@code builderSec} as
 * the player's LIFETIME building seconds and folds it with {@code MAX(...)}, so the field has to
 * carry a total rather than a delta. Pure — no server or Minecraft bootstrap needed.
 */
class BuilderTimeReporterTest {

    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    @Test
    @DisplayName("full payload carries uuid, player and builderSec")
    void fullPayload() {
        JsonObject out = BuilderTimeReporter.buildPayload(UUID, "NyoomBomb", 9412L);
        assertEquals(UUID, out.get("uuid").getAsString());
        assertEquals("NyoomBomb", out.get("player").getAsString());
        assertEquals(9412L, out.get("builderSec").getAsLong());
    }

    @Test
    @DisplayName("null or blank player is omitted rather than sent empty")
    void playerOptional() {
        assertFalse(BuilderTimeReporter.buildPayload(UUID, null, 10L).has("player"));
        assertFalse(BuilderTimeReporter.buildPayload(UUID, "", 10L).has("player"));
    }
}
