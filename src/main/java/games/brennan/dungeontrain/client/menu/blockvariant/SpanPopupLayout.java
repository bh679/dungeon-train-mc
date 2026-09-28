package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.editor.VariantSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry + labels of the Z menu's Span popup — the one layout the renderer draws and the
 * raycaster hit-tests, so a click can never land one button off.
 *
 * <p>Three stacked sections floating just above the panel (so no list row is covered), top to
 * bottom: <b>How many</b> (1 | 2 | R), <b>Position</b> (1 | 2 | R — only while How many is 1 or R)
 * and <b>Repeat</b> (Same | Random — only while How many is 2 or R). Hidden sections take no
 * space. Centred on the Span toolbar button and clamped to the panel's width.</p>
 */
final class SpanPopupLayout {

    static final int SECTION_COUNT = 0;
    static final int SECTION_POSITION = 1;
    static final int SECTION_FILL = 2;

    static final double ROW_HEIGHT = 0.26;
    static final double LABEL_WIDTH = 0.80;
    static final double BUTTONS_WIDTH = 1.35;
    static final double PAD = 0.02;
    static final double POPUP_WIDTH = PAD + LABEL_WIDTH + BUTTONS_WIDTH + PAD;

    private SpanPopupLayout() {}

    /** One visible section: its label, its buttons, and which one is selected. */
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

    /** The visible sections for the cell's current (resolved) span, top to bottom. */
    static List<Row> rows(double panelW, double halfH) {
        VariantSpan span = BlockVariantMenu.resolvedSpan();
        String random = MenuLang.t("block_variant.plane_random");
        List<Object[]> spec = new ArrayList<>(3);
        spec.add(new Object[] {SECTION_COUNT, MenuLang.t("block_variant.span_how_many"),
            new String[] {"1", "2", random}, span.count().ordinal()});
        if (span.usesPosition()) {
            spec.add(new Object[] {SECTION_POSITION, MenuLang.t("block_variant.span_position"),
                new String[] {"1", "2", random}, span.position().ordinal()});
        }
        if (span.usesFill()) {
            spec.add(new Object[] {SECTION_FILL, MenuLang.t("block_variant.span_repeat"),
                new String[] {MenuLang.t("block_variant.span_fill_same"),
                    MenuLang.t("block_variant.span_fill_random")}, span.fill().ordinal()});
        }

        List<BlockVariantMenu.CellKind> toolbar = BlockVariantMenu.toolbarCells();
        double cellW = panelW / toolbar.size();
        int idx = Math.max(0, toolbar.indexOf(BlockVariantMenu.CellKind.SPAN));
        double buttonCX = -panelW / 2.0 + (idx + 0.5) * cellW;
        double left = buttonCX - POPUP_WIDTH / 2.0;
        left = Math.max(-panelW / 2.0 + PAD, Math.min(left, panelW / 2.0 - PAD - POPUP_WIDTH));

        // Stack upward from just above the panel: the last section sits lowest.
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

    /** The span after clicking {@code option} in {@code section}, starting from {@code current}. */
    static VariantSpan apply(VariantSpan current, int section, int option) {
        return switch (section) {
            case SECTION_COUNT -> current.withCount(VariantSpan.Count.values()[option]);
            case SECTION_POSITION -> current.withPosition(VariantSpan.Position.values()[option]);
            default -> current.withFill(VariantSpan.Fill.values()[option]);
        };
    }

    /** Toolbar label for a resolved span, e.g. {@code 1·2}, {@code 2·S}, {@code R·1/S}. */
    static String shortLabel(VariantSpan span) {
        String random = MenuLang.t("block_variant.plane_random");
        String count = switch (span.count()) { case ONE -> "1"; case TWO -> "2"; case RANDOM -> random; };
        String pos = switch (span.position()) { case FIRST -> "1"; case SECOND -> "2"; case RANDOM -> random; };
        String fill = span.fill() == VariantSpan.Fill.SAME ? MenuLang.t("block_variant.span_same") : random;
        return switch (span.count()) {
            case ONE -> count + "·" + pos;
            case TWO -> count + "·" + fill;
            case RANDOM -> count + "·" + pos + "/" + fill;
        };
    }
}
