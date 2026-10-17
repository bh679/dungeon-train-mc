package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.client.live.life.LiveLifeStats.Watcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveFeedClientCrowdTest {

    private static List<Watcher> crowd(String json) {
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        return LiveFeedClient.parseCrowd(new LiveFeedClient.Result(200, body, null));
    }

    @Test
    void theWatchersComeBackLongestFirstWithNullsForTheUnnamed() {
        List<Watcher> top = crowd("{\"ok\":true,\"crowd\":{\"top\":["
            + "{\"name\":\"Moth\",\"uuid\":\"fedcba9876543210fedcba9876543210\",\"source\":\"game\",\"seconds\":4140},"
            + "{\"name\":null,\"uuid\":null,\"source\":\"web\",\"seconds\":3120},"
            + "{\"name\":\"Ash\",\"uuid\":\"not-a-uuid\",\"source\":\"game\",\"seconds\":2820},"
            + "{\"name\":\"Fourth\",\"source\":\"game\",\"seconds\":10}]}}");
        assertEquals(4, top.size());
        assertEquals(new Watcher("Moth", "fedcba9876543210fedcba9876543210", "game", 4140), top.get(0));
        assertEquals(new Watcher(null, null, "web", 3120), top.get(1));
        assertEquals(new Watcher("Ash", null, "game", 2820), top.get(2));
        assertEquals(new Watcher("Fourth", null, "game", 10), top.get(3));
    }

    @Test
    void anOlderRelayOrABadEntryLeavesThePodiumEmptyOrShort() {
        assertTrue(crowd("{\"ok\":true}").isEmpty());
        assertTrue(crowd("{\"ok\":true,\"crowd\":[]}").isEmpty());
        assertEquals(1, crowd("{\"ok\":true,\"crowd\":{\"top\":[{\"source\":\"twitch\",\"seconds\":9},"
            + "{\"source\":\"web\",\"seconds\":\"x\"},{\"source\":\"web\",\"seconds\":0},{\"source\":\"web\",\"seconds\":5}]}}").size());
    }

    @Test
    void aRankedPollCarriesTheViewersNameAndNoOtherDoes() {
        UUID id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        String life = "0123456789abcdef0123456789abcdef";
        LiveFeedClient.Life ranked = new LiveFeedClient.Life(life, false, "0.1268.2");
        assertEquals("/live/status?viewer=" + id + "&lb=1&life=" + life + "&fp=0&v=0.1268.2&name=Moth+B",
            LiveFeedClient.statusPath(id, ranked, "Moth B"));
        assertEquals("/live/status?viewer=" + id, LiveFeedClient.statusPath(id, null, "Moth"));
        assertEquals("/live/status?viewer=" + id + "&lb=1",
            LiveFeedClient.statusPath(id, new LiveFeedClient.Life("", false, "0.1268.2"), null));
    }
}
