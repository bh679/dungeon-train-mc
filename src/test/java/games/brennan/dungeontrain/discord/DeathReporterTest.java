package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembly tests for {@link DeathReporter#buildPayload}. The relay reads this as the authoritative
 * per-death signal ({@code death_direct}); {@code player} + {@code cause} are optional. Pure — no
 * running server or Minecraft bootstrap needed.
 */
class DeathReporterTest {

    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    /** No position captured — the shape every pre-feature life reports. */
    private static final RunPosition NO_POS = new RunPosition(null, null, null);

    @Test
    @DisplayName("full payload carries uuid, player, cause, runSec, carriage")
    void fullPayload() {
        JsonObject out = DeathReporter.buildPayload(
                UUID, "NyoomBomb", "You fell from a high place", 742L, 33, NO_POS);
        assertEquals(UUID, out.get("uuid").getAsString());
        assertEquals("NyoomBomb", out.get("player").getAsString());
        assertEquals("You fell from a high place", out.get("cause").getAsString());
        assertEquals(742L, out.get("runSec").getAsLong());
        assertEquals(33, out.get("carriage").getAsInt());
    }

    @Test
    @DisplayName("null or blank player is omitted")
    void playerOptional() {
        assertFalse(DeathReporter.buildPayload(UUID, null, "x", 10L, 0, NO_POS).has("player"));
        assertFalse(DeathReporter.buildPayload(UUID, "", "x", 10L, 0, NO_POS).has("player"));
    }

    @Test
    @DisplayName("null or blank cause is omitted; runSec/carriage always present")
    void causeOptional() {
        JsonObject noCause = DeathReporter.buildPayload(UUID, "x", null, 5L, 2, NO_POS);
        assertFalse(noCause.has("cause"));
        assertEquals(5L, noCause.get("runSec").getAsLong());
        assertEquals(2, noCause.get("carriage").getAsInt());
        assertFalse(DeathReporter.buildPayload(UUID, "x", "", 5L, 2, NO_POS).has("cause"));
    }

    @Test
    @DisplayName("position fields ride along when captured")
    void positionCarried() {
        JsonObject out = DeathReporter.buildPayload(
                UUID, "x", "y", 5L, 2, new RunPosition(1000, 26128, "nether"));
        assertEquals(1000, out.get("spawnX").getAsInt());
        assertEquals(26128, out.get("distanceTravelled").getAsInt());
        assertEquals("nether", out.get("band").getAsString());
    }

    @Test
    @DisplayName("distanceTravelled is signed — a train reversal reports a negative displacement")
    void distanceTravelledSigned() {
        JsonObject out = DeathReporter.buildPayload(
                UUID, "x", "y", 5L, 2, new RunPosition(4000, -1500, "overworld"));
        assertEquals(-1500, out.get("distanceTravelled").getAsInt());
    }

    @Test
    @DisplayName("an uncaptured origin omits spawnX/distanceTravelled rather than sending zero")
    void positionOmittedWhenUncaptured() {
        JsonObject out = DeathReporter.buildPayload(UUID, "x", "y", 5L, 2, NO_POS);
        assertFalse(out.has("spawnX"));
        assertFalse(out.has("distanceTravelled"));
        assertFalse(out.has("band"));
    }

    @Test
    @DisplayName("band survives on its own when the origin was never captured")
    void bandIndependentOfOrigin() {
        JsonObject out = DeathReporter.buildPayload(
                UUID, "x", "y", 5L, 2, new RunPosition(null, null, "upside_down"));
        assertTrue(out.has("band"));
        assertFalse(out.has("spawnX"));
    }

    @Test
    @DisplayName("a null RunPosition is tolerated")
    void nullPositionTolerated() {
        assertFalse(DeathReporter.buildPayload(UUID, "x", "y", 5L, 2, null).has("band"));
    }
}
