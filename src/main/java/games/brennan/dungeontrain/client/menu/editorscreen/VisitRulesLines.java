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
 * <p>Short cells, one setting a line, so a narrow pane cannot cut a setting off the end of a line:</p>
 * <pre>
 *   Order          Step 1
 *   Seen again     No
 *   After visits   3  → 6   (the arrow cell, the later weight, only while it is on)
 * </pre>
 */
final class VisitRulesLines {

    private VisitRulesLines() {}

    /** The lines for {@code v}, or none when it is not a dimensional sub-variant. */
    static List<TemplateDataSheet.Line> of(EditorTypeMenusPacket.Variant v, VariantKey key) {
        if (key == null || !key.isSubVariant() || key.category() != PlotCategory.PORTALS) return List.of();
        VisitRules r = v.visits();
        String root = "dungeontrain editor portals group visits " + key.parentId() + " " + key.modelName();
        List<TemplateDataSheet.Line> out = new java.util.ArrayList<>(4);

        String step = r.isOrdered()
            ? EditorScreenLang.text(EditorScreenLang.SHEET_ORDER_STEP, r.order())
            : EditorScreenLang.text(EditorScreenLang.SHEET_ORDER_ANY);
        out.add(line(EditorScreenLang.SHEET_ORDER, new TemplateDataSheet.Cell(step, stepper(root + " order"), r.isOrdered())
            .withTooltip(EditorScreenLang.text(EditorScreenLang.SHEET_ORDER_TIP))));

        out.add(line(EditorScreenLang.SHEET_SEEN_AGAIN, new TemplateDataSheet.Cell(yesNo(r.seenAgain()),
            new TemplateDataSheet.Action.Run(root + " seen-again " + (r.seenAgain() ? "off" : "on")), r.seenAgain())
            .withTooltip(EditorScreenLang.text(EditorScreenLang.SHEET_SEEN_AGAIN_TIP))));

        boolean on = r.afterVisits() > 0;
        String count = on ? Integer.toString(r.afterVisits())
            : EditorScreenLang.text(EditorScreenLang.SHEET_AFTER_VISITS_OFF);
        TemplateDataSheet.Cell after = new TemplateDataSheet.Cell(count, stepper(root + " after"), on)
            .withTooltip(EditorScreenLang.text(EditorScreenLang.SHEET_AFTER_VISITS_TIP));
        if (!on) {
            out.add(line(EditorScreenLang.SHEET_AFTER_VISITS, after));
        } else {
            // The later weight rides beside the count as a short arrow cell, so the line stays narrow
            // and no fourth line is lost off the bottom of a short pane.
            TemplateDataSheet.Cell later = new TemplateDataSheet.Cell("→ " + r.laterWeight(),
                stepper(root + " later-weight"), true)
                .withTooltip(EditorScreenLang.text(EditorScreenLang.SHEET_LATER_WEIGHT));
            out.add(new TemplateDataSheet.Line(EditorScreenLang.text(EditorScreenLang.SHEET_AFTER_VISITS),
                List.of(after, later)));
        }
        return List.copyOf(out);
    }

    private static TemplateDataSheet.Line line(String labelKey, TemplateDataSheet.Cell cell) {
        return new TemplateDataSheet.Line(EditorScreenLang.text(labelKey), List.of(cell));
    }

    /** Click steps up, shift- or right-click down, cmd-click types — every editor number's gestures. */
    private static TemplateDataSheet.Action.Step stepper(String prefix) {
        return new TemplateDataSheet.Action.Step(prefix, prefix + " dec", prefix + " inc");
    }

    private static String yesNo(boolean yes) {
        return EditorScreenLang.text(yes ? "gui.yes" : "gui.no");
    }
}
