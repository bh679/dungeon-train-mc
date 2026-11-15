package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.editor.VariantCharges;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry + labels of the Z menu's Charge popup for one respawn anchor row — opened from the same
 * pill slot as Grow (an anchor never grows), drawn with the Grow popup's {@link GrowthPopupLayout.Row}s.
 *
 * <p>Stacked sections, top to bottom: <b>Charge</b> ({@code As is | Range}), then — only while
 * ranged — <b>Min</b> and <b>Max</b>, one number cell each (0..{@value VariantCharges#MAX}),
 * typed or stepped like the Grow popup's.</p>
 */
final class ChargePopupLayout {

    static final int SECTION_MODE = 0;
    static final int SECTION_MIN = 1;
    static final int SECTION_MAX = 2;

    private static final int OPTION_AS_PLACED = 0;
    private static final int OPTION_RANGE = 1;

    private ChargePopupLayout() {}

    /** Whether {@code section} is a number cell (Min / Max) rather than a row of choices. */
    static boolean isNumber(int section) {
        return section == SECTION_MIN || section == SECTION_MAX;
    }

    /** The visible sections for {@code charges}, top to bottom, positioned like the Grow popup. */
    static List<GrowthPopupLayout.Row> rows(VariantCharges charges, double panelW, double halfH) {
        List<Object[]> spec = new ArrayList<>(3);
        spec.add(new Object[] {SECTION_MODE, MenuLang.t("block_variant.charges"),
            new String[] {MenuLang.t("block_variant.as_placed"), MenuLang.t("block_variant.charges_range")},
            charges.ranged() ? OPTION_RANGE : OPTION_AS_PLACED});
        if (charges.ranged()) {
            spec.add(new Object[] {SECTION_MIN, MenuLang.t("block_variant.grow_min"),
                new String[] {Integer.toString(charges.min())}, -1});
            spec.add(new Object[] {SECTION_MAX, MenuLang.t("block_variant.grow_max"),
                new String[] {Integer.toString(charges.max())}, -1});
        }
        double left = Math.max(-panelW / 2.0 + GrowthPopupLayout.PAD, -GrowthPopupLayout.POPUP_WIDTH / 2.0);
        double base = halfH + 2 * GrowthPopupLayout.PAD;
        List<GrowthPopupLayout.Row> rows = new ArrayList<>(spec.size());
        for (int i = 0; i < spec.size(); i++) {
            Object[] s = spec.get(i);
            double bottom = base + (spec.size() - 1 - i) * GrowthPopupLayout.ROW_HEIGHT;
            rows.add(new GrowthPopupLayout.Row((int) s[0], (String) s[1], (String[]) s[2], (int) s[3],
                left, left + GrowthPopupLayout.POPUP_WIDTH, bottom, bottom + GrowthPopupLayout.ROW_HEIGHT));
        }
        return rows;
    }

    /** The option after clicking {@code option} in the Charge section; Range starts at the full 1-4 span. */
    static VariantCharges applyMode(VariantCharges current, int option) {
        if (option == OPTION_AS_PLACED) return VariantCharges.AS_PLACED;
        return current.ranged() ? current : VariantCharges.range(current.min(), current.max());
    }

    /** The current value of a Min / Max section. */
    static int value(VariantCharges charges, int section) {
        return section == SECTION_MIN ? charges.min() : charges.max();
    }

    /**
     * {@code current} with Min or Max set to {@code value}, clamped to 0..{@value VariantCharges#MAX};
     * the other bound moves along so {@code min ≤ max} always holds.
     */
    static VariantCharges withValue(VariantCharges current, int section, int value) {
        int v = Math.max(0, Math.min(VariantCharges.MAX, value));
        return section == SECTION_MIN
            ? current.withRange(v, Math.max(v, current.max()))
            : current.withRange(Math.min(current.min(), v), v);
    }

    /** Row pill label: {@code As is} when as placed, else the range ({@code 1-4}, or {@code 3}). */
    static String shortLabel(VariantCharges charges) {
        if (charges.isDefault()) return MenuLang.t("block_variant.as_placed");
        return charges.min() == charges.max()
            ? Integer.toString(charges.min())
            : charges.min() + "-" + charges.max();
    }
}
