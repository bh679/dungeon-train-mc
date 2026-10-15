package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonParser;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatPage;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Result;
import games.brennan.dungeontrain.net.relay.LiveTakeoverClient.Holder;
import games.brennan.dungeontrain.net.relay.LiveTakeoverClient.Poll;
import games.brennan.dungeontrain.net.relay.LiveTakeoverClient.PollLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveTakeoverClientTest {

    private static Result result(int status, String json) {
        return new Result(status, JsonParser.parseString(json).getAsJsonObject(), null);
    }

    @Test
    @DisplayName("a 409 too_soon needs a vote, occupied is the wearer's call; anything else is not a block")
    void blocked() {
        Holder soon = LiveTakeoverClient.parseBlocked(result(409,
            "{\"error\":\"too_soon\",\"holder\":\"Alice\",\"tenureMs\":240000,\"takeoverMinMs\":600000,\"streamCount\":3,\"retryAfterMs\":360000}"));
        assertEquals(new Holder("Alice", 240000, 600000, 3, true), soon);
        Holder occupied = LiveTakeoverClient.parseBlocked(result(409,
            "{\"error\":\"occupied\",\"holder\":\"Alice\",\"tenureMs\":700000,\"takeoverMinMs\":600000,\"streamCount\":1}"));
        assertFalse(occupied.voteNeeded());
        assertNull(LiveTakeoverClient.parseBlocked(result(409, "{\"error\":\"rate_limited\"}")));
        assertNull(LiveTakeoverClient.parseBlocked(result(200, "{\"ok\":true}")));
        assertNull(LiveTakeoverClient.parseBlocked(new Result(0, null, new RuntimeException())));
    }

    @Test
    @DisplayName("the prompt's button changes over once the counter passes the protected minutes")
    void voteNeededAfter() {
        Holder h = new Holder("Alice", 590_000, 600_000, 1, true);
        assertTrue(h.voteNeededAfter(9_999));
        assertFalse(h.voteNeededAfter(10_000));
    }

    @Test
    @DisplayName("an old relay's 409 with no holder still blocks, as Someone, at least their 1st time")
    void oldRelay() {
        Holder h = LiveTakeoverClient.parseBlocked(result(409, "{\"error\":\"too_soon\",\"retryAfterMs\":30000}"));
        assertEquals("Someone", h.holder());
        assertEquals(1, h.streamCount());
    }

    @Test
    @DisplayName("a vote's status: open, approved with the requester's ticket, rejected with the totals")
    void poll() {
        Poll open = LiveTakeoverClient.parsePoll(result(200, "{\"ok\":true,\"id\":\"p1\",\"state\":\"open\",\"endsTs\":123}"));
        assertTrue(open.open());
        assertEquals(123, open.endsTs());
        Poll approved = LiveTakeoverClient.parsePoll(result(200, "{\"ok\":true,\"id\":\"p1\",\"state\":\"approved\",\"yes\":4,\"no\":2,\"ticket\":\"abc\"}"));
        assertTrue(approved.approved());
        assertEquals("abc", approved.ticket());
        Poll rejected = LiveTakeoverClient.parsePoll(result(200, "{\"ok\":true,\"id\":\"p1\",\"state\":\"rejected\",\"yes\":1,\"no\":3}"));
        assertTrue(rejected.rejected());
        assertNull(rejected.ticket());
        assertNull(LiveTakeoverClient.parsePoll(result(409, "{\"error\":\"busy\"}")));
    }

    @Test
    @DisplayName("a chat line carries its vote: opening (no totals yet) and result")
    void pollLine() {
        ChatPage p = LiveFeedClient.parseChat(result(200, """
            {"live":true,"seq":2,"messages":[
              {"seq":1,"author":"Dungeon Train","content":"Bob wants to take over","source":"poll",
               "poll":{"id":"p1","requester":"Bob","holder":"Alice","holderUuid":"aa","endsTs":99,"holderWeight":2,"state":"open"}},
              {"seq":2,"author":"Dungeon Train","content":"Takeover rejected","source":"poll",
               "poll":{"id":"p1","requester":"Bob","holder":"Alice","endsTs":99,"holderWeight":2,"state":"rejected","yes":1,"no":3}}
            ]}"""));
        PollLine opening = p.lines().get(0).poll();
        assertTrue(opening.open());
        assertEquals("aa", opening.holderUuid());
        assertEquals(2, opening.holderWeight());
        assertEquals(-1, opening.yes());
        PollLine closed = p.lines().get(1).poll();
        assertFalse(closed.open());
        assertNull(closed.holderUuid());
        assertEquals(3, closed.no());
    }
}
