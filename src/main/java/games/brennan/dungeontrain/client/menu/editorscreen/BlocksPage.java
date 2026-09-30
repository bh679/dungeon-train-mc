package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The Blocks page: how many kinds and blocks the build uses, then every cell as a slot-sized icon
 * with its count, most used first. Standing in the build, a click re-skins a cell with the held
 * block; elsewhere the page is the saved file's tally, to look at.
 */
final class BlocksPage {

    private BlocksPage() {}

    /** Draw this page's slice of {@code grid}; the hovered cell (absolute index) lit, -1 for none. */
    static void draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect area, LootGrid grid,
                     List<BlockGroupsState.Entry> entries, int hovered) {
        int total = 0;
        for (BlockGroupsState.Entry e : entries) total += e.count();
        String header = EditorScreenLang.text(EditorScreenLang.BLOCKS_PAGE_HEADER, entries.size(), total);
        g.drawString(font, font.plainSubstrByWidth(header, area.w() - 4), area.x() + 2, area.y() + 2,
            TemplateDataSheet.LABEL, false);
        for (int i = 0; i < grid.shown(); i++) {
            int index = grid.first() + i;
            int x = grid.cellX(i);
            int y = grid.cellY(i);
            g.fill(x, y, x + LootGrid.CELL - 1, y + LootGrid.CELL - 1,
                hovered == index ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
            BlockGroupsState.Entry e = entries.get(index);
            ItemStack stack = new ItemStack(e.block().asItem());
            g.renderItem(stack, x + 1, y + 1);
            g.renderItemDecorations(font, stack, x + 1, y + 1, e.count() > 1 ? compact(e.count()) : "");
        }
    }

    /** A count that fits a slot's corner: 1234 reads 1.2k. */
    static String compact(int n) {
        if (n < 1000) return Integer.toString(n);
        if (n < 10_000) return String.format(java.util.Locale.ROOT, "%.1fk", n / 1000.0);
        return (n / 1000) + "k";
    }

    /** The tooltip for cell {@code index}: its block and count, then what a click would do. */
    static List<String> tooltip(List<BlockGroupsState.Entry> entries, int index, boolean live, ItemStack held) {
        if (index < 0 || index >= entries.size()) return List.of();
        BlockGroupsState.Entry e = entries.get(index);
        String name = e.block().getName().getString();
        String head = e.count() > 1 ? name + " ×" + e.count() : name;
        String action;
        if (!live) action = EditorScreenLang.text(EditorScreenLang.BLOCKS_PAGE_GO_HERE);
        else if (held.isEmpty() || !(held.getItem() instanceof net.minecraft.world.item.BlockItem)) {
            action = EditorScreenLang.text(EditorScreenLang.BLOCKS_PAGE_HOLD);
        } else action = EditorScreenLang.text(EditorScreenLang.BLOCKS_PAGE_CLICK, held.getHoverName().getString());
        return List.of(head, action);
    }
}
