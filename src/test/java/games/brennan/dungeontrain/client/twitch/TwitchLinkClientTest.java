package games.brennan.dungeontrain.client.twitch;

import games.brennan.dungeontrain.client.twitch.TwitchLinkClient.Error;
import games.brennan.dungeontrain.client.twitch.TwitchLinkClient.Start;
import games.brennan.dungeontrain.client.twitch.TwitchLinkClient.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure half of the Twitch link client: payloads and the relay's answers. */
class TwitchLinkClientTest {

    @Test
    void startParsesCodeAndPage() {
        Start s = TwitchLinkClient.parseStart(200, "{\"ok\":true,\"code\":\"AB2CDE\",\"url\":\"https://brennan.games/dungeontrain/link/twitch/?code=AB2CDE\",\"expiresInSec\":900}");
        assertEquals(new Start(true, "AB2CDE", "https://brennan.games/dungeontrain/link/twitch/?code=AB2CDE", 900, Error.NONE), s);
        assertEquals(Error.FAILED, TwitchLinkClient.parseStart(200, "{\"ok\":true,\"code\":\"AB2CDE\"}").error(), "no page, no link");
        assertEquals(Error.FAILED, TwitchLinkClient.parseStart(200, "{\"ok\":true,\"code\":\"AB2CDE\",\"url\":\"http://evil/\"}").error(), "https only");
        assertEquals(Error.RATE_LIMITED, TwitchLinkClient.parseStart(429, "{}").error());
        assertEquals(Error.DISABLED, TwitchLinkClient.parseStart(503, "{\"error\":\"twitch_link_unconfigured\"}").error());
        assertEquals(Error.UNSUPPORTED, TwitchLinkClient.parseStart(404, "not found").error());
        assertEquals(Error.FAILED, TwitchLinkClient.parseStart(500, "").error());
    }

    @Test
    void statusParsesTheLinkAndOptIns() {
        Status s = TwitchLinkClient.parseStatus(200, "{\"ok\":true,\"linked\":true,\"verified\":true,\"login\":\"Pat_1\",\"display\":\"Pat\",\"restream\":true,\"setTitle\":false}");
        assertEquals(new Status(true, true, true, "pat_1", "Pat", true, false, Error.NONE), s);
        Status typed = TwitchLinkClient.parseStatus(200, "{\"ok\":true,\"linked\":true,\"verified\":false,\"login\":\"steve_streams\",\"display\":\"SteveStreams\"}");
        assertEquals(new Status(true, true, false, "steve_streams", "SteveStreams", false, false, Error.NONE), typed);
        assertEquals("https://twitch.tv/pat_1", s.url());
        Status off = TwitchLinkClient.parseStatus(200, "{\"ok\":true,\"linked\":false,\"login\":\"x\",\"restream\":true}");
        assertEquals(new Status(true, false, false, "", "", false, false, Error.NONE), off, "unlinked carries nothing, whatever else the body says");
        assertEquals("", off.url());
        assertEquals(Error.FAILED, TwitchLinkClient.parseStatus(200, "{\"ok\":true}").error(), "no readable linked");
        assertEquals(Error.FAILED, TwitchLinkClient.parseStatus(200, "{\"ok\":true,\"linked\":true,\"login\":\"bad login\"}").error(), "login outside Twitch's alphabet");
        assertEquals(Error.NOT_LINKED, TwitchLinkClient.parseStatus(404, "{\"error\":\"not_linked\"}").error());
        assertEquals(Error.UNSUPPORTED, TwitchLinkClient.parseStatus(404, "<html>").error(), "an older relay 404s the path");
    }

    @Test
    void claimParsesATypedHandleAndItsRefusals() {
        Status s = TwitchLinkClient.parseClaim(200, "{\"ok\":true,\"login\":\"Steve_Streams\",\"display\":\"SteveStreams\",\"verified\":false}");
        assertEquals(new Status(true, true, false, "steve_streams", "SteveStreams", false, false, Error.NONE), s);
        assertEquals(Error.UNKNOWN_CHANNEL, TwitchLinkClient.parseClaim(404, "{\"error\":\"unknown_channel\"}").error());
        assertEquals(Error.ALREADY_VERIFIED, TwitchLinkClient.parseClaim(409, "{\"error\":\"already_verified\",\"login\":\"pat_1\"}").error());
        assertEquals(Error.BAD_LOGIN, TwitchLinkClient.parseClaim(400, "{\"error\":\"bad_login\"}").error());
        assertEquals(Error.NOT_VERIFIED, TwitchLinkClient.parseStatus(403, "{\"error\":\"not_verified\"}").error());
        assertEquals(Error.UNSUPPORTED, TwitchLinkClient.parseClaim(404, "nope").error(), "an older relay");
    }

    @Test
    void unlinkAndPayload() {
        assertTrue(TwitchLinkClient.parseUnlink(200, "{\"ok\":true,\"revoked\":true}").ok());
        assertEquals(Error.NOT_LINKED, TwitchLinkClient.parseUnlink(404, "{\"error\":\"not_linked\"}").error());
        assertFalse(TwitchLinkClient.parseUnlink(503, "{}").ok());
        assertEquals("{\"uuid\":\"abc\"}", TwitchLinkClient.payload("abc").toString());
        assertEquals("{\"uuid\":\"\"}", TwitchLinkClient.payload(null).toString());
    }
}
