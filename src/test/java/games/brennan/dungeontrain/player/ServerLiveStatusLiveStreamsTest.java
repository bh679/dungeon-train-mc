package games.brennan.dungeontrain.player;

import games.brennan.dungeontrain.moderation.LiveReport.Stream;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The streams /livereport can name: the relay's view first, this server's own streamers as the fallback. */
final class ServerLiveStatusLiveStreamsTest {

    private static final UUID BO = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final UUID CY = UUID.fromString("00000000-0000-0000-0000-0000000000c0");

    private static Map<UUID, String> local(Object... pairs) {
        Map<UUID, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((UUID) pairs[i], (String) pairs[i + 1]);
        return m;
    }

    @Test
    void relayStreamsWithLocalUuidsByName() {
        List<Stream> s = ServerLiveStatus.liveStreams(true, "Alice", "main", true, "bo", "second", local(BO, "Bo"));
        assertEquals(List.of(new Stream(1, "main", "Alice", null), new Stream(2, "second", "bo", BO)), s);
    }

    @Test
    void secondOnlyWhenFresh() {
        assertEquals(1, ServerLiveStatus.liveStreams(true, "Alice", "second", false, "Bo", "main", local()).size());
    }

    @Test
    void staleRelayFallsBackToLocalStreamers() {
        List<Stream> s = ServerLiveStatus.liveStreams(false, "Alice", "main", false, null, "second", local(BO, "Bo", CY, "Cy"));
        assertEquals(List.of(new Stream(1, "main", "Bo", BO), new Stream(2, "second", "Cy", CY)), s);
        assertEquals(List.of(), ServerLiveStatus.liveStreams(false, null, "main", false, null, "second", local()));
    }
}
