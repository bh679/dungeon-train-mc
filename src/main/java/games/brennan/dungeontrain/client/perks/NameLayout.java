package games.brennan.dungeontrain.client.perks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the perk names go on screen so none overlaps another name, a class title or another star.
 * Names are placed in priority order (the caller's order); each tries below its star, then above,
 * right and left, and is left out when every spot is taken. Pure screen-space geometry, per frame.
 */
final class NameLayout {

    /** A screen rectangle. */
    record Box(double x0, double y0, double x1, double y1) {
        boolean hits(Box o) {
            return x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1;
        }
    }

    /**
     * A star to name: its id, screen centre, halo radius and the label's width and height. A
     * {@code forced} name is always placed: at its first free spot, or below its star if none is free.
     */
    record Want(String id, double x, double y, double halo, int width, int height, boolean forced) {
        Want(String id, double x, double y, double halo, int width, int height) {
            this(id, x, y, halo, width, height, false);
        }
    }

    private static final double GAP = 3, PAD = 1;

    private NameLayout() {}

    /**
     * @param wants     stars to name, highest priority first
     * @param obstacles boxes nothing may cover (class titles)
     * @param stars     every drawn star's centre and halo radius {x, y, r}, keyed by id
     * @return id to the label's top-left corner, for the names that fit
     */
    static Map<String, int[]> place(List<Want> wants, List<Box> obstacles, Map<String, double[]> stars) {
        List<Box> taken = new ArrayList<>(obstacles);
        Map<String, int[]> out = new LinkedHashMap<>();
        for (Want w : wants) {
            double[] chosen = null;
            for (double[] c : candidates(w)) {
                Box b = box(c, w);
                if (hitsAny(b, taken) || hitsStar(b, w.id(), stars)) continue;
                chosen = c;
                break;
            }
            if (chosen == null && w.forced()) {
                // clear of the other names at least, else straight below its star
                for (double[] c : candidates(w)) if (!hitsAny(box(c, w), taken)) { chosen = c; break; }
                if (chosen == null) chosen = candidates(w)[0];
            }
            if (chosen == null) continue;
            taken.add(box(chosen, w));
            out.put(w.id(), new int[] {(int) Math.round(chosen[0]), (int) Math.round(chosen[1])});
        }
        return out;
    }

    private static Box box(double[] c, Want w) {
        return new Box(c[0] - PAD, c[1] - PAD, c[0] + w.width() + PAD, c[1] + w.height() + PAD);
    }

    private static double[][] candidates(Want w) {
        double cx = w.x() - w.width() / 2.0;
        return new double[][] {
            {cx, w.y() + w.halo() + GAP},                              // below
            {cx, w.y() - w.halo() - GAP - w.height()},                 // above
            {w.x() + w.halo() + GAP, w.y() - w.height() / 2.0},        // right
            {w.x() - w.halo() - GAP - w.width(), w.y() - w.height() / 2.0}, // left
        };
    }

    private static boolean hitsAny(Box b, List<Box> boxes) {
        for (Box o : boxes) if (b.hits(o)) return true;
        return false;
    }

    /** Covers another star's disc (its own star never counts). */
    private static boolean hitsStar(Box b, String own, Map<String, double[]> stars) {
        for (Map.Entry<String, double[]> e : stars.entrySet()) {
            if (e.getKey().equals(own)) continue;
            double[] s = e.getValue();
            double nx = Math.max(b.x0(), Math.min(s[0], b.x1())), ny = Math.max(b.y0(), Math.min(s[1], b.y1()));
            if (Math.hypot(nx - s[0], ny - s[1]) < s[2]) return true;
        }
        return false;
    }
}
