package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The cells of the X editor's Blocks page: every block a template uses, grouped so that one cell is
 * one set of positions the author can re-skin in a click.
 *
 * <p>A fresh template starts with one cell per block. Re-skinning a cell changes only that cell's
 * block — it is never folded into another cell that already shows the same block. Two cells of
 * oak planks stay two cells, so the author can still tell apart (and re-skin again) the planks that
 * were there and the ones they just made, until they save. Saving drops the groups; the next
 * {@link #build} is one cell per block again, which is the merge.</p>
 *
 * <p>Pure and generic over the block type so the rules can be tested without a game registry.
 * Every method returns new lists; nothing passed in is changed.</p>
 */
public final class TemplateBlockGroups {

    private TemplateBlockGroups() {}

    /**
     * One use of a block in the template: a structure block at {@code local}, or — when
     * {@code variantIndex} is 0 or more — that candidate of the block-variant cell at {@code local}.
     */
    public record Member(BlockPos local, int variantIndex) {
        public static Member base(BlockPos local) {
            return new Member(local.immutable(), -1);
        }

        public static Member variant(BlockPos local, int index) {
            return new Member(local.immutable(), index);
        }

        public boolean isVariant() {
            return variantIndex >= 0;
        }
    }

    /** One cell: a block and every use of the template it stands for. */
    public record Group<B>(B block, List<Member> members) {
        public Group {
            Objects.requireNonNull(block, "block");
            members = List.copyOf(members);
        }

        public int count() {
            return members.size();
        }
    }

    /**
     * One cell per block, most used first, {@code tieBreak} ordering blocks used equally often.
     *
     * @param live every use in the template and the block it is now
     */
    public static <B> List<Group<B>> build(Map<Member, B> live, Comparator<B> tieBreak) {
        Map<B, List<Member>> byBlock = new LinkedHashMap<>();
        live.forEach((m, b) -> byBlock.computeIfAbsent(b, k -> new ArrayList<>()).add(m));
        List<Group<B>> out = new ArrayList<>(byBlock.size());
        byBlock.forEach((b, ms) -> out.add(new Group<>(b, ms)));
        out.sort(Comparator.<Group<B>>comparingInt(Group::count).reversed()
            .thenComparing(Group::block, tieBreak));
        return List.copyOf(out);
    }

    /**
     * Bring {@code groups} up to date with {@code live} after edits made some other way — by hand,
     * an undo, another menu. A use that is still its cell's block stays put. A cell whose every
     * use turned into one same block — undoing or redoing a re-skin — is relabelled where it
     * stands. Any other use that changed block, or is new, joins the first cell already showing
     * its block, or a new cell at the end.
     * Uses that are gone leave; a cell left with none goes. Cell order is otherwise kept, so the
     * page does not reshuffle under the pointer.
     */
    public static <B> List<Group<B>> reconcile(List<Group<B>> groups, Map<Member, B> live) {
        List<B> blocks = new ArrayList<>(groups.size());
        List<List<Member>> members = new ArrayList<>(groups.size());
        Set<Member> placed = new HashSet<>();
        for (Group<B> g : groups) {
            B block = g.block();
            List<Member> kept = new ArrayList<>(g.count());
            for (Member m : g.members()) {
                if (block.equals(live.get(m)) && placed.add(m)) kept.add(m);
            }
            B wholeCell = kept.isEmpty() ? sharedBlock(g.members(), live) : null;
            if (wholeCell != null) {
                // Every use of the cell turned into one block at once — an undo or redo of a
                // re-skin, or another bulk swap. It is still the same cell: relabel it in place,
                // so the page keeps its order and the cell stays apart until a save.
                block = wholeCell;
                for (Member m : g.members()) {
                    if (live.containsKey(m) && placed.add(m)) kept.add(m);
                }
            }
            blocks.add(block);
            members.add(kept);
        }
        live.forEach((m, b) -> {
            if (placed.contains(m)) return;
            int at = firstCellOf(blocks, members, b);
            if (at < 0) {
                blocks.add(b);
                members.add(new ArrayList<>());
                at = blocks.size() - 1;
            }
            members.get(at).add(m);
            placed.add(m);
        });
        List<Group<B>> out = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            if (!members.get(i).isEmpty()) out.add(new Group<>(blocks.get(i), members.get(i)));
        }
        return List.copyOf(out);
    }

    /** The one block every still-present member now is, or null when they differ or none remain. */
    private static <B> B sharedBlock(List<Member> cell, Map<Member, B> live) {
        B shared = null;
        for (Member m : cell) {
            B now = live.get(m);
            if (now == null) continue;
            if (shared == null) shared = now;
            else if (!shared.equals(now)) return null;
        }
        return shared;
    }

    /** The first cell of {@code block} still holding anything, or failing that the first of it at all; -1 for none. */
    private static <B> int firstCellOf(List<B> blocks, List<List<Member>> members, B block) {
        int any = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (!blocks.get(i).equals(block)) continue;
            if (!members.get(i).isEmpty()) return i;
            if (any < 0) any = i;
        }
        return any;
    }

    /**
     * {@code groups} with cell {@code index} now showing {@code block} — its own cell still, even
     * when another cell already shows that block. Out of range leaves the list as it was.
     */
    public static <B> List<Group<B>> reskin(List<Group<B>> groups, int index, B block) {
        if (index < 0 || index >= groups.size()) return groups;
        List<Group<B>> out = new ArrayList<>(groups);
        out.set(index, new Group<>(block, groups.get(index).members()));
        return List.copyOf(out);
    }
}
