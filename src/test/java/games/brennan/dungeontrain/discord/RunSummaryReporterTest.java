package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembly tests for {@link RunSummaryReporter#buildPayload}. The relay reads {@code runSec} as the
 * life's elapsed seconds ({@code runTicks / 20}); {@code player} is optional. Pure — no running
 * server or Minecraft bootstrap needed.
 */
class RunSummaryReporterTest {

    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    /** No position captured — the shape every pre-feature life reports. */
    private static final RunPosition NO_POS = new RunPosition(null, null, null);

    @Test
    @DisplayName("full payload carries uuid, player, runSec, carriage, distanceBlocks")
    void fullPayload() {
        JsonObject out = RunSummaryReporter.buildPayload(UUID, "NyoomBomb", 34861L, 33, 2013, NO_POS, false);
        assertEquals(UUID, out.get("uuid").getAsString());
        assertEquals("NyoomBomb", out.get("player").getAsString());
        assertEquals(34861L, out.get("runSec").getAsLong());
        assertEquals(33, out.get("carriage").getAsInt());
        assertEquals(2013, out.get("distanceBlocks").getAsInt());
    }

    @Test
    @DisplayName("null or blank player is omitted")
    void playerOptional() {
        assertFalse(RunSummaryReporter.buildPayload(UUID, null, 10L, 0, 0, NO_POS, false).has("player"));
        assertFalse(RunSummaryReporter.buildPayload(UUID, "", 10L, 0, 0, NO_POS, false).has("player"));
    }

    @Test
    @DisplayName("runSec passes through unchanged (already whole seconds)")
    void runSecPassthrough() {
        assertEquals(0L, RunSummaryReporter.buildPayload(UUID, "x", 0L, 0, 0, NO_POS, false).get("runSec").getAsLong());
        assertEquals(7200L,
                RunSummaryReporter.buildPayload(UUID, "x", 7200L, 5, 100, NO_POS, false).get("runSec").getAsLong());
    }

    @Test
    @DisplayName("odometer and displacement are independent — both ship, neither derives the other")
    void odometerAndDisplacementCoexist() {
        // A player who walked laps on a carriage: odometer 2013, but only 800 blocks down the line.
        JsonObject out = RunSummaryReporter.buildPayload(
                UUID, "x", 34861L, 33, 2013, new RunPosition(200, 800, "overworld"), false);
        assertEquals(2013, out.get("distanceBlocks").getAsInt());
        assertEquals(800, out.get("distanceTravelled").getAsInt());
        assertEquals(200, out.get("spawnX").getAsInt());
        assertEquals("overworld", out.get("band").getAsString());
    }

    @Test
    @DisplayName("legacy shape: distanceBlocks alone when no origin was captured")
    void legacyShape() {
        JsonObject out = RunSummaryReporter.buildPayload(UUID, "x", 10L, 1, 500, NO_POS, false);
        assertEquals(500, out.get("distanceBlocks").getAsInt());
        assertFalse(out.has("distanceTravelled"));
        assertFalse(out.has("spawnX"));
    }

    @Test
    @DisplayName("a Free Play life is flagged, so the relay can keep it off the public boards")
    void freePlayFlagged() {
        JsonObject out = RunSummaryReporter.buildPayload(UUID, "x", 60L, 2, 30, NO_POS, true);
        assertTrue(out.get("freePlay").getAsBoolean());
    }

    @Test
    @DisplayName("a legitimate life says so explicitly — false is an assertion, not a default")
    void legitimateRunStatesTheFlag() {
        // The relay no longer reads an absent key as legitimate: silence is what a client too old to
        // know the question sends. Omitting this would unrank every player on this build.
        JsonObject out = RunSummaryReporter.buildPayload(UUID, "x", 60L, 2, 30, NO_POS, false);
        assertTrue(out.has("freePlay"));
        assertFalse(out.get("freePlay").getAsBoolean());
    }
}
