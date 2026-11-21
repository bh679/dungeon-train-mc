package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkTree;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/** Keyboard moves around the sky: the arrow keys' next star and Tab's next class. Split out of {@link PerksScreen}. */
@OnlyIn(Dist.CLIENT)
final class SkyNav {

    private SkyNav() {}

    /** The linked (or, failing that, nearby) visible star whose direction best matches the arrow. */
    static String pick(PerkTree drawn, ClassVisibility vis, PerkTree.Perk from, double ax, double ay, boolean linkedOnly) {
        String best = null;
        double bestScore = 0.3;
        List<PerkTree.Perk> candidates = linkedOnly
            ? drawn.linksOf(from.id()).stream().map(l -> drawn.perk(l.other(from.id())).orElse(null)).filter(p -> p != null).toList()
            : drawn.perks();
        for (PerkTree.Perk p : candidates) {
            if (p.id().equals(from.id()) || (vis != null && vis.hidden(p))) continue;
            double dx = p.x() - from.x(), dy = p.y() - from.y(), d = Math.hypot(dx, dy);
            if (d == 0 || (!linkedOnly && d > 160)) continue;
            double cos = (dx * ax + dy * ay) / d;
            double score = cos - (linkedOnly ? 0 : d / 800.0);
            if (cos > 0.3 && score > bestScore) {
                best = p.id();
                bestScore = score;
            }
        }
        return best;
    }

    /** Tab: the first visible perk of the next class on the circle (shown classes, or hidden ones with a drawn perk). */
    static PerkTree.Perk hopTarget(PerkTree tree, PerkTree drawn, ClassVisibility vis, String selected, int dir) {
        List<PerkTree.PerkClass> classes = ClassVisibility.circleOrder(tree).stream()
            .filter(c -> vis == null || drawn.perksOf(c.id()).stream().anyMatch(p -> !vis.hidden(p))).toList();
        if (classes.isEmpty()) return null;
        int idx = -1;
        if (selected != null) {
            String cls = tree.perk(selected).map(PerkTree.Perk::cls).orElse(null);
            for (int i = 0; i < classes.size(); i++) if (classes.get(i).id().equals(cls)) idx = i;
        }
        int next = ((idx + dir) % classes.size() + classes.size()) % classes.size();
        PerkTree.PerkClass c = classes.get(next);
        return drawn.perksOf(c.id()).stream().filter(q -> vis == null || !vis.hidden(q)).findFirst().orElse(null);
    }
}
