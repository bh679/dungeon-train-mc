package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies the Love Note echo-arrival announcement pool ({@link LoveNoteSpawnMessage}): 20 lines,
 * each substituting the author name with no leftover placeholder, and a blank-name fallback — the
 * mirror of {@link DeathNoteSpawnMessageTest}.
 */
class LoveNoteSpawnMessageTest {

    @Test
    void hasTwentyVariations() {
        assertEquals(20, LoveNoteSpawnMessage.variantCount());
    }

    @Test
    void everyLineNamesTheAuthorWithNoLeftoverPlaceholder() {
        for (int i = 0; i < LoveNoteSpawnMessage.variantCount(); i++) {
            String line = LoveNoteSpawnMessage.lineFor(i, "Steve");
            assertTrue(line.contains("Steve"), "line " + i + " should name the author: " + line);
            assertFalse(line.contains("%s"), "line " + i + " has an unsubstituted placeholder: " + line);
        }
    }

    @Test
    void blankOrNullAuthorFallsBackToSomeone() {
        assertTrue(LoveNoteSpawnMessage.lineFor(0, "   ").contains("someone"));
        assertTrue(LoveNoteSpawnMessage.lineFor(0, null).contains("someone"));
    }

    @Test
    void readsDifferentlyFromTheCurse() {
        // The two pools are the same shape on purpose, but a Love Note arrival must never reuse a
        // vengeance line — the broadcast is the only warning a player gets about what just boarded.
        for (int i = 0; i < LoveNoteSpawnMessage.variantCount(); i++) {
            String love = LoveNoteSpawnMessage.lineFor(i, "Steve");
            for (int j = 0; j < DeathNoteSpawnMessage.variantCount(); j++) {
                assertFalse(love.equals(DeathNoteSpawnMessage.lineFor(j, "Steve")),
                        "love line " + i + " is identical to death line " + j + ": " + love);
            }
        }
    }
}
