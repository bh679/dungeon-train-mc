package games.brennan.dungeontrain.client.credits;

import games.brennan.dungeontrain.client.credits.CommunityLinkClient.Error;
import games.brennan.dungeontrain.client.credits.CommunityLinkClient.Pings;
import games.brennan.dungeontrain.client.credits.CommunityLinkClient.Start;
import games.brennan.dungeontrain.client.credits.CommunityLinkClient.Status;
import games.brennan.dungeontrain.discord.PingType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
        assertEquals(new Status(true, true, false, Map.of(), Error.NONE), CommunityLinkClient.parseStatus(200, "{\"ok\":true,\"linked\":true}"));
        assertEquals(new Status(true, true, true, Map.of(), Error.NONE), CommunityLinkClient.parseStatus(200, "{\"ok\":true,\"linked\":true,\"pings\":true}"));
        assertEquals(new Status(true, false, false, Map.of(), Error.NONE), CommunityLinkClient.parseStatus(200, "{\"ok\":true,\"linked\":false,\"pings\":true}"));
        assertEquals(new Status(true, false, false, Map.of(), Error.NONE), CommunityLinkClient.parseStatus(200, "{\"ok\":true,\"linked\":false}"));
        assertEquals(Status.of(Error.FAILED), CommunityLinkClient.parseStatus(200, "{\"linked\":\"true\"}"));
        assertEquals(Status.of(Error.FAILED), CommunityLinkClient.parseStatus(200, "{}"));
        assertEquals(Status.of(Error.FAILED), CommunityLinkClient.parseStatus(200, "junk"));
        assertEquals(Status.of(Error.UNSUPPORTED), CommunityLinkClient.parseStatus(404, ""));
        assertEquals(Status.of(Error.DISABLED), CommunityLinkClient.parseStatus(503, ""));
        assertEquals(Status.of(Error.FAILED), CommunityLinkClient.parseStatus(502, ""));
    }

    @Test
    @DisplayName("pings toggle: body, new state, and a real not-linked told apart from an older relay")
    void pings() {
        assertEquals("{\"uuid\":\"abc\",\"on\":false}", CommunityLinkClient.buildPingsPayload("abc", false, null).toString());
        assertEquals(new Pings(true, true, Map.of(), Error.NONE), CommunityLinkClient.parsePings(200, "{\"ok\":true,\"pings\":true}"));
        assertEquals(new Pings(true, false, Map.of(), Error.NONE), CommunityLinkClient.parsePings(200, "{\"ok\":true,\"pings\":false}"));
        assertEquals(Pings.of(Error.FAILED), CommunityLinkClient.parsePings(200, "{\"ok\":true}"));
        assertEquals(Pings.of(Error.NOT_LINKED), CommunityLinkClient.parsePings(404, "{\"error\":\"not_linked\"}"));
        assertEquals(Pings.of(Error.UNSUPPORTED), CommunityLinkClient.parsePings(404, "{\"error\":\"not_found\"}"));
        assertEquals(Pings.of(Error.DISABLED), CommunityLinkClient.parsePings(503, ""));
        assertEquals(Pings.of(Error.FAILED), CommunityLinkClient.parsePings(400, ""));
    }

    @Test
    @DisplayName("per-kind pings: read from types, missing kinds stay on, and the master switch wins")
    void pingKinds() {
        Status s = CommunityLinkClient.parseStatus(200,
                "{\"ok\":true,\"linked\":true,\"pings\":true,\"types\":{\"death\":false,\"milestone\":true,\"bogus\":false,\"mob_photo\":\"no\"}}");
        assertEquals(false, s.types().get(PingType.DEATH));
        assertEquals(true, s.types().get(PingType.MOB_PHOTO));
        assertEquals(true, s.types().get(PingType.OWN_TRIBUTE));
        assertEquals(PingType.values().length, s.types().size());
        assertEquals(false, s.pings(PingType.DEATH));
        assertEquals(true, s.pings(PingType.MILESTONE));
        Status masterOff = CommunityLinkClient.parseStatus(200, "{\"ok\":true,\"linked\":true,\"pings\":false}");
        assertEquals(false, masterOff.pings(PingType.MILESTONE));
        assertEquals("{\"uuid\":\"abc\",\"on\":true,\"type\":\"photo_tributed\"}",
                CommunityLinkClient.buildPingsPayload("abc", true, PingType.PHOTO_TRIBUTED).toString());
        Pings p = CommunityLinkClient.parsePings(200, "{\"ok\":true,\"pings\":true,\"types\":{\"own_tribute\":false}}");
        assertEquals(false, p.types().get(PingType.OWN_TRIBUTE));
    }
}
