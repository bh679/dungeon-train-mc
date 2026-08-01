package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembly tests for {@link DevMessageStateReporter#buildPayload}. The relay validates {@code state}
 * against a closed set and rejects anything else, so the constants here are a contract with
 * {@code devmessage-state.js} — a typo would be silently dropped at the other end. Pure: no running
 * server or Minecraft bootstrap needed.
 */
class DevMessageStateReporterTest {

    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    @Test
    @DisplayName("payload carries uuid, state and a send-time stamp")
    void fullPayload() {
        long before = System.currentTimeMillis();
        JsonObject out = DevMessageStateReporter.buildPayload(UUID, DevMessageStateReporter.CONSENT_REQUESTED);
        assertEquals(UUID, out.get("uuid").getAsString());
        assertEquals("consent_requested", out.get("state").getAsString());
        assertTrue(out.get("ts").getAsLong() >= before, "stamped at send time, not on arrival");
    }

    /**
     * The outbox is durable and at-least-once, so a retried report can land minutes late — after a
     * newer transition. The relay drops a report older than the state it holds, which only works if
     * the stamp is the moment the transition happened.
     */
    @Test
    @DisplayName("timestamp is present so a late retry cannot walk the state backwards")
    void stampIsSendTime() {
        JsonObject out = DevMessageStateReporter.buildPayload(UUID, DevMessageStateReporter.DELIVERED_IN_GAME);
        assertTrue(out.has("ts"));
        assertTrue(out.get("ts").getAsLong() > 0L);
    }

    @Test
    @DisplayName("the three state constants match the strings the relay accepts")
    void stateConstantsAreTheWireContract() {
        assertEquals("consent_requested", DevMessageStateReporter.CONSENT_REQUESTED);
        assertEquals("consent_accepted", DevMessageStateReporter.CONSENT_ACCEPTED);
        assertEquals("delivered_in_game", DevMessageStateReporter.DELIVERED_IN_GAME);
    }
}
