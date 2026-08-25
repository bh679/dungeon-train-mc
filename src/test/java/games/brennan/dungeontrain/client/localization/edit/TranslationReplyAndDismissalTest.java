package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two halves of the review conversation that cross the wire: a reviewer's reply coming back to
 * the translator, and a "good as is" dismissal going out.
 *
 * <p>Pure — no relay, no Minecraft bootstrap. The relay's own tests assert the server half of the
 * same contract ({@code translations.test.js} / {@code translations.integration.test.js}); these
 * assert the half that has to match it.</p>
 */
class TranslationReplyAndDismissalTest {

    private static final String UUID = "0123456789abcdef0123456789abcdef";

    // ---------------------------------------------------------------- replies

    @Test
    @DisplayName("a reply parses with its author and time")
    void notesParse() {
        List<TranslationSubmissionsClient.ReviewNote> notes = TranslationSubmissionsClient.parseNotes(
            "{\"ok\":true,\"count\":1,\"notes\":[{\"unitType\":\"lang\","
                + "\"unitId\":\"gui.dungeontrain.x\",\"source\":\"Save %s changes\","
                + "\"value\":\"保存更改\",\"flag\":\"rejected\","
                + "\"note\":\"the %s has to survive\",\"noteBy\":\"brennan\",\"noteTs\":1234}]}");
        assertEquals(1, notes.size());
        TranslationSubmissionsClient.ReviewNote note = notes.get(0);
        assertEquals("gui.dungeontrain.x", note.unitId());
        assertEquals("the %s has to survive", note.note());
        assertEquals("brennan", note.noteBy());
        assertEquals(1234L, note.noteTs());
        assertEquals("rejected", note.flag());
    }

    @Test
    @DisplayName("the two bodies never collide on a shared id")
    void noteKeysAreNamespacedByBody() {
        // A book field and a lang key can carry the same id; keying on the id alone would show a
        // reply about one against the other.
        List<TranslationSubmissionsClient.ReviewNote> notes = TranslationSubmissionsClient.parseNotes(
            "{\"ok\":true,\"notes\":[{\"unitType\":\"lang\",\"unitId\":\"x\",\"note\":\"a\"},"
                + "{\"unitType\":\"book\",\"unitId\":\"x\",\"note\":\"b\"}]}");
        assertEquals(2, notes.size());
        assertFalse(notes.get(0).key().equals(notes.get(1).key()));
        assertEquals("lang|x", notes.get(0).key());
        assertEquals("book|x", notes.get(1).key());
    }

    @Test
    @DisplayName("a cleared or malformed reply is not a reply")
    void blankAndMalformedNotesAreDropped() {
        // The reviewer withdrew what they wrote: the row still exists, the reply does not.
        assertTrue(TranslationSubmissionsClient.parseNotes(
            "{\"ok\":true,\"notes\":[{\"unitType\":\"lang\",\"unitId\":\"x\",\"note\":\"  \"}]}").isEmpty());
        assertTrue(TranslationSubmissionsClient.parseNotes("{\"ok\":false}").isEmpty());
        assertTrue(TranslationSubmissionsClient.parseNotes("[]").isEmpty());
        // A relay older than the feature answers the submissions shape and no notes at all.
        assertTrue(TranslationSubmissionsClient.parseNotes(
            "{\"ok\":true,\"count\":0,\"submissions\":[]}").isEmpty());
    }

    @Test
    @DisplayName("a submission's units carry the reply, and older relays simply carry none")
    void sentUnitsCarryTheReply() {
        List<TranslationSubmissionsClient.SentUnit> units = TranslationSubmissionsClient.parseUnits(
            "{\"ok\":true,\"units\":[{\"unitType\":\"lang\",\"unitId\":\"a\",\"value\":\"A\","
                + "\"flag\":\"rejected\",\"note\":\"nearly\",\"noteBy\":\"brennan\",\"noteTs\":9},"
                + "{\"unitType\":\"lang\",\"unitId\":\"b\",\"value\":\"B\",\"flag\":\"pending\"}]}");
        assertEquals(2, units.size());
        assertTrue(units.get(0).hasNote());
        assertEquals("nearly", units.get(0).note());
        assertEquals(9L, units.get(0).noteTs());
        assertFalse(units.get(1).hasNote(), "no reply is the normal case, not a blank one");
    }

    // ------------------------------------------------------------- dismissals

    @Test
    @DisplayName("a dismissal carries what was read, and no player-authored text at all")
    void dismissPayloadShape() {
        JsonObject body = TranslationDismissClient.buildPayload(UUID, "de_de", List.of(
            new TranslationUnit(TranslationUnit.Type.LANG, "dungeontrain", "gui.dungeontrain.x",
                "Depart", "Abfahren", true),
            new TranslationUnit(TranslationUnit.Type.BOOK, "dungeontrain", "random_books/x#title",
                "Death Note", "Todesnotizbuch", true)));
        assertEquals(UUID, body.get("uuid").getAsString());
        assertEquals("de_de", body.get("locale").getAsString());
        assertTrue(body.has("modVersion"));
        assertEquals(2, body.getAsJsonArray("units").size());
        JsonObject first = body.getAsJsonArray("units").get(0).getAsJsonObject();
        assertEquals("lang", first.get("type").getAsString());
        assertEquals("gui.dungeontrain.x", first.get("id").getAsString());
        assertEquals("Abfahren", first.get("shipped").getAsString(),
            "the machine translation being let stand IS the content of the claim");
        assertFalse(first.has("value"), "a dismissal is a judgement, never a submission");
        assertEquals("book", body.getAsJsonArray("units").get(1).getAsJsonObject()
            .get("type").getAsString());
    }

    @Test
    @DisplayName("no units means no request body worth sending")
    void dismissPayloadTolerartesNothingToSay() {
        JsonObject body = TranslationDismissClient.buildPayload(UUID, "de_de", null);
        assertEquals(0, body.getAsJsonArray("units").size());
    }

    // ---------------------------------------------- what a dismissal file holds

    @Test
    @DisplayName("a dismissal file's ids read back, skipping anything that is not one")
    void dismissalIdsRead() {
        JsonObject obj = com.google.gson.JsonParser.parseString(
            "{\"locale\":\"de_de\",\"lang\":[\"gui.a\",\"\",7,null,\"gui.b\",\"gui.a\"],"
                + "\"books\":\"not an array\"}").getAsJsonObject();
        assertEquals(List.of("gui.a", "gui.b"), List.copyOf(TranslationDismissals.readIds(obj, "lang")));
        assertTrue(TranslationDismissals.readIds(obj, "books").isEmpty());
        assertTrue(TranslationDismissals.readIds(obj, "missing").isEmpty());
    }

    @Test
    @DisplayName("a suspicious locale code never resolves to a file")
    void dismissalPathIsGuarded() {
        // The same door TranslationOverrideStore locks: locale codes reach this from config and
        // from relay responses.
        org.junit.jupiter.api.Assertions.assertNull(TranslationDismissals.fileFor("../../etc"));
        org.junit.jupiter.api.Assertions.assertNull(TranslationDismissals.fileFor(""));
    }
}
