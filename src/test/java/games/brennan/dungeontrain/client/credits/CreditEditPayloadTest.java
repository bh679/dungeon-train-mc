package games.brennan.dungeontrain.client.credits;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.client.credits.CreditEditClient.Action;
import games.brennan.dungeontrain.client.credits.CreditEditClient.Section;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The edit request's body and the reading of the relay's answer — pure, no relay. */
class CreditEditPayloadTest {

    @Test
    @DisplayName("a rename body is {uuid, section: all, action, from, to}, matching the relay's contract")
    void renamePayload() {
        JsonObject body = CreditEditClient.buildPayload("abc", Action.RENAME, "Old", "New");
        assertEquals("abc", body.get("uuid").getAsString());
        assertEquals("all", body.get("section").getAsString(), "one identity — every card at once");
        assertEquals("rename", body.get("action").getAsString());
        assertEquals("Old", body.get("from").getAsString());
        assertEquals("New", body.get("to").getAsString());
        assertEquals(5, body.size());
    }

    @Test
    @DisplayName("remove and restore carry no names")
    void removeRestorePayload() {
        JsonObject remove = CreditEditClient.buildPayload("abc", Action.REMOVE, "x", "y");
        assertEquals("all", remove.get("section").getAsString());
        assertEquals("remove", remove.get("action").getAsString());
        assertEquals(3, remove.size());
        assertEquals("restore", CreditEditClient.buildPayload("abc", Action.RESTORE, null, null)
            .get("action").getAsString());
    }

    @Test
    @DisplayName("on a dev build the live pool's answer wins unless the branch cap is the one that owned the credit")
    void twoPools() {
        CreditEditClient.Result ok = new CreditEditClient.Result(true, CreditEditClient.Error.NONE, 2);
        CreditEditClient.Result notYours = CreditEditClient.Result.of(CreditEditClient.Error.NOT_YOURS);
        CreditEditClient.Result taken = CreditEditClient.Result.of(CreditEditClient.Error.NAME_TAKEN);
        assertEquals(4, CreditEditClient.either(ok, ok).updated());
        assertTrue(CreditEditClient.either(ok, notYours).ok());
        assertTrue(CreditEditClient.either(notYours, ok).ok());
        assertEquals(CreditEditClient.Error.NAME_TAKEN, CreditEditClient.either(notYours, taken).error());
        assertEquals(CreditEditClient.Error.NAME_TAKEN, CreditEditClient.either(taken, notYours).error());
        assertEquals(CreditEditClient.Error.NOT_YOURS, CreditEditClient.either(notYours, notYours).error());
        assertEquals("translations", Section.TRANSLATIONS.wire());
    }

    @Test
    @DisplayName("a 2xx succeeds and carries how many rows a rename touched")
    void success() {
        CreditEditClient.Result r = CreditEditClient.interpret(200, "{\"ok\":true,\"action\":\"rename\",\"updated\":7}");
        assertTrue(r.ok());
        assertEquals(CreditEditClient.Error.NONE, r.error());
        assertEquals(7, r.updated());
        assertEquals(0, CreditEditClient.interpret(200, "{\"ok\":true,\"action\":\"remove\"}").updated());
    }

    @Test
    @DisplayName("the relay's two refusals are told apart, because the player can act on them")
    void refusals() {
        assertEquals(CreditEditClient.Error.NAME_TAKEN,
            CreditEditClient.interpret(409, "{\"error\":\"name_taken\"}").error());
        assertEquals(CreditEditClient.Error.NOT_YOURS,
            CreditEditClient.interpret(409, "{\"error\":\"not_yours\"}").error());
        assertEquals(CreditEditClient.Error.FAILED,
            CreditEditClient.interpret(409, "not json").error());
    }

    @Test
    @DisplayName("a relay without the endpoint is 'unsupported', not 'failed'")
    void olderRelay() {
        for (int status : List.of(404, 405, 501)) {
            assertEquals(CreditEditClient.Error.UNSUPPORTED,
                CreditEditClient.interpret(status, "").error(), "HTTP " + status);
        }
        assertEquals(CreditEditClient.Error.RATE_LIMITED, CreditEditClient.interpret(429, "").error());
        assertEquals(CreditEditClient.Error.FAILED, CreditEditClient.interpret(500, "").error());
        assertFalse(CreditEditClient.interpret(400, "{\"error\":\"bad_section\"}").ok());
    }
}
