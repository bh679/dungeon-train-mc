package games.brennan.dungeontrain.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code /deathnotes/submit} body shape ({@link DeathNoteReporter#buildPayload}) so the
 * DT → relay contract can be checked without a running server (mirrors the deathnotes.js normalise).
 */
class DeathNoteReporterTest {

    @Test
    void payloadMatchesTheRelayContract() {
        JsonObject body = DeathNoteReporter.buildPayload(
                "aaaa", "Author", "Victim", "bbbb", 7, "seed-123", "skin:x");
        assertEquals("aaaa", body.get("authorUuid").getAsString());
        assertEquals("Author", body.get("authorName").getAsString());
        assertEquals("Victim", body.get("targetName").getAsString());
        assertEquals("bbbb", body.get("targetUuid").getAsString());
        assertEquals(7, body.get("deathCarriage").getAsInt());
        assertEquals("seed-123", body.get("worldKey").getAsString());
        assertEquals("skin:x", body.get("authorSkinRef").getAsString());
    }

    @Test
    void nullStringsBecomeEmptyAndNegativeCarriageIsKept() {
        JsonObject body = DeathNoteReporter.buildPayload(null, null, null, null, -3, null, null);
        assertEquals("", body.get("authorUuid").getAsString());
        assertEquals("", body.get("authorName").getAsString());
        assertEquals("", body.get("targetName").getAsString());
        assertEquals("", body.get("targetUuid").getAsString());
        assertEquals(-3, body.get("deathCarriage").getAsInt()); // carriage index is signed
        assertEquals("", body.get("worldKey").getAsString());
        assertEquals("", body.get("authorSkinRef").getAsString());
    }
}
