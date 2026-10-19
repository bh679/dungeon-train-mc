package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.MenuTestLanguage;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.net.StageBlocksSyncPacket;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Stages tab's rows, built from the roster's stage list. */
@ExtendWith(MenuTestLanguage.class)
final class EditorStagesPageTest {

    private static EditorRosterPacket.StageEntry stage(String id, int min, int max, int mask, int unique) {
        EditorTypeMenusPacket.Variant v = new EditorTypeMenusPacket.Variant(
            id, EditorPlotLabelsPacket.NO_WEIGHT, min, max, mask, "stages", id, id, true, false);
        List<StageBlocksSyncPacket.BlockCount> blocks = new ArrayList<>();
        for (int i = 0; i < unique; i++) blocks.add(new StageBlocksSyncPacket.BlockCount("minecraft:b" + i, i + 1));
        return new EditorRosterPacket.StageEntry(v, blocks, unique, List.of("floor:a", "walls:b"));
    }

    private static EditorRosterPacket.StageEntry grouped(String id, int min, int max, String group) {
        EditorRosterPacket.StageEntry s = stage(id, min, max, 1, 0);
        return new EditorRosterPacket.StageEntry(s.stage(), s.blocks(), s.totalUnique(), s.parts(), s.palette(), group);
    }

    private static List<EditorRosterPacket.StageEntry> withWood() {
        return List.of(stage("stone", 0, -1, 1, 0), grouped("spruce", 436, 465, "wood"),
            stage("desert", 10, 40, 1, 0), grouped("acacia", 176, 205, "wood"), grouped("birch", 161, 175, "wood"));
    }

    @Test
    @DisplayName("a family folds to one row: it shows its first member, spans every band, and lists the rest")
    void familyFolds() {
        List<String> picked = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        List<EditorStagesPage.Row> rows = EditorStagesPage.groupedRows(withWood(), EditorStagesPage.Sort.DEFAULT,
            picked::add, g -> null, opened::add);
        assertEquals(List.of("desert", "stone", "acacia"), rows.stream().map(EditorStagesPage.Row::stageId).toList(),
            "Wood sorts by its own name, after stone");
        EditorStagesPage.Row wood = rows.get(2);
        assertTrue(wood.isGroup());
        assertEquals("wood", wood.group());
        assertEquals(List.of("acacia", "birch", "spruce"), wood.memberIds());
        assertTrue(wood.stands("birch"), "a member being shown marks the family chosen");
        assertFalse(rows.get(0).stands("birch"));
        CommandMenuEntry[] cells = MenuRowPainter.cellsOf(wood.entry());
        CommandMenuEntry.ClientAction name = assertInstanceOf(CommandMenuEntry.ClientAction.class, cells[EditorStagesPage.NAME_CELL]);
        assertEquals("Wood: acacia" + EditorStagesPage.DROPDOWN, name.label());
        assertEquals("161 → 465", ((CommandMenuEntry.Label) cells[EditorStagesPage.LEVEL_CELL]).text());
        name.action().run();
        assertEquals(List.of("acacia"), picked);
        assertEquals(List.of("wood"), opened);
    }

    @Test
    @DisplayName("a family shows its remembered pick; by level it sits at its lowest band")
    void familyPickAndLevelSort() {
        EditorStagesPage.Sort byLevel = EditorStagesPage.Sort.DEFAULT.toggled(EditorStagesPage.Column.LEVEL);
        List<EditorStagesPage.Row> rows = EditorStagesPage.groupedRows(withWood(), byLevel, s -> { },
            g -> "spruce", g -> { });
        assertEquals(List.of("stone", "desert", "spruce"), rows.stream().map(EditorStagesPage.Row::stageId).toList());
        assertEquals(List.of("birch", "acacia", "spruce"), rows.get(2).memberIds(), "members keep the level order");
        assertEquals("Wood: spruce" + EditorStagesPage.DROPDOWN,
            ((CommandMenuEntry.ClientAction) MenuRowPainter.cellsOf(rows.get(2).entry())[EditorStagesPage.NAME_CELL]).label());
        List<EditorStagesPage.Row> unknown = EditorStagesPage.groupedRows(withWood(), byLevel, s -> { },
            g -> "nether", g -> { });
        assertEquals("birch", unknown.get(2).stageId(), "a pick that is not a member falls back to the first");
    }

    @Test
    @DisplayName("an open-topped member leaves the family's band open")
    void familyOpenTop() {
        List<EditorStagesPage.Row> rows = EditorStagesPage.groupedRows(
            List.of(grouped("a", 5, 10, "wood"), grouped("b", 20, -1, "wood")), EditorStagesPage.Sort.DEFAULT,
            s -> { }, g -> null, g -> { });
        assertEquals(1, rows.size());
        assertEquals("5 → " + EditorStagesPage.OPEN_MAX,
            ((CommandMenuEntry.Label) MenuRowPainter.cellsOf(rows.get(0).entry())[EditorStagesPage.LEVEL_CELL]).text());
    }

    @Test
    @DisplayName("one row per stage: the title selects it, the level cell reads min → max")
    void rows() {
        List<String> picked = new ArrayList<>();
        List<EditorStagesPage.Row> rows = EditorStagesPage.rows(
            List.of(stage("desert", 10, 40, 1, 3), stage("nether", 0, -1, 2, 0)), picked::add);
        assertEquals(2, rows.size());
        assertEquals("desert", rows.get(0).stageId());
        CommandMenuEntry[] cells = MenuRowPainter.cellsOf(rows.get(0).entry());
        assertEquals(2, cells.length, "a title and a level band, no count");
        CommandMenuEntry.ClientAction name = assertInstanceOf(CommandMenuEntry.ClientAction.class, cells[EditorStagesPage.NAME_CELL]);
        assertEquals("desert", name.label());
        name.action().run();
        assertEquals(List.of("desert"), picked);
        CommandMenuEntry.Label level = assertInstanceOf(CommandMenuEntry.Label.class, cells[EditorStagesPage.LEVEL_CELL]);
        assertEquals("10 → 40", level.text());
        assertEquals("0 → " + EditorStagesPage.OPEN_MAX,
            ((CommandMenuEntry.Label) MenuRowPainter.cellsOf(rows.get(1).entry())[EditorStagesPage.LEVEL_CELL]).text());
    }

    private static List<String> order(EditorStagesPage.Sort sort) {
        List<EditorStagesPage.Row> rows = EditorStagesPage.rows(List.of(
            stage("stone", 0, -1, 1, 0), stage("desert", 10, 40, 1, 0),
            stage("acacia", 176, 205, 1, 0), stage("nether", 10, 30, 1, 0)), sort, s -> { });
        return rows.stream().map(EditorStagesPage.Row::stageId).toList();
    }

    @Test
    @DisplayName("the Name title sorts alphabetically, the Level title by band; a second click flips either")
    void sorting() {
        assertEquals(List.of("acacia", "desert", "nether", "stone"), order(EditorStagesPage.Sort.DEFAULT));
        assertEquals(List.of("stone", "nether", "desert", "acacia"),
            order(EditorStagesPage.Sort.DEFAULT.toggled(EditorStagesPage.Column.NAME)));
        EditorStagesPage.Sort byLevel = EditorStagesPage.Sort.DEFAULT.toggled(EditorStagesPage.Column.LEVEL);
        assertEquals(List.of("stone", "nether", "desert", "acacia"), order(byLevel), "same min: the lower max first");
        assertEquals(List.of("acacia", "desert", "nether", "stone"), order(byLevel.toggled(EditorStagesPage.Column.LEVEL)));
        assertEquals(new EditorStagesPage.Sort(EditorStagesPage.Column.NAME, false),
            byLevel.toggled(EditorStagesPage.Column.NAME), "switching titles starts ascending again");
    }

    @Test
    @DisplayName("the titles row marks the sorting title with its direction and resorts on click")
    void header() {
        List<EditorStagesPage.Column> clicked = new ArrayList<>();
        CommandMenuEntry[] cells = MenuRowPainter.cellsOf(EditorStagesPage.header(
            new EditorStagesPage.Sort(EditorStagesPage.Column.LEVEL, true), clicked::add));
        assertEquals(2, cells.length);
        CommandMenuEntry.ClientAction name = assertInstanceOf(CommandMenuEntry.ClientAction.class, cells[0]);
        CommandMenuEntry.ClientAction level = assertInstanceOf(CommandMenuEntry.ClientAction.class, cells[1]);
        assertFalse(name.label().endsWith(EditorStagesPage.ASCENDING) || name.label().endsWith(EditorStagesPage.DESCENDING));
        assertTrue(level.label().endsWith(EditorStagesPage.DESCENDING), level.label());
        assertTrue(level.highlighted());
        name.action().run();
        assertEquals(List.of(EditorStagesPage.Column.NAME), clicked);
    }

    @Test
    @DisplayName("an open-ended gate reads as ..all")
    void openGate() {
        assertTrue(EditorStagesPage.gateSummary(stage("nether", 0, -1, 2, 0)).startsWith("lvl 0..all"));
    }

    @Test
    @DisplayName("no stages, no rows")
    void empty() {
        assertTrue(EditorStagesPage.rows(List.of(), s -> { }).isEmpty());
    }
}
