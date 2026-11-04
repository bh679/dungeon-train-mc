package games.brennan.dungeontrain.portal.tribute;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * The layer of bedrock a tribute room always stands on, whatever its weights entry says about its
 * floor: the one block of it that is never the owner's to dig through, so the room stays the box it
 * was saved as and nobody tunnels out of a visit.
 */
public final class TributeRoomFloor {

    private TributeRoomFloor() {}

    /** Bedrock under every column of the room at {@code origin} of {@code size}. Returns the blocks laid. */
    public static int lay(ServerLevel level, BlockPos origin, Vec3i size) {
        if (level == null || origin == null || size == null) return 0;
        int y = origin.getY() - 1;
        if (y < level.getMinBuildHeight()) return 0;
        int laid = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < size.getZ(); dz++) {
            for (int dx = 0; dx < size.getX(); dx++) {
                cursor.set(origin.getX() + dx, y, origin.getZ() + dz);
                if (level.getBlockState(cursor).is(Blocks.BEDROCK)) continue;
                level.setBlock(cursor.immutable(), Blocks.BEDROCK.defaultBlockState(), 2);
                laid++;
            }
        }
        return laid;
    }
}
