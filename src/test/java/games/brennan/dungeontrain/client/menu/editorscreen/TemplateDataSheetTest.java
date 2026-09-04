package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The data sheet is where a template is edited, so what each cell does is pinned here: the command
 * behind every clickable value, and which values are not clickable at all.
 */
final class TemplateDataSheetTest {

    private static EditorTypeMenusPacket.Variant variant(String cat, String modelId, String modelName,
                                                         int weight, int min, int max, int phases,
                                                         List<String> stages) {
        return new EditorTypeMenusPacket.Variant(modelName, weight, min, max, phases, cat, modelId,
            modelName, false, false, List.of(), stages);
    }

    private static EditorRosterIndex.Tile tile(EditorTypeMenusPacket.Variant v, VariantKey key) {
        return new EditorRosterIndex.Tile(v, key, EditorPlotLabelsPacket.NO_WEIGHT);
    }

    private static List<TemplateDataSheet.Line> carriageSheet(int weight, List<String> stages) {
        VariantKey key = VariantKey.of(PlotCategory.CARRIAGES, "pen", "pen");
        EditorTypeMenusPacket.Variant v = variant("CARRIAGES", "pen", "pen", weight, 10, 60, 1, stages);
        return TemplateDataSheet.lines(tile(v, key), "Carriages › Carriages", null,
            EditorRosterIndex.Provenance.USER, key, List.of());
    }

    /** The line whose label matches, or null. */
    private static TemplateDataSheet.Line line(List<TemplateDataSheet.Line> lines, String labelKey) {
        String label = EditorScreenLang.text(labelKey);
        for (TemplateDataSheet.Line l : lines) {
            if (l.label().equals(label)) return l;
        }
        return null;
    }

    private static String typePrefix(TemplateDataSheet.Cell cell) {
        return assertInstanceOf(TemplateDataSheet.Action.Type.class, cell.action()).prefix();
    }

    private static String runCommand(TemplateDataSheet.Cell cell) {
        return assertInstanceOf(TemplateDataSheet.Action.Run.class, cell.action()).command();
    }

    @Test
    @DisplayName("the weight types over itself and carries its own nudge buttons")
    void weightIsEditable() {
        TemplateDataSheet.Line weight = line(carriageSheet(15, List.of()), EditorScreenLang.SHEET_WEIGHT);
        assertNotNull(weight);
        assertEquals(3, weight.cells().size());
        assertEquals("15", weight.cells().get(0).text());
        assertEquals("dungeontrain editor weight pen", typePrefix(weight.cells().get(0)));
        assertEquals("dungeontrain editor weight pen dec", runCommand(weight.cells().get(1)));
        assertEquals("dungeontrain editor weight pen inc", runCommand(weight.cells().get(2)));
    }

    @Test
    @DisplayName("both level bounds type, and each phase letter toggles the way it is not set")
    void spawnsAreEditable() {
        TemplateDataSheet.Line spawns = line(carriageSheet(15, List.of()), EditorScreenLang.SHEET_SPAWNS);
        assertNotNull(spawns);
        List<TemplateDataSheet.Cell> cells = spawns.cells();
        assertEquals("10", cells.get(1).text());
        assertEquals("dungeontrain editor minlevel pen", typePrefix(cells.get(1)));
        assertEquals("60", cells.get(3).text());
        assertEquals("dungeontrain editor maxlevel pen", typePrefix(cells.get(3)));

        // Phase mask 1 is Overworld only: it turns off, and every other dimension turns on.
        TemplateDataSheet.Cell overworld = cells.get(5);
        assertEquals("O", overworld.text());
        assertTrue(overworld.on());
        assertEquals("dungeontrain editor phase pen overworld off", runCommand(overworld));
        TemplateDataSheet.Cell nether = cells.get(6);
        assertFalse(nether.on());
        assertEquals("dungeontrain editor phase pen nether on", runCommand(nether));
    }

    @Test
    @DisplayName("a linked Stage replaces the gate with a chip that opens the picker")
    void stageLinkedSpawns() {
        TemplateDataSheet.Line spawns = line(carriageSheet(15, List.of("desert")), EditorScreenLang.SHEET_SPAWNS);
        assertNotNull(spawns);
        assertEquals(1, spawns.cells().size());
        assertTrue(spawns.cells().get(0).text().contains("desert"));
        assertInstanceOf(TemplateDataSheet.Action.Open.class, spawns.cells().get(0).action());
    }

    @Test
    @DisplayName("a carriage's size is measured, not set, so nothing on that line is clickable")
    void carriageSizeIsReadOnly() {
        TemplateDataSheet.Line size = line(carriageSheet(15, List.of()), EditorScreenLang.SHEET_SIZE);
        assertNotNull(size);
        for (TemplateDataSheet.Cell cell : size.cells()) {
            assertNull(cell.action(), "carriage size must not be editable: " + cell.text());
        }
    }

    @Test
    @DisplayName("a room's length, width and height type in place, taken from its own stepper rows")
    void roomSizeIsEditable() {
        VariantKey key = VariantKey.of(PlotCategory.PORTALS, "portal_room", "house");
        EditorTypeMenusPacket.Variant v = variant("PORTALS", "portal_room", "house", 1, 0, -1, 1, List.of());
        List<CommandMenuEntry> roomRows = List.of(
            games.brennan.dungeontrain.client.menu.EditorMenuPortalRows.sizeTripleFor("length", "Length", 5),
            games.brennan.dungeontrain.client.menu.EditorMenuPortalRows.sizeTripleFor("width", "Width", 7),
            games.brennan.dungeontrain.client.menu.EditorMenuPortalRows.sizeTripleFor("height", "Height", 4),
            new CommandMenuEntry.Label("not a size row"));
        TemplateDataSheet.Line size = line(
            TemplateDataSheet.lines(tile(v, key), "Dimensions", null,
                EditorRosterIndex.Provenance.USER, key, roomRows),
            EditorScreenLang.SHEET_SIZE);
        assertNotNull(size);
        assertEquals(List.of("5", "×", "7", "×", "4"), size.cells().stream().map(TemplateDataSheet.Cell::text).toList());
        assertEquals("dungeontrain editor portals length", typePrefix(size.cells().get(0)));
        assertEquals("dungeontrain editor portals height", typePrefix(size.cells().get(4)));
        assertNull(size.cells().get(1).action(), "the separator is not clickable");
    }

    @Test
    @DisplayName("a template with no weight pool shows a placeholder rather than a stepper")
    void noWeightPool() {
        VariantKey key = VariantKey.of(PlotCategory.PARTS, "floor", "oak");
        EditorTypeMenusPacket.Variant v = new EditorTypeMenusPacket.Variant(
            "oak", EditorPlotLabelsPacket.NO_WEIGHT, "PARTS", "floor", "oak", false, false);
        List<TemplateDataSheet.Line> lines = TemplateDataSheet.lines(tile(v, key), "Carriages › Floor",
            null, EditorRosterIndex.Provenance.USER, key, List.of());
        TemplateDataSheet.Line weight = line(lines, EditorScreenLang.SHEET_WEIGHT);
        assertNotNull(weight);
        assertEquals(1, weight.cells().size());
        assertNull(weight.cells().get(0).action());
        TemplateDataSheet.Line spawns = line(lines, EditorScreenLang.SHEET_SPAWNS);
        assertNotNull(spawns);
        assertNull(spawns.cells().get(0).action(), "a part has no spawn gate to edit");
    }

    @Test
    @DisplayName("placing puts every cell inside the sheet, and a hit finds only clickable ones")
    void placementAndHits() {
        List<TemplateDataSheet.Line> lines = carriageSheet(15, List.of());
        // Wide, because with no language loaded a label renders as its whole lang key.
        InventoryEditorLayout.Rect r = new InventoryEditorLayout.Rect(10, 20, 800, 62);
        List<TemplateDataSheet.Placed> placed = TemplateDataSheet.place(lines, r, new FixedFont());
        assertFalse(placed.isEmpty());
        for (TemplateDataSheet.Placed p : placed) {
            assertTrue(p.rect().x() >= r.x() && p.rect().right() <= r.right() + 2, "cell escaped: " + p);
        }
        TemplateDataSheet.Placed clickable = placed.stream().filter(p -> p.cell().action() != null)
            .findFirst().orElseThrow();
        assertEquals(placed.indexOf(clickable),
            TemplateDataSheet.hit(placed, clickable.rect().x() + 1, clickable.rect().y() + 1));
        assertEquals(-1, TemplateDataSheet.hit(placed, r.x() - 5, r.y() - 5));
    }

    /** A font whose every glyph is six pixels wide — enough to place cells without a client. */
    private static final class FixedFont extends net.minecraft.client.gui.Font {
        FixedFont() {
            super(loc -> null, false);
        }

        @Override
        public int width(String text) {
            return text.length() * 6;
        }
    }
}
