package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads a room's door offset off a door an author actually placed, rather than a value dialled in
 * separately — so "where the door is" and "what is standing there" cannot disagree.
 *
 * <p><b>Where it looks.</b> The same two columns {@link PortalRoomDoorCells#forRoom} ghosts: one
 * column outside the room box at each end ({@code origin.x - 1} and {@code origin.x + size.x}),
 * spanning the doorway's two rows ({@code origin.y + 1} and {@code + 2}). That is the plot's own
 * box plus nothing else — no corridor exists yet in an editor plot, so this reads the same live
 * world {@link EditorStrayBlocks} already scans, not the saved structure (which never captures
 * outside the box in the first place).</p>
 *
 * <p><b>One offset for a room with two ends.</b> Both doors sit on one straight walkway, so a door
 * at either end implies the same offset. If an author has built a door at both ends and they
 * disagree, the entry end (the near column) wins — that is the one the sentence in
 * {@code PortalRoomEditor.enter} names first, and the one a player actually arrives through.</p>
 *
 * <p><b>No door anywhere means centred</b>, not "leave whatever was there" — the offset is a pure
 * function of what is built each time this runs, so removing a door an author had placed brings the
 * room back to the default rather than leaving a stale value nothing on the ground explains.</p>
 */
public final class PortalRoomDoorDetection {

    private PortalRoomDoorDetection() {}

    /**
     * The offset implied by whatever door blocks stand in {@code level} at {@code origin}/{@code
     * size}'s two doorway columns, clamped to what this room's own width can spend — see
     * {@link PortalRoomLayout#clampDoorOffset}.
     */
    public static PortalRoomDoorOffset detect(
        ServerLevel level, BlockPos origin, Vec3i size, CarriageDims dims
    ) {
        if (origin == null || size == null || level == null) return PortalRoomDoorOffset.DEFAULT;
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 2) return PortalRoomDoorOffset.DEFAULT;

        Integer nearZ = doorZOnColumn(level, origin.getX() - 1, origin, size);
        Integer detectedZ = nearZ != null ? nearZ : doorZOnColumn(level, origin.getX() + size.getX(), origin, size);
        if (detectedZ == null) return PortalRoomDoorOffset.DEFAULT;

        // Same box-only centre line PortalRoomDoorCells.doorZ(origin, size) reads at offset 0 — an
        // editor plot has no corridor to measure against, only its own box.
        int centreZ = origin.getZ() + 1 + (size.getZ() - 2) / 2;
        int offset = detectedZ - centreZ;
        return new PortalRoomDoorOffset(PortalRoomLayout.clampDoorOffset(dims, size.getZ(), offset));
    }

    /**
     * The Z of the first door block found on doorway column {@code x}, scanning every legal Z in
     * {@code origin}/{@code size}'s interior — or {@code null} if there is none.
     */
    private static Integer doorZOnColumn(ServerLevel level, int x, BlockPos origin, Vec3i size) {
        int minZ = origin.getZ() + 1;
        int maxZ = origin.getZ() + size.getZ() - 2;
        int y = origin.getY() + 1;
        for (int z = minZ; z <= maxZ; z++) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (state.getBlock() instanceof DoorBlock) return z;
        }
        return null;
    }
}
