package games.brennan.dungeontrain.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The part of a chunk-dimension room's box that its pour will write, which the stamp need not.
 *
 * <p>A generated room is stamped from its template (a ground floor and captured air) and then
 * poured from its sample ({@link PortalChunkDimension}), which writes every cell the sample
 * reaches. Clearing that volume through the light engine and laying the template over it first
 * wrote a 3x3 room's 73,728 cells twice in the stamp's own tick, only for the pour to write them a
 * third time: 112 ms median in the editor, 554 ms in a ride. So the stamp clears this box through
 * the section-level {@link PortalClear#clearBox} instead, lays the template only around it, and
 * leaves the rest to the pour. Before the pour reaches a row the room is air, as it was.</p>
 *
 * <p><b>Why a box.</b> A slice holds a state for every cell inside its bounds (air where the cube
 * ran out), so the cells a pour writes are exactly the room cells whose shifted row falls inside
 * the slice: one box. The box leaves out the two end columns (room-local X {@code 0} and
 * {@code size.x - 1}), which the corridors' mouth seals copy while they are stamped, before any
 * pour ({@code PortalCarriageBuilder#sealFillFor}); those keep the template's cells so the seals
 * come out as they always have.</p>
 */
final class PortalChunkStampTrim {

    private PortalChunkStampTrim() {}

    /**
     * The room-local box {@code {minX, minY, minZ, maxX, maxY, maxZ}} the pour writes and the stamp
     * may skip, or null when there is none.
     *
     * <p>Pure geometry, so it is unit-tested without a level. The pour reads the slice at row
     * {@code y + shift} for room row {@code y} ({@code PortalChunkDimension#writeStrips}).</p>
     */
    static int[] localBox(Vec3i size, int sliceWidth, int sliceHeight, int shift) {
        int maxX = Math.min(size.getX(), sliceWidth) - 2;
        int maxZ = Math.min(size.getZ(), sliceWidth) - 1;
        int minY = Math.max(0, -shift);
        int maxY = Math.min(size.getY(), sliceHeight - shift) - 1;
        if (maxX < 1 || maxZ < 0 || maxY < minY) return null;
        return new int[] {1, minY, 0, maxX, maxY, maxZ};
    }

    /**
     * {@link #localBox} in world space at {@code roomOrigin}, or null when there is none or when
     * {@code corridors} reaches into it: the pour skips corridor cells, so the stamp must not, and
     * such a room is simply stamped whole.
     */
    static BoundingBox worldBox(BlockPos roomOrigin, Vec3i size, PortalChunkSlice slice, int shift,
                                PortalCorridorMask corridors) {
        if (slice == null) return null;
        int[] b = localBox(size, slice.width(), slice.height(), shift);
        if (b == null) return null;
        BoundingBox box = new BoundingBox(
            roomOrigin.getX() + b[0], roomOrigin.getY() + b[1], roomOrigin.getZ() + b[2],
            roomOrigin.getX() + b[3], roomOrigin.getY() + b[4], roomOrigin.getZ() + b[5]);
        return corridors.intersects(box) ? null : box;
    }
}
