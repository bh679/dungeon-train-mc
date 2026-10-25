package games.brennan.dungeontrain.client.live;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.client.live.HeadViewerPanes.Choice;
import games.brennan.dungeontrain.client.live.LiveChatRoom.Pick;
import games.brennan.dungeontrain.client.live.LiveChatRoom.Role;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveChatRoomTest {

    private static Status parse(String json) {
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        return LiveFeedClient.parseStatus(new LiveFeedClient.Result(200, body, null));
    }

    private static final Status ONE = parse("{\"ok\":true,\"live\":true,\"channel\":\"main\",\"session\":\"s1\","
        + "\"playlistUrl\":\"https://x/main/s1/live.m3u8\",\"streamer\":\"Alice\"}");
    private static final Status TWO = parse("{\"ok\":true,\"live\":true,\"channel\":\"main\",\"session\":\"s1\","
        + "\"playlistUrl\":\"https://x/main/s1/live.m3u8\",\"streamer\":\"Alice\","
        + "\"second\":{\"live\":true,\"channel\":\"second\",\"session\":\"s2\",\"playlistUrl\":\"https://x/second/s2/live.m3u8\",\"streamer\":\"Bo\"}}");
    /** The primary on the second slot: Stream 1 is still the primary. */
    private static final Status SWAPPED = parse("{\"ok\":true,\"live\":true,\"channel\":\"second\",\"session\":\"s2\","
        + "\"playlistUrl\":\"https://x/second/s2/live.m3u8\",\"streamer\":\"Bo\","
        + "\"second\":{\"live\":true,\"channel\":\"main\",\"session\":\"s1\",\"playlistUrl\":\"https://x/main/s1/live.m3u8\",\"streamer\":\"Alice\"}}");

    @AfterEach
    void reset() {
        LiveChatRoom.reset();
    }

    @Test
    void oneStreamUpNamesNoRoomForAnyViewer() {
        for (Role role : new Role[] {Role.EYE, Role.TV}) {
            for (Choice c : Choice.values()) {
                assertNull(LiveChatRoom.pick(ONE, role, false, c, 2));
            }
        }
        assertNull(LiveChatRoom.pick(ONE, Role.STREAMER, false, Choice.BOTH, 1));
        assertNull(LiveChatRoom.pick(Status.OFFLINE, Role.TV, false, Choice.BOTH, 1));
    }

    @Test
    void aTvShowingBothFollowsTheToggleStreamOneByDefault() {
        assertEquals(new Pick("main", 1, "Alice"), LiveChatRoom.pick(TWO, Role.TV, false, Choice.BOTH, LiveChatRoom.chosen()));
        LiveChatRoom.choose(2);
        assertEquals(new Pick("second", 2, "Bo"), LiveChatRoom.pick(TWO, Role.TV, false, Choice.BOTH, LiveChatRoom.chosen()));
        LiveChatRoom.choose(7);
        assertEquals(2, LiveChatRoom.chosen(), "a bad choice is ignored");
        LiveChatRoom.reset();
        assertEquals(1, LiveChatRoom.chosen());
    }

    @Test
    void aSoloedTvIsInItsOwnStreamsRoomWhateverTheToggle() {
        assertEquals("second", LiveChatRoom.roomOf(LiveChatRoom.pick(TWO, Role.TV, false, Choice.SECOND_ONLY, 1)));
        assertEquals("main", LiveChatRoom.roomOf(LiveChatRoom.pick(TWO, Role.TV, false, Choice.MAIN_ONLY, 2)));
    }

    @Test
    void aLiveEyeWaitingReadsStreamOne() {
        assertEquals(new Pick("main", 1, "Alice"), LiveChatRoom.pick(TWO, Role.EYE, false, Choice.SECOND_ONLY, 2));
        assertEquals(new Pick("second", 1, "Bo"), LiveChatRoom.pick(SWAPPED, Role.EYE, false, Choice.BOTH, 1));
    }

    @Test
    void streamOneAndTwoAreRelativeToTheStatusNotHardCodedSlots() {
        assertEquals(new Pick("second", 1, "Bo"), LiveChatRoom.pick(SWAPPED, Role.TV, false, Choice.BOTH, 1));
        assertEquals(new Pick("main", 2, "Alice"), LiveChatRoom.pick(SWAPPED, Role.TV, false, Choice.BOTH, 2));
        assertEquals("main", LiveChatRoom.roomOf(LiveChatRoom.pick(SWAPPED, Role.TV, false, Choice.SECOND_ONLY, 1)));
    }

    @Test
    void aStreamerIsInTheirClaimsRoom() {
        assertEquals(new Pick("main", 1, "Alice"), LiveChatRoom.pick(TWO, Role.STREAMER, false, Choice.BOTH, 2));
        assertEquals(new Pick("second", 2, "Bo"), LiveChatRoom.pick(TWO, Role.STREAMER, true, Choice.BOTH, 1));
        assertEquals(new Pick("second", 1, "Bo"), LiveChatRoom.pick(SWAPPED, Role.STREAMER, true, Choice.BOTH, 1));
        assertEquals("second", LiveChatRoom.roomOf(LiveChatRoom.pick(Status.OFFLINE, Role.STREAMER, true, Choice.BOTH, 1)),
            "the second slot's streamer reads their room even before a status says so");
    }

    @Test
    void theToggleShowsOnlyForAConnectedTvShowingBothOfTwoStreams() {
        assertTrue(LiveChatRoom.showToggle(true, TWO, Role.TV, Choice.BOTH));
        assertFalse(LiveChatRoom.showToggle(false, TWO, Role.TV, Choice.BOTH), "not in the chat");
        assertFalse(LiveChatRoom.showToggle(true, ONE, Role.TV, Choice.BOTH), "one stream up");
        assertFalse(LiveChatRoom.showToggle(true, TWO, Role.TV, Choice.SECOND_ONLY), "soloed");
        assertFalse(LiveChatRoom.showToggle(true, TWO, Role.STREAMER, Choice.BOTH));
        assertFalse(LiveChatRoom.showToggle(true, TWO, Role.EYE, Choice.BOTH));
    }

    @Test
    void togglingChangesTheRoomSoTheCursorStartsAgain() {
        String before = LiveChatRoom.roomOf(LiveChatRoom.pick(TWO, Role.TV, false, Choice.BOTH, 1));
        String after = LiveChatRoom.roomOf(LiveChatRoom.pick(TWO, Role.TV, false, Choice.BOTH, 2));
        assertFalse(before.equals(after), "each room has its own seqs: the bridge resets its cursor on this change");
    }
}
