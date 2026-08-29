package games.brennan.dungeontrain.net.relay;

import games.brennan.dungeontrain.debug.DebugAccessGrants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the {@code GET /debug-grants} wire shape — the one contract the relay and every shipped jar
 * share.
 *
 * <p>The distinction these tests exist for: a parsed response with a {@code null} grant means the
 * relay positively says "no access" and any cached grant should be revoked, whereas a {@code null}
 * response means "no usable answer" and the caller must change nothing. Collapsing the two would
 * turn a malformed body — or a relay outage that still returns 200 — into a silent revocation.</p>
 */
final class DebugGrantClientTest {

    @Test
    @DisplayName("a live grant parses with its expiry and source")
    void parse_grant() {
        DebugGrantClient.Response resp = DebugGrantClient.parse(
            "{\"ok\":true,\"grant\":{\"expiresAtMs\":1790000000000,\"source\":\"discord-thread\"}}");

        assertNotNull(resp);
        assertNotNull(resp.grant());
        assertEquals(1790000000000L, resp.grant().expiresAtMs());
        assertEquals("discord-thread", resp.grant().source());
    }

    @Test
    @DisplayName("expiresAtMs 0 parses as a forever grant, not as absent")
    void parse_foreverGrant() {
        DebugGrantClient.Response resp = DebugGrantClient.parse(
            "{\"ok\":true,\"grant\":{\"expiresAtMs\":0,\"source\":\"admin-page\"}}");

        assertNotNull(resp);
        assertNotNull(resp.grant());
        assertEquals(DebugAccessGrants.NEVER_EXPIRES, resp.grant().expiresAtMs());
    }

    @Test
    @DisplayName("an explicit null grant is an answer — revoke")
    void parse_noGrant_isAnAnswer() {
        DebugGrantClient.Response resp = DebugGrantClient.parse("{\"ok\":true,\"grant\":null}");

        assertNotNull(resp, "the relay answered; this must not be mistaken for a failed fetch");
        assertNull(resp.grant());
    }

    @Test
    @DisplayName("a missing grant key is also an answer — revoke")
    void parse_missingGrantKey_isAnAnswer() {
        DebugGrantClient.Response resp = DebugGrantClient.parse("{\"ok\":true}");

        assertNotNull(resp);
        assertNull(resp.grant());
    }

    @Test
    @DisplayName("ok:false is no answer at all — change nothing")
    void parse_notOk_isNoAnswer() {
        assertNull(DebugGrantClient.parse("{\"ok\":false,\"error\":\"nope\"}"));
    }

    @Test
    @DisplayName("a grant missing its expiry is malformed, not a forever grant")
    void parse_grantWithoutExpiry_isNoAnswer() {
        assertNull(DebugGrantClient.parse("{\"ok\":true,\"grant\":{\"source\":\"chat-button\"}}"));
    }

    @Test
    @DisplayName("a non-object body is no answer")
    void parse_nonObject_isNoAnswer() {
        assertNull(DebugGrantClient.parse("[]"));
    }

    @Test
    @DisplayName("a grant with no source parses with an empty source")
    void parse_grantWithoutSource() {
        DebugGrantClient.Response resp = DebugGrantClient.parse(
            "{\"ok\":true,\"grant\":{\"expiresAtMs\":1790000000000}}");

        assertNotNull(resp);
        assertNotNull(resp.grant());
        assertEquals("", resp.grant().source());
    }
}
