package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Evaluates {@link VariantCondition}s at roll time: which of a cell's entries may join the roll.
 *
 * <p>A condition asks what ends up at its target. For a touching cell that is itself a variant cell
 * the answer is that cell's own roll — rolls are pure functions of (seed, index, position or lock
 * id), so it is re-derived here rather than read back from placement, and placement order never
 * matters. For any other touching cell it is the template's block, which only the placer can see:
 * it supplies that through {@link #open}. A group target is the group's roll.</p>
 *
 * <p>Cycles (A's condition looks at B, B's looks at A) are cut deterministically: a cell asked about
 * while it is already being resolved on this thread rolls with its conditions ignored.</p>
 */
public final class VariantConditions {

    /** The template block at an authored local position, as the placer stamped it. */
    @FunctionalInterface
    public interface TemplateLookup {
        BlockState at(BlockPos authoredLocal);
    }

    /** Stand-in pick for a cell whose every entry was gated out: the placers write air for it. */
    public static final VariantState EMPTY_PICK = VariantState.of(CarriageVariantBlocks.emptyPlaceholder());

    private static final TemplateLookup NO_TEMPLATE = p -> Blocks.AIR.defaultBlockState();
    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();
    private static final ThreadLocal<Set<BlockPos>> RESOLVING = ThreadLocal.withInitial(HashSet::new);

    private VariantConditions() {}

    /**
     * Placement context for one sidecar pass. {@code template} answers non-variant neighbours;
     * {@code prefilter} is whatever the pass does to a cell's list before rolling it (the contents
     * difficulty band), so a neighbour is judged on the roll it really gets.
     */
    public static final class Scope implements AutoCloseable {
        private final TemplateLookup template;
        private final UnaryOperator<List<VariantState>> prefilter;
        @Nullable private final Scope previous;

        private Scope(TemplateLookup template, UnaryOperator<List<VariantState>> prefilter, @Nullable Scope previous) {
            this.template = template;
            this.prefilter = prefilter;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) SCOPE.remove();
            else SCOPE.set(previous);
        }
    }

    public static Scope open(TemplateLookup template) {
        return open(template, UnaryOperator.identity());
    }

    public static Scope open(TemplateLookup template, UnaryOperator<List<VariantState>> prefilter) {
        Scope scope = new Scope(template, prefilter, SCOPE.get());
        SCOPE.set(scope);
        return scope;
    }

    /**
     * A lookup that reads the already-stamped template back out of the world. {@code toWorld} maps
     * an authored local position to where the placer put it (flip / mirror included); positions
     * outside {@code size} read as air, so a cell on the template's edge never looks at a
     * neighbouring carriage and every pass over the same template agrees.
     */
    public static TemplateLookup stamped(net.minecraft.world.level.LevelReader level,
                                         java.util.function.Function<BlockPos, BlockPos> toWorld,
                                         net.minecraft.core.Vec3i size) {
        return p -> {
            if (p.getX() < 0 || p.getY() < 0 || p.getZ() < 0
                || p.getX() >= size.getX() || p.getY() >= size.getY() || p.getZ() >= size.getZ()) {
                return Blocks.AIR.defaultBlockState();
            }
            return level.getBlockState(toWorld.apply(p));
        };
    }

    /** True when any entry in {@code states} carries a condition. */
    public static boolean anyConditional(@Nullable List<VariantState> states) {
        if (states == null) return false;
        for (VariantState s : states) {
            if (s.isConditional()) return true;
        }
        return false;
    }

    /** True while {@code localPos} is being resolved further up this thread's stack (a cycle). */
    static boolean isResolving(BlockPos localPos) {
        return RESOLVING.get().contains(localPos);
    }

    /**
     * {@code states} minus every entry whose condition fails at {@code localPos}. Returns
     * {@code states} itself when nothing carries a condition. Each target is evaluated once.
     */
    public static List<VariantState> eligible(List<VariantState> states, BlockPos localPos,
                                              VariantGroupResolver view, long worldSeed, int index) {
        Set<BlockPos> resolving = RESOLVING.get();
        if (!resolving.add(localPos)) return states;
        try {
            List<VariantState> out = new ArrayList<>(states.size());
            java.util.Map<String, BlockState> targets = new java.util.HashMap<>();
            for (VariantState s : states) {
                if (!s.isConditional()) {
                    out.add(s);
                    continue;
                }
                VariantCondition c = s.condition();
                BlockState target = targets.computeIfAbsent(c.targetToken(),
                    k -> targetBlock(c, localPos, view, worldSeed, index));
                if (c.matches(target)) out.add(s);
            }
            return out;
        } finally {
            resolving.remove(localPos);
        }
    }

    /** What ends up at {@code c}'s target, seen from the cell at {@code localPos}. */
    @Nullable
    static BlockState targetBlock(VariantCondition c, BlockPos localPos,
                                  VariantGroupResolver view, long worldSeed, int index) {
        if (c.isGroupTarget()) {
            BlockPos anchor = view.anchorOf(c.groupId());
            return anchor == null ? null : rolledAt(anchor, view, worldSeed, index);
        }
        return blockAt(localPos.relative(c.direction()), view, worldSeed, index);
    }

    /** The block placed at {@code pos}: its variant roll, or the template's block. */
    static BlockState blockAt(BlockPos pos, VariantGroupResolver view, long worldSeed, int index) {
        if (view.hasCell(pos)) {
            BlockState rolled = rolledAt(pos, view, worldSeed, index);
            if (rolled != null) return rolled;
        }
        Scope scope = SCOPE.get();
        return (scope == null ? NO_TEMPLATE : scope.template).at(pos);
    }

    @Nullable
    private static BlockState rolledAt(BlockPos pos, VariantGroupResolver view, long worldSeed, int index) {
        List<VariantState> states = view.statesAt(pos);
        if (states == null || states.isEmpty()) return null;
        Scope scope = SCOPE.get();
        if (scope != null) states = scope.prefilter.apply(states);
        if (states.isEmpty()) return Blocks.AIR.defaultBlockState();
        VariantState picked = view.resolve(pos, view.lockIdAt(pos), states, worldSeed, index);
        return picked == null ? null : picked.state();
    }

    /** Touching targets in menu order: N S E W U D. */
    public static final Direction[] TOUCHING = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN
    };
}
