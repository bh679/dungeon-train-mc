package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.builder.relay.SubmitNote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The {@code submitNote} a /carriages/mine row carries, read back into the author's answers. */
final class SharedCarriageNoteOfTest {

    private static JsonObject row(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    @DisplayName("an answered note reads back field by field")
    void object() {
        assertEquals(new SubmitNote("lever", "", "engine"),
                SharedCarriageClient.noteOf(row("{\"id\":1,\"submitNote\":{\"redstone\":\"lever\",\"notes\":\"engine\"}}")));
    }

    @Test
    @DisplayName("absent, null or a bare string is no answers, not a broken row")
    void missingOrOdd() {
        assertEquals(SubmitNote.EMPTY, SharedCarriageClient.noteOf(row("{\"id\":1}")));
        assertEquals(SubmitNote.EMPTY, SharedCarriageClient.noteOf(row("{\"id\":1,\"submitNote\":null}")));
        assertEquals(SubmitNote.EMPTY, SharedCarriageClient.noteOf(row("{\"id\":1,\"submitNote\":\"legacy\"}")));
    }
}
