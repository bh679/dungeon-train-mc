package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Assembly tests for {@link NarrativeReportReporter#buildPayload} — the shape the relay's
 * {@code POST /narratives/report} normalises. Pure, no Minecraft bootstrap. Mirrors
 * {@link BookReportReporterTest}: the report is against a SERIES, with the letter the reader was on.
 */
class NarrativeReportReporterTest {

    @Test
    @DisplayName("payload carries uuid, player, seriesId, letterIndex and clientTsMs")
    void coreShape() {
        JsonObject out = NarrativeReportReporter.buildPayload("abc123", "Notch", "s1", 3, 1_712_000_000_000L);
        assertEquals("abc123", out.get("uuid").getAsString());
        assertEquals("Notch", out.get("player").getAsString());
        assertEquals("s1", out.get("seriesId").getAsString());
        assertEquals(3, out.get("letterIndex").getAsInt());
        assertEquals(1_712_000_000_000L, out.get("clientTsMs").getAsLong());
        assertEquals(5, out.entrySet().size());
    }

    @Test
    @DisplayName("no reason or vote field, and no letterIndex when it is not a real index")
    void noExtras() {
        JsonObject out = NarrativeReportReporter.buildPayload("u", null, "s1", 0, 5L);
        assertFalse(out.has("reason"));
        assertFalse(out.has("vote"));
        assertFalse(out.has("player"));
        assertFalse(out.has("letterIndex"));
    }
}
