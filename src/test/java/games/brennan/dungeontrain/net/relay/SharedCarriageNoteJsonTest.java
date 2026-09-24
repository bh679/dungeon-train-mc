package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.builder.relay.SubmitNote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The {@code note} object /carriages/publish carries: the answered questions, and only those. */
final class SharedCarriageNoteJsonTest {

    @Test
    @DisplayName("every answered field goes, under the name the relay reads")
    void allFields() {
        JsonObject o = SharedCarriageClient.noteJson(new SubmitNote("lever first", "diamonds on purpose", "near the engine"));
        assertEquals("lever first", o.get("redstone").getAsString());
        assertEquals("diamonds on purpose", o.get("loot").getAsString());
        assertEquals("near the engine", o.get("notes").getAsString());
    }

    @Test
    @DisplayName("an unanswered question is left out rather than sent empty")
    void emptyFieldsOmitted() {
        JsonObject o = SharedCarriageClient.noteJson(SubmitNote.of("just this"));
        assertFalse(o.has("redstone"));
        assertFalse(o.has("loot"));
        assertEquals("just this", o.get("notes").getAsString());
    }
}
