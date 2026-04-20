package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.HashSet;
import java.util.Set;

/**
 * Carriage blueprint — a 9×5×4 (X×Z×Y) hollow box. Four visual variants
 * are selected per-carriage via {@link CarriageType}; {@link #placeTrainAt}
 * cycles through them so a train of length ≥ 4 shows one of each.
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

    private static final int BED_FOOT_X = LENGTH / 2;
    private static final int BED_HEAD_X = BED_FOOT_X + 1;
    private static final int BED_Y = 1;
    private static final int BED_Z = WIDTH / 2;

    private static final BlockState BED_FOOT = Blocks.RED_BED.defaultBlockState()
        .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST)
        .setValue(BedBlock.PART, BedPart.FOOT);
    private static final BlockState BED_HEAD = Blocks.RED_BED.defaultBlockState()
        .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST)
        .setValue(BedBlock.PART, BedPart.HEAD);

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
     * Place {@code count} carriages end-to-end along +X. Type is selected
     * per-carriage as {@code CarriageType.values()[i % 4]} — deterministic,
     * and every train of length ≥ 4 shows all four variants.
     */
    public static Set<BlockPos> placeTrainAt(ServerLevel level, BlockPos origin, int count) {
        if (count < 1) throw new IllegalArgumentException("count must be >= 1, got " + count);
        Set<BlockPos> placed = new HashSet<>();
        CarriageType[] types = CarriageType.values();
        for (int i = 0; i < count; i++) {
            CarriageType type = types[i % types.length];
            placed.addAll(placeAt(level, origin.offset(i * LENGTH, 0, 0), type));
        }
        return placed;
    }

    private static BlockState stateAt(int dx, int dy, int dz, int doorZ, CarriageType type) {
        if (type == CarriageType.FLATBED) {
            if (dy == 0) return FLOOR;
            if (dy == BED_Y && dz == BED_Z) {
                if (dx == BED_FOOT_X) return BED_FOOT;
                if (dx == BED_HEAD_X) return BED_HEAD;
            }
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
