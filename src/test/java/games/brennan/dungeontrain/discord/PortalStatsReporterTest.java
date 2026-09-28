package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.portal.PortalConnectionStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Assembly tests for {@link PortalStatsReporter#buildPayload}. Pure — no Minecraft bootstrap. */
class PortalStatsReporterTest {

    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    @Test
    @DisplayName("full payload carries counts, reasons and the run context")
    void fullPayload() {
        PortalConnectionStats.Life life = new PortalConnectionStats.Life(3, 2,
            Map.of("TWIN_NOT_LOADED", 1, "SEVERED", 1));
        JsonObject out = PortalStatsReporter.buildPayload(UUID, "NyoomBomb", "0.777.1", 742L, 33, life);
        assertEquals(UUID, out.get("uuid").getAsString());
        assertEquals("NyoomBomb", out.get("player").getAsString());
        assertEquals("0.777.1", out.get("modVersion").getAsString());
        assertEquals(742L, out.get("runSec").getAsLong());
        assertEquals(33, out.get("carriage").getAsInt());
        assertEquals(3, out.get("connected").getAsInt());
        assertEquals(2, out.get("broken").getAsInt());
        JsonObject reasons = out.getAsJsonObject("reasons");
        assertEquals(1, reasons.get("TWIN_NOT_LOADED").getAsInt());
        assertEquals(1, reasons.get("SEVERED").getAsInt());
    }

    @Test
    @DisplayName("a life with only connections sends no reasons object; blank optionals are omitted")
    void connectionsOnly() {
        PortalConnectionStats.Life life = new PortalConnectionStats.Life(2, 0, Map.of());
        JsonObject out = PortalStatsReporter.buildPayload(UUID, "", null, 10L, 4, life);
        assertFalse(out.has("player"));
        assertFalse(out.has("modVersion"));
        assertFalse(out.has("reasons"));
        assertEquals(2, out.get("connected").getAsInt());
        assertEquals(0, out.get("broken").getAsInt());
    }

    @Test
    @DisplayName("an empty life is what the reporter skips")
    void emptyLifeIsSkippable() {
        assertTrue(new PortalConnectionStats.Life(0, 0, Map.of()).isEmpty());
        assertEquals("", PortalStatsReporter.describeReasons(Map.of()));
        assertEquals(" (SEVERED×2)", PortalStatsReporter.describeReasons(Map.of("SEVERED", 2)));
    }
}
