package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the ghosts go when the builder parks one carriage instead of the mode's full run.
 *
 * <p>Worth pinning because these offsets are the only thing keeping a drawn ghost off a real block:
 * a slot landing on the parked carriage would paint the thing you are editing, which is exactly what
 * the ghosts must never be confused with.</p>
 */
final class BuilderGhostSlotsTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;
    private static final int LENGTH = DIMS.length();
    private static final int PAD = CarriagePlacer.halfPadLen(DIMS);

    /** Exactly where the builder parks a lone carriage — the ghosts have to be laid out around it. */
    private static int parkedMinX() {
        return BuilderWorldLayout.trainStartX(1, DIMS);
    }

    private static BuilderGhostSlots.Ghosts ghosts(int full) {
        return BuilderGhostSlots.of(parkedMinX(), 1, full, LENGTH, PAD);
    }

    @Test
    @DisplayName("The ghost group is the layout a real group would have used")
    void ghostsMatchTheRealGroupLayout() {
        BuilderGhostSlots.Ghosts g = ghosts(3);
        int startX = BuilderWorldLayout.trainStartX(3, DIMS);
        int enclosedX = startX + PAD;

        // Two empty carriage slots, and they are the real group's slots 0 and 2.
        assertEquals(List.of(enclosedX, enclosedX + 2 * LENGTH), g.carriageMinX());
        // Pads cap the group exactly where placeHalfFlatbedPad would put them.
        assertEquals(List.of(startX, enclosedX + 3 * LENGTH), g.padMinX());
        assertEquals(PAD, g.padLength());
    }

    @Test
    @DisplayName("The parked carriage's own slot is never ghosted")
    void parkedSlotIsExcluded() {
        // Both this and trainStartX centre on the origin, so the lone carriage lands in the middle
        // slot of an odd group — and that slot must drop out, or the ghost draws over the build.
        for (int full = 2; full <= 8; full++) {
            assertFalse(ghosts(full).carriageMinX().contains(parkedMinX()),
                    "a ghost was placed on the parked carriage for a run of " + full);
        }
        assertEquals(2, ghosts(3).carriageMinX().size());
    }

    @Test
    @DisplayName("An inside build is ghosted the same run an outside one is")
    void insideCarriageGetsTheWholeGroup() {
        // Inside Carriage parks one carriage and used to feed that count in as the group size, so
        // there was never a slot left over and no ghost was drawn at all.
        int full = BuilderWorldLayout.ghostGroupCarriages(BuilderMode.INSIDE_CARRIAGE);
        int parked = BuilderWorldLayout.parkedCarriages(BuilderMode.INSIDE_CARRIAGE, null);
        BuilderGhostSlots.Ghosts g = BuilderGhostSlots.of(parkedMinX(), parked, full, LENGTH, PAD);

        assertEquals(2, g.carriageMinX().size(), "a carriage ghosted either side of the room");
        assertEquals(2, g.padMinX().size(), "and the flatbed ends capping them");
        assertFalse(g.carriageMinX().contains(parkedMinX()), "never over the room you're standing in");
    }

    @Test
    @DisplayName("Nothing is ghosted when nothing is missing")
    void fullRunGhostsNothing() {
        // A whole carriage parks the mode's full count, so there is no empty slot to fill.
        assertTrue(BuilderGhostSlots.of(parkedMinX(), 3, 3, LENGTH, PAD).isEmpty());
        assertTrue(BuilderGhostSlots.of(parkedMinX(), 1, 1, LENGTH, PAD).isEmpty());
        // Inside Carriage has one slot and always did — no ghosting there either.
        assertTrue(BuilderGhostSlots.of(parkedMinX(), 1, 0, LENGTH, PAD).isEmpty());
    }

    @Test
    @DisplayName("A group that would carry no pads is ghosted without them")
    void noPadsWhenTheRealGroupWouldHaveNone() {
        // usesPads is false for a run of one, so a one-carriage "group" gets no pad ghosts —
        // the ghost must not invent scenery the real layout would never have stamped.
        assertTrue(BuilderGhostSlots.of(parkedMinX(), 1, 1, LENGTH, PAD).padMinX().isEmpty());
    }

    @Test
    @DisplayName("A zero-length carriage ghosts nothing rather than stacking on the build")
    void zeroLengthIsRefused() {
        // Defensive: every slot would collapse onto one X, drawing the ghosts through the build.
        assertTrue(BuilderGhostSlots.of(parkedMinX(), 1, 3, 0, PAD).isEmpty());
    }

    @Test
    @DisplayName("Ghost carriages sit on the carriage grid, and pads sit outside all of them")
    void ghostsTileTheGroupWithoutOverlapping() {
        BuilderGhostSlots.Ghosts g = ghosts(3);
        int startX = BuilderWorldLayout.trainStartX(3, DIMS);
        int endX = startX + BuilderWorldLayout.totalTrainLength(3, DIMS) - 1;

        for (int x : g.carriageMinX()) {
            assertEquals(0, Math.floorMod(x - parkedMinX(), LENGTH), "off the carriage grid");
            assertTrue(x >= startX && x + LENGTH - 1 <= endX, "carriage ghost outside the group");
        }
        for (int x : g.padMinX()) {
            assertTrue(x >= startX && x + g.padLength() - 1 <= endX, "pad ghost outside the group");
            // A pad never overlaps an enclosed carriage — that is what halfPadLen buys.
            assertTrue(x + g.padLength() <= startX + PAD + 3 * LENGTH + PAD);
        }
    }
}
