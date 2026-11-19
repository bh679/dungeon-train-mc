package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two Streams: a chat room per stream, named by its slot. */
class LiveFeedClientChatRoomTest {

    private static final UUID ID = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");

    private static LiveFeedClient.Status parse(String json) {
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        return LiveFeedClient.parseStatus(new LiveFeedClient.Result(200, body, null));
    }

    @Test
    void theChatPathNamesARoomOnlyWhenAsked() {
        assertEquals("/live/chat", LiveFeedClient.chatPath(-1, null));
        assertEquals("/live/chat?since=12", LiveFeedClient.chatPath(12, null));
        assertEquals("/live/chat?room=second", LiveFeedClient.chatPath(-1, "second"));
        assertEquals("/live/chat?since=0&room=main", LiveFeedClient.chatPath(0, "main"));
    }

    @Test
    void aSendCarriesTheRoomOnlyWhenOneIsNamed() {
        JsonObject one = LiveFeedClient.chatSendBody(ID, "Ann", "hi", null);
        assertFalse(one.has("room"), "one stream up: exactly the body there always was");
        assertEquals("hi", one.get("content").getAsString());
        JsonObject two = LiveFeedClient.chatSendBody(ID, "Ann", "hi", "second");
        assertEquals("second", two.get("room").getAsString());
    }

    @Test
    void theStatusAndItsSecondCarryTheirOwnChannel() {
        LiveFeedClient.Status s = parse("{\"ok\":true,\"live\":true,\"channel\":\"second\",\"session\":\"s1\","
            + "\"playlistUrl\":\"https://x/second/s1/live.m3u8\",\"streamer\":\"Alice\","
            + "\"second\":{\"live\":true,\"channel\":\"main\",\"session\":\"s2\",\"playlistUrl\":\"https://x/main/s2/live.m3u8\",\"streamer\":\"Bo\"}}");
        assertEquals("second", s.channel(), "the primary can sit on the second slot");
        assertEquals("main", s.second().channel());
        assertEquals("second", LiveFeedClient.roomOf(s, false));
        assertEquals("main", LiveFeedClient.roomOf(s.second(), true));
    }

    @Test
    void anOlderRelayWithoutChannelsFallsBackToTheUsualSlots() {
        LiveFeedClient.Status s = parse("{\"ok\":true,\"live\":true,\"session\":\"s1\",\"playlistUrl\":\"https://x/main/s1/live.m3u8\","
            + "\"second\":{\"live\":true,\"session\":\"s2\",\"playlistUrl\":\"https://x/main/s2/live.m3u8\"}}");
        assertNull(s.channel());
        assertNull(s.second().channel());
        assertEquals("main", LiveFeedClient.roomOf(s, false));
        assertEquals("second", LiveFeedClient.roomOf(s.second(), true));
        assertTrue(LiveFeedClient.slotName("third") == null, "an unknown slot is not trusted");
    }
}
