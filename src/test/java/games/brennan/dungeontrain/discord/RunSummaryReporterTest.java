package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Assembly tests for {@link RunSummaryReporter#buildPayload}. The relay reads {@code runSec} as the
 * life's elapsed seconds ({@code runTicks / 20}); {@code player} is optional. Pure — no running
 * server or Minecraft bootstrap needed.
 */
class RunSummaryReporterTest {

    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    @Test
    @DisplayName("full payload carries uuid, player, runSec, carriage, distanceBlocks")
    void fullPayload() {
        JsonObject out = RunSummaryReporter.buildPayload(UUID, "NyoomBomb", 34861L, 33, 2013);
        assertEquals(UUID, out.get("uuid").getAsString());
        assertEquals("NyoomBomb", out.get("player").getAsString());
        assertEquals(34861L, out.get("runSec").getAsLong());
        assertEquals(33, out.get("carriage").getAsInt());
        assertEquals(2013, out.get("distanceBlocks").getAsInt());
    }

    @Test
    @DisplayName("null or blank player is omitted")
    void playerOptional() {
        assertFalse(RunSummaryReporter.buildPayload(UUID, null, 10L, 0, 0).has("player"));
        assertFalse(RunSummaryReporter.buildPayload(UUID, "", 10L, 0, 0).has("player"));
    }

    @Test
    @DisplayName("runSec passes through unchanged (already whole seconds)")
    void runSecPassthrough() {
        assertEquals(0L, RunSummaryReporter.buildPayload(UUID, "x", 0L, 0, 0).get("runSec").getAsLong());
        assertEquals(7200L, RunSummaryReporter.buildPayload(UUID, "x", 7200L, 5, 100).get("runSec").getAsLong());
    }
}
