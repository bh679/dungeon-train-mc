package games.brennan.dungeontrain.config;

import games.brennan.dungeontrain.client.live.LiveViewerMutes.Muted;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveViewerClientConfigTest {

    @Test
    void mutedStreamersRoundTripAndSkipBadEntries() {
        List<Muted> muted = List.of(new Muted("0123456789abcdef0123456789abcdef", "Alex"), new Muted(null, "Bo"));
        List<String> stored = LiveViewerClientConfig.serialize(muted);
        assertEquals(List.of("0123456789abcdef0123456789abcdef|Alex", "|Bo"), stored);
        assertEquals(muted, LiveViewerClientConfig.parse(stored));
        assertEquals(List.of(new Muted(null, "Bo")), LiveViewerClientConfig.parse(List.of("garbage", "|Bo", "|")),
            "a hand-edited bad line is skipped, not fatal");
    }
}
