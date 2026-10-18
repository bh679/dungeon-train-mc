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
 * <p>An entry's If list ({@link VariantConditionSet}) is combined AND or OR together with its Level
 * band ({@link VariantDifficulty}), which asks the scope's difficulty tier. A scope with no tier
 * ({@link #NO_TIER} — the editor, or a placer with no carriage to measure) lets every band pass.</p>
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

    /** Tier of a scope that has none: every Level band passes. */
    public static final int NO_TIER = -1;

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
        private final int tier;
        @Nullable private final Scope previous;

        private Scope(TemplateLookup template, UnaryOperator<List<VariantState>> prefilter, int tier,
                      @Nullable Scope previous) {
            this.template = template;
            this.prefilter = prefilter;
            this.tier = tier;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) SCOPE.remove();
            else SCOPE.set(previous);
        }
    }

    public static Scope open(TemplateLookup template) {
        return open(template, UnaryOperator.identity(), NO_TIER);
    }

    /** A scope whose Level conditions ask {@code tier} ({@link #NO_TIER}: every band passes). */
    public static Scope open(TemplateLookup template, int tier) {
        return open(template, UnaryOperator.identity(), tier);
    }

    public static Scope open(TemplateLookup template, UnaryOperator<List<VariantState>> prefilter) {
        return open(template, prefilter, NO_TIER);
    }

    public static Scope open(TemplateLookup template, UnaryOperator<List<VariantState>> prefilter, int tier) {
        Scope scope = new Scope(template, prefilter, tier, SCOPE.get());
        SCOPE.set(scope);
        return scope;
    }

    /**
     * The open scope again (or a template-less one) with its tier set to {@code tier} — for a pick
     * that knows its tier but may be asked outside its placer's scope. Close it like any scope.
     */
    public static Scope withTier(int tier) {
        Scope scope = SCOPE.get();
        return scope == null ? open(NO_TEMPLATE, UnaryOperator.identity(), tier)
            : open(scope.template, scope.prefilter, tier);
    }

    /**
     * The Level tier for a carriage index: {@link games.brennan.dungeontrain.difficulty.DifficultyProgression#positionTier},
     * or {@link #NO_TIER} for the editor's sentinel ({@code -1}) and the portal editor's no-pair
     * ({@link Integer#MIN_VALUE}) — neither is a place on the line, and {@code positionTier(-1)}
     * would quietly answer for distance 1.
     */
    public static int tierAt(int carriageIndex) {
        if (carriageIndex == -1 || carriageIndex == Integer.MIN_VALUE) return NO_TIER;
        return games.brennan.dungeontrain.difficulty.DifficultyProgression.positionTier(carriageIndex);
    }

    /** The open scope's difficulty tier, {@link #NO_TIER} outside a scope or in one without a tier. */
    public static int tier() {
        Scope scope = SCOPE.get();
        return scope == null ? NO_TIER : scope.tier;
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
            int tier = tier();
            for (VariantState s : states) {
                if (!s.isConditional() || passes(s, tier, localPos, view, worldSeed, index, seen)) out.add(s);
            }
            return out;
        } finally {
            resolving.remove(localPos);
        }
    }

    /**
     * Whether {@code s}'s If list holds: its gating conditions and its Level band (when narrower than
     * every tier), all of them for AND, any one for OR. Each test runs only when the answer still
     * hangs on it.
     */
    static boolean passes(VariantState s, int tier, BlockPos localPos, VariantGroupResolver view,
                          long worldSeed, int index, Map<Object, BlockState> seen) {
        boolean any = s.condition().any();
        boolean tested = false;
        // With no tier to ask, the band is left out rather than passed, so an OR row still answers
        // to its other conditions.
        if (!s.difficulty().isDefault() && tier != NO_TIER) {
            boolean ok = s.difficulty().eligible(tier);
            if (ok == any) return any;
            tested = true;
        }
        for (VariantCondition c : s.condition().gating()) {
            boolean ok = passes(c, localPos, view, worldSeed, index, seen);
            if (ok == any) return any;
            tested = true;
        }
        // AND: nothing failed. OR: nothing passed — fails unless there was nothing to test.
        return !any || !tested;
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
                BlockState b = anchor == null ? null : rolledAt(anchor, view, worldSeed, index);
                return b == null ? Blocks.AIR.defaultBlockState() : b;
            });
            return c.matches(rolled);
        }
        for (Direction d : c.sides()) {
            BlockState there = seen.computeIfAbsent(d, k -> blockAt(localPos.relative(d), view, worldSeed, index));
            if (c.matches(there)) return true;
        }
        return false;
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
