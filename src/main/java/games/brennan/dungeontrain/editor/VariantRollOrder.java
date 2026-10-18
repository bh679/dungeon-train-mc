package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToIntFunction;

/**
 * The order a sidecar's cells decide in, so conditions between neighbours have one answer (see
 * {@link VariantConditions}). Plain cells first; then conditional cells, each after every cell its
 * conditions read (a group target reads all the group's cells, a touching side its variant
 * neighbour). When the rest form a cycle the lowest remaining cell (y, then z, then x) goes next,
 * so a column of cells gating on each other resolves bottom-up.
 */
public final class VariantRollOrder {

    private VariantRollOrder() {}

    /** Rank of every cell, 0 first. */
    public static Map<BlockPos, Integer> rank(Map<BlockPos, List<VariantState>> entries,
                                              ToIntFunction<BlockPos> lockIdOf) {
        Map<Integer, List<BlockPos>> groups = new HashMap<>();
        for (BlockPos p : entries.keySet()) {
            int id = lockIdOf.applyAsInt(p);
            if (id > 0) groups.computeIfAbsent(id, k -> new ArrayList<>()).add(p);
        }
        Map<BlockPos, Set<BlockPos>> needs = new HashMap<>();
        Map<BlockPos, Set<BlockPos>> neededBy = new HashMap<>();
        for (Map.Entry<BlockPos, List<VariantState>> e : entries.entrySet()) {
            Set<BlockPos> deps = dependencies(e.getKey(), e.getValue(), entries.keySet(), groups);
            deps.remove(e.getKey());
            needs.put(e.getKey(), deps);
            for (BlockPos d : deps) neededBy.computeIfAbsent(d, k -> new HashSet<>()).add(e.getKey());
        }
        Comparator<BlockPos> key = Comparator
            .comparingInt((BlockPos p) -> VariantConditions.anyConditional(entries.get(p)) ? 1 : 0)
            .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX);
        TreeSet<BlockPos> remaining = new TreeSet<>(key);
        remaining.addAll(entries.keySet());
        Map<BlockPos, Integer> waiting = new HashMap<>();
        TreeSet<BlockPos> ready = new TreeSet<>(key);
        for (BlockPos p : entries.keySet()) {
            int n = needs.get(p).size();
            waiting.put(p, n);
            if (n == 0) ready.add(p);
        }
        Map<BlockPos, Integer> rank = new HashMap<>();
        while (!remaining.isEmpty()) {
            // A cycle leaves nothing ready: the lowest remaining cell goes next.
            BlockPos next = ready.isEmpty() ? remaining.first() : ready.pollFirst();
            ready.remove(next);
            remaining.remove(next);
            rank.put(next.immutable(), rank.size());
            for (BlockPos after : neededBy.getOrDefault(next, Set.of())) {
                if (!remaining.contains(after)) continue;
                int left = waiting.merge(after, -1, Integer::sum);
                if (left == 0) ready.add(after);
            }
        }
        return rank;
    }

    private static Set<BlockPos> dependencies(BlockPos pos, List<VariantState> states, Set<BlockPos> cells,
                                              Map<Integer, List<BlockPos>> groups) {
        Set<BlockPos> out = new HashSet<>();
        if (states == null) return out;
        for (VariantState s : states) {
            VariantCondition c = s.condition();
            if (c.isDefault()) continue;
            if (c.isGroupTarget()) {
                out.addAll(groups.getOrDefault(c.groupId(), List.of()));
            } else {
                for (Direction d : c.sides()) {
                    BlockPos n = pos.relative(d);
                    if (cells.contains(n)) out.add(n);
                }
            }
        }
        return out;
    }
}
