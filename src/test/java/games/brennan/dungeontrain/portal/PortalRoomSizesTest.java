package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Who owns a portal room's footprint, and for how long.
 *
 * <p>Two surfaces resize the same rooms and mean different things by it. In the Train Editor the
 * plot <em>is</em> the working copy, so a pending size has to outlive a restamp — otherwise stamping
 * the plot, which loads the template on its way to reading the size, would undo the resize before it
 * took effect. In the Train Builder the template is the working copy and Save is what commits, so a
 * resize that outlived the build read as a save nobody pressed.</p>
 */
final class PortalRoomSizesTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;
    private static final String ROOM = "test_room";

    private static final Vec3i ON_DISK = new Vec3i(11, 7, 13);
    private static final Vec3i RESIZED = new Vec3i(21, 7, 13);

    @BeforeEach
    void reset() {
        PortalRoomSizes.forget(ROOM);
    }

    @Test
    @DisplayName("With nothing known, a room is the built-in size")
    void unknownRoomIsBuiltIn() {
        assertEquals(PortalRoomLayout.builtInSize(DIMS), PortalRoomSizes.sizeOf(ROOM, DIMS));
    }

    @Test
    @DisplayName("A pending resize outranks what the template said")
    void pendingWinsOverObserved() {
        PortalRoomSizes.observe(ROOM, ON_DISK);
        PortalRoomSizes.pending(ROOM, RESIZED);
        assertEquals(RESIZED, PortalRoomSizes.sizeOf(ROOM, DIMS));
    }

    @Test
    @DisplayName("Loading the template again does not undo a resize")
    void observeDoesNotSpendPending() {
        // The editor's requirement: stamping a plot reads the template on the way past, and if that
        // cleared the override the size stepper would never take effect at all.
        PortalRoomSizes.pending(ROOM, RESIZED);
        PortalRoomSizes.observe(ROOM, ON_DISK);
        assertEquals(RESIZED, PortalRoomSizes.sizeOf(ROOM, DIMS));
    }

    @Test
    @DisplayName("Saving spends the resize — the template is the authority again")
    void settleSpendsPending() {
        PortalRoomSizes.pending(ROOM, RESIZED);
        PortalRoomSizes.settle(ROOM, RESIZED);
        PortalRoomSizes.observe(ROOM, ON_DISK);
        assertEquals(ON_DISK, PortalRoomSizes.sizeOf(ROOM, DIMS),
                "after a save, a later load should be believed");
    }

    @Test
    @DisplayName("Clearing a pending resize falls back to the template, not the built-in")
    void revertKeepsTheObservedSize() {
        // What the builder does on open. The distinction from forget() is the point: the resize goes,
        // the knowledge of how big the room actually is stays.
        PortalRoomSizes.observe(ROOM, ON_DISK);
        PortalRoomSizes.pending(ROOM, RESIZED);

        PortalRoomSizes.revert(ROOM);

        assertEquals(ON_DISK, PortalRoomSizes.sizeOf(ROOM, DIMS));
    }

    @Test
    @DisplayName("An unsaved resize does not survive being cleared twice, or a null name")
    void revertIsTotal() {
        PortalRoomSizes.pending(ROOM, RESIZED);
        PortalRoomSizes.revert(ROOM);
        PortalRoomSizes.revert(ROOM);
        PortalRoomSizes.revert(null);
        assertEquals(PortalRoomLayout.builtInSize(DIMS), PortalRoomSizes.sizeOf(ROOM, DIMS));
    }

    /**
     * Why {@code /dungeontrain portal test} must not size its stamp box from this class.
     *
     * <p>The cache is a plot-layout answer — it exists so {@code TrackSidePlots.locate} can size a
     * plot with no {@code ServerLevel} to load a template through — and it deliberately drifts from
     * the template while an author is resizing. A stamp sized from it and then filled from the
     * template disagrees with itself, and {@code PortalCarriageBuilder.stampRoomAt} resolves that
     * disagreement by clipping the authored room to the box (or shelling out the difference with the
     * built-in room). That is how a 13-block room came to be stamped into a 12-block box and lose an
     * edge, alternating between the two across successive tests as the pending resize came and went.
     * The stamp box now comes from the template, which is the only source that cannot disagree with
     * the blocks being stamped.</p>
     */
    @Test
    @DisplayName("The cache drifts from the template in both directions — so a stamp box must not use it")
    void cacheDriftsFromTheTemplateBothWays() {
        // Ahead of disk: an in-editor resize the author has not saved.
        PortalRoomSizes.observe(ROOM, ON_DISK);
        PortalRoomSizes.pending(ROOM, RESIZED);
        assertNotEquals(ON_DISK, PortalRoomSizes.sizeOf(ROOM, DIMS),
                "a pending resize makes the cache bigger than the template on disk");

        // Behind disk: the same session after `portal test back` reverts the override. Nothing about
        // the template changed, yet the cache now answers with the previous size.
        PortalRoomSizes.revert(ROOM);
        assertEquals(ON_DISK, PortalRoomSizes.sizeOf(ROOM, DIMS));
    }
}
