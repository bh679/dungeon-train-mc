package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.advancement.BurritoClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The first-claim body: the burrito's and The Challenge's times ride it, nothing else gains fields. And the Free Play report's body. */
class AdvancementFirstClientTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000ab");

    @Test
    @DisplayName("a burrito earn carries its play time and attempt")
    void burritoCarriesTime() {
        JsonObject body = AdvancementFirstClient.body(PLAYER, "Steve", "dungeontrain:dungeon_train/completionist",
            "hard", new BurritoClock.Completion(72_000L, 1L, 2, "hard", "1.0"), 0L);
        assertEquals(72_000L, body.get("burritoTicks").getAsLong());
        assertEquals(2, body.get("burritoAttempt").getAsInt());
        assertFalse(body.has("challengeTicks"));
    }

    @Test
    @DisplayName("a Challenge earn carries that life's time aboard, and no burrito fields")
    void challengeCarriesTime() {
        JsonObject body = AdvancementFirstClient.body(PLAYER, "Steve", "dungeontrain:dungeon_train/the_challenge",
            "normal", null, 144_000L);
        assertEquals(144_000L, body.get("challengeTicks").getAsLong());
        assertFalse(body.has("burritoTicks"));
    }

    @Test
    @DisplayName("any other earn sends no burrito fields")
    void otherEarnsUnchanged() {
        JsonObject body = AdvancementFirstClient.body(PLAYER, "Steve", "dungeontrain:dungeon_train/carts_100", "normal", null, 0L);
        assertFalse(body.has("burritoTicks"));
        assertFalse(body.has("burritoAttempt"));
        assertFalse(body.has("challengeTicks"));
        assertEquals("normal", body.get("difficulty").getAsString());
    }

    @Test
    @DisplayName("a Free Play report names the advancement and the bare uuid, and nothing a claim carries")
    void freePlayReport() {
        JsonObject body = AdvancementFirstClient.freePlayBody(PLAYER, "dungeontrain:dungeon_train/start_again");
        assertEquals("dungeontrain:dungeon_train/start_again", body.get("id").getAsString());
        assertEquals("000000000000000000000000000000ab", body.get("uuid").getAsString());
        assertFalse(body.has("name"));
        assertFalse(body.has("difficulty"));
        assertFalse(body.has("burritoTicks"));
    }

    @Test
    @DisplayName("only a retry is marked retry, and marking one leaves the first body as it was")
    void retryIsMarked() {
        JsonObject first = AdvancementFirstClient.body(PLAYER, "Steve", "dungeontrain:dungeon_train/carts_100", "normal", null, 0L);
        JsonObject retry = AdvancementFirstClient.retryBody(first);
        assertFalse(first.has("retry"));
        assertTrue(retry.get("retry").getAsBoolean());
        assertEquals(first.get("id"), retry.get("id"));
        assertEquals(first.get("uuid"), retry.get("uuid"));
    }

    @Test
    @DisplayName("a 5xx retries; an answer or a refusal does not")
    void retriesOnlyWhatMayStillLand() {
        assertTrue(AdvancementFirstClient.shouldRetryStatus(500));
        assertTrue(AdvancementFirstClient.shouldRetryStatus(503));
        assertFalse(AdvancementFirstClient.shouldRetryStatus(200));
        assertFalse(AdvancementFirstClient.shouldRetryStatus(400));
        assertFalse(AdvancementFirstClient.shouldRetryStatus(404));
    }

    @Test
    @DisplayName("the whole retry run, every try timing out, stays well inside the relay's 10 minute window")
    void retriesFitTheRelayWindow() {
        long total = 15L * (AdvancementFirstClient.RETRY_DELAYS_SECONDS.length + 1);
        for (long delay : AdvancementFirstClient.RETRY_DELAYS_SECONDS) total += delay;
        assertTrue(total < 600L, "retry run takes " + total + " s");
    }
}
