package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Carriage blueprint — a 9×5×4 (X×Z×Y) hollow box. The shell geometry is
 * driven by the carriage's {@link CarriageArchitecture}; block materials
 * come from the carriage's {@link CarriageStyle} (via its
 * {@link BlockPalette}). Contents (chest / lectern / mobs) are placed
 * lazily by {@link ContentsPopulator} once a player is near — the template
 * only lays down the frame.
 */
public final class CarriageTemplate {

    public static final int LENGTH = 9;
    public static final int WIDTH = 5;
    public static final int HEIGHT = 4;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private CarriageTemplate() {}

    /**
     * Place a single carriage at {@code origin} (minimum corner) using
     * the given spec. Returns the set of block positions filled — pass
     * directly to {@code ShipAssembler.assembleToShip()}.
     */
    public static Set<BlockPos> placeAt(ServerLevel level, BlockPos origin, CarriageSpec spec) {
        Set<BlockPos> placed = new HashSet<>();
        int doorZ = WIDTH / 2;
        BlockPalette palette = spec.style().palette();
        CarriageArchitecture arch = spec.architecture();

        for (int dx = 0; dx < LENGTH; dx++) {
            for (int dz = 0; dz < WIDTH; dz++) {
                for (int dy = 0; dy < HEIGHT; dy++) {
                    BlockState state = stateAt(dx, dy, dz, doorZ, arch, palette);
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
     * Place every carriage in {@code specs} end-to-end along +X. Spec
     * {@code i} is applied at offset {@code i * LENGTH}.
     */
    public static Set<BlockPos> placeTrainAt(ServerLevel level, BlockPos origin, List<CarriageSpec> specs) {
        if (specs.isEmpty()) throw new IllegalArgumentException("specs must be non-empty");
        Set<BlockPos> placed = new HashSet<>();
        for (int i = 0; i < specs.size(); i++) {
            placed.addAll(placeAt(level, origin.offset(i * LENGTH, 0, 0), specs.get(i)));
        }
        return placed;
    }

    /**
     * Erase a carriage footprint — set every block in the 9×4×5 region at
     * {@code origin} to air. Used by the rolling-window manager to remove
     * stale carriages from the trailing end of the train.
     */
    public static void eraseAt(ServerLevel level, BlockPos origin) {
        for (int dx = 0; dx < LENGTH; dx++) {
            for (int dz = 0; dz < WIDTH; dz++) {
                for (int dy = 0; dy < HEIGHT; dy++) {
                    level.setBlock(origin.offset(dx, dy, dz), AIR, 3);
                }
            }
        }
    }

    private static BlockState stateAt(
        int dx, int dy, int dz, int doorZ,
        CarriageArchitecture arch, BlockPalette palette
    ) {
        if (arch == CarriageArchitecture.FLATBED) {
            if (dy == 0) return palette.floor();
            return null;
        }

        if (dy == 0) return palette.floor();
        if (dy == HEIGHT - 1) {
            return (arch == CarriageArchitecture.SOLID_ROOF)
                ? palette.solidCeiling()
                : palette.glassCeiling();
        }

        // Only dx==0 has an end wall. Adjacent carriages share a single wall
        // (the next carriage's left-end wall) rather than stacking two walls
        // back-to-back, so an infinite train reads as one connected corridor
        // instead of a row of independent boxes.
        boolean onPerimeter = (dx == 0 || dz == 0 || dz == WIDTH - 1);
        if (!onPerimeter) return null;

        boolean isEndWall = (dx == 0);
        boolean isDoorGap = isEndWall && dz == doorZ && (dy == 1 || dy == 2);
        if (isDoorGap) return null;

        if (arch == CarriageArchitecture.WINDOWED && dy == 2 && !isEndWall) {
            return palette.window();
        }

        return palette.wallAt(dx, dy, dz);
    }
}
