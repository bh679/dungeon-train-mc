package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.editor.TemplateBlockGroups.Group;
import games.brennan.dungeontrain.editor.TemplateBlockGroups.Member;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TemplateBlockGroupsTest {

    private static Member at(int x) {
        return Member.base(new BlockPos(x, 0, 0));
    }

    /** stone ×3, planks ×2, glass ×1 — at x 0..5. */
    private static Map<Member, String> template() {
        Map<Member, String> live = new LinkedHashMap<>();
        live.put(at(0), "stone");
        live.put(at(1), "planks");
        live.put(at(2), "stone");
        live.put(at(3), "glass");
        live.put(at(4), "planks");
        live.put(at(5), "stone");
        return live;
    }

    private static List<String> blocks(List<Group<String>> groups) {
        return groups.stream().map(Group::block).toList();
    }

    private static List<Integer> counts(List<Group<String>> groups) {
        return groups.stream().map(Group::count).toList();
    }

    @Test
    @DisplayName("A fresh template is one cell per block, most used first")
    void buildsOneCellPerBlock() {
        List<Group<String>> g = TemplateBlockGroups.build(template(), Comparator.naturalOrder());
        assertEquals(List.of("stone", "planks", "glass"), blocks(g));
        assertEquals(List.of(3, 2, 1), counts(g));
    }

    @Test
    @DisplayName("Re-skinning to a block already used keeps the two cells apart")
    void reskinKeepsCellsApart() {
        Map<Member, String> live = template();
        List<Group<String>> g = TemplateBlockGroups.build(live, Comparator.naturalOrder());
        g = TemplateBlockGroups.reskin(g, 0, "planks");
        // The world now shows planks where stone was.
        Map<Member, String> after = new HashMap<>(live);
        after.replaceAll((m, b) -> b.equals("stone") ? "planks" : b);

        List<Group<String>> synced = TemplateBlockGroups.reconcile(g, after);
        assertEquals(List.of("planks", "planks", "glass"), blocks(synced));
        assertEquals(List.of(3, 2, 1), counts(synced));

        // Saving drops the cells; building again merges them.
        List<Group<String>> saved = TemplateBlockGroups.build(after, Comparator.naturalOrder());
        assertEquals(List.of("planks", "glass"), blocks(saved));
        assertEquals(List.of(5, 1), counts(saved));
    }

    @Test
    @DisplayName("Hand edits move to the first cell of their new block, or a new one; empty cells go")
    void reconcileFollowsHandEdits() {
        Map<Member, String> live = template();
        List<Group<String>> g = TemplateBlockGroups.build(live, Comparator.naturalOrder());
        Map<Member, String> after = new LinkedHashMap<>(live);
        after.put(at(0), "planks");       // one of three stones became planks
        after.remove(at(3));              // the glass broken
        after.put(at(9), "lantern");      // something new
        List<Group<String>> synced = TemplateBlockGroups.reconcile(g, after);
        assertEquals(List.of("stone", "planks", "lantern"), blocks(synced));
        assertEquals(List.of(2, 3, 1), counts(synced));
    }

    @Test
    @DisplayName("Undoing and redoing a re-skin keeps every cell where it was")
    void undoRedoKeepsOrder() {
        Map<Member, String> live = template();
        List<Group<String>> g = TemplateBlockGroups.build(live, Comparator.naturalOrder());
        // Re-skin the stone cell to planks.
        g = TemplateBlockGroups.reskin(g, 0, "planks");
        Map<Member, String> reskinned = new LinkedHashMap<>(live);
        reskinned.replaceAll((m, b) -> b.equals("stone") ? "planks" : b);
        g = TemplateBlockGroups.reconcile(g, reskinned);
        assertEquals(List.of("planks", "planks", "glass"), blocks(g));

        // Undo: the world is stone again — the first cell turns back, in place.
        g = TemplateBlockGroups.reconcile(g, live);
        assertEquals(List.of("stone", "planks", "glass"), blocks(g));
        assertEquals(List.of(3, 2, 1), counts(g));

        // Redo: planks again, still two cells, still in order.
        g = TemplateBlockGroups.reconcile(g, reskinned);
        assertEquals(List.of("planks", "planks", "glass"), blocks(g));
        assertEquals(List.of(3, 2, 1), counts(g));
    }

    @Test
    @DisplayName("Variant candidates count as uses of their own")
    void variantCandidatesCount() {
        Map<Member, String> live = new LinkedHashMap<>();
        BlockPos cell = new BlockPos(1, 1, 1);
        live.put(Member.variant(cell, 0), "stone");
        live.put(Member.variant(cell, 1), "mossy");
        live.put(at(0), "stone");
        List<Group<String>> g = TemplateBlockGroups.build(live, Comparator.naturalOrder());
        assertEquals(List.of("stone", "mossy"), blocks(g));
        assertEquals(List.of(2, 1), counts(g));
    }

    @Test
    void reskinOutOfRangeIsUnchanged() {
        List<Group<String>> g = TemplateBlockGroups.build(template(), Comparator.naturalOrder());
        assertSame(g, TemplateBlockGroups.reskin(g, 7, "planks"));
        assertSame(g, TemplateBlockGroups.reskin(g, -1, "planks"));
    }
}
