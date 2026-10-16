package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-sidecar owner of the v9 lock-group reference machinery: a live
 * {@link VariantGroupRefs.LockGroupView} over one sidecar's maps, the cached
 * resolvable-group set, and the whole pick tail (filter → roll → follow).
 *
 * <p>All four block-variant sidecars store the same two maps — {@code pos →
 * candidates} and {@code pos → lockId} — so they each hold one of these,
 * constructed over their own maps <b>by reference</b>. The resolver therefore
 * always sees current data; the only thing it caches is the graph, which
 * every sidecar mutator drops via {@link #invalidate}.</p>
 *
 * <p>Split out of the sidecars rather than copied into each so the reference
 * semantics have exactly one definition — four hand-maintained copies of a
 * graph and a filter would be four chances to drift.</p>
 */
public final class VariantGroupResolver implements VariantGroupRefs.LockGroupView {

    private final Map<BlockPos, List<VariantState>> entries;
    private final Map<BlockPos, Integer> lockIds;

    /**
     * Cached {@link VariantGroupRefs#analyse} result; {@code null} means
     * "recompute on next use". The analysis only walks lock groups (single
     * digits per template) but {@code resolve} runs on the worldgen hot path,
     * so it must not repeat the walk per cell. Volatile because worldgen
     * worker threads read it.
     */
    private volatile VariantGroupRefs.Graph graphCache;

    public VariantGroupResolver(Map<BlockPos, List<VariantState>> entries,
                                Map<BlockPos, Integer> lockIds) {
        this.entries = entries;
        this.lockIds = lockIds;
    }

    /** Drop the cached graph. Call from every mutator that touches entries or lock-ids. */
    public void invalidate() {
        graphCache = null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A scan of the lock-id map, so author-time callers and
     * {@link VariantGroupRefs#analyse}'s one-shot index build use it freely and
     * nothing on the placement path does — {@code analyse} calls this exactly
     * once per group and hands every later step the index it built, which is
     * also why this cannot be backed by that index without recursing.</p>
     */
    @Override
    @Nullable
    public List<VariantState> statesForLockId(int lockId) {
        if (lockId <= 0) return null;
        for (Map.Entry<BlockPos, Integer> e : lockIds.entrySet()) {
            if (e.getValue() == lockId) return entries.get(e.getKey());
        }
        return null;
    }

    @Override
    public Set<Integer> lockIdsInUse() {
        return new LinkedHashSet<>(lockIds.values());
    }

    /** True when {@code localPos} is a variant cell of this sidecar. */
    public boolean hasCell(BlockPos localPos) {
        return entries.containsKey(localPos);
    }

    /** The candidate list at {@code localPos}, or {@code null} when it is not a variant cell. */
    @Nullable
    public List<VariantState> statesAt(BlockPos localPos) {
        return entries.get(localPos);
    }

    /** The lock id at {@code localPos}; 0 when unlocked. */
    public int lockIdAt(BlockPos localPos) {
        Integer id = lockIds.get(localPos);
        return id == null ? 0 : id;
    }

    /**
     * The cell a group target is judged at: the group's lowest member (by y, then z, then x), so the
     * choice is stable whatever order the map iterates in. {@code null} when no cell carries the id.
     */
    @Nullable
    public BlockPos anchorOf(int lockId) {
        if (lockId <= 0) return null;
        BlockPos best = null;
        for (Map.Entry<BlockPos, Integer> e : lockIds.entrySet()) {
            if (e.getValue() != lockId) continue;
            BlockPos p = e.getKey();
            if (best == null || p.getY() < best.getY()
                || (p.getY() == best.getY() && (p.getZ() < best.getZ()
                    || (p.getZ() == best.getZ() && p.getX() < best.getX())))) {
                best = p;
            }
        }
        return best;
    }

    /** Cached analysis of this sidecar's reference graph, resolved in subset order. */
    public VariantGroupRefs.Graph graph() {
        VariantGroupRefs.Graph cached = graphCache;
        if (cached == null) {
            cached = VariantGroupRefs.analyse(this);
            graphCache = cached;
        }
        return cached;
    }

    /**
     * The full pick for one cell: drop dead references, roll, then follow a
     * reference to the value it points at.
     *
     * <p>{@code states} is the cell's candidate list — already
     * difficulty-filtered by the caller where that applies, so this composes
     * with the v8 band rather than competing with it. Returns {@code null}
     * when every candidate was a dead reference, which callers already treat
     * as "no variant here" and leave the NBT template's block in place.</p>
     *
     * <p>Cells with no reference in their list take the untouched pre-v9 path
     * and pick bit-identically to before — and, because that path never asks
     * for the graph, a sidecar with no references anywhere never analyses one.</p>
     */
    @Nullable
    public VariantState resolve(BlockPos localPos, int lockId, List<VariantState> states,
                                long worldSeed, int index) {
        if (states == null || states.isEmpty()) return null;
        if (VariantConditions.anyConditional(states)) {
            // Gate first: a failed condition drops its entry and the rest roll by weight. Every entry
            // gated out leaves the cell empty rather than falling back to the template block.
            states = VariantConditions.eligible(states, localPos, this, worldSeed, index);
            if (states.isEmpty()) return VariantConditions.EMPTY_PICK;
        }
        if (!VariantGroupRefs.anyRef(states)) {
            return states.get(pickIndex(localPos, lockId, states, worldSeed, index, null));
        }
        VariantGroupRefs.Graph graph = graph();
        List<VariantState> pool = VariantGroupRefs.eligible(states, lockId, graph);
        if (pool.isEmpty()) return null;
        VariantState picked = pool.get(
            pickIndex(localPos, lockId, pool, worldSeed, index, precomputed(graph, lockId, pool)));
        return VariantGroupRefs.follow(picked, this, graph, worldSeed, index);
    }

    /**
     * {@link #resolve} for a reference-free cell rolled on {@code weights} instead of its authored
     * weights — one per entry of {@code states}, where 0 means the entry is never picked. Locked cells
     * still roll on their group id, so a group whose cells share one list still lands on one index.
     * Returns {@code null} when every weight is 0, so the caller can fall back to the authored roll.
     */
    @Nullable
    public static VariantState resolveWeighted(BlockPos localPos, int lockId, List<VariantState> states,
                                               long worldSeed, int index, int[] weights) {
        if (states == null || states.isEmpty() || weights.length != states.size()) return null;
        int picked = lockId > 0
            ? CarriageVariantBlocks.pickIndexFromLockGroupAllowZero(lockId, worldSeed, index, weights)
            : CarriageVariantBlocks.pickIndexFromWeightsAllowZero(localPos, worldSeed, index, weights);
        return picked < 0 ? null : states.get(picked);
    }

    /**
     * The group's analysed pool when it is the very list about to be rolled —
     * the normal case, since the cells of a group share one candidate list.
     * Matching by identity rather than equality is deliberate: it is exactly
     * the condition under which the precomputed weight column is known to line
     * up, and it costs a pointer compare. A group whose cells disagree falls
     * back to building weights per cell, as before.
     */
    @Nullable
    private static VariantGroupRefs.Pool precomputed(VariantGroupRefs.Graph graph, int lockId,
                                                     List<VariantState> pool) {
        if (lockId <= 0) return null;
        VariantGroupRefs.Pool cached = graph.poolFor(lockId);
        return cached != null && cached.entries() == pool ? cached : null;
    }

    /**
     * Locked cells roll on their group id so the whole group lands on one
     * index; unlocked cells roll on their position. Same two pickers the
     * sidecars used inline before v9.
     */
    private static int pickIndex(BlockPos localPos, int lockId, List<VariantState> states,
                                 long worldSeed, int index,
                                 @Nullable VariantGroupRefs.Pool cached) {
        if (lockId > 0) {
            int[] weights = cached != null ? cached.weights() : VariantGroupRefs.weightsOf(states);
            return CarriageVariantBlocks.pickIndexFromLockGroup(lockId, worldSeed, index, weights);
        }
        return CarriageVariantBlocks.pickIndexWeighted(localPos, worldSeed, index, states);
    }
}
