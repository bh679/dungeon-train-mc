package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.net.StageBlocksSyncPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Stages tab's rows, built from the roster's stage list. */
final class EditorStagesPageTest {

    private static EditorRosterPacket.StageEntry stage(String id, int min, int max, int mask, int unique) {
        EditorTypeMenusPacket.Variant v = new EditorTypeMenusPacket.Variant(
            id, EditorPlotLabelsPacket.NO_WEIGHT, min, max, mask, "stages", id, id, true, false);
        List<StageBlocksSyncPacket.BlockCount> blocks = new ArrayList<>();
        for (int i = 0; i < unique; i++) blocks.add(new StageBlocksSyncPacket.BlockCount("minecraft:b" + i, i + 1));
        return new EditorRosterPacket.StageEntry(v, blocks, unique, 2);
    }

    @Test
    @DisplayName("one row per stage: name cell selects it, gate cell summarises the gate, count cell counts blocks")
    void rows() {
        List<String> picked = new ArrayList<>();
        List<EditorStagesPage.Row> rows = EditorStagesPage.rows(
            List.of(stage("desert", 10, 40, 1, 3), stage("nether", 0, -1, 2, 0)), picked::add);
        assertEquals(2, rows.size());
        assertEquals("desert", rows.get(0).stageId());
        CommandMenuEntry[] cells = MenuRowPainter.cellsOf(rows.get(0).entry());
        assertEquals(3, cells.length);
        CommandMenuEntry.ClientAction name = assertInstanceOf(CommandMenuEntry.ClientAction.class, cells[EditorStagesPage.NAME_CELL]);
        assertEquals("desert", name.label());
        name.action().run();
        assertEquals(List.of("desert"), picked);
        CommandMenuEntry.Label gate = assertInstanceOf(CommandMenuEntry.Label.class, cells[EditorStagesPage.GATE_CELL]);
        assertTrue(gate.text().startsWith("lvl 10..40"), gate.text());
        assertInstanceOf(CommandMenuEntry.Label.class, cells[EditorStagesPage.COUNT_CELL]);
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
