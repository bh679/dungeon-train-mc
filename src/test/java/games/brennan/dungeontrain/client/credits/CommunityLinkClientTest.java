package games.brennan.dungeontrain.client.credits;

import games.brennan.dungeontrain.client.credits.CommunityLinkClient.Error;
import games.brennan.dungeontrain.client.credits.CommunityLinkClient.Start;
import games.brennan.dungeontrain.client.credits.CommunityLinkClient.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The /dtlink handshake's request body and the reading of the relay's two answers — pure, no relay. */
final class CommunityLinkClientTest {

    @Test
    @DisplayName("the start body is {uuid}")
    void startPayload() {
        assertEquals("{\"uuid\":\"abc\"}", CommunityLinkClient.buildStartPayload("abc").toString());
        assertEquals("{\"uuid\":\"\"}", CommunityLinkClient.buildStartPayload(null).toString());
    }

    @Test
    @DisplayName("a 2xx with a code is a start; without one it failed; the status codes map to what the player can act on")
    void start() {
        assertEquals(new Start(true, "ABCD23", 900, Error.NONE),
            CommunityLinkClient.parseStart(200, "{\"ok\":true,\"code\":\" ABCD23 \",\"expiresInSec\":900,\"command\":\"/dtlink\"}"));
        assertEquals(new Start(true, "ABCD23", 0, Error.NONE), CommunityLinkClient.parseStart(200, "{\"code\":\"ABCD23\"}"));
        assertEquals(Start.of(Error.FAILED), CommunityLinkClient.parseStart(200, "{\"ok\":true}"));
        assertEquals(Start.of(Error.FAILED), CommunityLinkClient.parseStart(200, "junk"));
        assertEquals(Start.of(Error.RATE_LIMITED), CommunityLinkClient.parseStart(429, "{\"error\":\"rate_limited\"}"));
        assertEquals(Start.of(Error.DISABLED), CommunityLinkClient.parseStart(503, "{\"error\":\"community_disabled\"}"));
        assertEquals(Start.of(Error.UNSUPPORTED), CommunityLinkClient.parseStart(404, ""));
        assertEquals(Start.of(Error.UNSUPPORTED), CommunityLinkClient.parseStart(405, ""));
        assertEquals(Start.of(Error.FAILED), CommunityLinkClient.parseStart(500, ""));
    }

    @Test
    @DisplayName("linked is read only from a boolean; anything unreadable is a failure, never a link")
    void status() {
        assertEquals(new Status(true, true, Error.NONE), CommunityLinkClient.parseStatus(200, "{\"ok\":true,\"linked\":true}"));
        assertEquals(new Status(true, false, Error.NONE), CommunityLinkClient.parseStatus(200, "{\"ok\":true,\"linked\":false}"));
        assertEquals(Status.of(Error.FAILED), CommunityLinkClient.parseStatus(200, "{\"linked\":\"true\"}"));
        assertEquals(Status.of(Error.FAILED), CommunityLinkClient.parseStatus(200, "{}"));
        assertEquals(Status.of(Error.FAILED), CommunityLinkClient.parseStatus(200, "junk"));
        assertEquals(Status.of(Error.UNSUPPORTED), CommunityLinkClient.parseStatus(404, ""));
        assertEquals(Status.of(Error.DISABLED), CommunityLinkClient.parseStatus(503, ""));
        assertEquals(Status.of(Error.FAILED), CommunityLinkClient.parseStatus(502, ""));
    }
}
