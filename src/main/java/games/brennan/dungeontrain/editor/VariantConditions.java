package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * <p>Within one placement pass ({@link #open}) every cell has one answer. Its final pick is
 * remembered, and cells resolve in the sidecar's roll order ({@link VariantGroupResolver#rankOf}):
 * plain cells first, then conditional ones by dependency, a cycle broken bottom-up by position. A
 * condition reading a conditional cell ranked <i>after</i> its own sees that cell's ungated roll;
 * one ranked before sees its final pick. So two neighbours gating on each other ("a heart only
 * between two logs") can never both decide the other was a log.</p>
 *
 * <p>Outside a pass, and as a last resort, a cell asked about while it is already being resolved
 * on this thread rolls with its conditions ignored.</p>
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
        /** Final picks this pass, by (cell, seed, carriage index). */
        private final Map<Key, VariantState> finals = new HashMap<>();

        private record Key(BlockPos pos, long seed, int index) {}

        @Nullable VariantState memo(BlockPos pos, long seed, int index) {
            return finals.get(new Key(pos, seed, index));
        }

        void remember(BlockPos pos, long seed, int index, VariantState pick) {
            finals.put(new Key(pos.immutable(), seed, index), pick);
        }

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

    /** The placement pass open on this thread, or null. */
    @Nullable
    static Scope current() {
        return SCOPE.get();
    }

    /** True while {@code localPos} is being resolved further up this thread's stack (a cycle). */
    static boolean isResolving(BlockPos localPos) {
        return RESOLVING.get().contains(localPos);
    }

    /**
     * {@code states} minus every entry whose condition fails at {@code localPos}. Returns
     * {@code states} itself when nothing carries a condition. Each neighbour and group is evaluated
     * once however many entries ask about it.
     */
    public static List<VariantState> eligible(List<VariantState> states, BlockPos localPos,
                                              VariantGroupResolver view, long worldSeed, int index) {
        Set<BlockPos> resolving = RESOLVING.get();
        if (!resolving.add(localPos)) return states;
        try {
            List<VariantState> out = new ArrayList<>(states.size());
            Map<Object, BlockState> seen = new HashMap<>();
            for (VariantState s : states) {
                if (!s.isConditional() || passes(s.condition(), localPos, view, worldSeed, index, seen)) out.add(s);
            }
            return out;
        } finally {
            resolving.remove(localPos);
        }
    }

    /**
     * Whether {@code c} holds for the cell at {@code localPos}: a group target when that group rolled
     * a listed block, a touching target when <i>any</i> selected side holds one.
     */
    static boolean passes(VariantCondition c, BlockPos localPos, VariantGroupResolver view,
                          long worldSeed, int index, Map<Object, BlockState> seen) {
        if (c.isGroupTarget()) {
            BlockState rolled = seen.computeIfAbsent(c.groupId(), k -> {
                BlockPos anchor = view.anchorOf(c.groupId());
                BlockState b = anchor == null ? null : rolledAt(anchor, localPos, view, worldSeed, index);
                return b == null ? Blocks.AIR.defaultBlockState() : b;
            });
            return c.matches(rolled);
        }
        for (Direction d : c.sides()) {
            BlockState there = seen.computeIfAbsent(d, k -> blockAt(localPos.relative(d), localPos, view, worldSeed, index));
            boolean match = c.matches(there);
            // Any: one listed side is enough. All: every listed side must hold one.
            if (match && !c.all()) return true;
            if (!match && c.all()) return false;
        }
        return c.all();
    }

    /** The block placed at {@code pos}, as {@code asker} sees it: its variant roll, or the template's block. */
    static BlockState blockAt(BlockPos pos, BlockPos asker, VariantGroupResolver view, long worldSeed, int index) {
        if (view.hasCell(pos)) {
            BlockState rolled = rolledAt(pos, asker, view, worldSeed, index);
            if (rolled != null) return rolled;
        }
        Scope scope = SCOPE.get();
        return (scope == null ? NO_TEMPLATE : scope.template).at(pos);
    }

    /**
     * {@code pos}'s roll as {@code asker} sees it. Inside a pass a cell ranked before the asker has
     * its final pick; a conditional cell ranked after it is read ungated, since it decides later.
     */
    @Nullable
    private static BlockState rolledAt(BlockPos pos, BlockPos asker, VariantGroupResolver view,
                                       long worldSeed, int index) {
        List<VariantState> states = view.statesAt(pos);
        if (states == null || states.isEmpty()) return null;
        Scope scope = SCOPE.get();
        if (scope != null) {
            VariantState known = scope.memo(pos, worldSeed, index);
            if (known != null) return known.state();
            states = scope.prefilter.apply(states);
        }
        if (states.isEmpty()) return Blocks.AIR.defaultBlockState();
        boolean later = scope != null && anyConditional(states) && view.rankOf(pos) > view.rankOf(asker);
        VariantState picked = later
            ? ungated(pos, () -> view.resolve(pos, view.lockIdAt(pos), states(view, pos, scope), worldSeed, index))
            : view.resolve(pos, view.lockIdAt(pos), states, worldSeed, index);
        return picked == null ? null : picked.state();
    }

    private static List<VariantState> states(VariantGroupResolver view, BlockPos pos, Scope scope) {
        return scope.prefilter.apply(view.statesAt(pos));
    }

    /** Resolves {@code pos} with its conditions ignored (and so not remembered as its final pick). */
    @Nullable
    private static VariantState ungated(BlockPos pos, java.util.function.Supplier<VariantState> roll) {
        Set<BlockPos> resolving = RESOLVING.get();
        boolean added = resolving.add(pos);
        try {
            return roll.get();
        } finally {
            if (added) resolving.remove(pos);
        }
    }

    /** Touching targets in menu order: N S E W U D. */
    public static final Direction[] TOUCHING = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN
    };
}
