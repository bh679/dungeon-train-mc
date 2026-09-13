package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
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
 * <p>Every variant is a row of four columns — fold · name · weight · items inside — so the columns
 * line up down the whole list; a column a row has no use for is a blank label. The weight is one
 * cell that behaves as it does in the world-space menus (click +1, shift-click −1, cmd-click to
 * type), so the row carries its stepper for the pane to act on. Stage and Move live in the detail
 * pane once a row is selected. Group members sit under their parent one step in, with the parent's
 * own share as a first "(self)" row, since that is how the server addresses it. Pure: built from a
 * roster snapshot, the folds and the query, and nothing else, so the pane can cache the result.</p>
 *
 * <p>Selection and the standing plot are deliberately not baked into the entries — the pane draws
 * those two marks itself, which is what keeps the rows cacheable across hover and clicks.</p>
 */
public final class EditorLayoutPage {

    /**
     * One row: its entry, the template it is about (null for a section header), its indent, its
     * section, for a group parent the id its fold cell toggles, and for an editable weight the
     * stepper whose commands the weight cell runs.
     */
    public record Row(CommandMenuEntry entry, VariantKey key, int depth, String sectionId, String groupId,
                      TemplateDataSheet.Stepper weight) {
        public Row(CommandMenuEntry entry, VariantKey key, int depth, String sectionId) {
            this(entry, key, depth, sectionId, null, null);
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
     * What has been opened: type sections and the member lists of groups, each by id. Everything is
     * folded unless it has been opened — the tab starts as a list of headers.
     */
    public record Folds(Set<String> expandedSections, Set<String> expandedGroups, boolean allOpen) {
        /** Nothing folded away at all — the whole table. */
        public static final Folds NONE = new Folds(Set.of(), Set.of(), true);
        /** The opening state: every section and every group folded. */
        public static final Folds DEFAULT = new Folds(Set.of(), Set.of(), false);

        public Folds {
            expandedSections = expandedSections == null ? Set.of() : expandedSections;
            expandedGroups = expandedGroups == null ? Set.of() : expandedGroups;
        }

        /** The sections and groups opened; everything else stays folded. */
        public Folds(Set<String> expandedSections, Set<String> expandedGroups) {
            this(expandedSections, expandedGroups, false);
        }

        public boolean sectionOpen(String sectionId) {
            return allOpen || expandedSections.contains(sectionId);
        }

        public boolean groupOpen(String groupId) {
            return allOpen || expandedGroups.contains(groupId);
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

    /** Dividers between the four columns — fold · name · weight · items inside — as fractions of the row. */
    static final List<Double> BOUNDS = List.of(0.05, 0.72, 0.86);
    /** The cells' indexes, for the pane. */
    static final int FOLD_CELL = 0, NAME_CELL = 1, WEIGHT_CELL = 2, COUNT_CELL = 3;
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
     * <p>A section or a group is folded unless it has been opened: a folded section is its header,
     * a folded group its parent row — unless a search is on, when everything holding a match shows
     * it: folding away the very thing that was searched for would read as a broken search.</p>
     *
     * @param folds       the sections and groups opened; the rest show their header or parent only
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
            boolean folded = q.text().isEmpty() && !f.sectionOpen(id);
            out.add(header(g, id, tiles.size(), folded, toggleSection));
            if (folded) continue;
            for (EditorRosterIndex.Tile tile : tiles) {
                addVariant(out, g, tile, id, q.text(), f, select, toggleGroup);
            }
        }
        return out;
    }

    /** As above, with everything opened — the whole table. */
    public static List<Row> rows(EditorRosterIndex index, Query query,
                                 Consumer<VariantKey> select, Consumer<String> toggle) {
        return rows(index, Folds.NONE, query, select, toggle, id -> { });
    }

    /** The whole table, nothing narrowed. */
    public static List<Row> rows(EditorRosterIndex index, Consumer<VariantKey> select, Consumer<String> toggle) {
        return rows(index, Query.EVERYTHING, select, toggle);
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
            Weight w = weight(key, v.weight());
            out.add(new Row(cells(BLANK, name(v.displayName(), key, select), w.cell(), BLANK),
                key, 0, sectionId, null, w.stepper()));
            return;
        }

        String gid = groupId(key);
        // A search shows what it matched, folded or not.
        boolean folded = text.isEmpty() && !folds.groupOpen(gid);
        CommandMenuEntry fold = new CommandMenuEntry.ClientAction(folded ? FOLDED : OPEN, () -> toggleGroup.accept(gid), false);
        Weight w = weight(key, v.weight());
        out.add(new Row(cells(fold, name(v.displayName(), key, select), w.cell(),
            new CommandMenuEntry.Label(Integer.toString(v.subVariants().size()))),
            key, 0, sectionId, gid, w.stepper()));
        if (folded) return;

        // The parent's own share of its group, addressed through the member verb with itself as
        // the member — the same row the browser shows as the "(self)" tile.
        if (tile.selfWeight() != EditorPlotLabelsPacket.NO_WEIGHT) {
            VariantKey self = new VariantKey(key.category(), key.modelId(), key.modelName(), key.displayName());
            Weight sw = weight(self, tile.selfWeight());
            out.add(new Row(cells(BLANK,
                name(EditorScreenLang.text(EditorScreenLang.TILE_SELF, v.displayName()), key, select),
                sw.cell(), BLANK), key, 1, sectionId, null, sw.stepper()));
        }
        for (EditorRosterIndex.Tile member : EditorRosterIndex.subVariants(tile, EditorRosterIndex.Filters.NONE, text)) {
            VariantKey mk = member.key();
            EditorTypeMenusPacket.Variant mv = member.variant();
            Weight mw = weight(mk, mv.weight());
            out.add(new Row(cells(BLANK, name(mv.displayName(), mk, select), mw.cell(), BLANK),
                mk, 1, sectionId, null, mw.stepper()));
        }
    }

    private static CommandMenuEntry cells(CommandMenuEntry fold, CommandMenuEntry name, CommandMenuEntry weight,
                                          CommandMenuEntry count) {
        return new CommandMenuEntry.Cells(List.of(fold, name, weight, count), BOUNDS);
    }

    private static CommandMenuEntry name(String label, VariantKey key, Consumer<VariantKey> select) {
        return new CommandMenuEntry.ClientAction(label, () -> select.accept(key), false);
    }

    /** The weight cell and, when the commands can reach it, the stepper the pane runs on a click. */
    record Weight(CommandMenuEntry cell, TemplateDataSheet.Stepper stepper) {}

    /**
     * No weight pool (parts) is a blank; a pool the commands cannot reach yet (track members) is
     * the number, read-only; otherwise the number as a cell the pane steps or types over — the
     * action inside is a placeholder, since the pane reads the modifiers and picks the command.
     */
    static Weight weight(VariantKey key, int weight) {
        if (weight == EditorPlotLabelsPacket.NO_WEIGHT) return new Weight(BLANK, null);
        String value = Integer.toString(weight);
        TemplateDataSheet.Stepper stepper = TemplateDataSheet.Stepper.of(EditorScreenActions.weightRow(key, weight));
        if (stepper == null) return new Weight(new CommandMenuEntry.Label(value), null);
        return new Weight(new CommandMenuEntry.ClientAction(value, () -> { }, false), stepper);
    }
}
