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
 * always sees current data; the only thing it caches is the fixpoint, which
 * every sidecar mutator drops via {@link #invalidate}.</p>
 *
 * <p>Split out of the sidecars rather than copied into each so the reference
 * semantics have exactly one definition — four hand-maintained copies of a
 * fixpoint and a filter would be four chances to drift.</p>
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

    /** Cached analysis of this sidecar's reference graph. */
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
     * and pick bit-identically to before.</p>
     */
    @Nullable
    public VariantState resolve(BlockPos localPos, int lockId, List<VariantState> states,
                                long worldSeed, int index) {
        if (states == null || states.isEmpty()) return null;
        if (!VariantGroupRefs.anyRef(states)) {
            return states.get(pickIndex(localPos, lockId, states, worldSeed, index));
        }
        VariantGroupRefs.Graph graph = graph();
        List<VariantState> pool = VariantGroupRefs.eligible(states, lockId, graph);
        if (pool.isEmpty()) return null;
        VariantState picked = pool.get(pickIndex(localPos, lockId, pool, worldSeed, index));
        return VariantGroupRefs.follow(picked, this, graph, worldSeed, index);
    }

    /**
     * Locked cells roll on their group id so the whole group lands on one
     * index; unlocked cells roll on their position. Same two pickers the
     * sidecars used inline before v9.
     */
    private static int pickIndex(BlockPos localPos, int lockId, List<VariantState> states,
                                 long worldSeed, int index) {
        if (lockId > 0) {
            int[] weights = new int[states.size()];
            for (int i = 0; i < states.size(); i++) weights[i] = states.get(i).weight();
            return CarriageVariantBlocks.pickIndexFromLockGroup(lockId, worldSeed, index, weights);
        }
        return CarriageVariantBlocks.pickIndexWeighted(localPos, worldSeed, index, states);
    }
}
