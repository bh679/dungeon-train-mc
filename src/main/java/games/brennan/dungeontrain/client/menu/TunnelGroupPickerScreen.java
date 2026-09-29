package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.menu.editorscreen.EditorRosterClient;
import games.brennan.dungeontrain.client.menu.editorscreen.EditorRosterIndex;
import games.brennan.dungeontrain.net.EditorRosterPacket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Which tunnel template groups one tunnel section or entrance belongs to — a multi-select list in
 * the {@link StagePickerScreen} idiom: each group is a stay-open {@code [x]}/{@code [ ]} toggle beside
 * its roll weight (click to type a new one), then a row to join a brand-new group and a row for the
 * ungrouped pool's weight.
 *
 * <p>A tunnel rolls one group by these weights and builds every section and entrance from its
 * members, so the same template can serve several looks by being ticked in several groups.</p>
 */
public final class TunnelGroupPickerScreen implements MenuScreen {

    private static final String ROOT = "dungeontrain editor tracks tunnelgroups";

    private final String kindId;
    private final String name;
    /** Toggled locally as rows are clicked so the checkboxes update before the roster refreshes. */
    private final TreeSet<String> selected = new TreeSet<>();
    /** The roster {@link #selected} was last synced from — a newer one (after a typed edit) wins. */
    private EditorRosterIndex syncedFrom;

    public TunnelGroupPickerScreen(String kindId, String name, Collection<String> currentGroups) {
        this.kindId = kindId == null ? "" : kindId;
        this.name = name == null ? "" : name;
        if (currentGroups != null) selected.addAll(currentGroups);
        this.syncedFrom = EditorRosterClient.index();
    }

    /**
     * A picker that reads the template's current groups from the editor roster — for callers (the
     * world-space X menu) that only know which template they stand in.
     */
    public static TunnelGroupPickerScreen fromRoster(String kindId, String name) {
        TunnelGroupPickerScreen screen = new TunnelGroupPickerScreen(kindId, name, null);
        screen.syncedFrom = null; // sync on first draw
        return screen;
    }

    /** Take this template's memberships from a roster that arrived since the last look. */
    private void syncFromRoster() {
        EditorRosterIndex index = EditorRosterClient.index();
        if (index == syncedFrom) return;
        syncedFrom = index;
        for (EditorRosterIndex.Tile tile : index.allTiles()) {
            if (tile.key() != null && kindId.equals(tile.key().modelId()) && name.equals(tile.variant().name())) {
                selected.clear();
                selected.addAll(tile.variant().groupIds());
                return;
            }
        }
    }

    /** True for a tunnel section / entrance model id — the templates that take groups. */
    public static boolean groupable(String modelId) {
        return "tunnel_section".equals(modelId) || "tunnel_portal".equals(modelId);
    }

    /** Longest group id a {@link #summary} shows before cutting it. */
    private static final int SUMMARY_MAX = 10;

    /** Short label for a row's groups: the first id, {@code +N} for the rest, or a dim hint. */
    public static String summary(java.util.List<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) return "no group";
        String first = groupIds.get(0);
        if (first.length() > SUMMARY_MAX) first = first.substring(0, SUMMARY_MAX - 1) + "…";
        return first + (groupIds.size() > 1 ? " +" + (groupIds.size() - 1) : "");
    }

    /** {@code … toggle <kind> <name> <id>} — joins (registering if new) or leaves {@code id}. */
    public static String toggleCommand(String kindId, String name, String id) {
        return ROOT + " toggle " + kindId + " " + name + " " + id;
    }

    static String toggleCommandPrefix(String kindId, String name) {
        return ROOT + " toggle " + kindId + " " + name;
    }

    static String weightCommandPrefix(String id) {
        return ROOT + " weight " + id;
    }

    static String ungroupedCommandPrefix() {
        return ROOT + " ungrouped";
    }

    @Override
    public String title() {
        return "Tunnel groups";
    }

    @Override
    public List<CommandMenuEntry> entries() {
        syncFromRoster();
        EditorRosterPacket.TunnelGroups groups = EditorRosterClient.tunnelGroups();
        TreeSet<String> ids = new TreeSet<>(groups.weights().keySet());
        ids.addAll(selected);

        List<CommandMenuEntry> out = new ArrayList<>();
        for (String id : ids) {
            boolean on = selected.contains(id);
            Integer weight = groups.weights().get(id);
            CommandMenuEntry toggle = new CommandMenuEntry.ClientAction((on ? "[x] " : "[ ] ") + id, () -> {
                if (!selected.remove(id)) selected.add(id);
                CommandRunner.run(toggleCommand(kindId, name, id));
                EditorRosterClient.scheduleRefresh(EditorRosterClient.REFRESH_DELAY_TICKS);
            });
            CommandMenuEntry weightCell = new CommandMenuEntry.TypeArg(
                "×" + (weight == null ? 1 : weight), "weight", weightCommandPrefix(id), "", "");
            out.add(new CommandMenuEntry.Split(toggle, weightCell, 0.7));
        }
        if (ids.isEmpty()) {
            out.add(new CommandMenuEntry.Label("No groups yet — add this template to a new one."));
        }
        out.add(new CommandMenuEntry.TypeArg("+ New group…", "group id", toggleCommandPrefix(kindId, name), "", ""));
        out.add(new CommandMenuEntry.TypeArg("Ungrouped tunnels ×" + groups.ungroupedWeight(), "weight",
            ungroupedCommandPrefix(), "", ""));
        out.add(new CommandMenuEntry.Back(MenuLang.t("common.done")));
        return out;
    }
}
