package games.brennan.dungeontrain.client.menu.plot;

import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer.CellKind;
import games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer.Hovered;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorWholeTypeMenus;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * The one type-level settings row a nav menu can carry: <b>"Whole group every N"</b>, drawn between
 * the tab strip and the variant rows on the WHOLE category's {@code Group} menu.
 *
 * <p>Its own class so {@link EditorTypeMenuRenderer} only learns how many chrome rows a menu has
 * ({@link #rows}) and where to hand off ({@link #draw}, {@link #hit}); the renderer is long past
 * the size where a new kind of row belongs inline.</p>
 */
final class EditorTypeMenuSettingsRow {

    private static final int VALUE_COLOR = EditorTypeMenuRenderer.HEADER_COLOR;

    private EditorTypeMenuSettingsRow() {}

    /** Only the WHOLE category's Group nav menu carries the row. */
    static boolean present(EditorTypeMenusPacket.Menu menu) {
        return menu.isNavMenu()
            && EditorCategory.WHOLE.id().equals(menu.activeCategoryId())
            && EditorWholeTypeMenus.GROUP_TYPE_NAME.equals(menu.typeName());
    }

    /**
     * The in-plot Group companion carries the setting in its header row instead, beside the title —
     * that is the panel an author actually looks at while standing in a group plot.
     */
    static boolean headerEvery(EditorTypeMenusPacket.Menu menu) {
        if (!menu.isCompanion() || !EditorWholeTypeMenus.GROUP_TYPE_NAME.equals(menu.typeName())) return false;
        return !menu.variants().isEmpty()
            && menu.variants().get(0).plotCategory() == games.brennan.dungeontrain.editor.PlotCategory.WHOLE_GROUP;
    }

    static int rows(EditorTypeMenusPacket.Menu menu) {
        return present(menu) ? 1 : 0;
    }

    /** Draw the row whose top edge is {@code rowTop}, across the panel's full width. */
    static void draw(PoseStack ps, MultiBufferSource buffer, Font font, EditorTypeMenusPacket.Menu menu,
                     Hovered hovered, double halfW, double rowTop) {
        double rowBottom = rowTop - EditorTypeMenuRenderer.ROW_H;
        double cy = (rowTop + rowBottom) / 2.0;
        EditorTypeMenuRenderer.drawQuad(ps, buffer, -halfW, rowBottom, halfW, rowTop, EditorTypeMenuRenderer.HEADER_BG);
        if (hovered.cell() == CellKind.WHOLE_EVERY) {
            EditorTypeMenuRenderer.drawQuad(ps, buffer, -halfW + 0.005, rowBottom + 0.005,
                halfW - 0.005, rowTop - 0.005, EditorTypeMenuRenderer.HOVER_COLOR);
        }
        EditorTypeMenuRenderer.drawCenteredText(ps, buffer, font, label(), 0, cy, VALUE_COLOR);
    }

    static Hovered hit(int menuIdx, EditorTypeMenusPacket.Menu menu, double halfW, double hitX) {
        if (hitX < -halfW || hitX > halfW) return Hovered.NONE;
        return new Hovered(menuIdx, -1, CellKind.WHOLE_EVERY);
    }

    /** "Whole group every N", or the off wording when N is 0. */
    static String label() {
        int every = EditorTypeMenuRenderer.wholeGroupEvery();
        return every > 0
            ? MenuLang.t("type_menu.whole_every", every)
            : MenuLang.t("type_menu.whole_every_off");
    }
}
