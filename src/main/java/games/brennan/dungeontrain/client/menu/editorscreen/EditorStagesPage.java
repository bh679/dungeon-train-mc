package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.ClientStages;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.net.EditorRosterPacket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Stages tab's rows: a titles row, then one row per Stage — {@code name · min → max}, sorted
 * by whichever title was clicked last. Pure — the pane paints them and the tests read them.
 *
 * <p>A row says what a stage is called and where it sits on the train; what it is built from is
 * the preview's business, and editing its gate stays on the world-space Stages panel and the
 * {@code editor stage} commands. This list is for finding a stage.</p>
 */
public final class EditorStagesPage {

    /** One stage's row and the id its click selects. */
    public record Row(CommandMenuEntry entry, String stageId) {}

    /** The two titles, each a sort key. */
    public enum Column { NAME, LEVEL }

    /**
     * Which title is in force and which way. A title click sorts ascending; clicking the same title
     * again flips it. Ties keep the roster's id order (the sort is stable).
     */
    public record Sort(Column column, boolean descending) {
        public static final Sort DEFAULT = new Sort(Column.NAME, false);

        public Sort toggled(Column clicked) {
            return clicked == column ? new Sort(column, !descending) : new Sort(clicked, false);
        }
    }

    /** Column bounds as shares of the row: the name to 0.62, the level band to the end. */
    static final List<Double> BOUNDS = List.of(0.62);
    static final int NAME_CELL = 0;
    static final int LEVEL_CELL = 1;
    static final String ASCENDING = " ▲";
    static final String DESCENDING = " ▼";
    /** No upper level bound. */
    static final String OPEN_MAX = "∞";

    private EditorStagesPage() {}

    /** The titles row; the active title wears its direction, and a click on either resorts. */
    public static CommandMenuEntry header(Sort sort, Consumer<Column> click) {
        List<CommandMenuEntry> cells = new ArrayList<>(2);
        cells.add(title(EditorScreenLang.STAGES_COL_NAME, Column.NAME, sort, click));
        cells.add(title(EditorScreenLang.STAGES_COL_LEVEL, Column.LEVEL, sort, click));
        return new CommandMenuEntry.Cells(cells, BOUNDS);
    }

    private static CommandMenuEntry title(String key, Column column, Sort sort, Consumer<Column> click) {
        String label = EditorScreenLang.text(key);
        if (sort.column() == column) label += sort.descending() ? DESCENDING : ASCENDING;
        return new CommandMenuEntry.ClientAction(label, () -> click.accept(column), sort.column() == column);
    }

    public static List<Row> rows(List<EditorRosterPacket.StageEntry> stages, Sort sort, Consumer<String> select) {
        List<EditorRosterPacket.StageEntry> ordered = new ArrayList<>(stages);
        ordered.sort(comparator(sort));
        List<Row> out = new ArrayList<>(ordered.size());
        for (EditorRosterPacket.StageEntry s : ordered) {
            String id = s.id();
            CommandMenuEntry name = new CommandMenuEntry.ClientAction(s.name(), () -> select.accept(id), false);
            CommandMenuEntry level = new CommandMenuEntry.Label(levelText(s));
            out.add(new Row(new CommandMenuEntry.Cells(List.of(name, level), BOUNDS), id));
        }
        return out;
    }

    /** The roster's own order, the default sort. */
    public static List<Row> rows(List<EditorRosterPacket.StageEntry> stages, Consumer<String> select) {
        return rows(stages, Sort.DEFAULT, select);
    }

    static Comparator<EditorRosterPacket.StageEntry> comparator(Sort sort) {
        Comparator<EditorRosterPacket.StageEntry> c = switch (sort.column()) {
            case NAME -> Comparator.comparing((EditorRosterPacket.StageEntry s) -> s.name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(EditorRosterPacket.StageEntry::id);
            // An open top ("∞") sorts after every bounded one, so the band order reads top to bottom.
            case LEVEL -> Comparator.comparingInt((EditorRosterPacket.StageEntry s) -> s.stage().minLevel())
                .thenComparingInt(s -> s.stage().maxLevel() < 0 ? Integer.MAX_VALUE : s.stage().maxLevel())
                .thenComparing(EditorRosterPacket.StageEntry::id);
        };
        return sort.descending() ? c.reversed() : c;
    }

    /** {@code 10 → 40}, or {@code 10 → ∞} with no upper bound. */
    public static String levelText(EditorRosterPacket.StageEntry s) {
        int max = s.stage().maxLevel();
        return s.stage().minLevel() + " → " + (max < 0 ? OPEN_MAX : Integer.toString(max));
    }

    /** {@code lvl 10..all · VUC} — the same summary the world-space stage picker shows. */
    public static String gateSummary(EditorRosterPacket.StageEntry s) {
        return ClientStages.gateSummary(new ClientStages.Info(
            s.id(), s.name(), s.stage().minLevel(), s.stage().maxLevel(), s.stage().phaseMask()));
    }
}
