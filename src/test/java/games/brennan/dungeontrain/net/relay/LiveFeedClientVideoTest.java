package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The claim reply's {@code video} (relay live.js videoAt): the picture HD Streams raises a stream to. */
final class LiveFeedClientVideoTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static LiveFeedClient.Claim claim(String extra) {
        return LiveFeedClient.parseClaim(new LiveFeedClient.Result(200, json(
            "{\"ok\":true,\"session\":\"s\",\"token\":\"t\",\"playlistUrl\":\"https://x/live.m3u8\"" + extra + "}"), null));
    }

    @Test
    void hdStreamsOnCarries1080p() {
        assertEquals(new LiveFeedClient.Video(1920, 4500), claim(",\"video\":{\"width\":1920,\"kbps\":4500}").video());
    }

    @Test
    void anOlderRelayOrHdOffSaysNothing() {
        assertNull(claim("").video());
        assertNull(new LiveFeedClient.Claim("s", "t", "u", null).video());
    }

    @Test
    void malformedOrOutOfRangeIsIgnored() {
        assertNull(LiveFeedClient.parseVideo(json("{\"video\":\"hd\"}")));
        assertNull(LiveFeedClient.parseVideo(json("{\"video\":{\"width\":1921,\"kbps\":4500}}")), "odd width");
        assertNull(LiveFeedClient.parseVideo(json("{\"video\":{\"width\":3840,\"kbps\":4500}}")), "4K");
        assertNull(LiveFeedClient.parseVideo(json("{\"video\":{\"width\":1920,\"kbps\":20000}}")), "bitrate");
        assertNull(LiveFeedClient.parseVideo(json("{\"video\":{\"width\":1920}}")), "no bitrate");
        assertNull(LiveFeedClient.parseVideo(null));
    }
}
