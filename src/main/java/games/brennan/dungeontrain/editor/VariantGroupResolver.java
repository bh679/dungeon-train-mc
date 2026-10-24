package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    /** Roll order for conditions — see {@link #rankOf}. Dropped with the graph by {@link #invalidate}. */
    private volatile Map<BlockPos, Integer> rankCache;

    public VariantGroupResolver(Map<BlockPos, List<VariantState>> entries,
                                Map<BlockPos, Integer> lockIds) {
        this.entries = entries;
        this.lockIds = lockIds;
    }

    /**
     * Each cell-backed group's options as of the last mutation, kept across
     * invalidation. Without it, editing a group's cells and then deleting
     * them would fall back to whatever copy the references were saved with,
     * losing the edits; with it, a group that has just lost its last cell
     * keeps the options it had a moment ago. Only consulted for a group that
     * is still referenced, so it never resurrects a group on its own.
     */
    private final Map<Integer, List<VariantState>> lastSeen = new ConcurrentHashMap<>();

    /**
     * How deep a reference's copy of its group nests copies of further groups.
     * A backstop: the editor refuses loops, and {@link #refreshed} stops at a
     * group already on the path, so real chains are far shorter.
     */
    private static final int MAX_COPY_DEPTH = 4;

    /** Drop the cached graph. Call from every mutator that touches entries or lock-ids. */
    public void invalidate() {
        graphCache = null;
        rankCache = null;
        rememberCellGroups();
    }

    /** Record every cell-backed group's current options in {@link #lastSeen}. */
    private void rememberCellGroups() {
        Map<Integer, List<VariantState>> current = new HashMap<>();
        for (Map.Entry<BlockPos, Integer> e : lockIds.entrySet()) {
            List<VariantState> list = entries.get(e.getKey());
            if (list != null) current.putIfAbsent(e.getValue(), list);
        }
        lastSeen.putAll(current);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A cell carrying {@code lockId} always answers. When none does, the
     * group still exists for as long as some option in this sidecar refers to
     * it: its options are the ones it last had here ({@link #lastSeen}), or
     * else the copy a reference carries ({@link VariantState#groupStates}) —
     * which is how a group survives a save and reload with no cell of its own.</p>
     *
     * <p>A scan, so author-time callers and
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
        return detachedStates(lockId);
    }

    /** True when a cell in this sidecar carries {@code lockId} — the group is not only a reference. */
    public boolean hasCellFor(int lockId) {
        return lockId > 0 && lockIds.containsValue(lockId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The cells' lock-ids plus every group an option refers to that still
     * has options to give ({@link #statesForLockId}) — so a group kept alive
     * only by a reference is resolved, and its id is not handed out again by
     * {@code nextFreeLockId}.</p>
     */
    @Override
    public Set<Integer> lockIdsInUse() {
        Set<Integer> ids = new LinkedHashSet<>(lockIds.values());
        Set<Integer> referenced = new LinkedHashSet<>();
        for (List<VariantState> list : entries.values()) collectRefs(list, referenced, 0);
        for (int id : referenced) {
            if (!ids.contains(id) && detachedStates(id) != null) ids.add(id);
        }
        return ids;
    }

    /**
     * {@code states} with every reference's copy of its group brought up to
     * date ({@link #statesForLockId}), nested references included. Sidecars
     * write this rather than the raw list, so the file always holds the
     * group's current options and the group outlives its last cell.
     *
     * @param ownLockId the lock-id of the cell {@code states} belongs to (0 if
     *                  none) — a copy never nests the group it sits in
     */
    public List<VariantState> refreshed(List<VariantState> states, int ownLockId) {
        Set<Integer> path = new HashSet<>();
        if (ownLockId > 0) path.add(ownLockId);
        return refreshed(states, path, 0);
    }

    private List<VariantState> refreshed(List<VariantState> states, Set<Integer> path, int depth) {
        if (states == null || !VariantGroupRefs.anyRef(states)) return states;
        List<VariantState> out = new ArrayList<>(states.size());
        for (VariantState s : states) {
            if (!s.isGroupRef()) {
                out.add(s);
                continue;
            }
            int id = s.groupRef();
            if (depth >= MAX_COPY_DEPTH || path.contains(id)) {
                out.add(s.withGroupStates(List.of()));
                continue;
            }
            List<VariantState> group = statesForLockId(id);
            if (group == null || group.isEmpty()) {
                out.add(s);
                continue;
            }
            path.add(id);
            out.add(s.withGroupStates(refreshed(group, path, depth + 1)));
            path.remove(id);
        }
        return out;
    }

    /**
     * Options for a group no cell carries: {@code null} unless some option in
     * this sidecar still refers to it, then the options it last had here, else
     * the first non-empty copy a reference carries.
     */
    @Nullable
    private List<VariantState> detachedStates(int lockId) {
        boolean[] referenced = {false};
        List<VariantState> copy = null;
        for (List<VariantState> list : entries.values()) {
            List<VariantState> found = findCopy(list, lockId, referenced, 0);
            if (found != null) {
                copy = found;
                break;
            }
        }
        if (!referenced[0]) return null;
        List<VariantState> seen = lastSeen.get(lockId);
        return seen != null ? seen : copy;
    }

    @Nullable
    private static List<VariantState> findCopy(List<VariantState> list, int lockId,
                                               boolean[] referenced, int depth) {
        if (list == null || depth > MAX_COPY_DEPTH) return null;
        for (VariantState s : list) {
            if (!s.isGroupRef()) continue;
            if (s.groupRef() == lockId) {
                referenced[0] = true;
                if (!s.groupStates().isEmpty()) return s.groupStates();
            }
            List<VariantState> nested = findCopy(s.groupStates(), lockId, referenced, depth + 1);
            if (nested != null) return nested;
        }
        return null;
    }

    private static void collectRefs(List<VariantState> list, Set<Integer> out, int depth) {
        if (list == null || depth > MAX_COPY_DEPTH) return;
        for (VariantState s : list) {
            if (!s.isGroupRef()) continue;
            out.add(s.groupRef());
            collectRefs(s.groupStates(), out, depth + 1);
        }
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

    /** Groups whose anchor this thread is resolving through a reference — a loop falls back to a plain roll. */
    private static final ThreadLocal<Set<Integer>> FOLLOWING = ThreadLocal.withInitial(HashSet::new);

    /**
     * {@inheritDoc}
     *
     * <p>The group's {@link #anchorOf anchor} resolved like any placed cell (and so remembered for
     * the rest of the pass). {@code null} for a group with no cell here, or one already being
     * followed further up this thread's stack.</p>
     */
    @Override
    @Nullable
    public VariantState placedByGroup(int lockId, long worldSeed, int index) {
        BlockPos anchor = anchorOf(lockId);
        if (anchor == null) return null;
        Set<Integer> following = FOLLOWING.get();
        if (!following.add(lockId)) return null;
        try {
            return resolve(anchor, lockId, entries.get(anchor), worldSeed, index);
        } finally {
            following.remove(lockId);
        }
    }

    /**
     * Where {@code localPos} sits in this sidecar's roll order, for {@link VariantConditions}: plain
     * cells first, then conditional cells in dependency order (a cell after everything its
     * conditions read), a cycle broken by position — lowest y, then z, then x — so a column resolves
     * bottom-up. Re-derived after every change to the sidecar (so on every load and save). Cells
     * not in the sidecar rank first.
     */
    public int rankOf(BlockPos localPos) {
        Map<BlockPos, Integer> ranks = rankCache;
        if (ranks == null) {
            ranks = VariantRollOrder.rank(entries, this::lockIdAt);
            rankCache = ranks;
        }
        Integer r = ranks.get(localPos);
        return r == null ? -1 : r;
    }

    /** Cached analysis of this sidecar's reference graph, resolved in subset order. */
    public VariantGroupRefs.Graph graph() {
        VariantGroupRefs.Graph cached = graphCache;
        if (cached == null) {
            rememberCellGroups();
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
        // One answer per cell per pass: a cell already decided is read back, not re-rolled.
        VariantConditions.Scope scope = VariantConditions.current();
        if (scope != null) {
            VariantState known = scope.memo(localPos, worldSeed, index);
            if (known != null) return known;
        }
        boolean ungated = VariantConditions.isResolving(localPos);
        VariantState picked = resolveFresh(localPos, lockId, states, worldSeed, index);
        if (scope != null && !ungated && picked != null) scope.remember(localPos, worldSeed, index, picked);
        return picked;
    }

    @Nullable
    private VariantState resolveFresh(BlockPos localPos, int lockId, List<VariantState> states,
                                      long worldSeed, int index) {
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
