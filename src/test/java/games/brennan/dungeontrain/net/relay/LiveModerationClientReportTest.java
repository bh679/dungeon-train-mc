package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.net.relay.LiveModerationClient.ReportReply;
import games.brennan.dungeontrain.net.relay.LiveModerationClient.ReportReply.Kind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The /live/report body and the relay's answers to it. */
final class LiveModerationClientReportTest {

    private static final UUID ME = UUID.fromString("12345678-9abc-def0-1234-56789abcdef0");
    private static final UUID AL = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void bodyCarriesTheContractFields() {
        JsonObject o = JsonParser.parseString(LiveModerationClient.reportBody(ME, "Me", AL, "Alice", "second",
            "harassment", false)).getAsJsonObject();
        assertEquals("123456789abcdef0123456789abcdef0", o.get("uuid").getAsString());
        assertEquals("Me", o.get("name").getAsString());
        assertEquals("0000000000000000000000000000000b", o.get("streamerUuid").getAsString());
        assertEquals("Alice", o.get("streamerName").getAsString());
        assertEquals("second", o.get("channel").getAsString());
        assertEquals("harassment", o.get("reason").getAsString());
        assertFalse(o.get("abandon").getAsBoolean());
    }

    @Test
    void unknownStreamerUuidAndOddChannel() {
        JsonObject o = JsonParser.parseString(LiveModerationClient.reportBody(ME, null, null, null, "dev",
            null, false)).getAsJsonObject();
        assertFalse(o.has("streamerUuid"), "an unknown streamer uuid is left out, never sent empty");
        assertEquals("", o.get("streamerName").getAsString());
        assertEquals("main", o.get("channel").getAsString());
        assertEquals("", o.get("reason").getAsString());
    }

    @Test
    void abandonResendKeepsEverythingElse() {
        String body = LiveModerationClient.reportBody(ME, "Me", AL, "Alice", "main", "other", false);
        JsonObject o = JsonParser.parseString(LiveModerationClient.withAbandon(body)).getAsJsonObject();
        assertTrue(o.get("abandon").getAsBoolean());
        assertEquals("Alice", o.get("streamerName").getAsString());
        assertEquals("other", o.get("reason").getAsString());
    }

    @Test
    void okIsSent() {
        assertEquals(Kind.SENT, LiveModerationClient.parseReportReply(200,
            "{\"ok\":true,\"reportId\":\"r1\",\"status\":\"open\"}").kind());
        assertEquals(Kind.FAILED, LiveModerationClient.parseReportReply(200, "{\"ok\":false}").kind());
    }

    @Test
    void openReportCarriesWhenAndWho() {
        ReportReply r = LiveModerationClient.parseReportReply(409,
            "{\"error\":\"open_report\",\"existing\":{\"reportId\":\"r1\",\"createdAt\":1790000000000,\"streamerName\":\"Alice\"}}");
        assertEquals(Kind.OPEN, r.kind());
        assertEquals(1790000000000L, r.existingCreatedAt());
        assertEquals("Alice", r.existingStreamerName());
    }

    @Test
    void createdAtAsSecondsOrIso() {
        assertEquals(1790000000000L, LiveModerationClient.parseReportReply(409,
            "{\"error\":\"open_report\",\"existing\":{\"createdAt\":1790000000}}").existingCreatedAt());
        assertEquals(1790000000000L, LiveModerationClient.parseReportReply(409,
            "{\"error\":\"open_report\",\"existing\":{\"createdAt\":\"2026-09-21T14:13:20Z\"}}").existingCreatedAt());
        ReportReply bare = LiveModerationClient.parseReportReply(409, "{\"error\":\"open_report\"}");
        assertEquals(Kind.OPEN, bare.kind());
        assertEquals(0L, bare.existingCreatedAt());
        assertEquals("", bare.existingStreamerName());
    }

    @Test
    void refusals() {
        assertEquals(Kind.NOT_LIVE, LiveModerationClient.parseReportReply(400, "{\"error\":\"not_live\"}").kind());
        assertEquals(Kind.SELF, LiveModerationClient.parseReportReply(400, "{\"error\":\"self_report\"}").kind());
        // Any other 400 is a bad request, not a stream that ended.
        assertEquals(Kind.FAILED, LiveModerationClient.parseReportReply(400, "{\"error\":\"bad_uuid\"}").kind());
        assertEquals(Kind.FAILED, LiveModerationClient.parseReportReply(400, "").kind());
        assertEquals(Kind.BANNED, LiveModerationClient.parseReportReply(403, "").kind());
        assertEquals(Kind.RATE_LIMITED, LiveModerationClient.parseReportReply(429, "nope").kind());
    }

    @Test
    void anythingElseFails() {
        assertEquals(Kind.FAILED, LiveModerationClient.parseReportReply(409, "{\"error\":\"other\"}").kind());
        assertEquals(Kind.FAILED, LiveModerationClient.parseReportReply(404, "Not Found").kind());
        assertEquals(Kind.FAILED, LiveModerationClient.parseReportReply(500, "{}").kind());
        assertEquals(Kind.FAILED, LiveModerationClient.parseReportReply(200, "<html>").kind());
    }
}
