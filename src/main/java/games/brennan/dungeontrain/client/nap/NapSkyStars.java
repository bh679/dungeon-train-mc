package games.brennan.dungeontrain.client.nap;

import games.brennan.dungeontrain.perks.PerkTree;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which nap-sky star ({@link NapSky}) each perk is. {@code sky.json} carries positions only (no ids,
 * so the page never names a perk), so a perk is matched to the star at its place on the perks map
 * ({@link PerkTree}), within {@link #TOLERANCE}. Pure and immutable.
 */
public final class NapSkyStars {

    /** How far (editor units) a perk may sit from its star: {@code sky.json} rounds to 0.1. */
    static final double TOLERANCE = 0.5;

    private final Map<String, Integer> byId;

    private NapSkyStars(Map<String, Integer> byId) {
        this.byId = Map.copyOf(byId);
    }

    /** Match every perk in {@code tree} (the Engine excluded) to its star; a perk with no star is left out. */
    public static NapSkyStars match(NapSky sky, PerkTree tree) {
        Map<String, Integer> out = new HashMap<>();
        if (sky != null && tree != null) {
            for (PerkTree.Perk p : tree.perks()) {
                if (p.isRoot()) continue;
                int star = nearest(sky, p.x(), p.y());
                if (star > 0) out.put(p.id(), star);
            }
        }
        return new NapSkyStars(out);
    }

    private static int nearest(NapSky sky, double x, double y) {
        int best = -1;
        double bestD = TOLERANCE;
        for (int i = 0; i < sky.size(); i++) {
            double d = Math.hypot(sky.x(i) - x, sky.y(i) - y);
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** The star of {@code perkId}, or -1 when it has none. */
    public int star(String perkId) {
        Integer s = byId.get(perkId);
        return s == null ? -1 : s;
    }

    /** The stars of these perks, in order, unknown ids skipped. */
    public Set<Integer> stars(Collection<String> perkIds) {
        Set<Integer> out = new LinkedHashSet<>();
        for (String id : perkIds) {
            int s = star(id);
            if (s > 0) out.add(s);
        }
        return out;
    }

    public int size() {
        return byId.size();
    }
}
