package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The dead streamer's takeover offer in a viewer's status poll. */
class LiveFeedClientTakeoverTest {

    private static LiveFeedClient.Takeover parse(int status, String json) {
        return LiveFeedClient.parseTakeover(new LiveFeedClient.Result(status, JsonParser.parseString(json).getAsJsonObject(), null));
    }

    @Test
    void readsTheOffer() {
        LiveFeedClient.Takeover t = parse(200, "{\"ok\":true,\"live\":true,\"takeover\":{\"id\":\"0123456789abcdef\",\"from\":\"Alice\",\"untilMs\":42000}}");
        assertEquals(new LiveFeedClient.Takeover("0123456789abcdef", "Alice", 42000), t);
    }

    @Test
    void noOfferFromAnOlderRelayOrABadOne() {
        assertNull(parse(200, "{\"ok\":true,\"live\":true}"));
        assertNull(parse(200, "{\"takeover\":{\"id\":\"../x\",\"from\":\"A\",\"untilMs\":5}}"));
        assertNull(parse(200, "{\"takeover\":{\"id\":\"0123456789abcdef\",\"from\":\"A\",\"untilMs\":0}}"));
        assertNull(parse(200, "{\"takeover\":{\"id\":\"0123456789abcdef\",\"untilMs\":\"soon\"}}"));
        assertNull(parse(500, "{\"takeover\":{\"id\":\"0123456789abcdef\",\"from\":\"A\",\"untilMs\":5}}"));
    }
}
