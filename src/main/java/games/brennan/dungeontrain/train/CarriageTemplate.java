package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/**
 * Carriage blueprint — a 9×5×4 (X×Z×Y) hollow box. Four visual variants
 * are selected per-carriage via {@link CarriageType} and
 * {@link #typeForIndex(int)} so a train of length ≥ 4 shows one of each.
 */
public final class CarriageTemplate {

    public enum CarriageType {
        STANDARD,
        WINDOWED,
        SOLID_ROOF,
        FLATBED
    }

    public static final int LENGTH = 9;
    public static final int WIDTH = 5;
    public static final int HEIGHT = 4;

    private static final BlockState FLOOR = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState WALL = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState GLASS_CEILING = Blocks.GLASS.defaultBlockState();
    private static final BlockState SOLID_CEILING = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState WINDOW = Blocks.GLASS.defaultBlockState();

    private CarriageTemplate() {}

    public static Set<BlockPos> placeAt(ServerLevel level, BlockPos origin) {
        return placeAt(level, origin, CarriageType.STANDARD);
    }

    /**
     * Place a single carriage of the given type at origin (= minimum corner).
     * Returns the set of block positions filled — pass directly to
     * {@code ShipAssembler.assembleToShip()}.
     */
    public static Set<BlockPos> placeAt(ServerLevel level, BlockPos origin, CarriageType type) {
        Set<BlockPos> placed = new HashSet<>();
        int doorZ = WIDTH / 2;

        for (int dx = 0; dx < LENGTH; dx++) {
            for (int dz = 0; dz < WIDTH; dz++) {
                for (int dy = 0; dy < HEIGHT; dy++) {
                    BlockState state = stateAt(dx, dy, dz, doorZ, type);
                    if (state == null) continue;
                    BlockPos pos = origin.offset(dx, dy, dz);
                    level.setBlock(pos, state, 3);
                    placed.add(pos.immutable());
                }
            }
        }
        return placed;
    }

    /**
     * Deterministic type selector for carriage index {@code i}. Shared by the
     * initial spawn path and the rolling-window manager so carriage index N
     * always renders the same variant regardless of when it appears.
     */
    public static CarriageType typeForIndex(int i) {
        CarriageType[] types = CarriageType.values();
        int mod = Math.floorMod(i, types.length);
        return types[mod];
    }

    /**
     * Erase a carriage footprint — set every block in the 9×4×5 region at {@code origin}
     * to air. Used by the rolling-window manager to remove stale carriages from the
     * trailing end of the train.
     */
    public static void eraseAt(ServerLevel level, BlockPos origin) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = 0; dx < LENGTH; dx++) {
            for (int dz = 0; dz < WIDTH; dz++) {
                for (int dy = 0; dy < HEIGHT; dy++) {
                    level.setBlock(origin.offset(dx, dy, dz), air, 3);
                }
            }
        }
    }

    private static BlockState stateAt(int dx, int dy, int dz, int doorZ, CarriageType type) {
        if (type == CarriageType.FLATBED) {
            if (dy == 0) return FLOOR;
            return null;
        }

        if (dy == 0) return FLOOR;
        if (dy == HEIGHT - 1) {
            return (type == CarriageType.SOLID_ROOF) ? SOLID_CEILING : GLASS_CEILING;
        }

        boolean onPerimeter = (dx == 0 || dx == LENGTH - 1 || dz == 0 || dz == WIDTH - 1);
        if (!onPerimeter) return null;

        boolean isEndWall = (dx == 0 || dx == LENGTH - 1);
        boolean isDoorGap = isEndWall && dz == doorZ && (dy == 1 || dy == 2);
        if (isDoorGap) return null;

        if (type == CarriageType.WINDOWED && dy == 2 && !isEndWall) {
            return WINDOW;
        }

        return WALL;
    }
}
