package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class LiveTakeoverPollKindTest {

    private static JsonObject line(String poll) {
        return JsonParser.parseString("{\"seq\":1,\"content\":\"x\",\"poll\":" + poll + "}").getAsJsonObject();
    }

    @Test
    @DisplayName("a takeover poll line (no kind, or kind takeover) is read; any other kind is not a takeover")
    void onlyTakeoverPollsAreRead() {
        String takeover = "{\"id\":\"p1\",\"state\":\"open\",\"requester\":\"Cy\",\"holder\":\"Ann\",\"endsTs\":5}";
        LiveTakeoverClient.PollLine plain = LiveTakeoverClient.parsePollLine(line(takeover));
        assertNotNull(plain, "older relays send no kind");
        assertEquals("Cy", plain.requester());
        assertNotNull(LiveTakeoverClient.parsePollLine(line(takeover.replace("{", "{\"kind\":\"takeover\","))));
        assertNull(LiveTakeoverClient.parsePollLine(line("{\"id\":\"v1\",\"kind\":\"view\",\"state\":\"open\",\"endsTs\":5}")),
            "the Twitch view vote is not a takeover");
    }
}
