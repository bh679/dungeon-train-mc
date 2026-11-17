package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.editor.VariantSlime;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry + labels of the Z menu's Slime popup for one slime, magma cube or End slime row — opened
 * from the Grow pill slot (a mob row has no Grow), drawn with the Grow popup's
 * {@link GrowthPopupLayout.Row}s.
 *
 * <p>A {@link #SECTION_SIZE} row of {@code 1 2 3 4} and, on an End slime row, a {@link #SECTION_TYPE}
 * row of its four types. Every button toggles; the lit ones are the pool a spawn picks from, and
 * none lit means any.</p>
 */
final class SlimePopupLayout {

    static final int SECTION_SIZE = 0;
    static final int SECTION_TYPE = 1;

    private SlimePopupLayout() {}

    /** The popup's rows, top to bottom: Size, then Type on an End slime row. */
    static List<GrowthPopupLayout.Row> rows(boolean endSlime, double panelW, double halfH) {
        double left = Math.max(-panelW / 2.0 + GrowthPopupLayout.PAD, -GrowthPopupLayout.POPUP_WIDTH / 2.0);
        double base = halfH + 2 * GrowthPopupLayout.PAD;
        int count = endSlime ? 2 : 1;
        List<GrowthPopupLayout.Row> rows = new ArrayList<>(count);
        String[] sizes = new String[VariantSlime.SIZES];
        for (int s = 0; s < sizes.length; s++) sizes[s] = Integer.toString(s + 1);
        double sizeBottom = base + (count - 1) * GrowthPopupLayout.ROW_HEIGHT;
        rows.add(new GrowthPopupLayout.Row(SECTION_SIZE, MenuLang.t("block_variant.slime_size"), sizes, -1,
            left, left + GrowthPopupLayout.POPUP_WIDTH, sizeBottom, sizeBottom + GrowthPopupLayout.ROW_HEIGHT));
        if (endSlime) {
            String[] types = new String[VariantSlime.TYPES.size()];
            for (int t = 0; t < types.length; t++) types[t] = typeName(t);
            rows.add(new GrowthPopupLayout.Row(SECTION_TYPE, MenuLang.t("block_variant.slime_type"), types, -1,
                left, left + GrowthPopupLayout.POPUP_WIDTH, base, base + GrowthPopupLayout.ROW_HEIGHT));
        }
        return rows;
    }

    /** Whether the button is lit. */
    static boolean isSelected(VariantSlime slime, GrowthPopupLayout.Row row, int option) {
        return row.section() == SECTION_TYPE ? slime.hasType(option) : slime.hasSize(option + 1);
    }

    /** {@code slime} with the clicked button toggled. */
    static VariantSlime toggled(VariantSlime slime, int section, int option) {
        return section == SECTION_TYPE ? slime.toggledType(option) : slime.toggledSize(option + 1);
    }

    /** The End slime type's button label. */
    static String typeName(int type) {
        return MenuLang.t("block_variant.slime_type." + VariantSlime.TYPES.get(type));
    }

    /**
     * Row pill label: {@code Size} when nothing is set, else the lit sizes ({@code 2,4}) and, on an End
     * slime row, the lit type ({@code Mossy}) or type count ({@code Type ×2}), joined by a dot.
     */
    static String shortLabel(VariantSlime slime) {
        if (slime.isDefault()) return MenuLang.t("block_variant.slime_size");
        List<String> parts = new ArrayList<>(2);
        if (slime.sizesSet()) {
            StringBuilder sb = new StringBuilder();
            for (int s : slime.litSizes()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(s);
            }
            parts.add(sb.toString());
        }
        if (slime.typesSet()) {
            List<Integer> types = slime.types();
            parts.add(types.size() == 1 ? typeName(types.get(0))
                : MenuLang.t("block_variant.slime_type") + " ×" + types.size());
        }
        return String.join(" · ", parts);
    }
}
