package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/**
 * MVP carriage blueprint — a 9×5×4 (X×Z×Y) hollow box with stone-brick
 * floor and walls, glass ceiling, and a 1×2 door gap in the middle of
 * each end wall (x=0 and x=LENGTH-1). MVP only; procgen replaces this.
 */
public final class CarriageTemplate {

    public static final int LENGTH = 9;
    public static final int WIDTH = 5;
    public static final int HEIGHT = 4;

    private static final BlockState FLOOR = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState WALL = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState CEILING = Blocks.GLASS.defaultBlockState();

    private CarriageTemplate() {}

    /**
     * Place the blueprint into level at origin (= minimum corner).
     * Returns the immutable set of block positions filled — pass this
     * directly to ShipAssembler.assembleToShip().
     */
    public static Set<BlockPos> placeAt(ServerLevel level, BlockPos origin) {
        Set<BlockPos> placed = new HashSet<>();
        int doorZ = WIDTH / 2;

        for (int dx = 0; dx < LENGTH; dx++) {
            for (int dz = 0; dz < WIDTH; dz++) {
                for (int dy = 0; dy < HEIGHT; dy++) {
                    BlockState state = stateAt(dx, dy, dz, doorZ);
                    if (state == null) continue;
                    BlockPos pos = origin.offset(dx, dy, dz);
                    level.setBlock(pos, state, 3);
                    placed.add(pos.immutable());
                }
            }
        }
        return placed;
    }

    private static BlockState stateAt(int dx, int dy, int dz, int doorZ) {
        if (dy == 0) return FLOOR;
        if (dy == HEIGHT - 1) return CEILING;

        boolean onPerimeter = (dx == 0 || dx == LENGTH - 1 || dz == 0 || dz == WIDTH - 1);
        if (!onPerimeter) return null;

        boolean isEndWall = (dx == 0 || dx == LENGTH - 1);
        boolean isDoorGap = isEndWall && dz == doorZ && (dy == 1 || dy == 2);
        if (isDoorGap) return null;

        return WALL;
    }
}
