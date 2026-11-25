package games.brennan.dungeontrain.combat;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DigRecheck}: how long a digging mob waits before asking again after a "no dig" answer, what
 * sends it back to the usual clock, and when the navigator's held path answers without a fresh search.
 */
final class DigRecheckTest {

    @Test
    @DisplayName("the wait doubles on repeated no-dig answers and caps at 8 seconds")
    void waitGrowsAndCaps() {
        assertEquals(40, DigRecheck.waitTicks(0));
        assertEquals(80, DigRecheck.waitTicks(1));
        assertEquals(160, DigRecheck.waitTicks(2));
        assertEquals(160, DigRecheck.waitTicks(9));
        assertEquals(40, DigRecheck.waitTicks(-3));
    }

    @Test
    @DisplayName("the streak grows one per answer and stops at the cap")
    void streakGrowth() {
        assertEquals(1, DigRecheck.grow(0));
        assertEquals(2, DigRecheck.grow(1));
        assertEquals(2, DigRecheck.grow(2));
        assertEquals(1, DigRecheck.grow(-1));
    }

    @Test
    @DisplayName("losing sight of the target since the answer resets the back-off")
    void lostSightResets() {
        assertTrue(DigRecheck.resetsBackoff(true, false, 0));
        assertFalse(DigRecheck.resetsBackoff(true, true, 0));
        // Never saw it at the answer: not seeing it now is no news.
        assertFalse(DigRecheck.resetsBackoff(false, false, 0));
        assertFalse(DigRecheck.resetsBackoff(false, true, 0));
    }

    @Test
    @DisplayName("the target moving two blocks or more resets the back-off; one block does not")
    void targetMovedResets() {
        assertFalse(DigRecheck.resetsBackoff(true, true, 1));
        assertTrue(DigRecheck.resetsBackoff(true, true, 2));
        assertTrue(DigRecheck.resetsBackoff(false, false, 5));
    }

    @Test
    @DisplayName("blocks apart is the largest axis")
    void blocksApart() {
        assertEquals(0, DigRecheck.blocksApart(new BlockPos(3, 64, 7), new BlockPos(3, 64, 7)));
        assertEquals(2, DigRecheck.blocksApart(new BlockPos(3, 64, 7), new BlockPos(4, 66, 6)));
        assertEquals(5, DigRecheck.blocksApart(-2, 0, 0, 3, 0, 0));
    }

    @Test
    @DisplayName("a live held path that reaches the target's cell answers walkable")
    void heldPathReaches() {
        assertTrue(DigRecheck.heldPathWalkable(false, true, 3, 64, 7, 3, 64, 7, 3, 64, 7));
    }

    @Test
    @DisplayName("a held path aimed one block from where the target now stands still answers walkable")
    void heldPathOneStep() {
        assertTrue(DigRecheck.heldPathWalkable(false, true, 3, 64, 7, 3, 64, 7, 4, 64, 7));
        assertFalse(DigRecheck.heldPathWalkable(false, true, 3, 64, 7, 3, 64, 7, 5, 64, 7));
    }

    @Test
    @DisplayName("a partial held path ending in the target's column (carriage deck off the grid) answers walkable")
    void heldPathEndsInColumn() {
        assertTrue(DigRecheck.heldPathWalkable(false, false, 3, 65, 7, 3, 64, 7, 3, 64, 7));
        assertFalse(DigRecheck.heldPathWalkable(false, false, 4, 64, 7, 3, 64, 7, 3, 64, 7));
    }

    @Test
    @DisplayName("a done path, or one that stops short, never answers: a fresh search does")
    void heldPathFallsBack() {
        assertFalse(DigRecheck.heldPathWalkable(true, true, 3, 64, 7, 3, 64, 7, 3, 64, 7));
        assertFalse(DigRecheck.heldPathWalkable(false, false, 0, 64, 7, 3, 64, 7, 3, 64, 7));
    }
}
