package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.track.variant.VisitRules;

import java.util.List;

/**
 * The data sheet's lines for a dimensional sub-variant's visit rules ({@link VisitRules}): which
 * step of the order it is, whether it may be seen again, and the weight it draws at after the Xth
 * visit. Each cell runs one {@code portals group visits} verb; the sheet re-reads the roster after.
 *
 * <p>Two lines rather than three, so a short pane loses as little of what follows as it can:</p>
 * <pre>
 *   Order          [Step 1]  [Seen again: No]
 *   After visits   [3]       [then weight 4]
 * </pre>
 */
final class VisitRulesLines {

    private VisitRulesLines() {}

    /** The lines for {@code v}, or none when it is not a dimensional sub-variant. */
    static List<TemplateDataSheet.Line> of(EditorTypeMenusPacket.Variant v, VariantKey key) {
        if (key == null || !key.isSubVariant() || key.category() != PlotCategory.PORTALS) return List.of();
        VisitRules r = v.visits();
        String root = "dungeontrain editor portals group visits " + key.parentId() + " " + key.modelName();
        return List.of(orderLine(r, root), afterLine(r, root));
    }

    private static TemplateDataSheet.Line orderLine(VisitRules r, String root) {
        String step = r.isOrdered()
            ? EditorScreenLang.text(EditorScreenLang.SHEET_ORDER_STEP, r.order())
            : EditorScreenLang.text(EditorScreenLang.SHEET_ORDER_ANY);
        TemplateDataSheet.Cell order = new TemplateDataSheet.Cell(step, stepper(root + " order"), r.isOrdered())
            .withTooltip(EditorScreenLang.text(EditorScreenLang.SHEET_ORDER_TIP));
        String seen = EditorScreenLang.text(EditorScreenLang.SHEET_SEEN_AGAIN,
            yesNo(r.seenAgain()));
        TemplateDataSheet.Cell seenAgain = new TemplateDataSheet.Cell(seen,
            new TemplateDataSheet.Action.Run(root + " seen-again " + (r.seenAgain() ? "off" : "on")), r.seenAgain())
            .withTooltip(EditorScreenLang.text(EditorScreenLang.SHEET_SEEN_AGAIN_TIP));
        return new TemplateDataSheet.Line(EditorScreenLang.text(EditorScreenLang.SHEET_ORDER), List.of(order, seenAgain));
    }

    private static TemplateDataSheet.Line afterLine(VisitRules r, String root) {
        boolean on = r.afterVisits() > 0;
        String count = on ? Integer.toString(r.afterVisits())
            : EditorScreenLang.text(EditorScreenLang.SHEET_AFTER_VISITS_OFF);
        TemplateDataSheet.Cell after = new TemplateDataSheet.Cell(count, stepper(root + " after"), on)
            .withTooltip(EditorScreenLang.text(EditorScreenLang.SHEET_AFTER_VISITS_TIP));
        String label = EditorScreenLang.text(EditorScreenLang.SHEET_AFTER_VISITS);
        if (!on) return new TemplateDataSheet.Line(label, List.of(after));
        TemplateDataSheet.Cell later = new TemplateDataSheet.Cell(
            EditorScreenLang.text(EditorScreenLang.SHEET_LATER_WEIGHT, r.laterWeight()),
            stepper(root + " later-weight"), true)
            .withTooltip(EditorScreenLang.text(EditorScreenLang.SHEET_AFTER_VISITS_TIP));
        return new TemplateDataSheet.Line(label, List.of(after, later));
    }

    /** Click steps up, shift- or right-click down, cmd-click types — every editor number's gestures. */
    private static TemplateDataSheet.Action.Step stepper(String prefix) {
        return new TemplateDataSheet.Action.Step(prefix, prefix + " dec", prefix + " inc");
    }

    private static String yesNo(boolean yes) {
        return EditorScreenLang.text(yes ? "gui.yes" : "gui.no");
    }
}
