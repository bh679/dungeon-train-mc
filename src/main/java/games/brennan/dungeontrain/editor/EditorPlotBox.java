package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The box an editor plot occupies, cage included: one block outside the footprint on every side,
 * plus two blocks of headroom above it.
 *
 * <p>The headroom is for the roof landing: {@link EditorPlotArrival} puts a player who asked for
 * "on top" two blocks above the cage, and that player must still count as inside the plot or a
 * "Go here" from the roof restamps and discards edits. Every editor's {@code plotContaining} and
 * erase used to spell this box out by hand; these are the one spelling.</p>
 */
final class EditorPlotBox {

    /** Blocks above the cage that still count as the plot. */
    static final int HEADROOM = 2;

    private EditorPlotBox() {}

    /** True when {@code pos} is inside the plot box of a {@code size} footprint at {@code origin}. */
    static boolean contains(BlockPos pos, BlockPos origin, Vec3i size) {
        return pos.getX() >= origin.getX() - 1 && pos.getX() <= origin.getX() + size.getX()
            && pos.getY() >= origin.getY() - 1 && pos.getY() <= origin.getY() + size.getY() + HEADROOM
            && pos.getZ() >= origin.getZ() - 1 && pos.getZ() <= origin.getZ() + size.getZ();
    }

    /**
     * Set every block of the footprint and its cage back to air, with {@code flags} on each write.
     * Block entities go first so the air write cannot orphan one.
     */
    static void erase(ServerLevel level, BlockPos origin, Vec3i size, int flags) {
        fill(level, origin.offset(-1, -1, -1),
            origin.offset(size.getX(), size.getY(), size.getZ()), Blocks.AIR.defaultBlockState(), flags);
    }

    /** {@link #erase(ServerLevel, BlockPos, Vec3i, int)} reaching {@link #HEADROOM} above the cage too. */
    static void eraseWithHeadroom(ServerLevel level, BlockPos origin, Vec3i size, int flags) {
        fill(level, origin.offset(-1, -1, -1),
            origin.offset(size.getX(), size.getY() + HEADROOM, size.getZ()), Blocks.AIR.defaultBlockState(), flags);
    }

    /** Fill the inclusive box {@code min..max} with {@code state}, skipping blocks already in it. */
    static void fill(ServerLevel level, BlockPos min, BlockPos max, BlockState state, int flags) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    p.set(x, y, z);
                    BlockState current = level.getBlockState(p);
                    if (current == state) continue;
                    if (current.hasBlockEntity()) level.removeBlockEntity(p);
                    level.setBlock(p, state, flags);
                }
            }
        }
    }
}
