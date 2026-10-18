package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToIntFunction;

/**
 * Finds variant conditions that will not do what the author meant, for the warning shown when a
 * template is saved. Rolls are pure ({@link VariantConditions}): a condition re-rolls the group or
 * neighbour it reads, so order never matters, but a condition aimed at the wrong group, a gap in a
 * column, or two cells gating on each other still give gappy or arbitrary results.
 *
 * <ul>
 *   <li>{@link Kind#MISSING_GROUP}: a condition reads a group that has no cells.</li>
 *   <li>{@link Kind#DETACHED_GROUP}: a condition reads group G while the cell only touches group H
 *       — typically a column copied from another and re-locked.</li>
 *   <li>{@link Kind#UNGATED}: an unlocked, unconditioned cell inside a group's column, offering
 *       the same blocks as that group, while other cells gate on that group.</li>
 *   <li>{@link Kind#LOOP}: cells whose conditions depend on each other in a cycle; whichever is
 *       asked first sees the other ungated.</li>
 * </ul>
 */
public final class VariantConditionCheck {

    public enum Kind { MISSING_GROUP, DETACHED_GROUP, UNGATED, LOOP }

    /** One candidate of a cell: the block it places (air for an empty placeholder) and its condition. */
    public record Candidate(ResourceLocation block, VariantCondition condition) {}

    /** One variant cell: its lock group (0 = none) and candidates. */
    public record Cell(int lockId, List<Candidate> candidates) {
        boolean conditional() {
            for (Candidate c : candidates) if (!c.condition().isDefault()) return true;
            return false;
        }

        Set<Integer> groupTargets() {
            Set<Integer> out = new TreeSet<>();
            for (Candidate c : candidates) {
                if (!c.condition().isDefault() && c.condition().isGroupTarget()) out.add(c.condition().groupId());
            }
            return out;
        }

        Set<ResourceLocation> blocks() {
            Set<ResourceLocation> out = new HashSet<>();
            for (Candidate c : candidates) out.add(c.block());
            return out;
        }
    }

    /**
     * One finding. {@code group} is the group read (or sat in, for {@link Kind#UNGATED});
     * {@code otherGroup} the touched group for {@link Kind#DETACHED_GROUP}; {@code loop} the cells
     * of a {@link Kind#LOOP}, starting at {@code pos}.
     */
    public record Problem(Kind kind, BlockPos pos, int group, int otherGroup, List<BlockPos> loop) {}

    private VariantConditionCheck() {}

    /** Builds the check's view of a sidecar. */
    public static Map<BlockPos, Cell> fromEntries(List<CarriageVariantBlocks.Entry> entries,
                                                 ToIntFunction<BlockPos> lockIdOf) {
        Map<BlockPos, Cell> out = new LinkedHashMap<>();
        for (CarriageVariantBlocks.Entry e : entries) {
            List<Candidate> candidates = new ArrayList<>(e.states().size());
            for (VariantState s : e.states()) {
                candidates.add(new Candidate(VariantCondition.idOf(s.state()), s.condition()));
            }
            out.put(e.localPos().immutable(), new Cell(lockIdOf.applyAsInt(e.localPos()), List.copyOf(candidates)));
        }
        return out;
    }

    public static List<Problem> check(Map<BlockPos, Cell> cells) {
        Map<Integer, List<BlockPos>> members = membersByGroup(cells);
        Set<Integer> gatedGroups = new HashSet<>();
        for (Cell c : cells.values()) gatedGroups.addAll(c.groupTargets());

        List<Problem> out = new ArrayList<>();
        for (Map.Entry<BlockPos, Cell> e : cells.entrySet()) {
            BlockPos pos = e.getKey();
            Cell cell = e.getValue();
            Set<Integer> touching = touchingGroups(cells, pos, cell.lockId());
            for (int g : cell.groupTargets()) {
                if (!members.containsKey(g)) {
                    out.add(new Problem(Kind.MISSING_GROUP, pos, g, 0, List.of()));
                } else if (!touching.contains(g) && touching.size() == 1) {
                    out.add(new Problem(Kind.DETACHED_GROUP, pos, g, touching.iterator().next(), List.of()));
                }
            }
            if (cell.lockId() == 0 && !cell.conditional()) {
                for (int g : touching) {
                    if (gatedGroups.contains(g) && cell.blocks().equals(groupBlocks(cells, members.get(g)))) {
                        out.add(new Problem(Kind.UNGATED, pos, g, 0, List.of()));
                        break;
                    }
                }
            }
        }
        out.addAll(loops(cells, members));
        return out;
    }

    /**
     * The group a conditional cell's group conditions should read instead, or 0 to leave them: when
     * every group condition reads one group {@code G} the cell does not touch, and the cell touches
     * exactly one other group {@code H}, the answer is {@code H}.
     */
    public static int retargetFor(Cell cell, Set<Integer> touching) {
        Set<Integer> targets = cell.groupTargets();
        if (targets.size() != 1) return 0;
        int g = targets.iterator().next();
        if (touching.contains(g) || touching.size() != 1) return 0;
        return touching.iterator().next();
    }

    /** Lock groups among {@code pos}'s six neighbours, other than the cell's own and none. */
    public static Set<Integer> touchingGroups(Map<BlockPos, Cell> cells, BlockPos pos, int ownLockId) {
        Set<Integer> out = new TreeSet<>();
        for (Direction d : Direction.values()) {
            Cell n = cells.get(pos.relative(d));
            if (n != null && n.lockId() > 0 && n.lockId() != ownLockId) out.add(n.lockId());
        }
        return out;
    }

    private static Map<Integer, List<BlockPos>> membersByGroup(Map<BlockPos, Cell> cells) {
        Map<Integer, List<BlockPos>> out = new HashMap<>();
        cells.forEach((pos, c) -> {
            if (c.lockId() > 0) out.computeIfAbsent(c.lockId(), k -> new ArrayList<>()).add(pos);
        });
        return out;
    }

    private static Set<ResourceLocation> groupBlocks(Map<BlockPos, Cell> cells, List<BlockPos> members) {
        Set<ResourceLocation> out = new HashSet<>();
        for (BlockPos p : members) out.addAll(cells.get(p).blocks());
        return out;
    }

    /** What each cell's conditions read: every cell of a target group, and variant neighbours on gated sides. */
    private static List<BlockPos> dependencies(Map<BlockPos, Cell> cells, Map<Integer, List<BlockPos>> members,
                                               BlockPos pos, Cell cell) {
        Set<BlockPos> out = new LinkedHashSet<>();
        for (Candidate c : cell.candidates()) {
            VariantCondition cond = c.condition();
            if (cond.isDefault()) continue;
            if (cond.isGroupTarget()) {
                out.addAll(members.getOrDefault(cond.groupId(), List.of()));
            } else {
                for (Direction d : cond.sides()) {
                    BlockPos n = pos.relative(d);
                    if (cells.containsKey(n)) out.add(n);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Each dependency cycle once (by its set of cells), found with an iterative-colouring DFS. */
    private static List<Problem> loops(Map<BlockPos, Cell> cells, Map<Integer, List<BlockPos>> members) {
        Map<BlockPos, List<BlockPos>> edges = new HashMap<>();
        cells.forEach((pos, c) -> edges.put(pos, dependencies(cells, members, pos, c)));
        Map<BlockPos, Integer> colour = new HashMap<>();
        List<BlockPos> path = new ArrayList<>();
        Set<Set<BlockPos>> seen = new HashSet<>();
        List<Problem> out = new ArrayList<>();
        for (BlockPos start : cells.keySet()) {
            if (!colour.containsKey(start)) visit(start, edges, colour, path, seen, out);
        }
        return out;
    }

    private static void visit(BlockPos pos, Map<BlockPos, List<BlockPos>> edges, Map<BlockPos, Integer> colour,
                              List<BlockPos> path, Set<Set<BlockPos>> seen, List<Problem> out) {
        colour.put(pos, 1);
        path.add(pos);
        for (BlockPos next : edges.getOrDefault(pos, List.of())) {
            Integer c = colour.get(next);
            if (c == null) {
                visit(next, edges, colour, path, seen, out);
            } else if (c == 1) {
                List<BlockPos> cycle = List.copyOf(path.subList(path.indexOf(next), path.size()));
                if (seen.add(Set.copyOf(cycle))) out.add(new Problem(Kind.LOOP, cycle.get(0), 0, 0, cycle));
            }
        }
        path.remove(path.size() - 1);
        colour.put(pos, 2);
    }
}
