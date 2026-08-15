package games.brennan.dungeontrain.editor;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 *       or a usable edge to a group that is itself live. Iterated to a
 *       fixpoint, so a group whose every option is a dead reference is dead
 *       too, and that deadness propagates to whoever references it.</li>
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
     * A sidecar's reference graph, resolved: which edges may be followed and
     * which groups produce a value. Immutable, computed by {@link #analyse},
     * cached by {@link VariantGroupResolver}.
     *
     * @param live          lock-ids that resolve to a real entry
     * @param usableTargets {@code ownLockId → the lock-ids it may reference}.
     *                      Includes key {@code 0} for unlocked cells, which
     *                      may reference anything (nothing can reference back
     *                      into an unlocked cell, so no back-edge is possible).
     */
    public record Graph(Set<Integer> live, Map<Integer, Set<Integer>> usableTargets) {

        public static final Graph EMPTY = new Graph(Set.of(), Map.of());

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
    }

    /** True when any entry in {@code states} is a lock-group reference. Drives the no-op fast path. */
    public static boolean anyRef(List<VariantState> states) {
        for (VariantState s : states) {
            if (s.isGroupRef()) return true;
        }
        return false;
    }

    /** Resolve {@code view}'s whole reference graph — see the class docstring for the two rules. */
    public static Graph analyse(LockGroupView view) {
        Set<Integer> ids = view.lockIdsInUse();
        if (ids.isEmpty()) return Graph.EMPTY;

        // Rule 1 — usable edges. An unlocked cell (key 0) may reference any
        // group: nothing can reference back into it, so it can never sit on a
        // cycle.
        Map<Integer, Set<Integer>> usable = new HashMap<>(ids.size() + 1);
        Set<Integer> fromUnlocked = new LinkedHashSet<>(ids);
        usable.put(0, fromUnlocked);
        for (int from : ids) {
            Set<Integer> targets = new LinkedHashSet<>();
            for (int to : ids) {
                if (to == from) continue;
                if (reaches(view, to, from)) continue;  // would close a loop
                targets.add(to);
            }
            usable.put(from, targets);
        }

        // Rule 2 — liveness fixpoint. Seed with the groups that stand alone.
        Set<Integer> live = new HashSet<>(ids.size());
        for (int id : ids) {
            List<VariantState> states = view.statesForLockId(id);
            if (states == null) continue;
            for (VariantState s : states) {
                if (!s.isGroupRef()) {
                    live.add(id);
                    break;
                }
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int id : ids) {
                if (live.contains(id)) continue;
                List<VariantState> states = view.statesForLockId(id);
                if (states == null) continue;
                Set<Integer> targets = usable.getOrDefault(id, Set.of());
                for (VariantState s : states) {
                    if (s.isGroupRef() && targets.contains(s.groupRef()) && live.contains(s.groupRef())) {
                        live.add(id);
                        changed = true;
                        break;
                    }
                }
            }
        }
        return new Graph(live, usable);
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
     * {@code from} (inclusive: {@code from == target} counts). Used both to
     * exclude back-edges in {@link #analyse} and by the editor to refuse an
     * Add that would close a loop — better to reject the edit than to let an
     * author create a row that silently never fires.
     *
     * <p>Deliberately walks <i>every</i> reference edge, usable or not: a path
     * back through an edge that is itself excluded still signals a tangle, and
     * being conservative here costs at most an occasional refusal.</p>
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
     * <p>{@code picked} is returned unchanged when it is not a reference.
     * Returns {@code null} only if the chain dead-ends, which cannot happen
     * for a reference {@link #eligible} passed — the visited set is a backstop
     * against a caller filtering with a stale graph, not the cycle defence
     * (that is rule 1 in {@link #analyse}).</p>
     *
     * @param worldSeed same seed the caller used for its own roll
     * @param index     same carriage / tile index the caller used, so the
     *                  referenced group draws the value it drew for itself
     */
    @Nullable
    public static VariantState follow(VariantState picked, LockGroupView view,
                                      Graph graph, long worldSeed, int index) {
        if (picked == null || !picked.isGroupRef()) return picked;
        Set<Integer> visited = new HashSet<>();
        VariantState current = picked;
        while (current.isGroupRef()) {
            int target = current.groupRef();
            if (!visited.add(target)) return null;
            List<VariantState> states = view.statesForLockId(target);
            if (states == null || states.isEmpty()) return null;
            List<VariantState> pool = eligible(states, target, graph);
            if (pool.isEmpty()) return null;
            int[] weights = new int[pool.size()];
            for (int i = 0; i < pool.size(); i++) weights[i] = pool.get(i).weight();
            int idx = CarriageVariantBlocks.pickIndexFromLockGroup(target, worldSeed, index, weights);
            current = pool.get(idx);
        }
        return current;
    }
}
