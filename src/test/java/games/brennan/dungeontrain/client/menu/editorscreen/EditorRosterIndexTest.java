package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            new EditorRosterPacket.Group("contents", "Contents", "", List.of(e(armor, 2), e(cows, EditorPlotLabelsPacket.NO_WEIGHT))),
            new EditorRosterPacket.Group("portals", "Dimensional Carriage", "portal_room", List.of(e(house, 1)))),
            "contents");
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
    @DisplayName("filters keep a group parent whenever one of its members passes")
    void filterKeepsParentOfHit() {
        EditorRosterIndex idx = sample();
        List<EditorRosterIndex.Tile> tiles = idx.tiles(PlotCategory.CONTENTS, "Contents");
        List<EditorRosterIndex.Tile> community = EditorRosterIndex.filter(tiles, EditorRosterIndex.Filter.COMMUNITY, "");
        assertEquals(List.of("armor"), community.stream().map(t -> t.variant().name()).toList());
        List<EditorRosterIndex.Tile> mine = EditorRosterIndex.filter(tiles, EditorRosterIndex.Filter.MINE, "");
        assertEquals(List.of("cows"), mine.stream().map(t -> t.variant().name()).toList());
        List<EditorRosterIndex.Tile> text = EditorRosterIndex.filter(tiles, EditorRosterIndex.Filter.ALL, "5");
        assertEquals(List.of("armor"), text.stream().map(t -> t.variant().name()).toList());
        assertTrue(EditorRosterIndex.filter(tiles, EditorRosterIndex.Filter.BUILTIN, "zzz").isEmpty());
    }

    @Test
    @DisplayName("sub-variants are keyed under their parent and filtered the same way")
    void subVariants() {
        EditorRosterIndex idx = sample();
        EditorRosterIndex.Tile armor = idx.tiles(PlotCategory.CONTENTS, "Contents").get(0);
        assertTrue(armor.isGroup());
        assertEquals(2, armor.selfWeight());
        List<EditorRosterIndex.Tile> members = EditorRosterIndex.subVariants(armor, EditorRosterIndex.Filter.ALL, "");
        assertEquals(1, members.size());
        assertEquals("armor", members.get(0).key().parentId());
        assertTrue(members.get(0).key().isSubVariant());
        assertTrue(EditorRosterIndex.subVariants(armor, EditorRosterIndex.Filter.MINE, "").isEmpty());
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
