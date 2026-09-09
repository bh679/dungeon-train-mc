package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.StagePickerScreen;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The Layout tab's rows: the spawn table, one section per roster type.
 *
 * <p>Every variant is a row of six columns — name · weight − · value · + · stage · move — so the
 * columns line up down the whole list; a column a row has no use for is a blank label. Group
 * members sit under their parent one step in, with the parent's own share as a first "(self)"
 * row, since that is how the server addresses it. Pure: built from a roster snapshot and the
 * folded-section set, and nothing else, so the pane can cache the result on those two.</p>
 *
 * <p>Selection and the standing plot are deliberately not baked into the entries — the pane draws
 * those two marks itself, which is what keeps the rows cacheable across hover and clicks.</p>
 */
public final class EditorLayoutPage {

    /** One row: its entry, the template it is about (null for a section header), its indent, its section. */
    public record Row(CommandMenuEntry entry, VariantKey key, int depth, String sectionId) {
        public boolean isHeader() {
            return key == null;
        }
    }

    /** Dividers between the six columns, as fractions of the row. */
    static final List<Double> BOUNDS = List.of(0.46, 0.53, 0.63, 0.70, 0.86);
    static final String OPEN = "▾";
    static final String FOLDED = "▸";
    private static final CommandMenuEntry BLANK = new CommandMenuEntry.Label("");

    private EditorLayoutPage() {}

    /** A section's id, stable across roster refreshes: the category and type it lists. */
    public static String sectionId(EditorRosterPacket.Group g) {
        return g.categoryId() + "/" + g.typeName();
    }

    /**
     * Every row, top to bottom.
     *
     * @param collapsed sections that show only their header
     * @param select    what a name cell does — hands over the row's key
     * @param toggle    what a header does — hands over the section id
     */
    public static List<Row> rows(EditorRosterIndex index, Set<String> collapsed,
                                 Consumer<VariantKey> select, Consumer<String> toggle) {
        List<Row> out = new ArrayList<>();
        if (index == null) return out;
        for (EditorRosterPacket.Group g : index.groups()) {
            String id = sectionId(g);
            boolean folded = collapsed != null && collapsed.contains(id);
            out.add(header(g, id, folded, toggle));
            if (folded) continue;
            for (EditorRosterIndex.Tile tile : EditorRosterIndex.tiles(g)) {
                addVariant(out, g, tile, id, select);
            }
        }
        return out;
    }

    private static Row header(EditorRosterPacket.Group g, String id, boolean folded, Consumer<String> toggle) {
        String label = (folded ? FOLDED : OPEN) + " "
            + EditorScreenLang.text(EditorScreenLang.LAYOUT_SECTION, g.typeName(), g.entries().size());
        return new Row(new CommandMenuEntry.ClientAction(label, () -> toggle.accept(id), false), null, 0, id);
    }

    private static void addVariant(List<Row> out, EditorRosterPacket.Group g, EditorRosterIndex.Tile tile,
                                   String sectionId, Consumer<VariantKey> select) {
        VariantKey key = tile.key();
        EditorTypeMenusPacket.Variant v = tile.variant();
        out.add(new Row(cells(
            name(v.displayName(), key, select),
            weightCells(key, v.weight()),
            stageCell(v, key),
            moveCell(key)), key, 0, sectionId));
        if (!tile.isGroup()) return;

        // The parent's own share of its group, addressed through the member verb with itself as
        // the member — the same row the browser shows as the "(self)" tile.
        if (tile.selfWeight() != EditorPlotLabelsPacket.NO_WEIGHT) {
            VariantKey self = new VariantKey(key.category(), key.modelId(), key.modelName(), key.displayName());
            out.add(new Row(cells(
                name(EditorScreenLang.text(EditorScreenLang.TILE_SELF, v.displayName()), key, select),
                weightCells(self, tile.selfWeight()),
                BLANK, BLANK), key, 1, sectionId));
        }
        for (EditorRosterIndex.Tile member : EditorRosterIndex.subVariants(tile, EditorRosterIndex.Filters.NONE, "")) {
            VariantKey mk = member.key();
            EditorTypeMenusPacket.Variant mv = member.variant();
            out.add(new Row(cells(
                name(mv.displayName(), mk, select),
                weightCells(mk, mv.weight()),
                memberStageCell(g, tile, mv, mk),
                moveCell(mk)), mk, 1, sectionId));
        }
    }

    private static CommandMenuEntry cells(CommandMenuEntry name, List<CommandMenuEntry> weight,
                                          CommandMenuEntry stage, CommandMenuEntry move) {
        List<CommandMenuEntry> all = new ArrayList<>(6);
        all.add(name);
        all.addAll(weight);
        all.add(stage);
        all.add(move);
        return new CommandMenuEntry.Cells(all, BOUNDS);
    }

    private static CommandMenuEntry name(String label, VariantKey key, Consumer<VariantKey> select) {
        return new CommandMenuEntry.ClientAction(label, () -> select.accept(key), false);
    }

    /**
     * − · value · + from the same stepper the data sheet takes apart. No weight pool (parts) means
     * three blanks; a pool the commands cannot reach yet (track members) shows the number read-only.
     */
    static List<CommandMenuEntry> weightCells(VariantKey key, int weight) {
        if (weight == EditorPlotLabelsPacket.NO_WEIGHT) return List.of(BLANK, BLANK, BLANK);
        TemplateDataSheet.Stepper stepper = TemplateDataSheet.Stepper.of(EditorScreenActions.weightRow(key, weight));
        if (stepper == null) {
            return List.of(BLANK, new CommandMenuEntry.Label(Integer.toString(weight)), BLANK);
        }
        return List.of(
            new CommandMenuEntry.Stay("−", stepper.dec()),
            new CommandMenuEntry.TypeArg(Integer.toString(weight), "0-100", stepper.prefix(), "", ""),
            new CommandMenuEntry.Stay("+", stepper.inc()));
    }

    /** The Stage a top-level template is on, opening its picker; a dash where nothing is gated. */
    static CommandMenuEntry stageCell(EditorTypeMenusPacket.Variant v, VariantKey key) {
        if (v.phaseMask() == EditorTypeMenusPacket.Variant.NO_GATE) {
            return new CommandMenuEntry.Label(EditorScreenLang.text(EditorScreenLang.SHEET_PENDING));
        }
        boolean linked = v.isStageLinked();
        return new CommandMenuEntry.DrillIn(stageLabel(v),
            new StagePickerScreen(key.category(), key.modelId(), key.modelName(), linked ? v.primaryStageId() : ""));
    }

    /** A member's Stages, opening the multi-select picker its group verb routes through. */
    static CommandMenuEntry memberStageCell(EditorRosterPacket.Group g, EditorRosterIndex.Tile parent,
                                            EditorTypeMenusPacket.Variant mv, VariantKey mk) {
        PlotCategory cat = mk.category();
        StagePickerScreen picker = switch (cat) {
            case CONTENTS -> StagePickerScreen.forGroupMember(mk.parentId(), mk.displayName(), mv.stageIds());
            // Track-side members live under their kind: the parent's modelId is the kind token.
            case PORTALS, TRACKS -> StagePickerScreen.forTrackGroupMember(parent.key().modelId(),
                mk.parentId(), mk.modelName(), mv.stageIds());
            default -> null;
        };
        if (picker == null) return BLANK;
        return new CommandMenuEntry.DrillIn(stageLabel(mv), picker);
    }

    /** The primary Stage's id, "+n" when it has company, or Custom. */
    static String stageLabel(EditorTypeMenusPacket.Variant v) {
        if (!v.isStageLinked()) return EditorScreenLang.text(EditorScreenLang.STAGE_CUSTOM_SHORT);
        int extra = v.stageIds().size() - 1;
        return extra > 0 ? v.primaryStageId() + " +" + extra : v.primaryStageId();
    }

    private static CommandMenuEntry moveCell(VariantKey key) {
        CommandMenuEntry move = EditorScreenActions.moveEntryFor(key, EditorScreenLang.text(EditorScreenLang.LAYOUT_MOVE));
        return move == null ? BLANK : move;
    }
}
