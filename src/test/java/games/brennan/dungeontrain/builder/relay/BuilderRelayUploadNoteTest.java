package games.brennan.dungeontrain.builder.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuilderRelayUpload#cleanNote}: what the server forwards of the author's note to the reviewer.
 * The client caps it too, but the client is not trusted — this is the rule.
 */
final class BuilderRelayUploadNoteTest {

    @Test
    @DisplayName("null and blank notes are empty")
    void emptyNotes() {
        assertEquals("", BuilderRelayUpload.cleanNote(null));
        assertEquals("", BuilderRelayUpload.cleanNote(""));
        assertEquals("", BuilderRelayUpload.cleanNote("   \n\t "));
    }

    @Test
    @DisplayName("surrounding whitespace goes, the words stay")
    void trims() {
        assertEquals("Pull the lever first.", BuilderRelayUpload.cleanNote("  Pull the lever first. \n"));
    }

    @Test
    @DisplayName("line endings are normalised to newlines and other control characters are dropped")
    void normalisesControlCharacters() {
        assertEquals("one\ntwo\nthree", BuilderRelayUpload.cleanNote("one\r\ntwo\rthree"));
        assertEquals("ab\tc", BuilderRelayUpload.cleanNote("a\u0000b\tc\u0007"));
    }

    @Test
    @DisplayName("a note over the cap is cut at the cap")
    void caps() {
        String cleaned = BuilderRelayUpload.cleanNote("y".repeat(BuilderRelayUpload.NOTE_MAX + 50));
        assertEquals(BuilderRelayUpload.NOTE_MAX, cleaned.length());
        assertTrue(cleaned.chars().allMatch(c -> c == 'y'));
    }
}
