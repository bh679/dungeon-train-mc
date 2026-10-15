package games.brennan.dungeontrain.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editing a sidecar document in place — how a Workbench build's variant pools, chests and room
 * settings are written back so a commit carries them ({@link TemplateSidecars#withFileText},
 * {@link TemplateSidecars#withWeightsMode}).
 */
final class TemplateSidecarsEditTest {

    private static final String ROOM_DOC = """
        {"files":{"variants":"V","copies":"C","containers":"K"},
         "weights":{"weight":3,"mode":"door-left"},
         "credit":{"uuid":"0000-1111","name":"Alex","ts":1}}
        """;

    @Test
    @DisplayName("a role's text reads back; an absent role and a blank document read empty")
    void readsRoles() {
        assertEquals(Optional.of("V"), TemplateSidecars.fileTextOf(ROOM_DOC, TemplateSidecars.ROLE_VARIANTS));
        assertEquals(Optional.empty(), TemplateSidecars.fileTextOf(ROOM_DOC, "parts"));
        assertEquals(Optional.empty(), TemplateSidecars.fileTextOf("", TemplateSidecars.ROLE_VARIANTS));
    }

    @Test
    @DisplayName("replacing one role keeps every other role, the weights and the byline")
    void replaceKeepsTheRest() throws IOException {
        JsonObject out = JsonParser.parseString(
            TemplateSidecars.withFileText(ROOM_DOC, TemplateSidecars.ROLE_VARIANTS, "V2")).getAsJsonObject();
        JsonObject files = out.getAsJsonObject("files");
        assertEquals("V2", files.get("variants").getAsString());
        assertEquals("C", files.get("copies").getAsString());
        assertEquals("K", files.get("containers").getAsString());
        assertEquals(3, out.getAsJsonObject("weights").get("weight").getAsInt());
        assertEquals("Alex", out.getAsJsonObject("credit").get("name").getAsString());
    }

    @Test
    @DisplayName("a blank text removes the role, and the last role takes the files block with it")
    void blankRemoves() throws IOException {
        String once = TemplateSidecars.withFileText("{\"files\":{\"variants\":\"V\"}}", TemplateSidecars.ROLE_VARIANTS, "");
        assertFalse(JsonParser.parseString(once).getAsJsonObject().has("files"));
    }

    @Test
    @DisplayName("a blank document grows the role it is given")
    void blankDocumentGrows() throws IOException {
        assertEquals(Optional.of("V"), TemplateSidecars.fileTextOf(
            TemplateSidecars.withFileText("", TemplateSidecars.ROLE_VARIANTS, "V"), TemplateSidecars.ROLE_VARIANTS));
    }

    @Test
    @DisplayName("a document that will not parse is refused rather than overwritten")
    void unparseableRefused() {
        assertThrows(IOException.class,
            () -> TemplateSidecars.withFileText("{not json", TemplateSidecars.ROLE_VARIANTS, "V"));
    }

    @Test
    @DisplayName("a room's settings tag reads from the weights entry, and a new tag keeps the weight")
    void weightsMode() throws IOException {
        assertEquals(Optional.of("door-left"), TemplateSidecars.weightsModeOf(ROOM_DOC));
        String edited = TemplateSidecars.withWeightsMode(ROOM_DOC, "door-right");
        assertEquals(Optional.of("door-right"), TemplateSidecars.weightsModeOf(edited));
        assertEquals(3, JsonParser.parseString(edited).getAsJsonObject()
            .getAsJsonObject("weights").get("weight").getAsInt());
    }

    @Test
    @DisplayName("a document with no weights entry gets one carrying just the mode")
    void weightsModeOnBareDocument() throws IOException {
        assertTrue(TemplateSidecars.weightsModeOf("{}").isEmpty());
        assertEquals(Optional.of("door-right"),
            TemplateSidecars.weightsModeOf(TemplateSidecars.withWeightsMode("{}", "door-right")));
    }
}
