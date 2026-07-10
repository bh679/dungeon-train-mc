package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Assembly tests for {@link DeathReporter#buildPayload}. The relay reads this as the authoritative
 * per-death signal ({@code death_direct}); {@code player} + {@code cause} are optional. Pure — no
 * running server or Minecraft bootstrap needed.
 */
class DeathReporterTest {

    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    @Test
    @DisplayName("full payload carries uuid, player, cause, runSec, carriage")
    void fullPayload() {
        JsonObject out = DeathReporter.buildPayload(UUID, "NyoomBomb", "You fell from a high place", 742L, 33);
        assertEquals(UUID, out.get("uuid").getAsString());
        assertEquals("NyoomBomb", out.get("player").getAsString());
        assertEquals("You fell from a high place", out.get("cause").getAsString());
        assertEquals(742L, out.get("runSec").getAsLong());
        assertEquals(33, out.get("carriage").getAsInt());
    }

    @Test
    @DisplayName("null or blank player is omitted")
    void playerOptional() {
        assertFalse(DeathReporter.buildPayload(UUID, null, "x", 10L, 0).has("player"));
        assertFalse(DeathReporter.buildPayload(UUID, "", "x", 10L, 0).has("player"));
    }

    @Test
    @DisplayName("null or blank cause is omitted; runSec/carriage always present")
    void causeOptional() {
        JsonObject noCause = DeathReporter.buildPayload(UUID, "x", null, 5L, 2);
        assertFalse(noCause.has("cause"));
        assertEquals(5L, noCause.get("runSec").getAsLong());
        assertEquals(2, noCause.get("carriage").getAsInt());
        assertFalse(DeathReporter.buildPayload(UUID, "x", "", 5L, 2).has("cause"));
    }
}
