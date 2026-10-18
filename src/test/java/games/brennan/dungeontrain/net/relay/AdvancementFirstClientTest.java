package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.advancement.BurritoClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The first-claim body: the burrito's play time rides it, nothing else gains fields. */
class AdvancementFirstClientTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000ab");

    @Test
    @DisplayName("a burrito earn carries its play time and attempt")
    void burritoCarriesTime() {
        JsonObject body = AdvancementFirstClient.body(PLAYER, "Steve", "dungeontrain:dungeon_train/completionist",
            "hard", new BurritoClock.Completion(72_000L, 1L, 2, "hard", "1.0"));
        assertEquals(72_000L, body.get("burritoTicks").getAsLong());
        assertEquals(2, body.get("burritoAttempt").getAsInt());
    }

    @Test
    @DisplayName("any other earn sends no burrito fields")
    void otherEarnsUnchanged() {
        JsonObject body = AdvancementFirstClient.body(PLAYER, "Steve", "dungeontrain:dungeon_train/carts_100", "normal", null);
        assertFalse(body.has("burritoTicks"));
        assertFalse(body.has("burritoAttempt"));
        assertEquals("normal", body.get("difficulty").getAsString());
    }
}
