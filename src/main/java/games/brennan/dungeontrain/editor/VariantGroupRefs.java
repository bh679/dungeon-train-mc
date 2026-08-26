package games.brennan.dungeontrain.editor;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolution of v9 <b>lock-group references</b> — the shared core behind
 * {@code resolve()} in all four block-variant sidecars
 * ({@link CarriageVariantBlocks}, {@link CarriageContentsVariantBlocks},
 * {@link CarriagePartVariantBlocks},
 * {@link games.brennan.dungeontrain.track.variant.TrackVariantBlocks}).
 *
 * <p>A cell already joins a lock group via its {@code lockId}: every cell
 * sharing an id draws one index and renders the same block. A
 * <b>reference</b> takes that one level down — a single entry inside a cell's
 * candidate list can carry {@link VariantState#groupRef}, meaning "if the roll
 * lands here, render whatever lock group N resolved to":</p>
 *
 * <pre>
 *   group 1 → [ X, Y, Z ]
 *   group 2 → [ A, B, &lt;group 1&gt; ]
 * </pre>
 *
 * <p>Because a group's roll is the pure function
 * {@link CarriageVariantBlocks#pickIndexFromLockGroup}{@code (id, seed, index,
 * weights)}, a reference re-evaluates the exact expression the referenced
 * group's own cells evaluate and therefore lands on the same entry — with no
 * dependence on which cell, chunk, or carriage was placed first.</p>
 *
 * <h2>Subsets, and the order they resolve in</h2>
 *
 * <p>A group that references another group <b>requires</b> it: the referenced
 * group is a <b>subset</b> of the referencing one. {@link #analyse} orders the
 * whole sidecar by that relation — groups with no subsets first, then groups
 * whose subsets are all already ordered ({@link Graph#order}) — and every
 * derivation below runs forward along that order. A dependent is therefore
 * never asked a question about a subset that has not been answered yet, which
 * is what turns liveness from a re-walked fixpoint into a single pass and lets
 * each group's rollable pool be built once instead of per placed cell.</p>
 *
 * <p>The order is a genuine topological order, not a heuristic: rule 1 below
 * excludes every back-edge, so the usable-edge sub-graph is a DAG.</p>
 *
 * <h2>Dead references re-roll</h2>
 *
 * <p>A reference that cannot produce a value is dropped from the candidate
 * pool <b>before</b> the weighted roll, so the cell re-rolls among the
 * survivors — the same treatment the v8 difficulty band gives out-of-band mob
 * entries in {@link CarriageContentsVariantBlocks#resolve(net.minecraft.core.BlockPos, long, int, int)}.</p>
 *
 * <p>Liveness is decided once per sidecar by {@link #analyse}, over the whole
 * reference graph, rather than by descending at pick time. Two rules, and the
 * first is what makes the second safe:</p>
 *
 * <ol>
 *   <li><b>An edge {@code L → R} is usable only when {@code R} cannot reach
 *       {@code L}.</b> This kills self-references and every cycle at the edge
 *       level, including the mutual {@code A ⇄ B} pair that a group-level
 *       check would wave through: both groups have concrete options, so both
 *       "resolve", yet a walk that goes {@code A → B → A} has nowhere to
 *       land. Excluding back-edges makes every remaining path strictly
 *       descend the reachability order, so a walk cannot revisit a group and
 *       must terminate.</li>
 *   <li><b>A group is live</b> when it has a concrete (non-reference) entry,
 *       or a usable edge to a group that is itself live. Evaluated in subset
 *       order, so a group whose every option is a dead reference is dead too,
 *       and that deadness propagates to whoever references it.</li>
 * </ol>
 *
 * <p>The editor refuses to author a back-edge up front
 * ({@link #reaches} backs that check), so rule 1 normally fires only on a
 * hand-edited file or a clipboard pasted into a template with a different
 * group layout.</p>
 */
public final class VariantGroupRefs {

    private VariantGroupRefs() {}

    /**
     * Read-only view of one sidecar's lock groups. Implemented by each sidecar
     * over its own {@code entries} / {@code lockIds} maps.
     */
    public interface LockGroupView {

        /**
         * Candidate list of the first cell carrying {@code lockId}, or
         * {@code null} when no cell in this sidecar does.
         *
         * <p>"First" is the sidecar's own iteration order, which is
         * {@link java.util.LinkedHashMap} insertion order = JSON order, so the
         * choice is stable across loads. Cells in one group are expected to
         * share an identical candidate list (that is what the group means);
         * where they don't, the first cell's list wins for reference purposes,
         * matching the group's existing single-roll semantics.</p>
         */
        @Nullable List<VariantState> statesForLockId(int lockId);

        /** Every non-zero lock-id carried by a cell in this sidecar. */
        Set<Integer> lockIdsInUse();
    }

    /**
     * One lock group's rollable candidates, resolved once by {@link #analyse}
     * and reused by every cell that rolls or follows into that group.
     *
     * <p>{@code entries} is the group's list with its dead references already
     * dropped — the very list object the group's own cells were handed when
     * nothing needed dropping, so a reference-free group costs no copy — and
     * {@code weights} is that pool's weight column, which
     * {@link CarriageVariantBlocks#pickIndexFromLockGroup} needs as an array
     * and which {@link #follow} would otherwise rebuild per cell.</p>
     *
     * <p>Both components are treated as immutable. Never write through
     * {@code weights}; the array is shared by every caller of this pool. The
     * record's generated {@code equals} compares the array by identity, which
     * is why pools are only ever looked up, never compared.</p>
     */
    public record Pool(List<VariantState> entries, int[] weights) {}

    /**
     * A sidecar's reference graph, resolved: which edges may be followed,
     * which groups produce a value, the subset order they were resolved in,
     * and each group's rollable pool. Immutable, computed by {@link #analyse},
     * cached by {@link VariantGroupResolver}.
     *
     * @param live          lock-ids that resolve to a real entry
     * @param usableTargets {@code ownLockId → the lock-ids it may reference}.
     *                      Includes key {@code 0} for unlocked cells, which
     *                      may reference anything (nothing can reference back
     *                      into an unlocked cell, so no back-edge is possible).
     * @param order         every lock-id in use, subset-first: a group appears
     *                      only after every group it may reference. Groups with
     *                      no subsets lead.
     * @param pools         {@code lockId → its rollable pool}, absent for a
     *                      group with no cells or nothing left to roll.
     */
    public record Graph(Set<Integer> live, Map<Integer, Set<Integer>> usableTargets,
                        List<Integer> order, Map<Integer, Pool> pools) {

        public static final Graph EMPTY = new Graph(Set.of(), Map.of(), List.of(), Map.of());

        /**
         * True when an entry in a list whose own lock-id is {@code ownLockId}
         * may follow its reference to {@code ref} — the edge is usable and the
         * target resolves. This is the single predicate that decides whether a
         * reference row fires or is dropped from the pool.
         */
        public boolean isLiveRef(int ownLockId, int ref) {
            if (ref <= 0 || ref == ownLockId) return false;
            return usableTargets.getOrDefault(ownLockId, Set.of()).contains(ref)
                && live.contains(ref);
        }

        /** {@code lockId}'s precomputed pool, or {@code null} when it has nothing to roll. */
        @Nullable
        public Pool poolFor(int lockId) {
            return pools.get(lockId);
        }
    }

    /** True when any entry in {@code states} is a lock-group reference. Drives the no-op fast path. */
    public static boolean anyRef(List<VariantState> states) {
        for (VariantState s : states) {
            if (s.isGroupRef()) return true;
        }
        return false;
    }

    /** The weight column of {@code states}, in the shape {@code pickIndexFromLockGroup} wants. */
    public static int[] weightsOf(List<VariantState> states) {
        int[] weights = new int[states.size()];
        for (int i = 0; i < states.size(); i++) weights[i] = states.get(i).weight();
        return weights;
    }

    /**
     * Resolve {@code view}'s whole reference graph — see the class docstring
     * for the two rules and for what "subset order" means.
     *
     * <p>Runs once per sidecar and is cached, so it reads each group's
     * candidate list exactly once up front: every later step works off that
     * index rather than asking the view again, which is what keeps the
     * analysis off the per-cell cost curve.</p>
     */
    public static Graph analyse(LockGroupView view) {
        Set<Integer> ids = view.lockIdsInUse();
        if (ids.isEmpty()) return Graph.EMPTY;

        // 1 — index every group's candidate list once. statesForLockId is a
        // scan of the sidecar's whole lock-id map; asking it again per step
        // (as the pre-subset-order version did, once per reachability hop) is
        // what made the analysis quadratic in cells.
        Map<Integer, List<VariantState>> states = new LinkedHashMap<>();
        for (int id : ids) {
            List<VariantState> list = view.statesForLockId(id);
            if (list != null) states.put(id, list);
        }

        // 2 — the raw reference edges, and what each group can reach through
        // them. Same walk reaches() performs, done once per group instead of
        // once per ordered pair.
        Map<Integer, Set<Integer>> refs = refEdges(states);
        Map<Integer, Set<Integer>> reachable = new HashMap<>();
        for (int id : ids) reachable.put(id, reachableFrom(id, refs));

        // 3 — rule 1: usable edges. An unlocked cell (key 0) may reference any
        // group: nothing can reference back into it, so it can never sit on a
        // cycle.
        Map<Integer, Set<Integer>> usable = new HashMap<>(ids.size() + 1);
        usable.put(0, new LinkedHashSet<>(ids));
        for (int from : ids) {
            Set<Integer> targets = new LinkedHashSet<>();
            for (int to : ids) {
                if (to == from) continue;
                if (reachable.getOrDefault(to, Set.of()).contains(from)) continue;  // would close a loop
                targets.add(to);
            }
            usable.put(from, targets);
        }

        // 4 — subset order: no-subset groups first, then whoever requires them.
        List<Integer> order = subsetOrder(ids, refs, usable);

        // 5 — rule 2: liveness, one forward pass. Every group a group may
        // reference is one of its subsets and so already decided.
        Set<Integer> live = new HashSet<>();
        for (int id : order) {
            List<VariantState> list = states.get(id);
            if (list == null) continue;
            Set<Integer> allowed = usable.getOrDefault(id, Set.of());
            for (VariantState s : list) {
                if (!s.isGroupRef()) {
                    live.add(id);
                    break;
                }
                if (allowed.contains(s.groupRef()) && live.contains(s.groupRef())) {
                    live.add(id);
                    break;
                }
            }
        }

        // 6 — each group's rollable pool, built once in the same order. This is
        // what follow() reads instead of re-filtering and re-weighting per
        // placed cell. The throwaway Graph is only a carrier for isLiveRef.
        Graph shape = new Graph(live, usable, order, Map.of());
        Map<Integer, Pool> pools = new HashMap<>();
        for (int id : order) {
            List<VariantState> list = states.get(id);
            if (list == null || list.isEmpty()) continue;
            List<VariantState> pool = eligible(list, id, shape);
            if (pool.isEmpty()) continue;
            pools.put(id, new Pool(pool, weightsOf(pool)));
        }
        return new Graph(live, usable, order, pools);
    }

    /** {@code lockId → every group its candidate list names}, usable or not. */
    private static Map<Integer, Set<Integer>> refEdges(Map<Integer, List<VariantState>> states) {
        Map<Integer, Set<Integer>> refs = new HashMap<>();
        for (Map.Entry<Integer, List<VariantState>> e : states.entrySet()) {
            Set<Integer> out = null;
            for (VariantState s : e.getValue()) {
                if (!s.isGroupRef()) continue;
                if (out == null) out = new LinkedHashSet<>();
                out.add(s.groupRef());
            }
            if (out != null) refs.put(e.getKey(), out);
        }
        return refs;
    }

    /**
     * Every group reachable by following references out of {@code from},
     * exclusive of {@code from} itself unless a cycle leads back to it. The
     * batched form of {@link #reaches}, over an already-built edge map.
     */
    private static Set<Integer> reachableFrom(int from, Map<Integer, Set<Integer>> refs) {
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int next : refs.getOrDefault(from, Set.of())) {
            if (seen.add(next)) queue.add(next);
        }
        while (!queue.isEmpty()) {
            for (int next : refs.getOrDefault(queue.poll(), Set.of())) {
                if (seen.add(next)) queue.add(next);
            }
        }
        return seen;
    }

    /**
     * The lock-ids of {@code ids}, ordered so a group follows every group it
     * may reference — its <b>subsets</b>. Kahn's algorithm over the usable ref
     * edges, seeded in the sidecar's own id order so the result is stable
     * across loads.
     *
     * <p>Dangling and back-edge references are not subsets: the first names no
     * group in this sidecar and the second is already excluded by rule 1, so a
     * group whose only references are of those kinds leads the order alongside
     * the genuinely reference-free ones. That is also why the DAG assumption
     * holds — a cycle would need every edge on it to be usable, and rule 1
     * excludes at least one.</p>
     */
    private static List<Integer> subsetOrder(Set<Integer> ids,
                                             Map<Integer, Set<Integer>> refs,
                                             Map<Integer, Set<Integer>> usable) {
        Map<Integer, Set<Integer>> dependents = new HashMap<>();
        Map<Integer, Integer> pending = new HashMap<>();
        ArrayDeque<Integer> ready = new ArrayDeque<>();
        for (int id : ids) {
            Set<Integer> named = refs.get(id);
            int count = 0;
            if (named != null) {
                Set<Integer> allowed = usable.getOrDefault(id, Set.of());
                for (int target : named) {
                    if (!allowed.contains(target)) continue;
                    if (dependents.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(id)) count++;
                }
            }
            pending.put(id, count);
            if (count == 0) ready.add(id);
        }

        List<Integer> order = new ArrayList<>(ids.size());
        Set<Integer> emitted = new HashSet<>();
        while (!ready.isEmpty()) {
            int id = ready.poll();
            order.add(id);
            emitted.add(id);
            for (int dependent : dependents.getOrDefault(id, Set.of())) {
                if (pending.merge(dependent, -1, Integer::sum) == 0) ready.add(dependent);
            }
        }
        // Backstop only: rule 1 makes the edge sub-graph acyclic, so nothing
        // should be left. Emitting stragglers in id order keeps the order a
        // complete list rather than a silently short one.
        if (order.size() < ids.size()) {
            for (int id : ids) {
                if (!emitted.contains(id)) order.add(id);
            }
        }
        return order;
    }

    /**
     * {@code states} with every dead reference removed. Returns {@code states}
     * itself (no copy) when nothing needs dropping, so ref-free lists pay
     * nothing.
     *
     * <p>{@code ownLockId} is the lock-id of the list being filtered, not of
     * whoever is asking: filtering group R's list on behalf of a reference
     * must pass {@code R}, so the pool a reference rolls over is bit-identical
     * to the pool R's own cells roll over.</p>
     */
    public static List<VariantState> eligible(List<VariantState> states, int ownLockId, Graph graph) {
        boolean anyDead = false;
        for (VariantState s : states) {
            if (s.isGroupRef() && !graph.isLiveRef(ownLockId, s.groupRef())) {
                anyDead = true;
                break;
            }
        }
        if (!anyDead) return states;
        List<VariantState> out = new ArrayList<>(states.size());
        for (VariantState s : states) {
            if (s.isGroupRef() && !graph.isLiveRef(ownLockId, s.groupRef())) continue;
            out.add(s);
        }
        return out;
    }

    /**
     * True when {@code target} is reachable by following references out of
     * {@code from} (inclusive: {@code from == target} counts). Used by the
     * editor to refuse an Add that would close a loop — better to reject the
     * edit than to let an author create a row that silently never fires.
     *
     * <p>Deliberately walks <i>every</i> reference edge, usable or not: a path
     * back through an edge that is itself excluded still signals a tangle, and
     * being conservative here costs at most an occasional refusal.</p>
     *
     * <p>Author-time only. {@link #analyse} answers the same question in bulk
     * via {@link #reachableFrom} rather than calling this per ordered pair.</p>
     */
    public static boolean reaches(LockGroupView view, int from, int target) {
        if (from <= 0 || target <= 0) return false;
        if (from == target) return true;
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(from);
        seen.add(from);
        while (!queue.isEmpty()) {
            List<VariantState> states = view.statesForLockId(queue.poll());
            if (states == null) continue;
            for (VariantState s : states) {
                if (!s.isGroupRef()) continue;
                int next = s.groupRef();
                if (next == target) return true;
                if (seen.add(next)) queue.add(next);
            }
        }
        return false;
    }

    /**
     * Follow {@code picked} through however many references it takes to reach
     * a real value, and return that terminal entry whole — its state, NBT,
     * rotation, half, loot link and entity all come from the referenced entry,
     * because the reference <i>is</i> that entry's value.
     *
     * <p>This is the one part of the machinery that runs per placed cell, so
     * it does no work {@link #analyse} already did: each hop is a lookup of
     * the target's {@link Pool} plus the roll itself, with nothing allocated.
     * The {@code view} is only the fallback for a lock-id the graph has no
     * pool for, which means a caller filtered with a stale graph.</p>
     *
     * <p>{@code picked} is returned unchanged when it is not a reference.
     * Returns {@code null} only if the chain dead-ends, which cannot happen
     * for a reference {@link #eligible} passed — the hop bound is a backstop
     * against a stale graph, not the cycle defence (that is rule 1 in
     * {@link #analyse}, which is also why a chain can visit each group at most
     * once and so cannot outrun the bound).</p>
     *
     * @param worldSeed same seed the caller used for its own roll
     * @param index     same carriage / tile index the caller used, so the
     *                  referenced group draws the value it drew for itself
     */
    @Nullable
    public static VariantState follow(VariantState picked, LockGroupView view,
                                      Graph graph, long worldSeed, int index) {
        if (picked == null || !picked.isGroupRef()) return picked;
        int maxHops = graph.order().size() + 1;
        int hops = 0;
        VariantState current = picked;
        while (current.isGroupRef()) {
            if (++hops > maxHops) return null;
            int target = current.groupRef();
            List<VariantState> pool;
            int[] weights;
            Pool cached = graph.poolFor(target);
            if (cached != null) {
                pool = cached.entries();
                weights = cached.weights();
            } else {
                List<VariantState> states = view.statesForLockId(target);
                if (states == null || states.isEmpty()) return null;
                pool = eligible(states, target, graph);
                if (pool.isEmpty()) return null;
                weights = weightsOf(pool);
            }
            int idx = CarriageVariantBlocks.pickIndexFromLockGroup(target, worldSeed, index, weights);
            current = pool.get(idx);
        }
        return current;
    }
}
