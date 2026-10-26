package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkTree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Where the stars are drawn when only some classes are on the circle (the web page's {@code shown()}
 * and {@code drawn()}). Display only: the tree's own positions never change.
 *
 * <p>The classes on the circle share it: each gets an equal sector in their authored order round the
 * Engine (a class shown only because points are held gets {@link ClassVisibility#EXTRA_SHARE} of a
 * share), and its perks turn together about the Engine to the sector's centre, keeping their distances
 * and the class's shape, spread to {@link #FILL} of the share but never past {@link #MAX_SPREAD} of the
 * circle. With every class on the circle it is the tree exactly as authored.</p>
 *
 * <p>A new layout does not jump: every star swings round the Engine to it over {@link #MOVE_MS},
 * angle and radius eased together from wherever it is drawn now. Mutable only in that animation state;
 * one per screen.</p>
 */
final class SkyLayout {

    static final double FILL = 0.85;
    static final double MAX_SPREAD = 0.35;
    static final double MAX_STRETCH = 6;
    static final long MOVE_MS = 1500, MOVE_MIN_MS = 350;
    /** A previewed class at the fully zoomed-out view takes at least this share of the circle, and spreads across it. */
    static final double PREVIEW_SHARE = 0.4;
    static final double PREVIEW_FILL = 0.95;
    static final double PREVIEW_MAX_STRETCH = 40;
    private static final double TAU = Math.PI * 2;

    private String key;
    private Map<String, double[]> target = Map.of();
    private Map<String, double[]> from;
    private long start;
    private long dur;
    private Map<String, double[]> current = Map.of();

    /** The positions to draw at {@code now}: perk id to {x, y}; perks absent keep their authored place. */
    Map<String, double[]> positions(PerkTree tree, ClassVisibility vis, long now) {
        return positions(tree, vis, now, false);
    }

    /**
     * @param boostPreview the view is fully zoomed out: spread a previewed class across at least
     *                     {@link #PREVIEW_SHARE} of the circle so its perks can be read
     */
    Map<String, double[]> positions(PerkTree tree, ClassVisibility vis, long now, boolean boostPreview) {
        boolean boost = boostPreview && vis.preview() != null;
        String nextKey = keyOf(tree, vis) + (boost ? "|boost:" + vis.preview() : "");
        if (!nextKey.equals(key)) {
            Map<String, double[]> nextTarget = nextKey.isEmpty() ? Map.of() : target(tree, vis, boost);
            if (key == null) {
                from = null;
            } else {
                long left = from != null ? start + dur - now : 0;
                dur = left > 0 ? Math.min(MOVE_MS, Math.max(MOVE_MIN_MS, left)) : MOVE_MS;
                from = snapshotOf(tree, current);
                start = now;
            }
            key = nextKey;
            target = nextTarget;
        }
        if (from == null || dur <= 0) {
            current = target;
            return current;
        }
        double t = Math.min(1, (now - start) / (double) dur);
        if (t >= 1) {
            from = null;
            current = target;
            return current;
        }
        double k = ease(t);
        Map<String, double[]> out = new HashMap<>();
        for (PerkTree.Perk p : tree.perks()) {
            if (p.isRoot()) continue;
            double[] a = from.get(p.id());
            double[] b = target.getOrDefault(p.id(), new double[] {p.x(), p.y()});
            if (a == null) {
                out.put(p.id(), b);
                continue;
            }
            double fa = Math.atan2(a[1] - PerkTree.CENTRE, a[0] - PerkTree.CENTRE), fr = Math.hypot(a[0] - PerkTree.CENTRE, a[1] - PerkTree.CENTRE);
            double ta = Math.atan2(b[1] - PerkTree.CENTRE, b[0] - PerkTree.CENTRE), tr = Math.hypot(b[0] - PerkTree.CENTRE, b[1] - PerkTree.CENTRE);
            double da = wrap(ta - fa);
            double ang = fa + da * k, r = fr + (tr - fr) * k;
            out.put(p.id(), new double[] {PerkTree.CENTRE + r * Math.cos(ang), PerkTree.CENTRE + r * Math.sin(ang)});
        }
        current = out;
        return current;
    }

    /** True while stars are swinging to a new layout. */
    boolean moving() {
        return from != null;
    }

    private static Map<String, double[]> snapshotOf(PerkTree tree, Map<String, double[]> drawn) {
        Map<String, double[]> out = new HashMap<>();
        for (PerkTree.Perk p : tree.perks()) out.put(p.id(), drawn.getOrDefault(p.id(), new double[] {p.x(), p.y()}));
        return out;
    }

    /** Empty when every class is on the circle as an anchor (the tree as authored). */
    static String keyOf(PerkTree tree, ClassVisibility vis) {
        TreeSet<String> off = new TreeSet<>(), extra = new TreeSet<>();
        for (PerkTree.PerkClass c : ClassVisibility.liveClasses(tree)) {
            if (!vis.onCircle(c.id())) off.add(c.id());
            else if (!vis.anchored(c.id())) extra.add(c.id());
        }
        if (off.isEmpty() && extra.isEmpty()) return "";
        return String.join(",", off) + "|" + String.join(",", extra);
    }

    private record Group(String id, double mid, double half, double weight) {}

    /** The laid-out positions for the classes on the circle (web {@code shown()}). */
    static Map<String, double[]> target(PerkTree tree, ClassVisibility vis) {
        return target(tree, vis, false);
    }

    static Map<String, double[]> target(PerkTree tree, ClassVisibility vis, boolean boost) {
        List<Group> groups = new ArrayList<>();
        for (PerkTree.PerkClass c : ClassVisibility.liveClasses(tree)) {
            if (!vis.onCircle(c.id())) continue;
            List<PerkTree.Perk> ps = tree.perksOf(c.id());
            double sx = 0, sy = 0;
            for (PerkTree.Perk p : ps) { double a = angle(p); sx += Math.cos(a); sy += Math.sin(a); }
            double mean = Math.atan2(sy, sx), lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (PerkTree.Perk p : ps) { double o = wrap(angle(p) - mean); lo = Math.min(lo, o); hi = Math.max(hi, o); }
            double weight = vis.anchored(c.id()) ? 1 : ClassVisibility.EXTRA_SHARE;
            groups.add(new Group(c.id(), wrap(mean + (lo + hi) / 2), (hi - lo) / 2, weight));
        }
        if (groups.isEmpty()) return Collections.emptyMap();
        groups.sort((a, b) -> Double.compare(a.mid(), b.mid()));
        String boosted = boost ? vis.preview() : null;
        if (boosted != null) {
            // the previewed class takes at least PREVIEW_SHARE of the circle: w / (w + rest) >= share
            double rest = groups.stream().filter(g -> !g.id().equals(boosted)).mapToDouble(Group::weight).sum();
            double need = rest * PREVIEW_SHARE / (1 - PREVIEW_SHARE);
            groups.replaceAll(g -> g.id().equals(boosted) ? new Group(g.id(), g.mid(), g.half(), Math.max(g.weight(), need)) : g);
        }
        double total = groups.stream().mapToDouble(Group::weight).sum();
        double[] size = new double[groups.size()], offset = new double[groups.size()];
        double run = 0;
        for (int i = 0; i < groups.size(); i++) {
            size[i] = TAU * groups.get(i).weight() / total;
            offset[i] = run + size[i] / 2;
            run += size[i];
        }
        // turn the ring so the anchors move least
        double rs = 0, rc = 0;
        boolean anyAnchor = groups.stream().anyMatch(g -> g.weight() >= 1);
        for (int i = 0; i < groups.size(); i++) {
            if (anyAnchor && groups.get(i).weight() < 1) continue;
            rs += Math.sin(groups.get(i).mid() - offset[i]);
            rc += Math.cos(groups.get(i).mid() - offset[i]);
        }
        double startAngle = Math.atan2(rs, rc);
        Map<String, double[]> out = new HashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            Group g = groups.get(i);
            double centre = startAngle + offset[i];
            boolean big = g.id().equals(boosted);
            double spread = big ? Math.max(PREVIEW_SHARE * TAU, Math.min(PREVIEW_FILL * size[i], 0.9 * TAU)) : Math.min(FILL * size[i], MAX_SPREAD * TAU);
            double k = g.half() > 0.01 ? Math.min(big ? PREVIEW_MAX_STRETCH : MAX_STRETCH, (spread / 2) / g.half()) : 1;
            for (PerkTree.Perk p : tree.perksOf(g.id())) {
                double r = Math.hypot(p.x() - PerkTree.CENTRE, p.y() - PerkTree.CENTRE);
                double a = centre + wrap(angle(p) - g.mid()) * k;
                out.put(p.id(), new double[] {PerkTree.CENTRE + r * Math.cos(a), PerkTree.CENTRE + r * Math.sin(a)});
            }
        }
        return out;
    }

    private static double angle(PerkTree.Perk p) {
        return Math.atan2(p.y() - PerkTree.CENTRE, p.x() - PerkTree.CENTRE);
    }

    static double wrap(double a) {
        return Math.atan2(Math.sin(a), Math.cos(a));
    }

    static double ease(double t) {
        return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
    }
}
