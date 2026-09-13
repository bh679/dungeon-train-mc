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
    /** The Bands line: every band a letter, under the Stage line when the gate is Custom. */
    private static List<TemplateDataSheet.Cell> bandCells(List<TemplateDataSheet.Line> lines) {
        String label = EditorScreenLang.text(EditorScreenLang.STAGES_BANDS);
        for (TemplateDataSheet.Line l : lines) {
            if (l.label().equals(label)) return l.cells();
        }
        throw new AssertionError("no Bands line");
    }

    private static String typePrefix(TemplateDataSheet.Cell cell) {
        return assertInstanceOf(TemplateDataSheet.Action.Type.class, cell.action()).prefix();
    }

    private static String runCommand(TemplateDataSheet.Cell cell) {
        return assertInstanceOf(TemplateDataSheet.Action.Run.class, cell.action()).command();
    }

    /** A stepping cell's three commands must share one prefix: {@code prefix}, {@code prefix dec}, {@code prefix inc}. */
    private static String stepPrefix(TemplateDataSheet.Cell cell) {
        TemplateDataSheet.Action.Step step = assertInstanceOf(TemplateDataSheet.Action.Step.class, cell.action());
        assertEquals(step.prefix() + " dec", step.dec());
        assertEquals(step.prefix() + " inc", step.inc());
        return step.prefix();
    }

    @Test
    @DisplayName("the weight steps in place (cmd-click types) and carries its own nudge buttons")
    void weightIsEditable() {
        TemplateDataSheet.Line weight = line(carriageSheet(15, List.of()), EditorScreenLang.SHEET_WEIGHT);
        assertNotNull(weight);
        assertEquals(3, weight.cells().size());
        assertEquals("15", weight.cells().get(0).text());
        assertEquals("dungeontrain editor weight pen", stepPrefix(weight.cells().get(0)));
        assertEquals("dungeontrain editor weight pen dec", runCommand(weight.cells().get(1)));
        assertEquals("dungeontrain editor weight pen inc", runCommand(weight.cells().get(2)));
    }

    @Test
    @DisplayName("Custom opens the picker, both bounds step, and each phase toggles the way it is not set")
    void stageLineIsEditableWhenCustom() {
        TemplateDataSheet.Line stage = line(carriageSheet(15, List.of()), EditorScreenLang.SHEET_STAGE);
        assertNotNull(stage);
        List<TemplateDataSheet.Cell> cells = stage.cells();

        // A Stage and a spawn gate are one thing, so they share a line — and an unlinked template
        // says Custom, which is itself the way to link one.
        assertEquals(EditorScreenLang.text(EditorScreenLang.STAGE_CUSTOM_SHORT), cells.get(0).text());
        assertInstanceOf(TemplateDataSheet.Action.Open.class, cells.get(0).action());
        assertNotNull(cells.get(0).tooltip());

        // The bounds sit beside Custom: Custom · Lv · min · — · max.
        assertEquals("10", cells.get(2).text());
        assertEquals("dungeontrain editor minlevel pen", stepPrefix(cells.get(2)));
        assertTrue(cells.get(2).tooltip().contains(EditorScreenLang.text(EditorScreenLang.LAYOUT_WEIGHT_TIP)));
        assertEquals("60", cells.get(4).text());
        assertEquals("dungeontrain editor maxlevel pen", stepPrefix(cells.get(4)));

        // Phase mask 1 is Overworld only: it turns off, and every other dimension turns on — each
        // band its own button on the Bands line.
        List<TemplateDataSheet.Cell> bands = bandCells(carriageSheet(15, List.of()));
        assertEquals(games.brennan.dungeontrain.worldgen.TrainPhase.values().length, bands.size());
        TemplateDataSheet.Cell overworld = bands.get(0);
        assertEquals("O", overworld.text());
        assertTrue(overworld.on());
        assertEquals("dungeontrain editor phase pen overworld off", runCommand(overworld));
        assertEquals("Overworld", overworld.tooltip());
        TemplateDataSheet.Cell nether = bands.get(1);
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
    @DisplayName("a Custom stage keeps its bounds on the Stage line and its bands on a Bands line under it; a linked one has no Bands line")
    void customStageGetsABandsLine() {
        List<TemplateDataSheet.Line> lines = carriageSheet(15, List.of());
        int stageAt = -1;
        String label = EditorScreenLang.text(EditorScreenLang.SHEET_STAGE);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).label().equals(label)) stageAt = i;
        }
        assertTrue(stageAt >= 0);
        assertEquals(5, lines.get(stageAt).cells().size(), "Custom · Lv · min · — · max");
        TemplateDataSheet.Line bands = lines.get(stageAt + 1);
        assertEquals(EditorScreenLang.text(EditorScreenLang.STAGES_BANDS), bands.label());
        assertEquals("O", bands.cells().get(0).text());
        assertFalse(carriageSheet(15, List.of("desert")).stream()
            .anyMatch(l -> l.label().equals(EditorScreenLang.text(EditorScreenLang.STAGES_BANDS))),
            "a linked Stage owns its bands; they stay read-only on the Stage line");
        assertFalse(lines.stream().anyMatch(l -> l.label().equals(EditorScreenLang.text(EditorScreenLang.SHEET_PATH))),
            "the Path line gave its row to Bands");
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
        InventoryEditorLayout.Rect r = new InventoryEditorLayout.Rect(10, 20, 800, InventoryEditorLayout.SHEET_H);
        List<TemplateDataSheet.Placed> placed = TemplateDataSheet.place(lines, r, new FixedFont());
        assertFalse(placed.isEmpty());
        for (TemplateDataSheet.Placed p : placed) {
            assertTrue(p.rect().x() >= r.x() && p.rect().right() <= r.right() + 2, "cell escaped: " + p);
        }
        // Every band's button lands inside the sheet, on the Bands line's own row.
        List<TemplateDataSheet.Line> withGate = carriageSheet(15, List.of());
        List<TemplateDataSheet.Placed> gatePlaced = TemplateDataSheet.place(withGate, r, new FixedFont());
        List<TemplateDataSheet.Placed> letters = gatePlaced.stream()
            .filter(p -> p.cell().action() instanceof TemplateDataSheet.Action.Run
                && p.cell().text().length() == 1 && Character.isUpperCase(p.cell().text().charAt(0))).toList();
        assertEquals(games.brennan.dungeontrain.worldgen.TrainPhase.values().length, letters.size(),
            "every band's letter must be placed, none cut by the width");
        assertEquals(1, letters.stream().map(p -> p.rect().y()).distinct().count(), "all on one row");

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

    @Test
    @DisplayName("Built by is read-only in play and a picker in dev mode, per category")
    void builderLine() {
        VariantKey pen = VariantKey.of(PlotCategory.CARRIAGES, "pen", "pen");
        EditorTypeMenusPacket.Variant anon = variant("CARRIAGES", "pen", "pen", 10, 0, -1, 1, List.of());
        EditorTypeMenusPacket.Variant credited = anon.withBuilder("380df991f603344ca090369bad2a924a", "Mika");

        TemplateDataSheet.Line play = TemplateDataSheet.builderLine(credited, pen, false);
        assertEquals(EditorScreenLang.text(EditorScreenLang.SHEET_BUILDER), play.label());
        assertEquals("Mika", play.cells().get(0).text());
        assertNull(play.cells().get(0).action());

        TemplateDataSheet.Line dev = TemplateDataSheet.builderLine(anon, pen, true);
        assertEquals(EditorScreenLang.text(EditorScreenLang.SHEET_BUILDER_NONE), dev.cells().get(0).text());
        assertFalse(dev.cells().get(0).on());
        TemplateDataSheet.Action.PickBuilder pick =
            assertInstanceOf(TemplateDataSheet.Action.PickBuilder.class, dev.cells().get(0).action());
        assertEquals("dungeontrain editor builder pen", pick.prefix());

        // A room is addressed as <kind> <name>, like its label; a part has no verb at all.
        assertEquals("dungeontrain editor portals builder portal_room lobby",
            TemplateDataSheet.builderCommandPrefix(VariantKey.of(PlotCategory.PORTALS, "portal_room", "lobby")));
        assertEquals("dungeontrain editor contents builder tomes",
            TemplateDataSheet.builderCommandPrefix(VariantKey.of(PlotCategory.CONTENTS, "tomes", "tomes")));
        assertNull(TemplateDataSheet.builderCommandPrefix(VariantKey.of(PlotCategory.PARTS, "floor", "standard")));
        TemplateDataSheet.Line part = TemplateDataSheet.builderLine(credited,
            VariantKey.of(PlotCategory.PARTS, "floor", "standard"), true);
        assertNull(part.cells().get(0).action());
    }
}
