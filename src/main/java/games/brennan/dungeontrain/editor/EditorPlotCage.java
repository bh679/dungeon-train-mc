package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageStampGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The outline cage every editor plot wears: blocks along the 12 edges of the bounding box that sits
 * one block outside the {@code length × height × width} footprint. Faces are left empty so the
 * player can fly in and out freely.
 *
 * <p>The one edge walk for every editor. Each used to carry its own copy; they were byte-identical
 * apart from the write flags, which are a parameter here.</p>
 */
final class EditorPlotCage {

    static final BlockState OUTLINE_BLOCK = Blocks.BEDROCK.defaultBlockState();

    /** Vanilla's default block-write flags: neighbours notified, clients updated. */
    static final int NOTIFY = Block.UPDATE_ALL;

    /**
     * Write flags for plots whose contents must not react to the cage going up or down: no
     * neighbour updates, no drops, shape taken as given. The building-sized plots use these.
     */
    static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private EditorPlotCage() {}

    /** Draw (or, with air, erase) the cage around a {@code size} footprint at {@code origin}. */
    static void setOutline(ServerLevel level, BlockPos origin, Vec3i size, BlockState state) {
        setOutline(level, origin, size, state, NOTIFY);
    }

    /** {@link #setOutline(ServerLevel, BlockPos, Vec3i, BlockState)} with explicit block-write flags. */
    static void setOutline(ServerLevel level, BlockPos origin, Vec3i size, BlockState state, int flags) {
        int x0 = origin.getX() - 1;
        int y0 = origin.getY() - 1;
        int z0 = origin.getZ() - 1;
        int x1 = origin.getX() + size.getX();
        int y1 = origin.getY() + size.getY();
        int z1 = origin.getZ() + size.getZ();

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int extremes = (x == x0 || x == x1 ? 1 : 0)
                        + (y == y0 || y == y1 ? 1 : 0)
                        + (z == z0 || z == z1 ? 1 : 0);
                    if (extremes < 2) continue;
                    level.setBlock(p.set(x, y, z), state, flags);
                }
            }
        }
    }

    /**
     * {@link #setOutline(ServerLevel, BlockPos, Vec3i, BlockState)} under {@link CarriageStampGuard}:
     * the cage sits on the plot boundary, so an observer on the template's outer face would see the
     * flag-3 writes. Portal rooms draw theirs this way.
     */
    static void setOutlineGuarded(ServerLevel level, BlockPos origin, Vec3i size, BlockState state) {
        CarriageStampGuard.run(() -> setOutline(level, origin, size, state, NOTIFY));
    }
}
