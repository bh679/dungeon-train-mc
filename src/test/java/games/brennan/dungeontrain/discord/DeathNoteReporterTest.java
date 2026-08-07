package games.brennan.dungeontrain.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.narrative.NoteKind;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code /deathnotes/submit} body shape ({@link DeathNoteReporter#buildPayload}) so the
 * DT → relay contract can be checked without a running server (mirrors the deathnotes.js normalise).
 */
class DeathNoteReporterTest {

    @Test
    void payloadMatchesTheRelayContract() {
        JsonObject body = DeathNoteReporter.buildPayload(
                "aaaa", "Author", "Victim", "bbbb", 7, "seed-123", "skin:x", true, NoteKind.DEATH);
        assertEquals("aaaa", body.get("authorUuid").getAsString());
        assertEquals("Author", body.get("authorName").getAsString());
        assertEquals("Victim", body.get("targetName").getAsString());
        assertEquals("bbbb", body.get("targetUuid").getAsString());
        assertEquals(7, body.get("deathCarriage").getAsInt());
        assertEquals("seed-123", body.get("worldKey").getAsString());
        assertEquals("skin:x", body.get("authorSkinRef").getAsString());
        assertEquals(true, body.get("freePlay").getAsBoolean());
        assertEquals("death", body.get("kind").getAsString());
    }

    @Test
    void loveNotesRideTheSameSubmitBodyAndDifferOnlyByKind() {
        JsonObject body = DeathNoteReporter.buildPayload(
                "aaaa", "Author", "Beloved", "bbbb", 7, "seed-123", "skin:x", false, NoteKind.LOVE);
        assertEquals("love", body.get("kind").getAsString());
        assertEquals("Beloved", body.get("targetName").getAsString());
        assertEquals(9, body.size()); // same eight fields as a curse, plus the kind
    }

    @Test
    void aNullKindSubmitsAsTheCurse() {
        // Matches the relay's own fallback (deathnotes.js normKind) and NoteKind.fromId: anything
        // that isn't explicitly a Love Note is the curse, which is what every note used to be.
        JsonObject body = DeathNoteReporter.buildPayload(
                "aaaa", "Author", "Victim", "bbbb", 7, "seed-123", "", false, null);
        assertEquals("death", body.get("kind").getAsString());
    }

    @Test
    void nullStringsBecomeEmptyAndNegativeCarriageIsKept() {
        JsonObject body = DeathNoteReporter.buildPayload(
                null, null, null, null, -3, null, null, false, NoteKind.DEATH);
        assertEquals("", body.get("authorUuid").getAsString());
        assertEquals("", body.get("authorName").getAsString());
        assertEquals("", body.get("targetName").getAsString());
        assertEquals("", body.get("targetUuid").getAsString());
        assertEquals(-3, body.get("deathCarriage").getAsInt()); // carriage index is signed
        assertEquals("", body.get("worldKey").getAsString());
        assertEquals("", body.get("authorSkinRef").getAsString());
        assertEquals(false, body.get("freePlay").getAsBoolean());
    }

    @Test
    void outcomePayloadCarriesTheNoteIdAndTheEnding() {
        JsonObject body = DeathNoteReporter.buildOutcomePayload(
            12, DeathNoteReporter.OUTCOME_ECHO_KILLED_TARGET);
        assertEquals(12, body.get("id").getAsInt());
        assertEquals("echo_killed_target", body.get("outcome").getAsString());
        assertEquals(2, body.size()); // the relay validates the ending itself — send nothing else
    }

    @Test
    void outcomeConstantsMatchTheRelaysAcceptedValues() {
        // These two strings are the contract with dp-relay's deathnotes.OUTCOMES; a typo here means
        // the ending is silently rejected and the author's story book says "no one knows".
        assertEquals("echo_killed_target", DeathNoteReporter.OUTCOME_ECHO_KILLED_TARGET);
        assertEquals("target_killed_echo", DeathNoteReporter.OUTCOME_TARGET_KILLED_ECHO);
    }
}
