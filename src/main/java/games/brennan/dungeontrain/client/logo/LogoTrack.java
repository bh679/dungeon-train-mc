package games.brennan.dungeontrain.client.logo;

/**
 * The track the loading bar's carriages drive off along once loaded, in the bar's own world units
 * (the bar lies on y = 0 from x = 0 to {@code barLength}, y up). On past the bar's end, a bend up,
 * a climb, a bend back, a run back left along the top, then a loop: a circle of radius
 * {@link #LOOP_R} hanging from the top line, whose circumference the eight carriages and their gaps
 * fill exactly, so the train closes into a ring.
 *
 * <p>Sampled once into a table of points with their exact arc lengths; {@link #point} interpolates
 * it (and runs straight on past either end).</p>
 */
final class LogoTrack {

    /** The loop's radius: eight carriages and gaps fill a circle of radius 2R. */
    static final double LOOP_R = 2 * LogoRingGeometry.R;
    private static final double RUN_ON = 0.5;
    private static final double BEND_R = 0.8;
    private static final double TOP_Y = 2.6;
    private static final double STEP = 0.004;

    private final double[] xs, ys, headings, lengths;
    /** Arc length where the loop starts. */
    final double loopStart;
    /** The loop's centre. */
    final double loopX, loopY;

    LogoTrack(double barLength) {
        Builder b = new Builder();
        b.line(barLength + RUN_ON);                           // on past the bar's end
        b.arc(BEND_R, Math.PI / 2);                           // bend up
        b.line(TOP_Y - 2 * BEND_R);                           // climb
        b.arc(BEND_R, Math.PI / 2);                           // bend back left
        b.line(b.x - barLength / 2);                          // back along the top, to over the bar's middle
        loopStart = b.length;
        loopX = b.x;                // heading back left (pi), so the loop's centre is straight below
        loopY = b.y - LOOP_R;
        b.arc(LOOP_R, 2 * Math.PI);                           // the loop
        xs = b.xs.stream().mapToDouble(Double::doubleValue).toArray();
        ys = b.ys.stream().mapToDouble(Double::doubleValue).toArray();
        headings = b.hs.stream().mapToDouble(Double::doubleValue).toArray();
        lengths = b.ls.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** {x, y, heading} at arc length {@code s}. */
    double[] point(double s) {
        int last = xs.length - 1;
        if (s <= 0) return new double[] { xs[0] + s * Math.cos(headings[0]), ys[0] + s * Math.sin(headings[0]), headings[0] };
        if (s >= lengths[last]) {
            double over = s - lengths[last];
            return new double[] { xs[last] + over * Math.cos(headings[last]), ys[last] + over * Math.sin(headings[last]), headings[last] };
        }
        int i = java.util.Arrays.binarySearch(lengths, s);
        if (i < 0) i = -i - 2; // the sample at or before s
        double a = (s - lengths[i]) / (lengths[i + 1] - lengths[i]);
        return new double[] { xs[i] + (xs[i + 1] - xs[i]) * a, ys[i] + (ys[i + 1] - ys[i]) * a, headings[i] + (headings[i + 1] - headings[i]) * a };
    }

    /** Lines and left-hand arcs, sampled every STEP of length. */
    private static final class Builder {
        final java.util.List<Double> xs = new java.util.ArrayList<>(), ys = new java.util.ArrayList<>(),
                hs = new java.util.ArrayList<>(), ls = new java.util.ArrayList<>();
        double x, y, heading, length;
        Builder() { add(); }

        void line(double len) {
            int n = Math.max(1, (int) Math.ceil(len / STEP));
            for (int i = 0; i < n; i++) {
                x += len / n * Math.cos(heading);
                y += len / n * Math.sin(heading);
                length += len / n;
                add();
            }
        }

        /** Turns left (counter-clockwise) by {@code angle} on a circle of {@code radius}. */
        void arc(double radius, double angle) {
            double cx = x - radius * Math.sin(heading), cy = y + radius * Math.cos(heading);
            int n = Math.max(1, (int) Math.ceil(radius * angle / STEP));
            double start = heading;
            for (int i = 1; i <= n; i++) {
                heading = start + angle * i / n;
                x = cx + radius * Math.sin(heading);
                y = cy - radius * Math.cos(heading);
                length += radius * angle / n;
                add();
            }
        }

        private void add() { xs.add(x); ys.add(y); hs.add(heading); ls.add(length); }
    }
}
