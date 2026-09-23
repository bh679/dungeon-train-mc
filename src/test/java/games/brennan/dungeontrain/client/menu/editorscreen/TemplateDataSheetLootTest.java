package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.builder.TemplateSummary;
import games.brennan.dungeontrain.client.menu.MenuTestLanguage;
import games.brennan.dungeontrain.editor.TemplateCells;
import games.brennan.dungeontrain.editor.TemplateLoot;
import net.minecraft.SharedConstants;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Lights and Loot lines: loot as item icons in value order, readable on hover, never clickable. */
@ExtendWith(MenuTestLanguage.class)
final class TemplateDataSheetLootTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static TemplateSummary summary(List<TemplateCells.LightBlock> lights, List<TemplateLoot.LootBlock> loot) {
        return new TemplateSummary(96, Vec3i.ZERO, 0, 0, 0, lights, loot);
    }

    private static final List<TemplateCells.LightBlock> LIGHTS = List.of(
        new TemplateCells.LightBlock(Blocks.TORCH, 4, 14),
        new TemplateCells.LightBlock(Blocks.LANTERN, 1, 15));

    private static final List<TemplateLoot.LootBlock> LOOT = List.of(
        new TemplateLoot.LootBlock(Blocks.BARREL, 1, TemplateLoot.Source.PREFAB, "fullgold", 100, false, 40,
            List.of(Items.GOLDEN_SWORD)),
        new TemplateLoot.LootBlock(Blocks.CHEST, 3, TemplateLoot.Source.POOL, "", 100, false, 10,
            List.of(Items.BREAD)),
        new TemplateLoot.LootBlock(Blocks.CHISELED_BOOKSHELF, 1, TemplateLoot.Source.POOL, "", 25, true, 2,
            List.of()));

    @Test
    @DisplayName("Lights is one icon per light kind with its count; 0 with none, pending before the tile is read")
    void lightsLine() {
        List<TemplateDataSheet.Cell> cells = TemplateDataSheet.lightsLine(summary(LIGHTS, List.of()), "…").cells();
        assertEquals(2, cells.size());
        assertEquals(Items.TORCH, cells.get(0).icon().getItem());
        assertEquals("4", cells.get(0).text(), "the count sits beside the icon");
        assertEquals(Items.LANTERN, cells.get(1).icon().getItem());
        assertEquals("", cells.get(1).text(), "a single one has no count");
        assertTrue(cells.get(0).tooltip().contains("14"), cells.get(0).tooltip());
        assertEquals("0", TemplateDataSheet.lightsLine(summary(List.of(), List.of()), "…").cells().get(0).text());
        assertEquals("…", TemplateDataSheet.lightsLine(null, "…").cells().get(0).text());
    }

    @Test
    @DisplayName("Loot is one icon per block, in value order, with count, variant mark and tooltip")
    void lootIcons() {
        TemplateDataSheet.Line line = TemplateDataSheet.lootLine(summary(List.of(), LOOT), "…");
        List<TemplateDataSheet.Cell> cells = line.cells();
        assertEquals(3, cells.size());
        assertEquals(Items.BARREL, cells.get(0).icon().getItem());
        assertEquals(Items.CHEST, cells.get(1).icon().getItem());
        assertEquals("3", cells.get(1).text(), "the count sits beside the icon");
        assertTrue(cells.get(0).on());
        assertFalse(cells.get(2).on(), "a variant-only block is marked");
        for (TemplateDataSheet.Cell c : cells) {
            assertTrue(c.isIcon());
            assertNull(c.action(), "read-only");
            assertTrue(c.tooltip() != null && !c.tooltip().isEmpty());
        }
        assertTrue(cells.get(0).tooltip().contains("fullgold"), cells.get(0).tooltip());
    }

    @Test
    @DisplayName("no loot reads as a dash, and the line stays text height")
    void noLoot() {
        TemplateDataSheet.Line line = TemplateDataSheet.lootLine(summary(List.of(), List.of()), "…");
        assertFalse(line.cells().get(0).isIcon());
    }

    @Test
    @DisplayName("small icons keep a text row's height, the cheapest dropped first, and hover hits them")
    void placeIcons() {
        TemplateDataSheet.Line loot = TemplateDataSheet.lootLine(summary(List.of(), LOOT), "…");
        TemplateDataSheet.Line after = TemplateDataSheet.Line.of("Weight", "1");
        FixedFont font = new FixedFont();
        int labelW = Math.max(font.width(loot.label()), font.width(after.label()));
        int left = 2 + labelW + TemplateDataSheet.LABEL_GAP;
        // Room for the barrel and the chest with its count, not the bookshelf too.
        int two = TemplateDataSheet.ICON + TemplateDataSheet.CELL_GAP
            + TemplateDataSheet.ICON + 1 + font.width("3");
        InventoryEditorLayout.Rect r = new InventoryEditorLayout.Rect(0, 0, left + two + 2, 200);
        List<TemplateDataSheet.Placed> placed = TemplateDataSheet.place(List.of(loot, after), r, font);

        assertEquals(3, placed.size(), "two icons and the weight");
        assertEquals(Items.BARREL, placed.get(0).cell().icon().getItem());
        assertEquals(Items.CHEST, placed.get(1).cell().icon().getItem());
        assertEquals(TemplateDataSheet.ICON + 2, placed.get(0).rect().w());
        assertEquals(placed.get(0).rect().y() + TemplateDataSheet.LINE_H, placed.get(2).rect().y(),
            "an icon line is a text line's height");
        InventoryEditorLayout.Rect icon = placed.get(0).rect();
        assertEquals(0, TemplateDataSheet.hit(placed, icon.x() + 4, icon.y() + 4), "hover finds the icon");
    }

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
