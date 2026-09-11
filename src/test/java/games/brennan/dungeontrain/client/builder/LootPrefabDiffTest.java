package games.brennan.dungeontrain.client.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static games.brennan.dungeontrain.client.builder.LootPrefabDiff.Kind;
import static games.brennan.dungeontrain.client.builder.LootPrefabDiff.State;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The side-by-side comparison the conflict screen draws.
 *
 * <p>The colours on that screen are the whole basis of the player's choice, so what each line is
 * marked as is pinned here: a changed count must not read as "same", and an entry only on one side
 * must show up on that side and not be silently paired with the wrong thing on the other.</p>
 */
final class LootPrefabDiffTest {

    private static String prefab(String block, int fillMin, int fillMax, String... entries) {
        StringBuilder sb = new StringBuilder("{\"schemaVersion\":4,\"block\":\"" + block
                + "\",\"category\":\"loot\",\"fillMin\":" + fillMin + ",\"fillMax\":" + fillMax + ",\"entries\":[");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(entries[i]);
        }
        return sb.append("]}").toString();
    }

    private static String entry(String item, int count, int weight) {
        return "{\"id\":\"" + item + "\",\"count\":" + count + ",\"weight\":" + weight + "}";
    }

    private static List<State> states(List<LootPrefabDiff.Line> lines) {
        return lines.stream().map(LootPrefabDiff.Line::state).toList();
    }

    @Test
    @DisplayName("block, fill and entries each get a line, in that order")
    void shape() {
        String same = prefab("minecraft:chest", 0, -1, entry("minecraft:gold_ingot", 3, 10));
        LootPrefabDiff.Columns c = LootPrefabDiff.of("gold", same, same);
        assertEquals(List.of(Kind.BLOCK, Kind.FILL, Kind.ENTRY),
                c.yours().stream().map(LootPrefabDiff.Line::kind).toList());
        assertEquals(c.yours().stream().map(LootPrefabDiff.Line::kind).toList(),
                c.theirs().stream().map(LootPrefabDiff.Line::kind).toList());
        assertTrue(LootPrefabDiff.identical(c));
        assertEquals("minecraft:chest", c.yours().get(0).item());
        assertEquals("minecraft:gold_ingot", c.yours().get(2).item());
        assertEquals(3, c.yours().get(2).count());
        assertEquals(10, c.yours().get(2).weight());
    }

    @Test
    @DisplayName("a changed count or weight marks the entry CHANGED on both sides")
    void changedEntry() {
        String mine = prefab("minecraft:chest", 0, -1, entry("minecraft:gold_ingot", 3, 10));
        String theirs = prefab("minecraft:chest", 0, -1, entry("minecraft:gold_ingot", 9, 10));
        LootPrefabDiff.Columns c = LootPrefabDiff.of("gold", mine, theirs);
        assertEquals(List.of(State.SAME, State.SAME, State.CHANGED), states(c.yours()));
        assertEquals(List.of(State.SAME, State.SAME, State.CHANGED), states(c.theirs()));
        assertFalse(LootPrefabDiff.identical(c));
    }

    @Test
    @DisplayName("an item on one side only is ONLY_HERE on that side and absent from the other")
    void onlyHere() {
        String mine = prefab("minecraft:chest", 0, -1,
                entry("minecraft:gold_ingot", 3, 10), entry("minecraft:bread", 1, 5));
        String theirs = prefab("minecraft:chest", 0, -1,
                entry("minecraft:gold_ingot", 3, 10), entry("minecraft:diamond", 1, 1));
        LootPrefabDiff.Columns c = LootPrefabDiff.of("gold", mine, theirs);
        assertEquals(List.of(State.SAME, State.SAME, State.SAME, State.ONLY_HERE), states(c.yours()));
        assertEquals("minecraft:bread", c.yours().get(3).item());
        assertEquals(List.of(State.SAME, State.SAME, State.SAME, State.ONLY_HERE), states(c.theirs()));
        assertEquals("minecraft:diamond", c.theirs().get(3).item());
    }

    @Test
    @DisplayName("block and fill differences are marked on their own lines")
    void blockAndFill() {
        String mine = prefab("minecraft:chest", 0, -1, entry("minecraft:gold_ingot", 3, 10));
        String theirs = prefab("minecraft:barrel", 2, 6, entry("minecraft:gold_ingot", 3, 10));
        LootPrefabDiff.Columns c = LootPrefabDiff.of("gold", mine, theirs);
        assertEquals(List.of(State.CHANGED, State.CHANGED, State.SAME), states(c.yours()));
        assertEquals(2, c.theirs().get(1).count(), "fill min rides in count");
        assertEquals(6, c.theirs().get(1).weight(), "fill max rides in weight");
    }

    @Test
    @DisplayName("two entries of one item pair up in order, so a second copy is not matched twice")
    void duplicateItemsPairInOrder() {
        String mine = prefab("minecraft:chest", 0, -1,
                entry("minecraft:diamond", 1, 1), entry("minecraft:diamond", 2, 2));
        String theirs = prefab("minecraft:chest", 0, -1,
                entry("minecraft:diamond", 1, 1));
        LootPrefabDiff.Columns c = LootPrefabDiff.of("gold", mine, theirs);
        assertEquals(List.of(State.SAME, State.SAME, State.SAME, State.ONLY_HERE), states(c.yours()));
        assertEquals(List.of(State.SAME, State.SAME, State.SAME), states(c.theirs()));
    }

    @Test
    @DisplayName("a side that will not parse is one UNREADABLE line; the other side stands alone")
    void unreadableSide() {
        String mine = prefab("minecraft:chest", 0, -1, entry("minecraft:gold_ingot", 3, 10));
        LootPrefabDiff.Columns c = LootPrefabDiff.of("gold", mine, "cut off mid");
        assertEquals(List.of(State.ONLY_HERE, State.ONLY_HERE, State.ONLY_HERE), states(c.yours()));
        assertEquals(List.of(new LootPrefabDiff.Line(Kind.UNREADABLE, State.UNREADABLE, "", 0, 0)), c.theirs());
        assertFalse(LootPrefabDiff.identical(c));
    }
}
