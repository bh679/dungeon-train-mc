package games.brennan.dungeontrain.net.relay;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing of the relay's {@code /books/submit} answer — the pure half of
 * {@link SharedBookSubmitResponses} (the chat half needs a running server). What matters here is that
 * only a real refusal produces a verdict: an accepted book, an older relay, a network failure or an
 * unattributable request must all leave the game exactly as it was.
 */
class SharedBookSubmitResponsesTest {

    private static final String REQ = "{\"uuid\":\"069a79f444e94726a5befca90e38aaf5\",\"title\":\"A Gentle Tale\"}";
    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    void aDuplicateRefusalNamesThePlayerTheWindowAndTheLadder() {
        SharedBookSubmitResponses.Verdict v = SharedBookSubmitResponses.parse(
                REQ, 409, "{\"error\":\"duplicate_book\",\"id\":7,\"minor\":false,\"remainingSec\":30,\"strikes\":1}");
        assertNotNull(v);
        assertEquals(PLAYER, v.player());
        assertEquals(30L, v.remainingSec());
        assertEquals(1, v.strikes());
        assertTrue(v.duplicate(), "this is the book that started the pause");
    }

    @Test
    void aBlockedSubmitDuringTheWindowIsAVerdictButNotTheDuplicate() {
        SharedBookSubmitResponses.Verdict v = SharedBookSubmitResponses.parse(
                REQ, 403, "{\"error\":\"suspended\",\"remainingSec\":12,\"strikes\":2}");
        assertNotNull(v);
        assertFalse(v.duplicate());
        assertEquals(12L, v.remainingSec());
    }

    @Test
    void anAcceptedBookSaysNothing() {
        assertNull(SharedBookSubmitResponses.parse(REQ, 200, "{\"ok\":true,\"id\":7,\"flag\":\"approved\"}"));
    }

    @Test
    void anOlderRelayThatStillDedupesSilentlySaysNothing() {
        assertNull(SharedBookSubmitResponses.parse(REQ, 200, "{\"ok\":true,\"id\":7,\"deduped\":true}"));
    }

    @Test
    void otherRefusalsAreNotSuspensions() {
        assertNull(SharedBookSubmitResponses.parse(REQ, 429, "{\"error\":\"rate_limited\"}"));
        assertNull(SharedBookSubmitResponses.parse(REQ, 400, "{\"error\":\"empty_title\"}"));
        assertNull(SharedBookSubmitResponses.parse(REQ, 403, "{\"error\":\"forbidden\"}"),
                "a 403 that is not OUR suspension is left alone");
    }

    @Test
    void networkFailuresAndGarbledBodiesAreIgnored() {
        assertNull(SharedBookSubmitResponses.parse(REQ, -1, null));
        assertNull(SharedBookSubmitResponses.parse(REQ, 409, "not json at all"));
        assertNull(SharedBookSubmitResponses.parse(REQ, 409, ""));
    }

    @Test
    void aLapsedWindowIsNotNews() {
        assertNull(SharedBookSubmitResponses.parse(
                REQ, 403, "{\"error\":\"suspended\",\"remainingSec\":0,\"strikes\":1}"));
    }

    @Test
    void aRequestWeCannotAttributeToAPlayerIsDropped() {
        String dupe = "{\"error\":\"duplicate_book\",\"remainingSec\":30,\"strikes\":1}";
        assertNull(SharedBookSubmitResponses.parse("{\"title\":\"no uuid here\"}", 409, dupe));
        assertNull(SharedBookSubmitResponses.parse("{\"uuid\":\"too-short\"}", 409, dupe));
        assertNull(SharedBookSubmitResponses.parse(null, 409, dupe));
    }

    @Test
    void aDashedUuidIsAcceptedToo() {
        SharedBookSubmitResponses.Verdict v = SharedBookSubmitResponses.parse(
                "{\"uuid\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\"}", 409,
                "{\"error\":\"duplicate_book\",\"remainingSec\":30,\"strikes\":1}");
        assertNotNull(v);
        assertEquals(PLAYER, v.player());
    }
}
