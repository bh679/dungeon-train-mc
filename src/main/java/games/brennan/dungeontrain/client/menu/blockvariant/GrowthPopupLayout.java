package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.editor.GrowthShapes;
import games.brennan.dungeontrain.editor.VariantGrowth;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry + labels of the Z menu's Grow popup for one row — the one layout the renderer draws and
 * the raycaster hit-tests, the same contract as {@link SpanPopupLayout}.
 *
 * <p>Stacked sections floating just above the panel, top to bottom: <b>Grow</b> (Off | On), then —
 * only while on — <b>Min</b> and <b>Max</b> ({@code − n +}), <b>Direction</b> (Up | Down, only for
 * blocks that grow both ways) and <b>Tip</b> (On | Off, only for blocks with a tip form).</p>
 */
final class GrowthPopupLayout {

    static final int SECTION_ON = 0;
    static final int SECTION_MIN = 1;
    static final int SECTION_MAX = 2;
    static final int SECTION_DIR = 3;
    static final int SECTION_TIP = 4;

    /** Option index of a stepper's middle (value) button — shown, never an action. */
    static final int STEPPER_VALUE = 1;

    static final double ROW_HEIGHT = SpanPopupLayout.ROW_HEIGHT;
    static final double LABEL_WIDTH = SpanPopupLayout.LABEL_WIDTH;
    static final double BUTTONS_WIDTH = SpanPopupLayout.BUTTONS_WIDTH;
    static final double PAD = SpanPopupLayout.PAD;
    static final double POPUP_WIDTH = SpanPopupLayout.POPUP_WIDTH;

    private GrowthPopupLayout() {}

    /** One visible section: its label, its buttons, and which one is selected ({@code -1} = none). */
    record Row(int section, String label, String[] buttons, int selected,
               double left, double right, double bottom, double top) {

        double buttonsLeft() { return left + PAD + LABEL_WIDTH; }

        double buttonWidth() { return BUTTONS_WIDTH / buttons.length; }

        double buttonLeft(int i) { return buttonsLeft() + i * buttonWidth(); }
    }

    /** The whole popup rectangle {@code {left, right, bottom, top}} for the visible rows. */
    static double[] rect(List<Row> rows) {
        Row top = rows.get(0);
        Row bottom = rows.get(rows.size() - 1);
        return new double[] {top.left(), top.right(), bottom.bottom() - PAD, top.top() + PAD};
    }

    /** The visible sections for {@code growth} on a row holding {@code state}, top to bottom. */
    static List<Row> rows(VariantGrowth growth, BlockState state, double panelW, double halfH) {
        String on = MenuLang.t("block_variant.active_on");
        String off = MenuLang.t("block_variant.active_off");
        List<Object[]> spec = new ArrayList<>(5);
        spec.add(new Object[] {SECTION_ON, MenuLang.t("block_variant.grow"),
            new String[] {off, on}, growth.on() ? 1 : 0});
        if (growth.on()) {
            spec.add(new Object[] {SECTION_MIN, MenuLang.t("block_variant.grow_min"),
                new String[] {"−", Integer.toString(growth.min()), "+"}, -1});
            spec.add(new Object[] {SECTION_MAX, MenuLang.t("block_variant.grow_max"),
                new String[] {"−", Integer.toString(growth.max()), "+"}, -1});
            if (GrowthShapes.growsBothWays(state)) {
                spec.add(new Object[] {SECTION_DIR, MenuLang.t("block_variant.grow_dir"),
                    new String[] {MenuLang.t("block_variant.grow_up"), MenuLang.t("block_variant.grow_down")},
                    growth.dir().ordinal()});
            }
            if (GrowthShapes.hasTip(state)) {
                spec.add(new Object[] {SECTION_TIP, MenuLang.t("block_variant.grow_tip"),
                    new String[] {on, off}, growth.tip() ? 0 : 1});
            }
        }

        // Centred over the panel, clamped inside it.
        double left = Math.max(-panelW / 2.0 + PAD, -POPUP_WIDTH / 2.0);
        List<Row> rows = new ArrayList<>(spec.size());
        double base = halfH + 2 * PAD;
        for (int i = 0; i < spec.size(); i++) {
            Object[] s = spec.get(i);
            int fromBottom = spec.size() - 1 - i;
            double bottom = base + fromBottom * ROW_HEIGHT;
            rows.add(new Row((int) s[0], (String) s[1], (String[]) s[2], (int) s[3],
                left, left + POPUP_WIDTH, bottom, bottom + ROW_HEIGHT));
        }
        return rows;
    }

    /** Hit {@code secondary} for a button: {@code section * 10 + option}. */
    static int encode(int section, int option) {
        return section * 10 + option;
    }

    /**
     * The growth after clicking {@code option} in {@code section}, starting from {@code current}.
     * Turning growth on starts at 2–4 so the click visibly does something; the steppers keep
     * {@code min ≤ max} by moving the other bound along.
     */
    static VariantGrowth apply(VariantGrowth current, BlockState state, int section, int option) {
        return switch (section) {
            case SECTION_ON -> option == 1
                ? VariantGrowth.of(Math.max(2, current.min()), Math.max(4, current.max()),
                    GrowthShapes.effectiveDir(state, current.dir()), current.tip())
                : VariantGrowth.NONE;
            case SECTION_MIN -> {
                int min = step(current.min(), option);
                yield current.withRange(min, Math.max(min, current.max()));
            }
            case SECTION_MAX -> {
                int max = step(current.max(), option);
                yield current.withRange(Math.min(current.min(), max), max);
            }
            case SECTION_DIR -> current.withDir(VariantGrowth.Dir.values()[option]);
            default -> current.withTip(option == 0);
        };
    }

    private static int step(int value, int option) {
        if (option == STEPPER_VALUE) return value;
        int next = option == 0 ? value - 1 : value + 1;
        return Math.max(1, Math.min(VariantGrowth.MAX_LENGTH, next));
    }

    /** Row pill label: the key word when off, e.g. {@code ↓2-5} when on. */
    static String shortLabel(VariantGrowth growth, BlockState state) {
        if (!growth.on()) return MenuLang.t("block_variant.grow");
        String arrow = GrowthShapes.effectiveDir(state, growth.dir()) == VariantGrowth.Dir.UP ? "↑" : "↓";
        return growth.min() == growth.max()
            ? arrow + growth.min()
            : arrow + growth.min() + "-" + growth.max();
    }
}
