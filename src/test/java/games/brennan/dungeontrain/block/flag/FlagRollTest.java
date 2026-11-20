package games.brennan.dungeontrain.block.flag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flag roll rules Brennan asked for: every copy of Flag N in one room roll matches, a new roll
 * picks again, and no two flag numbers in one roll share a design.
 */
class FlagRollTest {

    private static final int DESIGNS = 34;

    @Test
    @DisplayName("Same roll key, same flag number: always the same design")
    void sameKeySameDesign() {
        for (long key = -50; key < 50; key++) {
            for (int slot = 1; slot <= FlagPlaceholders.COUNT; slot++) {
                assertEquals(FlagRoll.designIndex(key, slot, DESIGNS), FlagRoll.designIndex(key, slot, DESIGNS));
            }
        }
    }

    @Test
    @DisplayName("Flags 1-8 in one roll are all different designs")
    void flagNumbersNeverShareADesign() {
        for (int carriage = 0; carriage < 1000; carriage++) {
            long key = FlagRoll.carriageKey(12345L, carriage);
            Set<Integer> seen = new HashSet<>();
            for (int slot = 1; slot <= FlagPlaceholders.COUNT; slot++) {
                int design = FlagRoll.designIndex(key, slot, DESIGNS);
                assertTrue(design >= 0 && design < DESIGNS);
                assertTrue(seen.add(design), "carriage " + carriage + " repeated design " + design);
            }
        }
    }

    @Test
    @DisplayName("Different rooms pick differently, and every design turns up")
    void rollsSpreadAcrossAllDesigns() {
        Set<Integer> flagOne = new HashSet<>();
        int changes = 0;
        int previous = -1;
        for (int carriage = 0; carriage < 500; carriage++) {
            int design = FlagRoll.designIndex(FlagRoll.carriageKey(99L, carriage), 1, DESIGNS);
            flagOne.add(design);
            if (design != previous) changes++;
            previous = design;
        }
        assertEquals(DESIGNS, flagOne.size(), "Flag 1 should reach every design over 500 rooms");
        assertTrue(changes > 450, "neighbouring rooms should rarely repeat Flag 1 (changed " + changes + "/500)");
    }

    @Test
    @DisplayName("Carriage and portal room keys differ for the same number and seed")
    void carriageAndRoomKeysAreApart() {
        for (int i = 0; i < 100; i++) {
            assertNotEquals(FlagRoll.carriageKey(7L, i), FlagRoll.roomKey(7L, i));
        }
        assertNotEquals(FlagRoll.carriageKey(1L, 5), FlagRoll.carriageKey(2L, 5), "a new world rolls again");
    }
}
