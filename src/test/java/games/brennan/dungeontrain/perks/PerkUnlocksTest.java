package games.brennan.dungeontrain.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PerkUnlocksTest {

    @Test
    @DisplayName("a perk reaches 10 freely; each 100 Advancement Points spent opens 10 more")
    void gate() {
        assertTrue(PerkUnlocks.allowed(1, 0));
        assertTrue(PerkUnlocks.allowed(10, 0));
        assertFalse(PerkUnlocks.allowed(11, 99));
        assertTrue(PerkUnlocks.allowed(11, 100));
        assertTrue(PerkUnlocks.allowed(20, 100));
        assertFalse(PerkUnlocks.allowed(21, 199));
        assertTrue(PerkUnlocks.allowed(21, 200));
        assertFalse(PerkUnlocks.allowed(0, 500));
    }

    @Test
    @DisplayName("spent needed and the level limit agree")
    void spentNeeded() {
        assertEquals(0, PerkUnlocks.spentNeeded(10));
        assertEquals(100, PerkUnlocks.spentNeeded(11));
        assertEquals(200, PerkUnlocks.spentNeeded(21));
        assertEquals(10, PerkUnlocks.levelLimit(0));
        assertEquals(20, PerkUnlocks.levelLimit(150));
    }

    @Test
    @DisplayName("repeat unlocks stack and survive a new life")
    void stateLevels() {
        PerkState s = PerkState.EMPTY.withUnlockedOnce("health").withUnlockedOnce("health");
        assertEquals(2, s.unlockLevel("health"));
        assertEquals(2, s.withUnlocked("health").unlockLevel("health"), "withUnlocked never lowers a level");
        s = s.withActive("health", 2);
        assertEquals(2, s.rank("health"));
        s = s.clearedActive();
        assertEquals(2, s.unlockLevel("health"));
        assertEquals(0, s.rank("health"));
    }
}
