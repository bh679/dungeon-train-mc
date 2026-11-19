package games.brennan.dungeontrain.client.live;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.client.live.LiveChatRoom.Pick;
import games.brennan.dungeontrain.client.live.LiveChatRoom.Role;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Live chat's [Report streamer]: when it shows and which command a click runs. */
class LiveReportButtonTest {

    private static Status parse(String json) {
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        return LiveFeedClient.parseStatus(new LiveFeedClient.Result(200, body, null));
    }

    private static final Status ONE = parse("{\"ok\":true,\"live\":true,\"channel\":\"main\",\"session\":\"s1\","
        + "\"playlistUrl\":\"https://x/main/s1/live.m3u8\",\"streamer\":\"Alice\"}");
    private static final Status TWO = parse("{\"ok\":true,\"live\":true,\"channel\":\"main\",\"session\":\"s1\","
        + "\"playlistUrl\":\"https://x/main/s1/live.m3u8\",\"streamer\":\"Alice\","
        + "\"second\":{\"live\":true,\"channel\":\"second\",\"session\":\"s2\",\"playlistUrl\":\"https://x/second/s2/live.m3u8\",\"streamer\":\"Bo\"}}");

    @Test
    void showsWhileSomeoneElseIsLive() {
        assertFalse(LiveReportButton.shows(Status.OFFLINE, Role.TV));
        assertTrue(LiveReportButton.shows(ONE, Role.TV));
        assertTrue(LiveReportButton.shows(ONE, Role.EYE));
        // The only streamer has nobody to report; alongside a second stream they do.
        assertFalse(LiveReportButton.shows(ONE, Role.STREAMER));
        assertTrue(LiveReportButton.shows(TWO, Role.STREAMER));
    }

    @Test
    void clickNamesTheReadersRoom() {
        assertNull(LiveReportButton.streamOf(null, Role.TV));
        assertEquals(2, LiveReportButton.streamOf(new Pick("second", 2, "Bo"), Role.TV));
        assertNull(LiveReportButton.streamOf(new Pick("main", 1, "Alice"), Role.STREAMER));
    }

    @Test
    void command() {
        assertEquals("livereport", LiveReportButton.command(List.of()));
        assertEquals("livereport stream 2", LiveReportButton.command(List.of("2")));
        assertEquals("livereport", LiveReportButton.command(List.of("3")));
        assertEquals("livereport", LiveReportButton.command(List.of("1; op me")));
    }
}
