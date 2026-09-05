package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The browser's queries over a roster snapshot. */
final class EditorRosterIndexTest {

    private static EditorTypeMenusPacket.Variant v(String name, String cat, String modelId, String modelName,
                                                   boolean user, boolean imported,
                                                   List<EditorTypeMenusPacket.Variant> subs) {
        return new EditorTypeMenusPacket.Variant(name, 1, 0, -1, 1, cat, modelId, modelName, user, imported, subs, List.of());
    }

    private static EditorRosterPacket.Entry e(EditorTypeMenusPacket.Variant variant, int self) {
        return new EditorRosterPacket.Entry(variant, self);
    }

    private static EditorRosterIndex sample() {
        EditorTypeMenusPacket.Variant armor5 = v("armor5", "CONTENTS", "armor5", "armor5", false, true, List.of());
        EditorTypeMenusPacket.Variant armor = v("armor", "CONTENTS", "armor", "armor", false, false, List.of(armor5));
        EditorTypeMenusPacket.Variant cows = v("cows", "CONTENTS", "cows", "cows", true, false, List.of());
        EditorTypeMenusPacket.Variant standard = v("standard", "CARRIAGES", "standard", "standard", false, false, List.of());
        EditorTypeMenusPacket.Variant oakFloor = v("oak", "PARTS", "floor", "oak", true, false, List.of());
        EditorTypeMenusPacket.Variant house = v("house", "PORTALS", "portal_room", "house", false, false, List.of());
        return new EditorRosterIndex(List.of(
            new EditorRosterPacket.Group("carriages", "Carriages", "", List.of(e(standard, EditorPlotLabelsPacket.NO_WEIGHT))),
            new EditorRosterPacket.Group("parts", "Floor", "floor", List.of(e(oakFloor, EditorPlotLabelsPacket.NO_WEIGHT))),
            new EditorRosterPacket.Group("contents", "Contents", "",
                List.of(e(armor, 2), e(cows, EditorPlotLabelsPacket.NO_WEIGHT))),
            new EditorRosterPacket.Group("portals", "Dimensional Carriage", "portal_room", List.of(e(house, 1)))),
            "contents", new EditorRosterPacket.TrainSize(9, 7, 7));
    }

    @Test
    @DisplayName("the Carriages page lists the carriage strip and the part kinds; other pages only themselves")
    void typeStripsPerPage() {
        EditorRosterIndex idx = sample();
        List<EditorRosterIndex.TypeStrip> carriages = idx.typeStrips(PlotCategory.CARRIAGES);
        assertEquals(List.of("Carriages", "Floor"), carriages.stream().map(EditorRosterIndex.TypeStrip::typeName).toList());
        assertEquals(PlotCategory.PARTS, carriages.get(1).category());
        assertEquals(1, idx.typeStrips(PlotCategory.CONTENTS).size());
        assertEquals(2, idx.typeStrips(PlotCategory.CONTENTS).get(0).count());
        assertEquals("Dimensional Carriage", idx.firstStrip(PlotCategory.PORTALS).typeName());
        assertTrue(idx.typeStrips(PlotCategory.TRACKS).isEmpty());
        assertNull(idx.firstStrip(PlotCategory.TRACKS));
    }

    @Test
    @DisplayName("provenance: imported beats user, and neither is built-in")
    void provenance() {
        EditorRosterIndex idx = sample();
        List<EditorRosterIndex.Tile> tiles = idx.tiles(PlotCategory.CONTENTS, "Contents");
        assertEquals(EditorRosterIndex.Provenance.BUILTIN, EditorRosterIndex.provenanceOf(tiles.get(0).variant()));
        assertEquals(EditorRosterIndex.Provenance.USER, EditorRosterIndex.provenanceOf(tiles.get(1).variant()));
        assertEquals(EditorRosterIndex.Provenance.IMPORTED,
            EditorRosterIndex.provenanceOf(tiles.get(0).variant().subVariants().get(0)));
    }

    @Test
    @DisplayName("no toggle set filters nothing; the two toggles add up rather than replacing each other")
    void togglesAddUp() {
        EditorRosterIndex idx = sample();
        List<EditorRosterIndex.Tile> tiles = idx.tiles(PlotCategory.CONTENTS, "Contents");
        List<String> all = names(EditorRosterIndex.filter(tiles, EditorRosterIndex.Filters.NONE, ""));
        assertEquals(List.of("armor", "cows"), all, "nothing set means nothing filtered out");

        EditorRosterIndex.Filters mine = EditorRosterIndex.Filters.NONE.withMine(true);
        assertEquals(List.of("cows"), names(EditorRosterIndex.filter(tiles, mine, "")));

        // Built-in on its own keeps the parent, because a member of it is built-in.
        EditorRosterIndex.Filters builtin = EditorRosterIndex.Filters.NONE.withBuiltin(true);
        assertEquals(List.of("armor"), names(EditorRosterIndex.filter(tiles, builtin, "")));

        // Both together is the union — the thing the old one-of-four picker could not say.
        assertEquals(List.of("armor", "cows"),
            names(EditorRosterIndex.filter(tiles, mine.withBuiltin(true), "")));
    }

    @Test
    @DisplayName("All lists every template in the roster, from every category")
    void allTiles() {
        List<String> all = names(sample().allTiles());
        assertEquals(List.of("standard", "oak", "armor", "cows", "house"), all);
    }

    @Test
    @DisplayName("the template underfoot sorts to the front, wherever it appears")
    void standingSortsFirst() {
        EditorRosterIndex idx = sample();
        List<EditorRosterIndex.Tile> tiles = idx.allTiles();
        VariantKey standing = VariantKey.of(PlotCategory.CONTENTS, "cows", "cows");
        assertEquals("cows", names(EditorRosterIndex.standingFirst(tiles, standing)).get(0));

        // Order is otherwise untouched, and nothing is lost or duplicated.
        List<String> moved = names(EditorRosterIndex.standingFirst(tiles, standing));
        assertEquals(tiles.size(), moved.size());
        assertEquals(List.of("cows", "standard", "oak", "armor", "house"), moved);

        // Standing nowhere in this list, or nowhere at all, leaves it as it was.
        assertEquals(names(tiles), names(EditorRosterIndex.standingFirst(tiles, null)));
        assertEquals(names(tiles), names(EditorRosterIndex.standingFirst(tiles,
            VariantKey.of(PlotCategory.TRACKS, "tile", "nope"))));
        // Already first is a no-op rather than a shuffle.
        assertEquals(names(tiles), names(EditorRosterIndex.standingFirst(tiles,
            VariantKey.of(PlotCategory.CARRIAGES, "standard", "standard"))));
    }

    @Test
    @DisplayName("the template underfoot survives a filter that would have dropped it, faded")
    void standingSurvivesTheFilter() {
        EditorRosterIndex idx = sample();
        List<EditorRosterIndex.Tile> all = idx.allTiles();
        VariantKey standing = VariantKey.of(PlotCategory.CONTENTS, "cows", "cows");

        // Filtered out: put back at the front, marked as not part of what was asked for.
        List<EditorRosterIndex.Tile> filtered = all.stream()
            .filter(t -> !t.variant().name().equals("cows")).toList();
        EditorRosterIndex.Shown shown = EditorRosterIndex.standingFirst(filtered, all, standing);
        assertTrue(shown.firstIsGhost());
        assertEquals("cows", names(shown.tiles()).get(0));
        assertEquals(filtered.size() + 1, shown.tiles().size(), "nothing else is lost");

        // Still in the filtered list: moved to the front like before, and NOT faded.
        EditorRosterIndex.Shown kept = EditorRosterIndex.standingFirst(all, all, standing);
        assertFalse(kept.firstIsGhost());
        assertEquals("cows", names(kept.tiles()).get(0));

        // Standing in something this page does not hold, or nowhere: nothing is inserted.
        EditorRosterIndex.Shown elsewhere = EditorRosterIndex.standingFirst(filtered, filtered, standing);
        assertFalse(elsewhere.firstIsGhost());
        assertEquals(names(filtered), names(elsewhere.tiles()));
        assertFalse(EditorRosterIndex.standingFirst(filtered, all, null).firstIsGhost());
    }

    private static List<String> names(List<EditorRosterIndex.Tile> tiles) {
        return tiles.stream().map(t -> t.variant().name()).toList();
    }

    @Test
    @DisplayName("sub-variants are keyed under their parent and filtered the same way")
    void subVariants() {
        EditorRosterIndex idx = sample();
        EditorRosterIndex.Tile armor = idx.tiles(PlotCategory.CONTENTS, "Contents").get(0);
        assertTrue(armor.isGroup());
        assertEquals(2, armor.selfWeight());
        List<EditorRosterIndex.Tile> members = EditorRosterIndex.subVariants(armor, EditorRosterIndex.Filters.NONE, "");
        assertEquals(1, members.size());
        assertEquals("armor", members.get(0).key().parentId());
        assertTrue(members.get(0).key().isSubVariant());
        assertTrue(EditorRosterIndex.subVariants(armor,
            EditorRosterIndex.Filters.NONE.withMine(true), "").isEmpty());
    }

    @Test
    @DisplayName("find resolves top-level and member keys, and the group they sit in")
    void findAndGroupOf() {
        EditorRosterIndex idx = sample();
        VariantKey member = VariantKey.of(PlotCategory.CONTENTS, "armor5", "armor5");
        EditorRosterIndex.Tile found = idx.find(member);
        assertNotNull(found);
        assertEquals("armor", found.key().parentId());
        assertEquals("Contents", idx.groupOf(member).typeName());
        assertEquals("armor", idx.parentOf(found.key()).variant().name());

        VariantKey part = VariantKey.fromStatus("Parts", "floor:oak", "oak");
        assertNotNull(idx.find(part));
        assertEquals("Floor", idx.groupOf(part).typeName());
        assertNull(idx.find(VariantKey.of(PlotCategory.TRACKS, "tile", "nope")));
        assertEquals(PlotCategory.CONTENTS, idx.stampedCategory());
    }
}
