package games.brennan.dungeontrain.editor.workbench;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Workbench's slot allocator: first fit along {@code +X}, nothing ever moves.
 *
 * <p>Pinned because it is the whole point of the category — every other editor re-lays itself out
 * when a template is added, and this one must not. If a later build could shift an earlier box, a
 * staged plot would stand where its record no longer says, and the sweep would erase it.</p>
 */
final class WorkbenchPlacementTest {

    private static final Vec3i SMALL = new Vec3i(5, 4, 7);
    private static final Vec3i BIG = new Vec3i(20, 10, 12);

    @Test
    @DisplayName("an empty Workbench puts the first build at the row origin on the lower layer")
    void firstSlotIsTheOrigin() {
        BlockPos origin = WorkbenchPlacement.nextFree(List.of(), SMALL);
        assertEquals(new BlockPos(WorkbenchPlacement.FIRST_X, WorkbenchPlacement.PLOT_Y, WorkbenchPlacement.ROW_Z), origin);
    }

    @Test
    @DisplayName("the next build lands one gap past the previous cage, never overlapping it")
    void slotsFollowAlongX() {
        List<BoundingBox> taken = new ArrayList<>();
        BlockPos first = WorkbenchPlacement.nextFree(taken, SMALL);
        taken.add(WorkbenchPlacement.boxAt(first, SMALL));
        BlockPos second = WorkbenchPlacement.nextFree(taken, BIG);
        BoundingBox secondBox = WorkbenchPlacement.boxAt(second, BIG);
        assertFalse(WorkbenchPlacement.collides(secondBox, taken), "second slot must not touch the first cage");
        // first cage spans FIRST_X-1 .. FIRST_X+5; the next origin is GAP past its far edge.
        assertEquals(WorkbenchPlacement.FIRST_X + SMALL.getX() + 1 + WorkbenchPlacement.GAP, second.getX());
        assertEquals(WorkbenchPlacement.PLOT_Y, second.getY());
        assertEquals(WorkbenchPlacement.ROW_Z, second.getZ());
    }

    @Test
    @DisplayName("adding a third build leaves the first two exactly where they were")
    void existingBoxesNeverMove() {
        List<BoundingBox> taken = new ArrayList<>();
        BlockPos a = WorkbenchPlacement.nextFree(taken, SMALL);
        taken.add(WorkbenchPlacement.boxAt(a, SMALL));
        BlockPos b = WorkbenchPlacement.nextFree(taken, BIG);
        taken.add(WorkbenchPlacement.boxAt(b, BIG));
        List<BoundingBox> before = List.copyOf(taken);
        BlockPos c = WorkbenchPlacement.nextFree(taken, SMALL);
        // The allocator is pure: the caller's boxes are untouched and the new one is free of them.
        assertEquals(before, taken);
        assertFalse(WorkbenchPlacement.collides(WorkbenchPlacement.boxAt(c, SMALL), taken));
    }

    @Test
    @DisplayName("a gap left by a removed build is reused by a build that fits it")
    void gapsAreReused() {
        List<BoundingBox> taken = new ArrayList<>();
        BlockPos a = WorkbenchPlacement.nextFree(taken, BIG);
        taken.add(WorkbenchPlacement.boxAt(a, BIG));
        BlockPos b = WorkbenchPlacement.nextFree(taken, SMALL);
        taken.add(WorkbenchPlacement.boxAt(b, SMALL));
        BlockPos c = WorkbenchPlacement.nextFree(taken, SMALL);
        taken.add(WorkbenchPlacement.boxAt(c, SMALL));
        // Remove the big one at the front; a small build takes its place rather than the row's end.
        taken.remove(0);
        BlockPos d = WorkbenchPlacement.nextFree(taken, SMALL);
        assertEquals(a, d, "the freed front slot is the first fit");
        assertFalse(WorkbenchPlacement.collides(WorkbenchPlacement.boxAt(d, SMALL), taken));
    }

    @Test
    @DisplayName("an anchor prefers the first free slot at or past it")
    void anchorIsHonoured() {
        List<BoundingBox> taken = new ArrayList<>();
        BlockPos a = WorkbenchPlacement.nextFree(taken, SMALL);
        taken.add(WorkbenchPlacement.boxAt(a, SMALL));
        BlockPos near = WorkbenchPlacement.nextFree(taken, SMALL, 100);
        assertEquals(100, near.getX(), "a free anchor is taken as it is");
        // An anchor inside the taken cage steps past it.
        BlockPos stepped = WorkbenchPlacement.nextFree(taken, SMALL, a.getX() + 2);
        assertTrue(stepped.getX() > a.getX() + SMALL.getX());
        assertFalse(WorkbenchPlacement.collides(WorkbenchPlacement.boxAt(stepped, SMALL), taken));
    }

    @Test
    @DisplayName("the world-data form round-trips through a box and back")
    void recordRoundTrip() {
        BlockPos origin = new BlockPos(37, WorkbenchPlacement.PLOT_Y, 0);
        int[] rec = WorkbenchPlacement.record(origin, BIG);
        assertEquals(WorkbenchPlacement.boxAt(origin, BIG), WorkbenchPlacement.boxOf(rec));
        assertEquals(null, WorkbenchPlacement.boxOf(new int[] {1, 2, 3}));
    }
}
