package games.brennan.dungeontrain.train;

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
 * Asserts the {@link PartRegionFilterProcessor} containment check correctly
 * identifies cells claimed by assigned part placements. The factory's disk /
 * store lookups need an in-engine {@code ServerLevel} so end-to-end coverage
 * lives in the Gate 2 manual checks; here we verify the geometric
 * containment logic the processor relies on.
 */
final class PartRegionFilterProcessorTest {

    /** Carriage dims used across all tests — chosen to match the bundled defaults. */
    private static final CarriageDims DIMS = new CarriageDims(9, 5, 5);

    /** Build a box list for the given kinds, using each kind's full placement footprint. */
    private static List<BoundingBox> claimEveryPlacementOf(CarriagePartKind... kinds) {
        List<BoundingBox> boxes = new ArrayList<>();
        for (CarriagePartKind kind : kinds) {
            Vec3i size = kind.dims(DIMS);
            for (CarriagePartKind.Placement p : kind.placements(DIMS)) {
                BlockPos off = p.originOffset();
                boxes.add(new BoundingBox(
                    off.getX(), off.getY(), off.getZ(),
                    off.getX() + size.getX() - 1,
                    off.getY() + size.getY() - 1,
                    off.getZ() + size.getZ() - 1));
            }
        }
        return boxes;
    }

    @Test
    @DisplayName("Doors claim both end-cap columns (x=0 and x=length-1) at every y/z")
    void doorsClaimEndCapColumns() {
        PartRegionFilterProcessor proc = PartRegionFilterProcessor.forTest(
            BlockPos.ZERO, claimEveryPlacementOf(CarriagePartKind.DOORS));
        assertEquals(2, proc.claimedBoxCountForTest(),
            "DOORS has two placements (front + back caps)");
        for (int y = 0; y < DIMS.height(); y++) {
            for (int z = 0; z < DIMS.width(); z++) {
                assertTrue(proc.isLocalPosClaimedForTest(0, y, z),
                    "Front cap cell (0," + y + "," + z + ") must be claimed");
                assertTrue(proc.isLocalPosClaimedForTest(DIMS.length() - 1, y, z),
                    "Back cap cell (L-1," + y + "," + z + ") must be claimed");
            }
        }
    }

    @Test
    @DisplayName("Interior cells (not on any part) are not claimed")
    void interiorCellsNotClaimed() {
        PartRegionFilterProcessor proc = PartRegionFilterProcessor.forTest(
            BlockPos.ZERO, claimEveryPlacementOf(CarriagePartKind.values()));
        // The carriage interior — strictly inside every part. With L=9, H=5,
        // W=5, parts own: x∈{0,L-1} (DOORS), z∈{0,W-1} for x∈[1,L-2] (WALLS),
        // y=0 and y=H-1 for x∈[1,L-2], z∈[1,W-2] (FLOOR/ROOF). The genuine
        // interior is x∈[1,L-2], y∈[1,H-2], z∈[1,W-2].
        for (int x = 1; x <= DIMS.length() - 2; x++) {
            for (int y = 1; y <= DIMS.height() - 2; y++) {
                for (int z = 1; z <= DIMS.width() - 2; z++) {
                    assertFalse(proc.isLocalPosClaimedForTest(x, y, z),
                        "Interior cell (" + x + "," + y + "," + z + ") must not be claimed");
                }
            }
        }
    }

    @Test
    @DisplayName("No claimed boxes means no cell is filtered (NONE-everything carriage)")
    void emptyClaimedSetClaimsNothing() {
        PartRegionFilterProcessor proc = PartRegionFilterProcessor.forTest(
            BlockPos.ZERO, List.of());
        assertEquals(0, proc.claimedBoxCountForTest());
        // Spot-check positions that DOORS / WALLS would normally claim — none
        // should be filtered when no part is assigned.
        assertFalse(proc.isLocalPosClaimedForTest(0, 0, 0));
        assertFalse(proc.isLocalPosClaimedForTest(DIMS.length() - 1, 2, 2));
        assertFalse(proc.isLocalPosClaimedForTest(3, 0, 0));
    }

    @Test
    @DisplayName("Only-front-door assignment claims only x=0 column, not x=length-1")
    void singlePlacementClaimsOnlyThatBox() {
        Vec3i size = CarriagePartKind.DOORS.dims(DIMS);
        BlockPos off = CarriagePartKind.DOORS.placements(DIMS).get(0).originOffset();
        BoundingBox frontOnly = new BoundingBox(
            off.getX(), off.getY(), off.getZ(),
            off.getX() + size.getX() - 1,
            off.getY() + size.getY() - 1,
            off.getZ() + size.getZ() - 1);
        PartRegionFilterProcessor proc = PartRegionFilterProcessor.forTest(
            BlockPos.ZERO, List.of(frontOnly));

        assertTrue(proc.isLocalPosClaimedForTest(0, 2, 2),
            "Front cap at x=0 must be claimed");
        assertFalse(proc.isLocalPosClaimedForTest(DIMS.length() - 1, 2, 2),
            "Back cap at x=L-1 must NOT be claimed when only the front pick exists");
    }

    @Test
    @DisplayName("Carriage origin offset is applied — world pos maps to local for containment")
    void carriageOriginOffsetApplied() {
        BlockPos carriageOrigin = new BlockPos(100, 64, 200);
        BoundingBox frontDoor = new BoundingBox(0, 0, 0, 0,
            DIMS.height() - 1, DIMS.width() - 1);
        PartRegionFilterProcessor proc = PartRegionFilterProcessor.forTest(
            carriageOrigin, List.of(frontDoor));
        // World x=100 → local 0 (claimed); world x=101 → local 1 (not claimed).
        assertTrue(proc.isLocalPosClaimedForTest(
            100 - carriageOrigin.getX(), 0, 0));
        assertFalse(proc.isLocalPosClaimedForTest(
            101 - carriageOrigin.getX(), 0, 0));
    }
}
