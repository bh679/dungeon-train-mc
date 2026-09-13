package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stage preview's template page: linked parts first, then every roster row that links to the stage. */
final class EditorStageTemplatesTest {

    private static EditorRosterPacket.StageEntry desert(List<String> parts) {
        EditorTypeMenusPacket.Variant v = new EditorTypeMenusPacket.Variant(
            "desert", EditorPlotLabelsPacket.NO_WEIGHT, 10, 40, 1, "stages", "desert", "desert", true, false);
        return new EditorRosterPacket.StageEntry(v, List.of(), 0, parts);
    }

    private static EditorRosterIndex roster() {
        EditorTypeMenusPacket.Variant member = new EditorTypeMenusPacket.Variant(
            "armor2", 5, 0, -1, 1, "CONTENTS", "armor2", "armor2", true, false, List.of(), List.of("desert"));
        EditorTypeMenusPacket.Variant parent = new EditorTypeMenusPacket.Variant(
            "armor", 5, 0, -1, 1, "CONTENTS", "armor", "armor", false, false, List.of(member), List.of());
        EditorTypeMenusPacket.Variant sandy = new EditorTypeMenusPacket.Variant(
            "sandy", 19, 0, -1, 1, "CARRIAGES", "sandy", "sandy", false, false, List.of(), List.of("Desert"));
        EditorTypeMenusPacket.Variant standard = new EditorTypeMenusPacket.Variant(
            "standard", 19, 0, -1, 1, "CARRIAGES", "standard", "standard", false, false, List.of(), List.of("nether"));
        return new EditorRosterIndex(List.of(
            new EditorRosterPacket.Group("carriages", "Carriages", "", List.of(
                new EditorRosterPacket.Entry(sandy, EditorPlotLabelsPacket.NO_WEIGHT),
                new EditorRosterPacket.Entry(standard, EditorPlotLabelsPacket.NO_WEIGHT))),
            new EditorRosterPacket.Group("contents", "Contents", "", List.of(
                new EditorRosterPacket.Entry(parent, 3)))),
            "", EditorRosterPacket.TrainSize.UNKNOWN);
    }

    @Test
    @DisplayName("parts lead, then linked templates and group members; case-insensitive on the stage id")
    void rows() {
        List<EditorStageTemplates.Row> rows = EditorStageTemplates.rows(
            desert(List.of("floor:sand_1", "walls:sand_1")), roster());
        assertEquals(4, rows.size());
        assertEquals("Floor · sand_1", rows.get(0).label());
        assertEquals(VariantKey.of(PlotCategory.PARTS, "floor", "sand_1"), rows.get(0).key());
        assertEquals("Walls · sand_1", rows.get(1).label());
        assertEquals("Carriages · sandy", rows.get(2).label());
        assertEquals(VariantKey.of(PlotCategory.CARRIAGES, "sandy", "sandy"), rows.get(2).key());
        assertEquals("Contents · armor / armor2", rows.get(3).label());
        assertTrue(rows.get(3).key().isSubVariant());
        assertEquals("armor", rows.get(3).key().parentId());
    }

    @Test
    @DisplayName("a stage nothing links to has no rows; a null stage or roster does not throw")
    void empty() {
        EditorRosterPacket.StageEntry lonely = new EditorRosterPacket.StageEntry(
            new EditorTypeMenusPacket.Variant("x", 0, 0, -1, 1, "stages", "x", "x", true, false),
            List.of(), 0, List.of());
        assertTrue(EditorStageTemplates.rows(lonely, roster()).isEmpty());
        assertTrue(EditorStageTemplates.rows(null, roster()).isEmpty());
        assertEquals(1, EditorStageTemplates.rows(desert(List.of("roof:r")), null).size());
    }
}
