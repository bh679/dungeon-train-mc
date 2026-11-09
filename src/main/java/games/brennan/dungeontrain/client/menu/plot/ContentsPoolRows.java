package games.brennan.dungeontrain.client.menu.plot;

import games.brennan.dungeontrain.net.EditorTypeMenusPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The in-world Contents menu's pool rows ({@link EditorTypeMenusPacket.Variant#poolHeader()} and the groups
 * under it): which pools the player has folded away, and each group's share of its pool's slot.
 *
 * <p>Folding is view state only. The renderer's cache holds the folded view, so every reader of
 * {@link EditorTypeMenuRenderer#menus()} — draw, raycast, click dispatch — indexes the same rows.</p>
 */
final class ContentsPoolRows {

    private static final Set<String> FOLDED = new HashSet<>();
    /** Percent of its pool's slot each pooled group holds with every room open, by row name. */
    private static Map<String, Integer> shares = Map.of();
    /** How many groups each pool holds, by pool id. */
    private static Map<String, Integer> counts = Map.of();

    private ContentsPoolRows() {}

    /** {@code menus} with folded pools' groups left out; also refreshes the shares and counts. */
    static List<EditorTypeMenusPacket.Menu> view(List<EditorTypeMenusPacket.Menu> menus) {
        Map<String, Integer> totals = new HashMap<>();
        Map<String, Integer> nextCounts = new HashMap<>();
        for (EditorTypeMenusPacket.Menu m : menus) {
            for (EditorTypeMenusPacket.Variant v : m.variants()) {
                if (!v.isPooled()) continue;
                totals.merge(v.pool(), Math.max(0, v.weight()), Integer::sum);
                nextCounts.merge(v.pool(), 1, Integer::sum);
            }
        }
        Map<String, Integer> nextShares = new HashMap<>();
        List<EditorTypeMenusPacket.Menu> out = new ArrayList<>(menus.size());
        for (EditorTypeMenusPacket.Menu m : menus) {
            List<EditorTypeMenusPacket.Variant> rows = new ArrayList<>(m.variants().size());
            boolean dropped = false;
            for (EditorTypeMenusPacket.Variant v : m.variants()) {
                if (v.isPooled()) {
                    int total = totals.getOrDefault(v.pool(), 0);
                    nextShares.put(v.name(), total <= 0 ? 0 : Math.round(100f * Math.max(0, v.weight()) / total));
                    if (FOLDED.contains(v.pool())) {
                        dropped = true;
                        continue;
                    }
                }
                rows.add(v);
            }
            out.add(dropped ? withVariants(m, rows) : m);
        }
        shares = Map.copyOf(nextShares);
        counts = Map.copyOf(nextCounts);
        return List.copyOf(out);
    }

    static void toggle(String poolId) {
        if (!FOLDED.remove(poolId)) FOLDED.add(poolId);
    }

    static boolean isFolded(String poolId) {
        return FOLDED.contains(poolId);
    }

    /** What a pool header row reads: an open or shut marker, the pool's name and how many groups it holds. */
    static String headerLabel(EditorTypeMenusPacket.Variant header) {
        return (isFolded(header.pool()) ? "▸ " : "▾ ") + header.displayName()
            + " (" + counts.getOrDefault(header.pool(), 0) + ")";
    }

    /** A pooled group's share of its pool's slot, as drawn beside its weight. */
    static String shareLabel(EditorTypeMenusPacket.Variant pooled) {
        return shares.getOrDefault(pooled.name(), 0) + "%";
    }

    private static EditorTypeMenusPacket.Menu withVariants(EditorTypeMenusPacket.Menu m,
                                                          List<EditorTypeMenusPacket.Variant> rows) {
        return new EditorTypeMenusPacket.Menu(m.worldPos(), m.typeName(), rows, m.isCompanion(),
            m.activeCategoryId(), m.categoryBar(), m.typeStrip(), m.isPackageMenu(), m.isStagesMenu());
    }
}
