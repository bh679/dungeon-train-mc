package games.brennan.dungeontrain.client.logo;

import java.util.List;

/**
 * The loading bar and its finish, as geometry. Eight carriages in a line on y = 0 fill in with the
 * progress (the front carriage growing); once loaded, a {@link Finish} turns them into the logo.
 *
 * <p>The finish is {@link PortalFinish}. {@link RollFinish} is kept as a dev alternative, chosen
 * with {@code -Ddungeontrain.loadingFinish=roll}.</p>
 *
 * <p>Two facts make the finishes work: four carriages and their gaps are exactly the logo's
 * circumference (2 pi R), and the bar twists at exactly the logo's rate (half a turn per
 * circumference). So four carriages laid round a circle of radius R simply are the logo, with
 * nothing resized or merged. Plain maths; {@link LogoRingRenderer} supplies the clock and draws it.</p>
 */
final class BarMorph {

    static final int CARRIAGES = 8;
    static final double CAR = LogoRingGeometry.segmentLength();
    static final double GAP_LEN = LogoRingGeometry.gapLength();
    static final double TOTAL = CARRIAGES * CAR + (CARRIAGES - 1) * GAP_LEN;
    static final double MID = TOTAL / 2;
    /** The tube's half height including its outline, world units. */
    static final double TUBE = LogoRingGeometry.HALF * Math.sqrt(2) + LogoRingGeometry.OUTLINE;

    /** A portal, drawn by the renderer as a glowing vertical line: centred on (x, y), grown {@code grown} of the way up from its foot. */
    record PortalLine(double x, double y, double height, double grown) {}

    /** How the loaded bar becomes the logo, from morph time 0 (the moment it read 100%). */
    interface Finish {
        /** The carriages at morph time t. */
        void emit(double t, double drift, double spin, LogoCanvas.Emitter out);

        /** How fast the logo's cuts turn (0..1 of their speed); 0 when the carriages' own travel is the turning. */
        double spinRamp(double t);

        /** Carriages fading out, drawn on their own layer at {@link #fadeAlpha}. */
        default void emitFading(double t, double drift, LogoCanvas.Emitter out) { }

        default float fadeAlpha(double t) { return 0f; }

        /** Portals showing at morph time t. */
        default List<PortalLine> portals(double t) { return List.of(); }
    }

    private BarMorph() {}

    static Finish finish() {
        return "roll".equals(System.getProperty("dungeontrain.loadingFinish")) ? RollFinish.INSTANCE : PortalFinish.INSTANCE;
    }

    /**
     * Emits the tube at morph time {@code t}: negative is the plain bar, its front {@code front} of
     * the way along; from 0, the finish.
     *
     * @param twistOffset the twist's drift
     * @param spin        how far the finished logo's cuts have turned
     */
    static void emit(double t, double front, double twistOffset, double spin, LogoCanvas.Emitter out) {
        if (t >= 0) {
            finish().emit(t, twistOffset, spin, out);
            return;
        }
        double end = front * TOTAL;
        for (int k = 0; k < CARRIAGES; k++) {
            double start = k * (CAR + GAP_LEN), len = Math.min(CAR, end - start);
            if (len < 0.01) break;
            LogoRingGeometry.buildBent(0, MID, start, len, twistOffset, k % LogoRingGeometry.PIECES, out.withSegLen((float) len));
        }
    }

    static double ease(double x) {
        double c = Math.max(0, Math.min(1, x));
        return c * c * (3 - 2 * c);
    }
}
