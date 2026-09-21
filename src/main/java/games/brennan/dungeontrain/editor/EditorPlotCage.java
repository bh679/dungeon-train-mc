package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The outline cage every editor plot wears: blocks along the 12 edges of the bounding box that sits
 * one block outside the {@code length × height × width} footprint. Faces are left empty so the
 * player can fly in and out freely.
 *
 * <p>Extracted from {@link CarriageEditor} so the Whole section's plots draw the same cage without a
 * second copy of the edge walk.</p>
 */
final class EditorPlotCage {

    static final BlockState OUTLINE_BLOCK = Blocks.BEDROCK.defaultBlockState();

    private EditorPlotCage() {}

    /** Draw (or, with air, erase) the cage around a {@code size} footprint at {@code origin}. */
    static void setOutline(ServerLevel level, BlockPos origin, Vec3i size, BlockState state) {
        int x0 = origin.getX() - 1;
        int y0 = origin.getY() - 1;
        int z0 = origin.getZ() - 1;
        int x1 = origin.getX() + size.getX();
        int y1 = origin.getY() + size.getY();
        int z1 = origin.getZ() + size.getZ();

        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int extremes = (x == x0 || x == x1 ? 1 : 0)
                        + (y == y0 || y == y1 ? 1 : 0)
                        + (z == z0 || z == z1 ? 1 : 0);
                    if (extremes < 2) continue;
                    level.setBlock(new BlockPos(x, y, z), state, 3);
                }
            }
        }
    }
}
