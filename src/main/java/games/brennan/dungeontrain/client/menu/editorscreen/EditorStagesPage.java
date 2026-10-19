package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.ClientStages;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.net.EditorRosterPacket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Stages tab's rows: a titles row, then one row per Stage — {@code name · min → max}, sorted
 * by whichever title was clicked last — with each family of stages (the wood stages, say) folded
 * into one row whose dropdown picks the member. Pure — the pane paints them and the tests read them.
 *
 * <p>A row says what a stage is called and where it sits on the train; what it is built from is
 * the preview's business, and editing its gate stays on the world-space Stages panel and the
 * {@code editor stage} commands. This list is for finding a stage.</p>
 */
public final class EditorStagesPage {

    /**
     * One row and the stage its click shows. A <b>group row</b> stands for a whole family of stages
     * (every stage sharing a {@code Stage.group()}, e.g. the wood stages): {@code stageId} is the
     * member it currently shows and {@code memberIds} the family in list order, for its dropdown.
     */
    public record Row(CommandMenuEntry entry, String stageId, String group, List<String> memberIds) {
        public Row {
            group = group == null ? "" : group;
            memberIds = memberIds == null ? List.of() : List.copyOf(memberIds);
        }

        /** A single stage's row. */
        public Row(CommandMenuEntry entry, String stageId) {
            this(entry, stageId, "", List.of());
        }

        public boolean isGroup() {
            return !group.isEmpty();
        }

        /** Whether this row is, or folds in, the stage — so a shown member marks its group row chosen. */
        public boolean stands(String id) {
            if (id == null) return false;
            if (id.equalsIgnoreCase(stageId)) return true;
            for (String m : memberIds) if (m.equalsIgnoreCase(id)) return true;
            return false;
        }
    }

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
    /** After a family's label: its row opens a dropdown of its members. */
    static final String DROPDOWN = " ▾";

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

    /** Every stage on its own row — no families folded. */
    public static List<Row> rows(List<EditorRosterPacket.StageEntry> stages, Sort sort, Consumer<String> select) {
        List<EditorRosterPacket.StageEntry> ordered = new ArrayList<>(stages);
        ordered.sort(comparator(sort));
        List<Row> out = new ArrayList<>(ordered.size());
        for (EditorRosterPacket.StageEntry s : ordered) out.add(stageRow(s, select));
        return out;
    }

    /** The roster's own order, the default sort. */
    public static List<Row> rows(List<EditorRosterPacket.StageEntry> stages, Consumer<String> select) {
        return rows(stages, Sort.DEFAULT, select);
    }

    /**
     * The list with each family folded into one row, placed where the family sorts: by its name
     * ({@link #groupName}) under the Name title, by its widest band under the Level title. The
     * family's members keep the same order inside it.
     *
     * @param pickOf    the member a family shows ({@code group → stage id}); null or a non-member
     *                  falls back to its first member
     * @param openGroup what a click on a family's row does besides showing its pick — the pane opens
     *                  the member dropdown
     */
    public static List<Row> groupedRows(List<EditorRosterPacket.StageEntry> stages, Sort sort, Consumer<String> select,
                                        Function<String, String> pickOf, Consumer<String> openGroup) {
        Map<String, List<EditorRosterPacket.StageEntry>> families = new LinkedHashMap<>();
        List<EditorRosterPacket.StageEntry> singles = new ArrayList<>();
        for (EditorRosterPacket.StageEntry s : stages) {
            if (s.group().isEmpty()) singles.add(s);
            else families.computeIfAbsent(s.group(), g -> new ArrayList<>()).add(s);
        }
        List<Item> items = new ArrayList<>();
        for (EditorRosterPacket.StageEntry s : singles) {
            items.add(new Item(s.name(), s.stage().minLevel(), s.stage().maxLevel(), s.id(), s, List.of()));
        }
        for (Map.Entry<String, List<EditorRosterPacket.StageEntry>> f : families.entrySet()) {
            List<EditorRosterPacket.StageEntry> members = new ArrayList<>(f.getValue());
            members.sort(comparator(sort));
            items.add(new Item(groupName(f.getKey()), span(members, true), span(members, false), f.getKey(), null, members));
        }
        items.sort(itemComparator(sort));
        List<Row> out = new ArrayList<>(items.size());
        for (Item item : items) {
            if (item.single() != null) {
                out.add(stageRow(item.single(), select));
            } else {
                out.add(groupRow(item, pickOf == null ? null : pickOf.apply(item.id()), select, openGroup));
            }
        }
        return out;
    }

    /** A family's row: {@code Wood: spruce ▾ · lowest → highest}; a click shows the pick and opens the dropdown. */
    private static Row groupRow(Item item, String wanted, Consumer<String> select, Consumer<String> openGroup) {
        EditorRosterPacket.StageEntry pick = item.members().get(0);
        for (EditorRosterPacket.StageEntry m : item.members()) {
            if (m.id().equalsIgnoreCase(wanted)) pick = m;
        }
        String pickId = pick.id();
        String group = item.id();
        CommandMenuEntry name = new CommandMenuEntry.ClientAction(groupLabel(group, pick.name()), () -> {
            select.accept(pickId);
            if (openGroup != null) openGroup.accept(group);
        }, false);
        CommandMenuEntry level = new CommandMenuEntry.Label(levelText(item.min(), item.max()));
        List<String> ids = new ArrayList<>(item.members().size());
        for (EditorRosterPacket.StageEntry m : item.members()) ids.add(m.id());
        return new Row(new CommandMenuEntry.Cells(List.of(name, level), BOUNDS), pickId, group, ids);
    }

    private static Row stageRow(EditorRosterPacket.StageEntry s, Consumer<String> select) {
        String id = s.id();
        CommandMenuEntry name = new CommandMenuEntry.ClientAction(s.name(), () -> select.accept(id), false);
        CommandMenuEntry level = new CommandMenuEntry.Label(levelText(s));
        return new Row(new CommandMenuEntry.Cells(List.of(name, level), BOUNDS), id);
    }

    /** A family's label, from its id: {@code wood} reads {@code Wood}. Data, like a stage's name — not translated. */
    public static String groupName(String group) {
        if (group == null || group.isEmpty()) return "";
        String spaced = group.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** {@code Wood: spruce ▾} — the family, the member it shows, and the dropdown mark. */
    public static String groupLabel(String group, String memberName) {
        return groupName(group) + ": " + memberName + DROPDOWN;
    }

    /** The family's lowest min, or its highest max ({@code -1}, open, when any member is open). */
    private static int span(List<EditorRosterPacket.StageEntry> members, boolean min) {
        int out = min ? Integer.MAX_VALUE : 0;
        for (EditorRosterPacket.StageEntry m : members) {
            if (min) {
                out = Math.min(out, m.stage().minLevel());
            } else {
                int max = m.stage().maxLevel();
                if (max < 0) return -1;
                out = Math.max(out, max);
            }
        }
        return out;
    }

    /** A sortable entry of the folded list: one stage, or one family ({@code single == null}). */
    private record Item(String name, int min, int max, String id, EditorRosterPacket.StageEntry single,
                        List<EditorRosterPacket.StageEntry> members) {}

    private static Comparator<Item> itemComparator(Sort sort) {
        Comparator<Item> c = switch (sort.column()) {
            case NAME -> Comparator.comparing(Item::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Item::id);
            case LEVEL -> Comparator.comparingInt(Item::min)
                .thenComparingInt(i -> i.max() < 0 ? Integer.MAX_VALUE : i.max())
                .thenComparing(Item::id);
        };
        return sort.descending() ? c.reversed() : c;
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
        return levelText(s.stage().minLevel(), s.stage().maxLevel());
    }

    static String levelText(int min, int max) {
        return min + " → " + (max < 0 ? OPEN_MAX : Integer.toString(max));
    }

    /** {@code lvl 10..all · VUC} — the same summary the world-space stage picker shows. */
    public static String gateSummary(EditorRosterPacket.StageEntry s) {
        return ClientStages.gateSummary(new ClientStages.Info(
            s.id(), s.name(), s.stage().minLevel(), s.stage().maxLevel(), s.stage().phaseMask()));
    }
}
