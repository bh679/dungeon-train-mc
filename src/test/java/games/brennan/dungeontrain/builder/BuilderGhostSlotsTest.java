package games.brennan.dungeontrain.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the ghosts go when the builder parks one carriage instead of the mode's full run.
 *
 * <p>Worth pinning because the offsets are the only thing keeping a drawn ghost off a real block:
 * an offset of zero would paint the carriage you are editing, which is exactly the thing the ghosts
 * must never be confused with.</p>
 */
final class BuilderGhostSlotsTest {

    private static final int LENGTH = 18;

    @Test
    @DisplayName("One carriage in a three-carriage mode ghosts one slot either side")
    void oneCarriageGhostsBothNeighbours() {
        List<Integer> offsets = BuilderGhostSlots.offsets(1, 3, LENGTH);
        assertEquals(List.of(-LENGTH, LENGTH), offsets);
    }

    @Test
    @DisplayName("Ghosts grow outwards alternately, keeping the build in the middle of the run")
    void ghostsAlternateSides() {
        // The build stays centred rather than ending up at one end of its own train.
        assertEquals(List.of(-LENGTH, LENGTH, -2 * LENGTH, 2 * LENGTH),
                BuilderGhostSlots.offsets(1, 5, LENGTH));
    }

    @Test
    @DisplayName("Nothing is ghosted when nothing is missing")
    void fullRunGhostsNothing() {
        // A whole carriage parks the mode's full count, so there is no empty slot to fill.
        assertTrue(BuilderGhostSlots.offsets(3, 3, LENGTH).isEmpty());
        assertTrue(BuilderGhostSlots.offsets(1, 1, LENGTH).isEmpty());
        // Inside Carriage has one slot and always did — no ghosting there either.
        assertTrue(BuilderGhostSlots.offsets(1, 0, LENGTH).isEmpty());
    }

    @Test
    @DisplayName("A zero-length carriage ghosts nothing rather than stacking on the build")
    void zeroLengthIsRefused() {
        // Defensive: every offset would be 0, which draws the ghosts straight onto the real blocks.
        assertTrue(BuilderGhostSlots.offsets(1, 3, 0).isEmpty());
    }

    @Test
    @DisplayName("No offset is ever zero")
    void noGhostLandsOnTheBuild() {
        for (int full = 2; full <= 8; full++) {
            for (int offset : BuilderGhostSlots.offsets(1, full, LENGTH)) {
                assertEquals(0, offset % LENGTH, "ghosts sit on the carriage grid");
                assertTrue(offset != 0, "a ghost would be drawn over the build itself");
            }
        }
    }
}
