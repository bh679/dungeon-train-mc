package games.brennan.dungeontrain.client.constellation;

import java.util.List;

/**
 * Where the rays that clear blocks out of the constellation's way are aimed, in the picture's GUI
 * units. Pure maths, unit-tested.
 */
final class StarPadding {

    /** The padding round a star, as a multiple of its size. */
    static final float STAR_PADDING = 2f;

    private StarPadding() {}

    /** A star's centre, a ring at its edge and a ring at its padding ({@link #STAR_PADDING} times its size). */
    static void around(float cx, float cy, float radius, List<float[]> out) {
        out.add(new float[] {cx, cy});
        ring(cx, cy, radius, 6, out);
        ring(cx, cy, radius * STAR_PADDING, 10, out);
    }

    /**
     * A filled disc of {@code radius}, sampled in rings about {@code spacing} apart, so a block anywhere
     * in it that is bigger than a sample gap is met by a ray.
     */
    static void disc(float cx, float cy, float radius, float spacing, List<float[]> out) {
        float step = Math.max(spacing, radius / 8f);
        out.add(new float[] {cx, cy});
        for (float r = step; r < radius + step * 0.5f; r += step) {
            float ringR = Math.min(r, radius);
            int n = Math.max(6, (int) Math.ceil(2 * Math.PI * ringR / step));
            ring(cx, cy, ringR, n, out);
        }
    }

    private static void ring(float cx, float cy, float r, int n, List<float[]> out) {
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            out.add(new float[] {cx + r * (float) Math.cos(t), cy + r * (float) Math.sin(t)});
        }
    }
}
