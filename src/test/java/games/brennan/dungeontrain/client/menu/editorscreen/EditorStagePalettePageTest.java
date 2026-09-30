package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.MenuTestLanguage;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.StagePaletteEditPacket;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Palette and Stone pages' rows, from the panel's own placeholder layout. */
@ExtendWith(MenuTestLanguage.class)
final class EditorStagePalettePageTest {

    private static final EditorRosterPacket.Palette PALETTE =
        new EditorRosterPacket.Palette(List.of(), "spruce", "deepslate", true, false);

    @Test
    @DisplayName("Palette page: Solid, Shapes, Wood and Colours sections, cells wrapped to the column width")
    void paletteRows() {
        List<EditorStagePalettePage.Row> rows = EditorStagePalettePage.paletteRows(PALETTE, 4);
        assertEquals(EditorStagePalettePage.Kind.HEADING, rows.get(0).kind());
        // 10 solid cells at 4 across = 3 rows
        assertEquals(EditorStagePalettePage.Kind.CELLS, rows.get(1).kind());
        assertEquals(List.of("stage_block_1", "stage_block_2", "stage_block_3", "stage_block_4"), rows.get(1).names());
        assertEquals(2, rows.get(3).names().size());
        assertEquals(EditorStagePalettePage.Kind.HEADING, rows.get(4).kind());
        // 6 shapes at 4 across = 2 rows
        assertEquals(4, rows.get(5).names().size());
        assertEquals(2, rows.get(6).names().size());
        EditorStagePalettePage.Row wood = rows.get(7);
        assertEquals(EditorStagePalettePage.Kind.FAMILY, wood.kind());
        assertEquals(StagePaletteEditPacket.Op.SET_WOOD, wood.familyOp());
        assertTrue(wood.text().endsWith("spruce" + EditorStagePalettePage.LOCK), wood.text());
        int colours = rows.size() - 5;
        assertEquals(EditorStagePalettePage.Kind.HEADING, rows.get(colours).kind(), "Colours heading");
        int woodCells = rows.subList(8, colours).stream().mapToInt(r -> r.names().size()).sum();
        assertEquals(14, woodCells, "the whole wood set");
        List<EditorStagePalettePage.Row> colourRows = rows.subList(colours + 1, rows.size());
        for (EditorStagePalettePage.Row r : colourRows) {
            assertEquals(EditorStagePalettePage.Kind.LABELLED, r.kind(), r.text());
        }
        assertEquals(List.of("stage_terracotta_primary", "stage_terracotta_secondary", "stage_terracotta_background",
            "stage_glazed_terracotta"), colourRows.get(0).names());
        assertEquals(List.of("stage_concrete_primary", "stage_concrete_secondary", "stage_concrete_background"),
            colourRows.get(1).names());
        assertEquals(List.of("stage_glass_primary", "stage_glass_secondary"), colourRows.get(2).names());
        assertEquals(List.of("stage_glass_pane_primary", "stage_glass_pane_secondary"), colourRows.get(3).names());
        int all = rows.stream().mapToInt(r -> r.names().size()).sum();
        assertEquals(10 + 6 + 14 + 11, all);
    }

    @Test
    @DisplayName("Stone page: the family heading (unlocked, no lock mark) then one labelled row of four per kind")
    void stoneRows() {
        List<EditorStagePalettePage.Row> rows = EditorStagePalettePage.stoneRows(PALETTE);
        EditorStagePalettePage.Row stone = rows.get(0);
        assertEquals(EditorStagePalettePage.Kind.FAMILY, stone.kind());
        assertEquals(StagePaletteEditPacket.Op.SET_STONE, stone.familyOp());
        assertTrue(stone.text().endsWith("deepslate"), stone.text());
        assertEquals(8, rows.size(), "heading + 7 kinds");
        for (EditorStagePalettePage.Row r : rows.subList(1, rows.size())) {
            assertEquals(EditorStagePalettePage.Kind.LABELLED, r.kind());
            assertEquals(4, r.names().size(), r.text());
        }
        assertEquals(List.of("stage_stone_cobbled", "stage_stone_cobbled_stairs", "stage_stone_cobbled_slab",
            "stage_stone_cobbled_wall"), rows.get(1).names());
        int all = rows.stream().mapToInt(r -> r.names().size()).sum();
        assertEquals(28, all);
    }

    @Test
    @DisplayName("a palette with no family yet reads as a dash")
    void noFamily() {
        assertTrue(EditorStagePalettePage.familyText(EditorScreenLang.STAGES_PALETTE_WOOD, "", false).endsWith("—"));
    }
}
