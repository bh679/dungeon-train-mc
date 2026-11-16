package games.brennan.dungeontrain.portal;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The "same structure?" rule {@link PortalFluidBarrier} vetoes fluid flow on. */
class PortalFluidBarrierTest {

    /** An 11 x 7 x 13 room at (0, -38, 0), copies at (0,0) and (1,0), one corridor box off its -X end. */
    private static PortalFluidBarrier.Room hall() {
        PortalRoomTiling tiling = PortalRoomTiling.base().with(new PortalRoomTiling.Tile(1, 0));
        PortalCorridorMask corridor = new PortalCorridorMask(List.of(new BoundingBox(-9, -38, 4, -1, -34, 8)));
        return new PortalFluidBarrier.Room(new BoundingBox(-12, -39, -1, 22, -31, 13),
            0, -38, 0, new Vec3i(11, 7, 13), tiling, corridor);
    }

    private static PortalFluidBarrier.Snapshot of(PortalFluidBarrier.Room... rooms) {
        return new PortalFluidBarrier.Snapshot(0, List.of(rooms));
    }

    @Test
    void flowInsideOneCopyIsAllowed() {
        assertTrue(of(hall()).sameStructure(3, -36, 3, 4, -36, 3));
    }

    @Test
    void flowBetweenTwoStandingCopiesIsAllowed() {
        assertTrue(of(hall()).sameStructure(10, -36, 5, 11, -36, 5));
    }

    @Test
    void flowFromRoomIntoItsCorridorIsAllowed() {
        assertTrue(of(hall()).sameStructure(0, -36, 6, -1, -36, 6));
    }

    @Test
    void flowOutOfAnOpenEdgeIsRefused() {
        // Off the +X end of copy (1,0) — no copy stands at (2,0).
        assertFalse(of(hall()).sameStructure(21, -36, 5, 22, -36, 5));
        // Off the -Z side.
        assertFalse(of(hall()).sameStructure(5, -36, 0, 5, -36, -1));
    }

    @Test
    void flowFallingOntoTheRoofFromAboveIsRefused() {
        assertFalse(of(hall()).sameStructure(5, -31, 5, 5, -32, 5));
    }

    @Test
    void flowFromOneStructureIntoAnotherIsRefused() {
        PortalFluidBarrier.Room above = new PortalFluidBarrier.Room(new BoundingBox(-1, -27, -1, 11, -19, 13),
            0, -26, 0, new Vec3i(11, 7, 13), PortalRoomTiling.base(), PortalCorridorMask.NONE);
        // Not touching here, but two structures' cells must never be treated as one.
        assertFalse(of(hall(), above).sameStructure(5, -26, 5, 5, -36, 5));
    }

    @Test
    void flowFarFromEveryRoomIsUntouched() {
        assertTrue(of(hall()).sameStructure(500, 64, 500, 501, 64, 500));
        assertTrue(of().sameStructure(0, -36, 0, 1, -36, 0));
    }
}
