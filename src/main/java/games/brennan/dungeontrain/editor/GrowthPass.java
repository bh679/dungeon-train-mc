package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * The spawn half of the per-row {@link VariantState#growth()}: a column-forming
 * variant placed with growth on is grown into a column once the overlay is in.
 *
 * <p>Works like {@link ConnectPass}: the overlay placers open a {@link Scope}
 * around their cell loop and {@link #note} every write; when the outermost scope
 * on this thread closes — every neighbouring cell is placed by then — each noted
 * cell rolls a length ({@link VariantGrowth#rollLength}) and grows. The column
 * stops early at the first space that is not free ({@link GrowthShapes#isFree}),
 * lies outside the piece being placed, or where the next block can't survive.
 * It never overwrites a block.</p>
 *
 * <p>Writes go through {@link SilentBlockOps#setBlockSilentNoCascade}: no
 * neighbour shape updates, so scaffolding and dripstone keep the shape given
 * here instead of re-deriving (or dropping) mid-overlay.</p>
 */
public final class GrowthPass {

    private record Pending(ServerLevel level, BlockPos pos, BlockState placed, VariantGrowth growth,
                           int length, Predicate<BlockPos> within) {}

    private static final class Frame {
        int depth;
        final List<Pending> pending = new ArrayList<>();
    }

    private static final ThreadLocal<Frame> FRAME = ThreadLocal.withInitial(Frame::new);

    private GrowthPass() {}

    /** An open collection scope — close it (try-with-resources) once the overlay has been placed. */
    public static final class Scope implements AutoCloseable {
        private boolean closed;

        private Scope() {}

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            Frame frame = FRAME.get();
            if (--frame.depth > 0) return;
            List<Pending> toFlush = List.copyOf(frame.pending);
            frame.pending.clear();
            for (Pending p : toFlush) grow(p);
        }
    }

    /** Open a scope; the outermost one grows every {@link #note}d cell when it closes. */
    public static Scope open() {
        FRAME.get().depth++;
        return new Scope();
    }

    /** A {@code within} test for {@link #note}: inside the world-space box of the piece being placed. */
    public static Predicate<BlockPos> inside(BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        BoundingBox box = new BoundingBox(origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + sizeX - 1, origin.getY() + sizeY - 1, origin.getZ() + sizeZ - 1);
        return box::isInside;
    }

    /**
     * Mark {@code world} as holding {@code entry}, placed as {@code placed}. Ignored unless the entry
     * has growth on and the placed block can grow, and on the editor's template-load stamp (its blocks
     * would be captured back into the template). {@code localPos} / {@code seed} / {@code index} seed
     * the length roll; the column only grows into world positions {@code within} accepts.
     */
    public static void note(ServerLevel level, BlockPos world, VariantState entry, BlockState placed,
                            BlockPos localPos, long seed, int index, Predicate<BlockPos> within) {
        VariantGrowth growth = entry.growth();
        if (growth.isDefault() || !GrowthShapes.canGrow(placed)) return;
        if (index == games.brennan.dungeontrain.train.CarriageContentsPlacer.EDITOR_SENTINEL_PIDX) return;
        int length = growth.rollLength(seed, localPos.asLong(), index);
        if (length < 2) return;
        Pending p = new Pending(level, world.immutable(), placed, growth, length, within);
        Frame frame = FRAME.get();
        if (frame.depth > 0) frame.pending.add(p);
        else grow(p);
    }

    /**
     * How many blocks of a {@code length}-long column fit, counting the cell: {@code freeAt(k)} is
     * asked for {@code k = 1, 2, …} and the first {@code false} ends the column.
     */
    static int reach(int length, IntPredicate freeAt) {
        int n = 1;
        while (n < length && freeAt.test(n)) n++;
        return n;
    }

    private static void grow(Pending p) {
        ServerLevel level = p.level();
        // A later write in the overlay may have replaced the cell — only grow what is still there.
        if (level.getBlockState(p.pos()).getBlock() != p.placed().getBlock()) return;
        // Nor from a cell that can't hold its own block (bamboo on stone, a ladder with no wall): it
        // breaks at the first neighbour update and takes the whole column with it as dropped items.
        if (!p.placed().canSurvive(level, p.pos())) return;
        VariantGrowth.Dir dir = GrowthShapes.effectiveDir(p.placed(), p.growth().dir());
        Direction step = dir == VariantGrowth.Dir.UP ? Direction.UP : Direction.DOWN;

        int n = reach(p.length(), k -> {
            BlockPos at = p.pos().relative(step, k);
            if (!p.within().test(at)) return false;
            return GrowthShapes.isFree(p.placed(), level.getBlockState(at));
        });

        // Place, and shorten if a block can't survive where it lands (a ladder run off its wall).
        // A failed block is never written, so re-placing the shorter column overwrites only its own.
        while (n >= 2) {
            int survived = place(level, p, step, n);
            if (survived == n) return;
            n = survived;
        }
        SilentBlockOps.setBlockSilentNoCascade(level, p.pos(), p.placed(), null);
    }

    /** Write an {@code n}-long column; returns how many blocks were placed before one failed to survive. */
    private static int place(ServerLevel level, Pending p, Direction step, int n) {
        List<BlockState> states = GrowthShapes.column(p.placed(), p.growth().dir(), n, p.growth().tip());
        boolean scaffolding = GrowthShapes.isScaffolding(p.placed());
        for (int i = 0; i < n; i++) {
            BlockPos at = p.pos().relative(step, i);
            BlockState state = states.get(i);
            if (i > 0 && scaffolding) {
                int distance = ScaffoldingBlock.getDistance(level, at);
                if (distance >= ScaffoldingBlock.STABILITY_MAX_DISTANCE) return i;
                state = state.setValue(ScaffoldingBlock.DISTANCE, distance)
                    .setValue(ScaffoldingBlock.BOTTOM, distance > 0 && !level.getBlockState(at.below()).is(Blocks.SCAFFOLDING));
            }
            if (i > 0 && !state.canSurvive(level, at)) return i;
            SilentBlockOps.setBlockSilentNoCascade(level, at, state, null);
        }
        return n;
    }
}
