package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.menu.editorscreen.EditorRosterClient;
import games.brennan.dungeontrain.client.menu.editorscreen.EditorRosterIndex;
import games.brennan.dungeontrain.net.EditorRosterPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which tunnel sections (or entrances) belong to one tunnel group — the Groups tab's {@code +}
 * tile. Every template of the kind is a stay-open {@code [x]}/{@code [ ]} toggle that joins or
 * leaves the group, in the {@link TunnelGroupPickerScreen} idiom (which answers the same question
 * the other way round: which groups one template is in).
 */
public final class TunnelGroupMembersScreen implements MenuScreen {

    private final String groupId;
    private final String kindId;
    /** Toggled locally so the checkboxes move before the roster refreshes. */
    private final TreeSet<String> members = new TreeSet<>();

    public TunnelGroupMembersScreen(String groupId, String kindId) {
        this.groupId = groupId;
        this.kindId = kindId;
        for (EditorRosterPacket.TunnelGroups.Member m : EditorRosterClient.tunnelGroups().membersOf(groupId, kindId)) {
            members.add(m.name());
        }
    }

    @Override
    public String title() {
        return ("tunnel_portal".equals(kindId) ? "Entrances" : "Sections") + " in " + groupId;
    }

    @Override
    public List<CommandMenuEntry> entries() {
        // Template id → label, top-level templates of this kind only (sub-variants are drawn through
        // their parent and never join a group on their own).
        TreeMap<String, String> templates = new TreeMap<>();
        for (EditorRosterIndex.Tile tile : EditorRosterClient.index().allTiles()) {
            if (tile.key() == null || tile.key().isSubVariant()) continue;
            if (!kindId.equals(tile.key().modelId())) continue;
            templates.put(tile.variant().name(), tile.variant().displayName());
        }
        List<CommandMenuEntry> out = new ArrayList<>();
        for (var e : templates.entrySet()) {
            String name = e.getKey();
            boolean on = members.contains(name);
            out.add(new CommandMenuEntry.ClientAction((on ? "[x] " : "[ ] ") + e.getValue(), () -> {
                if (!members.remove(name)) members.add(name);
                CommandRunner.run(TunnelGroupPickerScreen.toggleCommand(kindId, name, groupId));
                EditorRosterClient.scheduleRefresh(EditorRosterClient.REFRESH_DELAY_TICKS);
            }));
        }
        if (templates.isEmpty()) out.add(new CommandMenuEntry.Label("No templates of this kind yet."));
        out.add(new CommandMenuEntry.Back(MenuLang.t("common.done")));
        return out;
    }
}
