package games.brennan.dungeontrain.tunnel;

import games.brennan.dungeontrain.editor.TrackVariantGroupStore;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.template.TemplateMeta;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The editor's operations on tunnel template groups — membership toggles on a template, and
 * create / weight / delete on the registry — kept apart from the command layer so each rule has one
 * home: deleting a group always strips it from every template, and only tunnel kinds take groups.
 */
public final class TunnelGroupEditing {

    /** The two kinds whose templates can join a group. */
    public static final List<TrackKind> KINDS = List.of(TrackKind.TUNNEL_SECTION, TrackKind.TUNNEL_PORTAL);

    private TunnelGroupEditing() {}

    public static boolean isGroupable(TrackKind kind) {
        return KINDS.contains(kind);
    }

    /**
     * Every group the editor should offer — the registry plus any id a tunnel template already names
     * — with the weight a tunnel rolls it at.
     */
    public static EditorRosterPacket.TunnelGroups snapshot() {
        TunnelGroupStore.Registry registry = TunnelGroupStore.current();
        Map<String, Integer> weights = new TreeMap<>(registry.groups());
        for (TrackKind kind : KINDS) {
            for (String name : TrackVariantGroupStore.topLevelNames(kind)) {
                for (String id : TrackVariantWeights.groupsFor(kind, name)) {
                    weights.putIfAbsent(id, registry.weightOf(id));
                }
            }
        }
        return new EditorRosterPacket.TunnelGroups(weights, registry.ungroupedWeight());
    }

    /**
     * Add {@code (kind, name)} to {@code groupId} if it is not in it, else take it out. Joining a
     * group the registry does not know registers it at its default weight. Returns true when the
     * template is in the group afterwards.
     */
    public static boolean toggle(TrackKind kind, String name, String groupId) throws IOException {
        List<String> next = new ArrayList<>(TrackVariantWeights.groupsFor(kind, name));
        boolean joining = !next.remove(groupId);
        if (joining) {
            next.add(groupId);
            if (!TunnelGroupStore.current().groups().containsKey(groupId)) TunnelGroupStore.create(groupId);
        }
        TrackVariantWeights.setGroups(kind, name, next);
        return joining;
    }

    /** Delete {@code groupId} from the registry and from every tunnel template. Returns templates stripped. */
    public static int delete(String groupId) throws IOException {
        int stripped = 0;
        for (TrackKind kind : KINDS) stripped += TrackVariantWeights.stripGroup(kind, groupId);
        TunnelGroupStore.delete(groupId);
        return stripped;
    }

    /** {@code raw} as a group id, or null when it cannot be one. */
    public static String parseId(String raw) {
        return TemplateMeta.normaliseGroupId(raw);
    }
}
