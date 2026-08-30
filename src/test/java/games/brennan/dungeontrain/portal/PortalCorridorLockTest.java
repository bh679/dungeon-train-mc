package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
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
    /** Far enough above the fixtures that the top clamp never binds — the basement case. */
    private static final int WORLD_MAX_Y = 320;

    private static List<BoundingBox> boxes(PortalCarriageRole role) {
        return PortalCarriageBuilder.corridorLockBoxes(
            ORIGIN, DIMS, LENGTH, role, WORLD_MIN_Y, WORLD_MAX_Y);
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
     * The room the corridors stand against: one corridor along in X, wide enough and tall enough to
     * contain the corridor's cross-section, which {@code PortalRoomLayout.minWidth}/{@code minHeight}
     * guarantee.
     */
    private static final BlockPos ROOM_ORIGIN =
        new BlockPos(ORIGIN.getX() + LENGTH, ORIGIN.getY(), ORIGIN.getZ() - 2);
    private static final Vec3i ROOM_SIZE = new Vec3i(15, 9, DIMS.width() + 4);

    private static List<BoundingBox> cap(PortalCarriageRole role) {
        BlockPos corridor = role == PortalCarriageRole.ENTRY
            ? ORIGIN
            : new BlockPos(ROOM_ORIGIN.getX() + ROOM_SIZE.getX(), ORIGIN.getY(), ORIGIN.getZ());
        return PortalCarriageBuilder.roomEndCapBoxes(
            corridor, DIMS, role, ROOM_ORIGIN, ROOM_SIZE, WORLD_MIN_Y, WORLD_MAX_Y);
    }

    /**
     * The cap stands one column beyond the seal plane, never in it. The seal plane is the room's own
     * wall carried on — bedrock there is bedrock inside somebody's authored room.
     */
    @Test
    @DisplayName("The end cap clears the seal plane, and the room box behind it")
    void capStandsOutsideTheSealPlane() {
        for (BoundingBox box : cap(PortalCarriageRole.ENTRY)) {
            assertEquals(ROOM_ORIGIN.getX() - 2, box.minX());
            assertEquals(ROOM_ORIGIN.getX() - 2, box.maxX());
        }
        for (BoundingBox box : cap(PortalCarriageRole.EXIT)) {
            assertEquals(ROOM_ORIGIN.getX() + ROOM_SIZE.getX() + 1, box.minX());
            assertEquals(ROOM_ORIGIN.getX() + ROOM_SIZE.getX() + 1, box.maxX());
        }
    }

    /**
     * The whole face, minus the hole the corridor runs through — the hole is the geometry a twin
     * shares with its carriage, and a cell of it overwritten is a seam in the crossing.
     */
    @Test
    @DisplayName("The end cap covers the room's face exactly once, and never the corridor")
    void capFramesTheCorridor() {
        for (PortalCarriageRole role : PortalCarriageRole.values()) {
            List<BoundingBox> boxes = cap(role);
            BlockPos corridor = role == PortalCarriageRole.ENTRY
                ? ORIGIN
                : new BlockPos(ROOM_ORIGIN.getX() + ROOM_SIZE.getX(), ORIGIN.getY(), ORIGIN.getZ());
            int planeX = boxes.get(0).minX();
            int below = PortalCarriageBuilder.lowestWritableY(WORLD_MIN_Y, ORIGIN.getY());

            for (int y = below; y <= ROOM_ORIGIN.getY() + ROOM_SIZE.getY(); y++) {
                for (int z = ROOM_ORIGIN.getZ() - 1; z <= ROOM_ORIGIN.getZ() + ROOM_SIZE.getZ(); z++) {
                    boolean inCorridor = z >= corridor.getZ()
                        && z < corridor.getZ() + DIMS.width()
                        && y >= corridor.getY() && y < corridor.getY() + DIMS.height();
                    int hits = 0;
                    for (BoundingBox box : boxes) {
                        if (planeX >= box.minX() && planeX <= box.maxX()
                            && y >= box.minY() && y <= box.maxY()
                            && z >= box.minZ() && z <= box.maxZ()) {
                            hits++;
                        }
                    }
                    assertEquals(inCorridor ? 0 : 1, hits, role + " cell " + y + "," + z);
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
                lowest, DIMS, LENGTH, role, WORLD_MIN_Y, WORLD_MAX_Y);
            // Two sides and a roof — no floor slab, because that row is the world's own bedrock.
            assertEquals(3, lanes.size(), role.toString());
            for (BoundingBox box : lanes) {
                assertTrue(box.minY() > WORLD_MIN_Y, role + " slab " + box + " reaches the world floor");
            }
        }
    }

    /**
     * A corridor standing entirely below the world has no shell to write — and asking for one must
     * not throw.
     *
     * <p>This is the author's crash, in its own numbers: a {@code 11x70x11} room with its entry door
     * 59 blocks up hung its exit corridor at {@code y=-105} in a world floored at {@code -48}. The
     * clamps then pulled the bottom of the shell up to {@code -47} while its top stayed at
     * {@code -98}, and {@code new BoundingBox(...)} threw out of {@code /dungeontrain portal test}.
     * {@link PortalStructure#corridorLift} is what stops a structure being placed there at all; this
     * is what stops such a placement ever being a stack trace again.</p>
     */
    @Test
    @DisplayName("A corridor below the world gets no shell, rather than an inverted box")
    void belowTheWorld_writesNothing() {
        BlockPos sunken = new BlockPos(126, -105, 512);
        for (PortalCarriageRole role : PortalCarriageRole.values()) {
            assertTrue(PortalCarriageBuilder.corridorLockBoxes(
                    sunken, new CarriageDims(9, 7, 7), LENGTH, role, -48, 320).isEmpty(),
                role + " must write nothing outside the world");
        }
    }
}
