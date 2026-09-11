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

    /**
     * One row: its entry, the template it is about (null for a section header), its indent, its
     * section, and — for a group parent — the id its fold cell toggles.
     */
    public record Row(CommandMenuEntry entry, VariantKey key, int depth, String sectionId, String groupId) {
        public Row(CommandMenuEntry entry, VariantKey key, int depth, String sectionId) {
            this(entry, key, depth, sectionId, null);
        }

        public boolean isHeader() {
            return key == null;
        }

        /** True for a group parent, whose first cell folds its members away. */
        public boolean isGroupParent() {
            return groupId != null;
        }
    }

    /**
     * What is folded away: whole type sections, by the sections folded; and the member lists of
     * groups, by the groups <em>opened</em> — a group is folded unless it has been opened.
     */
    public record Folds(Set<String> sections, Set<String> expandedGroups, boolean allGroupsOpen) {
        /** Nothing folded away at all: no sections folded, every group opened. */
        public static final Folds NONE = new Folds(Set.of(), Set.of(), true);
        /** The opening state: every section open, every group folded. */
        public static final Folds DEFAULT = new Folds(Set.of(), Set.of(), false);

        public Folds {
            sections = sections == null ? Set.of() : sections;
            expandedGroups = expandedGroups == null ? Set.of() : expandedGroups;
        }

        /** Sections folded and groups opened; the rest of the groups stay folded. */
        public Folds(Set<String> sections, Set<String> expandedGroups) {
            this(sections, expandedGroups, false);
        }

        public boolean groupOpen(String groupId) {
            return allGroupsOpen || expandedGroups.contains(groupId);
        }
    }

    /**
     * What the filter bar narrows the table by — the same four things it narrows the browser by.
     *
     * @param category the category cell, All for every group
     * @param typeName one type's group under that category, or {@code ""} for all of them
     */
    public record Query(EditorCategoryFilter category, String typeName, EditorRosterIndex.Filters filters, String text) {
        public static final Query EVERYTHING = new Query(EditorCategoryFilter.ALL, "", EditorRosterIndex.Filters.NONE, "");

        public Query {
            category = category == null ? EditorCategoryFilter.ALL : category;
            typeName = typeName == null ? "" : typeName;
            filters = filters == null ? EditorRosterIndex.Filters.NONE : filters;
            text = text == null ? "" : text;
        }

        /** What the screen's remembered filters ask for right now. */
        public static Query current() {
            return new Query(EditorScreenState.category(), EditorScreenState.typeName(),
                EditorScreenState.filters(), EditorScreenState.text());
        }

        /** Whether a roster group is inside this query's category and type. */
        boolean admits(EditorRosterPacket.Group g) {
            PlotCategory cat = category.category();
            if (cat == null) return true;
            PlotCategory gc = PlotCategory.fromId(g.categoryId()).orElse(null);
            if (gc == null) return false;
            boolean inCategory = gc == cat || (cat == PlotCategory.CARRIAGES && gc == PlotCategory.PARTS);
            return inCategory && (typeName.isEmpty() || typeName.equals(g.typeName()));
        }
    }

    /** Dividers between the seven columns — fold · name · − · value · + · stage · move — as fractions of the row. */
    static final List<Double> BOUNDS = List.of(0.05, 0.48, 0.55, 0.64, 0.71, 0.86);
    static final String OPEN = "▾";
    static final String FOLDED = "▸";
    private static final CommandMenuEntry BLANK = new CommandMenuEntry.Label("");

    private EditorLayoutPage() {}

    /** A section's id, stable across roster refreshes: the category and type it lists. */
    public static String sectionId(EditorRosterPacket.Group g) {
        return g.categoryId() + "/" + g.typeName();
    }

    /** A group parent's id for folding: its key, which a roster refresh does not change. */
    public static String groupId(VariantKey key) {
        return key.category().id() + "/" + key.modelId() + "/" + key.modelName();
    }

    /**
     * Every row, top to bottom, narrowed by {@code query}.
     *
     * <p>A section outside the query's category or type is not there at all, and neither is one
     * the chips and the search leave empty — a header over nothing reads as a bug. The count on a
     * header is what is shown under it. A group parent survives the search when a member matches,
     * as it does in the browser, and the provenance chips never hide members.</p>
     *
     * <p>A group is folded unless it has been opened: its parent row shows, the "(self)" and member
     * rows under it do not — unless a search is on, when every group shows whatever the search
     * matched: folding away the very thing that was searched for would read as a broken search.</p>
     *
     * @param folds       sections that show only their header, and the groups opened past their parent
     * @param select      what a name cell does — hands over the row's key
     * @param toggleSection what a header does — hands over the section id
     * @param toggleGroup what a parent's fold cell does — hands over the group id
     */
    public static List<Row> rows(EditorRosterIndex index, Folds folds, Query query, Consumer<VariantKey> select,
                                 Consumer<String> toggleSection, Consumer<String> toggleGroup) {
        List<Row> out = new ArrayList<>();
        if (index == null) return out;
        Query q = query == null ? Query.EVERYTHING : query;
        Folds f = folds == null ? Folds.DEFAULT : folds;
        for (EditorRosterPacket.Group g : index.groups()) {
            if (!q.admits(g)) continue;
            List<EditorRosterIndex.Tile> tiles = EditorRosterIndex.filter(EditorRosterIndex.tiles(g), q.filters(), q.text());
            if (tiles.isEmpty()) continue;
            String id = sectionId(g);
            boolean folded = f.sections().contains(id);
            out.add(header(g, id, tiles.size(), folded, toggleSection));
            if (folded) continue;
            for (EditorRosterIndex.Tile tile : tiles) {
                addVariant(out, g, tile, id, q.text(), f, select, toggleGroup);
            }
        }
        return out;
    }

    /** As above, with every group opened — the whole table. */
    public static List<Row> rows(EditorRosterIndex index, Set<String> collapsed, Query query,
                                 Consumer<VariantKey> select, Consumer<String> toggle) {
        return rows(index, new Folds(collapsed, Set.of(), true), query, select, toggle, id -> { });
    }

    /** As above, with nothing narrowed. */
    public static List<Row> rows(EditorRosterIndex index, Set<String> collapsed,
                                 Consumer<VariantKey> select, Consumer<String> toggle) {
        return rows(index, collapsed, Query.EVERYTHING, select, toggle);
    }

    private static Row header(EditorRosterPacket.Group g, String id, int shown, boolean folded, Consumer<String> toggle) {
        String label = (folded ? FOLDED : OPEN) + " "
            + EditorScreenLang.text(EditorScreenLang.LAYOUT_SECTION, g.typeName(), shown);
        return new Row(new CommandMenuEntry.ClientAction(label, () -> toggle.accept(id), false), null, 0, id);
    }

    private static void addVariant(List<Row> out, EditorRosterPacket.Group g, EditorRosterIndex.Tile tile,
                                   String sectionId, String text, Folds folds,
                                   Consumer<VariantKey> select, Consumer<String> toggleGroup) {
        VariantKey key = tile.key();
        EditorTypeMenusPacket.Variant v = tile.variant();
        if (!tile.isGroup()) {
            out.add(new Row(cells(BLANK,
                name(v.displayName(), key, select),
                weightCells(key, v.weight()),
                stageCell(v, key),
                moveCell(key)), key, 0, sectionId));
            return;
        }

        String gid = groupId(key);
        // A search shows what it matched, folded or not.
        boolean folded = text.isEmpty() && !folds.groupOpen(gid);
        CommandMenuEntry fold = new CommandMenuEntry.ClientAction(folded ? FOLDED : OPEN, () -> toggleGroup.accept(gid), false);
        out.add(new Row(cells(fold,
            name(v.displayName(), key, select),
            weightCells(key, v.weight()),
            stageCell(v, key),
            moveCell(key)), key, 0, sectionId, gid));
        if (folded) return;

        // The parent's own share of its group, addressed through the member verb with itself as
        // the member — the same row the browser shows as the "(self)" tile.
        if (tile.selfWeight() != EditorPlotLabelsPacket.NO_WEIGHT) {
            VariantKey self = new VariantKey(key.category(), key.modelId(), key.modelName(), key.displayName());
            out.add(new Row(cells(BLANK,
                name(EditorScreenLang.text(EditorScreenLang.TILE_SELF, v.displayName()), key, select),
                weightCells(self, tile.selfWeight()),
                BLANK, BLANK), key, 1, sectionId));
        }
        for (EditorRosterIndex.Tile member : EditorRosterIndex.subVariants(tile, EditorRosterIndex.Filters.NONE, text)) {
            VariantKey mk = member.key();
            EditorTypeMenusPacket.Variant mv = member.variant();
            out.add(new Row(cells(BLANK,
                name(mv.displayName(), mk, select),
                weightCells(mk, mv.weight()),
                memberStageCell(g, tile, mv, mk),
                moveCell(mk)), mk, 1, sectionId));
        }
    }

    private static CommandMenuEntry cells(CommandMenuEntry fold, CommandMenuEntry name, List<CommandMenuEntry> weight,
                                          CommandMenuEntry stage, CommandMenuEntry move) {
        List<CommandMenuEntry> all = new ArrayList<>(7);
        all.add(fold);
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
