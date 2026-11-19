package games.brennan.dungeontrain.compat.vista;

import net.mehvahdjukaar.vista.common.connection.IConnectedBlock;
import net.mehvahdjukaar.vista.common.tv.TVBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * The screen a Vista TV block belongs to: the connected grid of TVs it sits in, and which of them is
 * the master.
 *
 * <p>Vista joins placed TVs into one screen by writing each block's {@code connection} state (which
 * edges of the screen it has); only the bottom-left block, the master, holds the block entity — the
 * cassette, the video source, the tick that plays the sound. Every other block of the grid is a child
 * with no block entity. The grid is read from those states: from any block, the blocks it says it is
 * connected to, and so on ({@link #tiles}); the master is the one with both the bottom and the left
 * edge ({@link #isMaster}).</p>
 */
public final class TvGrid {

    /** The side of the grid a walk gives up at: no screen Vista allows is bigger (its cap is per side). */
    static final int MAX_TILES = 16 * 16;

    private TvGrid() {}

    /** Every block of the screen the TV at {@code pos} belongs to, {@code pos} included; just {@code pos} for a lone TV. */
    public static Set<BlockPos> tiles(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof IConnectedBlock block)) return Set.of(pos.immutable());
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        List<Direction> sides = List.of(Direction.UP, Direction.DOWN, facing.getClockWise(), facing.getCounterClockWise());
        return walk(pos, sides, (from, side) -> {
            BlockState at = level.getBlockState(from);
            return at.is(state.getBlock()) && block.isNeighborConnected(level, from, at, side);
        });
    }

    /**
     * The blocks reachable from {@code start} over {@code connected} (whether the block at a position is
     * joined to its neighbour on a side), {@code start} included, in walk order. Pure. Capped at
     * {@link #MAX_TILES} so a corrupt ring of states can never walk forever.
     */
    static Set<BlockPos> walk(BlockPos start, List<Direction> sides, BiPredicate<BlockPos, Direction> connected) {
        Set<BlockPos> seen = new LinkedHashSet<>();
        Deque<BlockPos> open = new ArrayDeque<>();
        seen.add(start.immutable());
        open.add(start.immutable());
        while (!open.isEmpty() && seen.size() < MAX_TILES) {
            BlockPos from = open.poll();
            for (Direction side : sides) {
                if (!connected.test(from, side)) continue;
                BlockPos next = from.relative(side).immutable();
                if (seen.add(next)) open.add(next);
            }
        }
        return seen;
    }

    /** Whether {@code state} is the master block of its screen (or a lone TV): the one Vista gives the block entity. */
    public static boolean isMaster(BlockState state) {
        return state.getBlock() instanceof IConnectedBlock block && block.shouldHaveBlockEntity(state);
    }

    /** Whether the block at {@code pos} is a TV that is a master and whose block state says it is switched on. */
    public static boolean isLitMaster(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return isMaster(state) && state.hasProperty(TVBlock.POWER_STATE) && state.getValue(TVBlock.POWER_STATE).isOn();
    }
}
