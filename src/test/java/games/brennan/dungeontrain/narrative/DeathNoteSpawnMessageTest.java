package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies the Death Note echo-spawn announcement pool ({@link DeathNoteSpawnMessage}): 20 lines,
 * each substituting the author name with no leftover placeholder, and a blank-name fallback.
 */
class DeathNoteSpawnMessageTest {

    @Test
    void hasTwentyVariations() {
        assertEquals(20, DeathNoteSpawnMessage.variantCount());
    }

    @Test
    void everyLineNamesTheAuthorWithNoLeftoverPlaceholder() {
        for (int i = 0; i < DeathNoteSpawnMessage.variantCount(); i++) {
            String line = DeathNoteSpawnMessage.lineFor(i, "Steve");
            assertTrue(line.contains("Steve"), "line " + i + " should name the author: " + line);
            assertFalse(line.contains("%s"), "line " + i + " has an unsubstituted placeholder: " + line);
        }
    }

    @Test
    void blankOrNullAuthorFallsBackToSomeone() {
        assertTrue(DeathNoteSpawnMessage.lineFor(0, "   ").contains("someone"));
        assertTrue(DeathNoteSpawnMessage.lineFor(0, null).contains("someone"));
    }
}
