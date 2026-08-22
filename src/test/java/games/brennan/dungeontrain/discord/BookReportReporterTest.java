package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.net.BookReportPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Assembly tests for {@link BookReportReporter#buildPayload} — the exact shape the relay's
 * {@code POST /books/report} normalises. Pure, no Minecraft bootstrap. Mirrors
 * {@link BookVoteReporterTest}, minus the vote value and the variantIndex branch: a report carries
 * no value and no reason, only who reported what and when.
 */
class BookReportReporterTest {

    @Test
    @DisplayName("payload carries uuid, player, identity and clientTsMs")
    void coreShape() {
        JsonObject out = BookReportReporter.buildPayload("abc123", "Notch",
            new BookReportPacket("shared", "42"), 1_712_000_000_000L);
        assertEquals("abc123", out.get("uuid").getAsString());
        assertEquals("Notch", out.get("player").getAsString());
        assertEquals("shared", out.get("bookType").getAsString());
        assertEquals("42", out.get("bookId").getAsString());
        assertEquals(1_712_000_000_000L, out.get("clientTsMs").getAsLong());
    }

    @Test
    @DisplayName("no vote or reason field — a report is not a downvote and asks for no free text")
    void carriesNoVerdictFields() {
        JsonObject out = BookReportReporter.buildPayload("u", "Notch",
            new BookReportPacket("shared", "7"), 5L);
        assertFalse(out.has("vote"));
        assertFalse(out.has("reason"));
        assertFalse(out.has("variantIndex"));
        assertEquals(5, out.entrySet().size(), "uuid, player, bookType, bookId, clientTsMs");
    }

    @Test
    @DisplayName("blank player name is omitted")
    void blankPlayerOmitted() {
        JsonObject out = BookReportReporter.buildPayload("u", "",
            new BookReportPacket("shared", "7"), 5L);
        assertFalse(out.has("player"));
    }

    @Test
    @DisplayName("null player name is omitted")
    void nullPlayerOmitted() {
        JsonObject out = BookReportReporter.buildPayload("u", null,
            new BookReportPacket("shared", "7"), 5L);
        assertFalse(out.has("player"));
    }
}
