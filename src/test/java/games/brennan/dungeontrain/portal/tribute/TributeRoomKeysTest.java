package games.brennan.dungeontrain.portal.tribute;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What may name a tribute room's file. */
class TributeRoomKeysTest {

    @Test
    @DisplayName("Difficulty names and Free Play are keys; anything else is not")
    void keysAreLowercaseWords() {
        assertTrue(TributeRoomKeys.isKey("normal"));
        assertTrue(TributeRoomKeys.isKey("hard"));
        assertTrue(TributeRoomKeys.isKey(TributeRoomKeys.FREE_PLAY));
        assertFalse(TributeRoomKeys.isKey(""));
        assertFalse(TributeRoomKeys.isKey(null));
        assertFalse(TributeRoomKeys.isKey("../x"));
        assertFalse(TributeRoomKeys.isKey("Hard"));
    }
}
