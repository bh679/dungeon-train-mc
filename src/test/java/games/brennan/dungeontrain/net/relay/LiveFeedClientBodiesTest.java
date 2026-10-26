package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The claim and chat-line bodies: the Archivist perks' optional fields appear only when they matter. */
final class LiveFeedClientBodiesTest {

    private static final UUID ME = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void claimCarriesSafeDurationExtrasOnlyWhenPositive() {
        JsonObject plain = LiveFeedClient.claimBody(ME, "Alice", null, null, null, false, null, null, 0L, 0L);
        assertFalse(plain.has("takeoverExtraMs"));
        assertFalse(plain.has("holderVoteExtraMs"));
        assertEquals("Alice", plain.get("name").getAsString());

        JsonObject perked = LiveFeedClient.claimBody(ME, "Alice", null, null, null, false, null, null, 300_000L, 150_000L);
        assertEquals(300_000L, perked.get("takeoverExtraMs").getAsLong());
        assertEquals(150_000L, perked.get("holderVoteExtraMs").getAsLong());
    }

    @Test
    void chatLineCarriesWeightOnlyAboveOne() {
        JsonObject one = LiveFeedClient.chatSendBody(ME, "Alice", "yes", 1);
        assertFalse(one.has("weight"));
        assertEquals("yes", one.get("content").getAsString());
        JsonObject three = LiveFeedClient.chatSendBody(ME, "Alice", "yes", 3);
        assertEquals(3, three.get("weight").getAsInt());
    }
}
