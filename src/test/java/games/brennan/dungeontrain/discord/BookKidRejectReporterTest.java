package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.net.BookKidRejectPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Assembly tests for {@link BookKidRejectReporter#buildPayload} — the exact shape the relay's
 * {@code POST /books/kidreject} normalises, which is {@code bookreports.normalise}, the same one
 * {@code /books/report} uses. Pure, no Minecraft bootstrap. Mirrors {@link BookReportReporterTest},
 * because the payload is deliberately identical: the two controls differ in what the relay DOES with
 * the verdict, not in what the mod has to say to describe it.
 */
class BookKidRejectReporterTest {

    @Test
    @DisplayName("payload carries uuid, player, identity and clientTsMs")
    void coreShape() {
        JsonObject out = BookKidRejectReporter.buildPayload("abc123", "Notch",
            new BookKidRejectPacket("shared", "42"), 1_712_000_000_000L);
        assertEquals("abc123", out.get("uuid").getAsString());
        assertEquals("Notch", out.get("player").getAsString());
        assertEquals("shared", out.get("bookType").getAsString());
        assertEquals("42", out.get("bookId").getAsString());
        assertEquals(1_712_000_000_000L, out.get("clientTsMs").getAsLong());
    }

    @Test
    @DisplayName("no vote, rating or reason field — the control is one-tap and says one thing")
    void carriesNoVerdictFields() {
        JsonObject out = BookKidRejectReporter.buildPayload("u", "Notch",
            new BookKidRejectPacket("shared", "7"), 5L);
        assertFalse(out.has("vote"));
        assertFalse(out.has("reason"));
        assertFalse(out.has("rating"), "the rating is the relay's to decide, not the client's to send");
        assertFalse(out.has("variantIndex"));
        assertEquals(5, out.entrySet().size(), "uuid, player, bookType, bookId, clientTsMs");
    }

    @Test
    @DisplayName("bookType rides along so the relay can refuse anything but a player-written book")
    void carriesBookType() {
        JsonObject out = BookKidRejectReporter.buildPayload("u", "Notch",
            new BookKidRejectPacket("random", "quiet_rules"), 5L);
        assertEquals("random", out.get("bookType").getAsString());
    }

    @Test
    @DisplayName("blank player name is omitted")
    void blankPlayerOmitted() {
        JsonObject out = BookKidRejectReporter.buildPayload("u", "",
            new BookKidRejectPacket("shared", "7"), 5L);
        assertFalse(out.has("player"));
    }

    @Test
    @DisplayName("null player name is omitted")
    void nullPlayerOmitted() {
        JsonObject out = BookKidRejectReporter.buildPayload("u", null,
            new BookKidRejectPacket("shared", "7"), 5L);
        assertFalse(out.has("player"));
    }
}
