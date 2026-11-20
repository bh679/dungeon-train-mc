package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.editor.VariantBedColours;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry + labels of the Z menu's Colours popup for one white bed row — opened from the same pill
 * slot as Grow (a bed never grows), drawn with the Grow popup's {@link GrowthPopupLayout.Row}s.
 *
 * <p>A 4×4 grid of dye swatches in {@link DyeColor} id order: section {@code r}, option {@code c}
 * is colour {@code r * 4 + c}. A click switches that colour on or off; the last one left on stays
 * on. The renderer paints each button in its dye's colour ({@link #swatchColour}).</p>
 */
final class BedColourPopupLayout {

    static final int COLUMNS = 4;
    static final int ROWS = DyeColor.values().length / COLUMNS;

    private BedColourPopupLayout() {}

    /** The grid's rows, top to bottom; only the first carries the label. Buttons have no text. */
    static List<GrowthPopupLayout.Row> rows(double panelW, double halfH) {
        double left = Math.max(-panelW / 2.0 + GrowthPopupLayout.PAD, -GrowthPopupLayout.POPUP_WIDTH / 2.0);
        double base = halfH + 2 * GrowthPopupLayout.PAD;
        List<GrowthPopupLayout.Row> rows = new ArrayList<>(ROWS);
        for (int r = 0; r < ROWS; r++) {
            String[] buttons = new String[COLUMNS];
            java.util.Arrays.fill(buttons, "");
            double bottom = base + (ROWS - 1 - r) * GrowthPopupLayout.ROW_HEIGHT;
            rows.add(new GrowthPopupLayout.Row(r, r == 0 ? MenuLang.t("block_variant.bed_colours") : "",
                buttons, -1, left, left + GrowthPopupLayout.POPUP_WIDTH,
                bottom, bottom + GrowthPopupLayout.ROW_HEIGHT));
        }
        return rows;
    }

    /** The colour a grid button stands for. */
    static DyeColor colour(int section, int option) {
        return DyeColor.byId(section * COLUMNS + option);
    }

    /** Whether the grid button is one of the row's enabled colours. */
    static boolean isSelected(VariantBedColours colours, GrowthPopupLayout.Row row, int option) {
        return colours.has(colour(row.section(), option));
    }

    /** Opaque ARGB fill for a swatch: the dye's text colour. */
    static int swatchColour(DyeColor colour) {
        return 0xFF000000 | colour.getTextColor();
    }

    /** A tick colour that reads on {@code colour}'s swatch: black on light dyes, white on dark ones. */
    static int tickColour(DyeColor colour) {
        int rgb = colour.getTextColor();
        int luma = (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
        return luma > 140 ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** Row pill label: the colour's name when one is on (White by default), else {@code N col}. */
    static String shortLabel(VariantBedColours colours) {
        if (colours.count() == 1) {
            return Component.translatable("color.minecraft." + colours.colours().get(0).getSerializedName()).getString();
        }
        return MenuLang.t("block_variant.bed_colours_count", colours.count());
    }
}
