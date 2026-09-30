package games.brennan.dungeontrain.tunnel;

import games.brennan.dungeontrain.editor.TrackVariantGroupStore;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Where each tunnel template's editor plot sits along its row, laid out <b>by group</b> so a group's
 * entrances (the upper row) stand over its sections (the lower row), column for column.
 *
 * <p>Groups take consecutive runs of columns — named groups in name order, then the ungrouped
 * templates — each run as wide as the group's larger side, so the shorter side leaves empty slots
 * rather than borrowing the next group's columns. Within a group, templates keep name order with
 * {@link TrackKind#DEFAULT_NAME} first. A template in several groups stands under its first group
 * (in name order): a plot is in one place.</p>
 *
 * <p>Recomputed on every call, like every editor slot; a change of membership moves plots, so the
 * editor commands that make one clear the tunnel rows before it and restamp after.</p>
 */
public final class TunnelPlotOrder {

    private TunnelPlotOrder() {}

    /** Column of {@code (kind, name)} in its row, or -1 when it is not a tunnel template. */
    public static int slotOf(TrackKind kind, String name) {
        Integer slot = slots(kind).get(name);
        return slot == null ? -1 : slot;
    }

    /** {@code names} (templates of a tunnel kind) sorted by their column. */
    public static List<String> sortBySlot(TrackKind kind, List<String> names) {
        Map<String, Integer> slots = slots(kind);
        List<String> out = new ArrayList<>(names);
        out.sort(Comparator.comparingInt(n -> slots.getOrDefault(n, Integer.MAX_VALUE)));
        return out;
    }

    public static boolean isTunnel(TrackKind kind) {
        return kind == TrackKind.TUNNEL_SECTION || kind == TrackKind.TUNNEL_PORTAL;
    }

    private static Map<String, Integer> slots(TrackKind kind) {
        Layout layout = layout(
            TrackVariantGroupStore.topLevelNames(TrackKind.TUNNEL_SECTION),
            n -> TrackVariantWeights.groupsFor(TrackKind.TUNNEL_SECTION, n),
            TrackVariantGroupStore.topLevelNames(TrackKind.TUNNEL_PORTAL),
            n -> TrackVariantWeights.groupsFor(TrackKind.TUNNEL_PORTAL, n));
        return kind == TrackKind.TUNNEL_PORTAL ? layout.portals() : layout.sections();
    }

    /** Both rows' columns: template name → column. */
    record Layout(Map<String, Integer> sections, Map<String, Integer> portals) {}

    /** The layout for these templates and memberships — pure, for tests. */
    static Layout layout(List<String> sections, Function<String, List<String>> sectionGroups,
                         List<String> portals, Function<String, List<String>> portalGroups) {
        // Primary group per template: its first group in name order; "" for ungrouped. Named groups
        // sort before the ungrouped run, which goes last.
        Comparator<String> groupOrder = (a, b) -> {
            if (a.isEmpty() != b.isEmpty()) return a.isEmpty() ? 1 : -1;
            return a.compareTo(b);
        };
        TreeMap<String, List<List<String>>> byGroup = new TreeMap<>(groupOrder);
        place(byGroup, sections, sectionGroups, 0);
        place(byGroup, portals, portalGroups, 1);

        Map<String, Integer> sectionSlots = new HashMap<>();
        Map<String, Integer> portalSlots = new HashMap<>();
        int base = 0;
        for (List<List<String>> rows : byGroup.values()) {
            for (int i = 0; i < rows.get(0).size(); i++) sectionSlots.put(rows.get(0).get(i), base + i);
            for (int i = 0; i < rows.get(1).size(); i++) portalSlots.put(rows.get(1).get(i), base + i);
            base += Math.max(rows.get(0).size(), rows.get(1).size());
        }
        return new Layout(sectionSlots, portalSlots);
    }

    private static void place(TreeMap<String, List<List<String>>> byGroup, List<String> names,
                              Function<String, List<String>> groupsOf, int row) {
        List<String> ordered = new ArrayList<>(names);
        ordered.sort(Comparator.comparing((String n) -> !TrackKind.DEFAULT_NAME.equals(n))
            .thenComparing(Comparator.naturalOrder()));
        for (String name : ordered) {
            List<String> groups = groupsOf.apply(name);
            String primary = groups == null || groups.isEmpty() ? ""
                : groups.stream().min(Comparator.naturalOrder()).orElse("");
            byGroup.computeIfAbsent(primary, g -> List.of(new ArrayList<>(), new ArrayList<>())).get(row).add(name);
        }
    }
}
