package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.net.BookVotePacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembly tests for {@link BookVoteReporter#buildPayload} — the exact shape the relay's
 * {@code POST /books/vote} normalises. Pure, no Minecraft bootstrap. Mirrors
 * {@link BookReadReporterTest}: the one bookType-specific branch is the {@code variantIndex}
 * inclusion rule (random/starting with a resolved variant only).
 */
class BookVoteReporterTest {

    @Test
    @DisplayName("payload carries uuid, player, identity, vote and clientTsMs")
    void coreShape() {
        JsonObject out = BookVoteReporter.buildPayload("abc123", "Notch",
            new BookVotePacket("shared", "42", 1, -1), 1_712_000_000_000L);
        assertEquals("abc123", out.get("uuid").getAsString());
        assertEquals("Notch", out.get("player").getAsString());
        assertEquals("shared", out.get("bookType").getAsString());
        assertEquals("42", out.get("bookId").getAsString());
        assertEquals(1, out.get("vote").getAsInt());
        assertEquals(1_712_000_000_000L, out.get("clientTsMs").getAsLong());
        assertFalse(out.has("variantIndex"), "shared books never carry variantIndex");
    }

    @Test
    @DisplayName("downvote is carried as -1")
    void downvote() {
        JsonObject out = BookVoteReporter.buildPayload("abc123", "Notch",
            new BookVotePacket("random", "the_lost_miner", -1, 2), 5L);
        assertEquals(-1, out.get("vote").getAsInt());
    }

    @Test
    @DisplayName("random/starting books include a resolved variantIndex, omit -1")
    void variantInclusionRule() {
        JsonObject random = BookVoteReporter.buildPayload("u", null,
            new BookVotePacket("random", "b", 1, 2), 5L);
        assertTrue(random.has("variantIndex"));
        assertEquals(2, random.get("variantIndex").getAsInt());

        JsonObject starting = BookVoteReporter.buildPayload("u", null,
            new BookVotePacket("starting", "b", 1, 0), 5L);
        assertTrue(starting.has("variantIndex"));

        JsonObject unresolved = BookVoteReporter.buildPayload("u", null,
            new BookVotePacket("random", "b", 1, -1), 5L);
        assertFalse(unresolved.has("variantIndex"));

        JsonObject narrative = BookVoteReporter.buildPayload("u", null,
            new BookVotePacket("narrative", "story#1", 1, 3), 5L);
        assertFalse(narrative.has("variantIndex"), "narrative books never carry variantIndex");
    }

    @Test
    @DisplayName("blank player name is omitted")
    void blankPlayerOmitted() {
        JsonObject out = BookVoteReporter.buildPayload("u", "",
            new BookVotePacket("starting", "welcome", 1, -1), 5L);
        assertFalse(out.has("player"));
    }
}
