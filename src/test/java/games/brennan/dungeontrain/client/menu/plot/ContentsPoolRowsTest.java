package games.brennan.dungeontrain.client.menu.plot;

import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link ContentsPoolRows}: folding a pool hides its groups, and each group's share is of its pool's total. */
final class ContentsPoolRowsTest {

    private static EditorTypeMenusPacket.Variant group(String id, int weight) {
        return new EditorTypeMenusPacket.Variant(id, weight, 0, -1, 1, "CONTENTS", id, id, false, false).inPool("p");
    }

    private static List<EditorTypeMenusPacket.Menu> menus() {
        EditorTypeMenusPacket.Variant books = new EditorTypeMenusPacket.Variant("books", 4, 0, -1, 1,
            "CONTENTS", "books", "books", false, false);
        return List.of(new EditorTypeMenusPacket.Menu(BlockPos.ZERO, "Room", List.of(books,
            EditorTypeMenusPacket.Variant.poolHeader("p", "Pool", 8, "CONTENTS"), group("a", 3), group("b", 1))));
    }

    @Test
    @DisplayName("shares are of the pool's total, and the header counts its groups")
    void sharesAndCount() {
        List<EditorTypeMenusPacket.Menu> view = ContentsPoolRows.view(menus());
        List<EditorTypeMenusPacket.Variant> rows = view.get(0).variants();
        assertEquals(4, rows.size());
        assertEquals("75%", ContentsPoolRows.shareLabel(rows.get(2)));
        assertEquals("25%", ContentsPoolRows.shareLabel(rows.get(3)));
        assertEquals("▾ Pool (2)", ContentsPoolRows.headerLabel(rows.get(1)));
    }

    @Test
    @DisplayName("folding leaves the header and drops its groups; unfolding brings them back")
    void foldsAndUnfolds() {
        ContentsPoolRows.toggle("p");
        try {
            List<EditorTypeMenusPacket.Variant> rows = ContentsPoolRows.view(menus()).get(0).variants();
            assertEquals(List.of("books", "p"), rows.stream().map(EditorTypeMenusPacket.Variant::name).toList());
            assertEquals("▸ Pool (2)", ContentsPoolRows.headerLabel(rows.get(1)));
        } finally {
            ContentsPoolRows.toggle("p");
        }
        assertEquals(4, ContentsPoolRows.view(menus()).get(0).variants().size());
    }

    @Test
    @DisplayName("the same pool in two menus is counted once per menu, not summed across them")
    void countedPerMenu() {
        List<EditorTypeMenusPacket.Menu> twice = new java.util.ArrayList<>(menus());
        twice.addAll(menus());
        List<EditorTypeMenusPacket.Variant> rows = ContentsPoolRows.view(twice).get(0).variants();
        assertEquals("75%", ContentsPoolRows.shareLabel(rows.get(2)));
        assertEquals("▾ Pool (2)", ContentsPoolRows.headerLabel(rows.get(1)));
    }
}
