package games.brennan.dungeontrain.perks;

import java.util.List;

/**
 * A chance curve over Diff-Level, drawn as a chain of cubic bezier segments (x = Diff-Level,
 * y = chance in per cent), flat before its first point and after its last. Used by
 * {@link CosmicAppleDrops#tierFor} for how often the rarer perk apples turn up at each depth.
 *
 * <p>Each segment's handles must sit between its two ends on x, so x rises along the segment and
 * {@link #at} can find the point for a Diff-Level by bisection. The constructor checks this.
 */
public final class AppleTierCurve {

    /** A point on the curve or a bezier handle, in (Diff-Level, per cent). */
    public record Point(double x, double y) {}

    /** One cubic bezier piece: from {@code start}, pulled by {@code out} then {@code in}, to {@code end}. */
    public record Segment(Point start, Point out, Point in, Point end) {}

    /** Bisection steps when solving for the bezier parameter: far below a basis point of error. */
    private static final int SOLVE_STEPS = 40;

    /** Celestial Bite: none until Diff-Level 5, 13.8% at 41, 25% from 100 on. */
    public static final AppleTierCurve BITE = new AppleTierCurve(List.of(
        new Segment(new Point(5, 0), new Point(11.7, 0), new Point(30.3, 9), new Point(41, 13.8)),
        new Segment(new Point(41, 13.8), new Point(54.2, 19.7), new Point(75, 25), new Point(100, 25))));

    /** Violet Core: none until Diff-Level 25, slow until about 75, 10% from 150 on. */
    public static final AppleTierCurve CORE = new AppleTierCurve(List.of(
        new Segment(new Point(25, 0), new Point(83.2, 0.5), new Point(125, 10), new Point(150, 10))));

    private final List<Segment> segments;

    public AppleTierCurve(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("a curve needs at least one segment");
        }
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (!(s.start().x() < s.end().x())
                    || s.out().x() < s.start().x() || s.out().x() > s.end().x()
                    || s.in().x() < s.start().x() || s.in().x() > s.end().x()) {
                throw new IllegalArgumentException("segment " + i + " does not rise along x: " + s);
            }
            if (i > 0 && !segments.get(i - 1).end().equals(s.start())) {
                throw new IllegalArgumentException("segment " + i + " does not start where segment " + (i - 1) + " ends");
            }
        }
        this.segments = List.copyOf(segments);
    }

    /** The curve's chance in per cent at {@code diffLevel}, never below 0. */
    public double at(double diffLevel) {
        Segment first = segments.get(0);
        if (diffLevel <= first.start().x()) return Math.max(0, first.start().y());
        Segment last = segments.get(segments.size() - 1);
        if (diffLevel >= last.end().x()) return Math.max(0, last.end().y());
        for (Segment s : segments) {
            if (diffLevel <= s.end().x()) return Math.max(0, yOn(s, diffLevel));
        }
        return Math.max(0, last.end().y());
    }

    /** The curve's chance at {@code diffLevel} in basis points (hundredths of a per cent). */
    public int basisPointsAt(int diffLevel) {
        return (int) Math.round(at(diffLevel) * 100);
    }

    private static double yOn(Segment s, double x) {
        double lo = 0;
        double hi = 1;
        for (int n = 0; n < SOLVE_STEPS; n++) {
            double mid = (lo + hi) / 2;
            if (cubic(s.start().x(), s.out().x(), s.in().x(), s.end().x(), mid) < x) lo = mid;
            else hi = mid;
        }
        return cubic(s.start().y(), s.out().y(), s.in().y(), s.end().y(), (lo + hi) / 2);
    }

    private static double cubic(double p0, double p1, double p2, double p3, double t) {
        double u = 1 - t;
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3;
    }
}
