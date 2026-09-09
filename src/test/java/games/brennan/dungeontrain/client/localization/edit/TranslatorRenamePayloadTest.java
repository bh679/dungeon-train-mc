package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rename request's body and the reading of the relay's answer — pure, no relay. */
class TranslatorRenamePayloadTest {

    @Test
    @DisplayName("the body is {uuid, from, to}, matching the relay's contract")
    void payloadShape() {
        JsonObject body = TranslatorRenameClient.buildPayload("abc", "Old", "New");
        assertEquals("abc", body.get("uuid").getAsString());
        assertEquals("Old", body.get("from").getAsString());
        assertEquals("New", body.get("to").getAsString());
        assertEquals(3, body.size());
    }

    @Test
    @DisplayName("a 2xx succeeds and carries how many rows were renamed")
    void success() {
        TranslatorRenameClient.Result r = TranslatorRenameClient.interpret(200, "{\"ok\":true,\"updated\":7}");
        assertTrue(r.ok());
        assertEquals(TranslatorRenameClient.Error.NONE, r.error());
        assertEquals(7, r.updated());
    }

    @Test
    @DisplayName("the relay's two refusals are told apart, because the translator can act on them")
    void refusals() {
        assertEquals(TranslatorRenameClient.Error.NAME_TAKEN,
            TranslatorRenameClient.interpret(409, "{\"error\":\"name_taken\"}").error());
        assertEquals(TranslatorRenameClient.Error.NOT_YOURS,
            TranslatorRenameClient.interpret(409, "{\"error\":\"not_yours\"}").error());
        assertEquals(TranslatorRenameClient.Error.FAILED,
            TranslatorRenameClient.interpret(409, "not json").error());
    }

    @Test
    @DisplayName("a relay without the endpoint is 'unsupported', not 'failed'")
    void olderRelay() {
        for (int status : List.of(404, 405, 501)) {
            assertEquals(TranslatorRenameClient.Error.UNSUPPORTED,
                TranslatorRenameClient.interpret(status, "").error(), "HTTP " + status);
        }
        assertEquals(TranslatorRenameClient.Error.RATE_LIMITED, TranslatorRenameClient.interpret(429, "").error());
        assertEquals(TranslatorRenameClient.Error.FAILED, TranslatorRenameClient.interpret(500, "").error());
        assertFalse(TranslatorRenameClient.interpret(400, "{\"error\":\"bad_name\"}").ok());
    }

    @Test
    @DisplayName("own names are the distinct credited names on submissions the relay has")
    void ownNames() {
        List<TranslationSubmission> history = List.of(
            new TranslationSubmission(3L, "de_de", " Ada ", 2, 1, 1, 0, 0, false, false),
            new TranslationSubmission(2L, "fr_fr", "Ada", 1, 0, 1, 0, 0, false, false),
            new TranslationSubmission(1L, "de_de", "", 1, 0, 0, 0, 1, false, false),
            new TranslationSubmission(4L, "de_de", "Bea", 1, 0, 0, 0, 0, false, false),
            TranslationSubmission.queued(5L, "de_de", "Queued Only", 1),
            TranslationSubmission.unsubmitted("de_de", 0));
        assertEquals(Set.of("Ada", "Bea"), TranslatorOwnNames.namesOf(history));
        assertEquals(Set.of(), TranslatorOwnNames.namesOf(null));
    }
}
