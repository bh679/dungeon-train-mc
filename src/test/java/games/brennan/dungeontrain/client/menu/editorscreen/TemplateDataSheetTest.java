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

    /** The cells of the unlabelled line that follows the Stage line. */
    private static List<TemplateDataSheet.Cell> gateCells(List<TemplateDataSheet.Line> lines) {
        String label = EditorScreenLang.text(EditorScreenLang.SHEET_STAGE);
        for (int i = 0; i < lines.size() - 1; i++) {
            if (lines.get(i).label().equals(label)) return lines.get(i + 1).cells();
        }
        throw new AssertionError("no gate line after the Stage line");
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
    @DisplayName("Custom opens the picker, both bounds type, and each phase toggles the way it is not set")
    void stageLineIsEditableWhenCustom() {
        TemplateDataSheet.Line stage = line(carriageSheet(15, List.of()), EditorScreenLang.SHEET_STAGE);
        assertNotNull(stage);
        List<TemplateDataSheet.Cell> cells = stage.cells();

        // A Stage and a spawn gate are one thing, so they share a line — and an unlinked template
        // says Custom, which is itself the way to link one.
        assertEquals(EditorScreenLang.text(EditorScreenLang.STAGE_CUSTOM_SHORT), cells.get(0).text());
        assertInstanceOf(TemplateDataSheet.Action.Open.class, cells.get(0).action());
        assertNotNull(cells.get(0).tooltip());

        List<TemplateDataSheet.Cell> gate = gateCells(carriageSheet(15, List.of()));
        assertEquals("10", gate.get(1).text());
        assertEquals("dungeontrain editor minlevel pen", typePrefix(gate.get(1)));
        assertEquals("60", gate.get(3).text());
        assertEquals("dungeontrain editor maxlevel pen", typePrefix(gate.get(3)));

        // Phase mask 1 is Overworld only: it turns off, and every other dimension turns on.
        cells = gate;
        TemplateDataSheet.Cell overworld = cells.get(5);
        assertEquals("O", overworld.text());
        assertTrue(overworld.on());
        assertEquals("dungeontrain editor phase pen overworld off", runCommand(overworld));
        assertEquals("Overworld", overworld.tooltip());
        TemplateDataSheet.Cell nether = cells.get(6);
        assertFalse(nether.on());
        assertEquals("dungeontrain editor phase pen nether on", runCommand(nether));
    }

    @Test
    @DisplayName("a linked Stage names itself and owns the gate, so the bounds beside it are read-only")
    void stageLinkedOwnsTheGate() {
        TemplateDataSheet.Line stage = line(carriageSheet(15, List.of("desert")), EditorScreenLang.SHEET_STAGE);
        assertNotNull(stage);
        List<TemplateDataSheet.Cell> cells = stage.cells();
        assertEquals("desert", cells.get(0).text());
        assertInstanceOf(TemplateDataSheet.Action.Open.class, cells.get(0).action());
        // A linked gate is read-only and compact, so it stays on the one line.
        assertNull(cells.get(2).action(), "min level must be read-only under a Stage");
        assertNull(cells.get(4).action(), "max level must be read-only under a Stage");
        assertNull(cells.get(6).action(), "phases must be read-only under a Stage");
    }

    @Test
    @DisplayName("a Custom stage keeps its name on the Stage line and its gate on the next one")
    void customStageWrapsToASecondLine() {
        List<TemplateDataSheet.Line> lines = carriageSheet(15, List.of());
        int stageAt = -1;
        String label = EditorScreenLang.text(EditorScreenLang.SHEET_STAGE);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).label().equals(label)) stageAt = i;
        }
        assertTrue(stageAt >= 0);
        assertEquals(1, lines.get(stageAt).cells().size(), "the Stage line carries only the stage");
        TemplateDataSheet.Line gate = lines.get(stageAt + 1);
        assertEquals("", gate.label(), "the gate continues on an unlabelled line");
        assertEquals("Lv", gate.cells().get(0).text());
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
        TemplateDataSheet.Line stage = line(lines, EditorScreenLang.SHEET_STAGE);
        assertNotNull(stage);
        assertNull(stage.cells().get(0).action(), "a part has no spawn gate to edit");
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
