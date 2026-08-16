package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bedrock shell a Bedrock Lock room wraps its two corridors in.
 *
 * <p>Three properties, and each of them is a bug that has a face. A slab reaching into the corridor's
 * own box would overwrite the geometry a twin shares with its carriage and tear the crossing open. A
 * slab reaching the seal plane at the corridor's mouth would put a ring of bedrock around the doorway,
 * in plain view from inside the room. And a gap on any of the four lateral sides is the hole the whole
 * feature exists to close — a player mines the corridor wall and walks out of the lock.</p>
 */
class PortalCorridorLockTest {

    private static final CarriageDims DIMS = new CarriageDims(9, 7, 7);
    /** Off-origin and off-axis, so no symmetry can hide an off-by-one. */
    private static final BlockPos ORIGIN = new BlockPos(37, -50, -12);
    private static final int LENGTH = 13;
    private static final int WORLD_MIN_Y = -64;

    private static List<BoundingBox> boxes(PortalCarriageRole role) {
        return PortalCarriageBuilder.corridorLockBoxes(ORIGIN, DIMS, LENGTH, role, WORLD_MIN_Y);
    }

    private static boolean covered(List<BoundingBox> boxes, int x, int y, int z) {
        for (BoundingBox box : boxes) {
            if (x >= box.minX() && x <= box.maxX()
                && y >= box.minY() && y <= box.maxY()
                && z >= box.minZ() && z <= box.maxZ()) {
                return true;
            }
        }
        return false;
    }

    /** The corridor's own box — walls included. Nothing here may be written to. */
    private static BoundingBox corridorBox() {
        return new BoundingBox(ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ(),
            ORIGIN.getX() + LENGTH - 1, ORIGIN.getY() + DIMS.height() - 1,
            ORIGIN.getZ() + DIMS.width() - 1);
    }

    @Test
    @DisplayName("No slab reaches into the corridor — its geometry is shared with the carriage")
    void neverInsideTheCorridor() {
        for (PortalCarriageRole role : PortalCarriageRole.values()) {
            BoundingBox corridor = corridorBox();
            for (BoundingBox box : boxes(role)) {
                assertFalse(box.intersects(corridor), role + " slab " + box + " reaches the corridor");
            }
        }
    }

    /**
     * The mouth column is {@code sealCorridorMouth}'s plane, filled across the room's cross-section
     * with the room's own wall. The room's skin caps it from outside; bedrock in it would be a ring
     * around the doorway.
     */
    @Test
    @DisplayName("The span stops one column short of the mouth, and one plug depth past the dead end")
    void spansPlugToMouth() {
        int entryMouth = ORIGIN.getX() + LENGTH - 1;
        for (BoundingBox box : boxes(PortalCarriageRole.ENTRY)) {
            assertEquals(ORIGIN.getX() - PortalCarriageBuilder.plugDepth(), box.minX());
            assertEquals(entryMouth - 1, box.maxX());
        }
        // The exit corridor is the mirror image: mouth at its near end, plug beyond its far one.
        for (BoundingBox box : boxes(PortalCarriageRole.EXIT)) {
            assertEquals(ORIGIN.getX() + 1, box.minX());
            assertEquals(ORIGIN.getX() + LENGTH - 1 + PortalCarriageBuilder.plugDepth(), box.maxX());
        }
    }

    @Test
    @DisplayName("All four lateral sides are closed, every column of the span")
    void closesEverySide() {
        for (PortalCarriageRole role : PortalCarriageRole.values()) {
            List<BoundingBox> boxes = boxes(role);
            int xLo = boxes.get(0).minX();
            int xHi = boxes.get(0).maxX();
            int zLo = ORIGIN.getZ() - 1;
            int zHi = ORIGIN.getZ() + DIMS.width();
            int below = PortalCarriageBuilder.lowestWritableY(WORLD_MIN_Y, ORIGIN.getY());
            int above = ORIGIN.getY() + DIMS.height();

            for (int x = xLo; x <= xHi; x++) {
                for (int y = below; y <= above; y++) {
                    assertTrue(covered(boxes, x, y, zLo), role + " -Z open at " + x + "," + y);
                    assertTrue(covered(boxes, x, y, zHi), role + " +Z open at " + x + "," + y);
                }
                for (int z = zLo; z <= zHi; z++) {
                    assertTrue(covered(boxes, x, above, z), role + " roof open at " + x + "," + z);
                    assertTrue(covered(boxes, x, below, z), role + " floor open at " + x + "," + z);
                }
            }
        }
    }

    /**
     * In the lowest lane there is no row under the floor to write — it is the world's own bedrock,
     * and {@code eraseTwin} sweeping it would open the bottom of the world. The shell has to agree
     * with {@code lowestWritableY} about that, exactly as the room's skin does.
     */
    @Test
    @DisplayName("The lowest lane writes no floor plane, and nothing below the world")
    void lowestLaneHasNoFloorPlane() {
        BlockPos lowest = new BlockPos(ORIGIN.getX(), WORLD_MIN_Y + 1, ORIGIN.getZ());
        for (PortalCarriageRole role : PortalCarriageRole.values()) {
            List<BoundingBox> lanes = PortalCarriageBuilder.corridorLockBoxes(
                lowest, DIMS, LENGTH, role, WORLD_MIN_Y);
            // Two sides and a roof — no floor slab, because that row is the world's own bedrock.
            assertEquals(3, lanes.size(), role.toString());
            for (BoundingBox box : lanes) {
                assertTrue(box.minY() > WORLD_MIN_Y, role + " slab " + box + " reaches the world floor");
            }
        }
    }
}
