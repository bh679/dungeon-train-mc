package games.brennan.dungeontrain.client.live;

/**
 * The shape the live stream goes out at, and which part of the game window fills it. The picture is
 * never stretched: the stream takes the streamer's own window ratio (within {@link #MIN_RATIO} to
 * {@link #MAX_RATIO}), and whatever doesn't fit that ratio is cropped off, centred.
 *
 * <p>The ratio is fixed when a stream starts ({@link #frameSize}); a window resized mid-stream is
 * cropped to it ({@link #cropRect}) rather than changing the stream's size under its viewers. Pure
 * arithmetic, any thread.</p>
 */
public final class LiveAspect {

    /** Narrowest ratio streamed: square. A taller window loses its top and bottom. */
    static final double MIN_RATIO = 1.0;
    /** Widest ratio streamed: 21:9. A wider window loses its sides. */
    static final double MAX_RATIO = 21.0 / 9.0;

    private LiveAspect() {}

    /**
     * The stream's size, {@code {width, height}}: the window's ratio fitted inside the configured
     * {@code boxW×boxH} (so it never has more pixels than the setting promises), both sides even for
     * yuv420p. Bad input gives the box itself.
     */
    public static int[] frameSize(int boxW, int boxH, int winW, int winH) {
        if (boxW <= 0 || boxH <= 0 || winW <= 0 || winH <= 0) return new int[] {boxW, boxH};
        double ratio = Math.max(MIN_RATIO, Math.min(MAX_RATIO, (double) winW / winH));
        if (ratio >= (double) boxW / boxH) return new int[] {boxW, even(boxW / ratio)};
        return new int[] {even(boxH * ratio), boxH};
    }

    /**
     * The centred part of a {@code winW×winH} window with the frame's ratio, as
     * {@code {x0, y0, x1, y1}} window pixels. The whole window when the ratios match to within a pixel,
     * or on bad input.
     */
    public static int[] cropRect(int winW, int winH, int frameW, int frameH) {
        if (winW <= 0 || winH <= 0 || frameW <= 0 || frameH <= 0) return new int[] {0, 0, Math.max(0, winW), Math.max(0, winH)};
        double frame = (double) frameW / frameH;
        int cropW = (int) Math.round(winH * frame);
        if (cropW < winW - 1) {
            int x0 = (winW - cropW) / 2;
            return new int[] {x0, 0, x0 + cropW, winH};
        }
        int cropH = (int) Math.round(winW / frame);
        if (cropH < winH - 1) {
            int y0 = (winH - cropH) / 2;
            return new int[] {0, y0, winW, y0 + cropH};
        }
        return new int[] {0, 0, winW, winH};
    }

    /** {@code ratio} kept within {@link #MIN_RATIO}..{@link #MAX_RATIO}: the shapes a stream can have. */
    public static float clampRatio(float ratio) {
        if (!(ratio > 0)) return 16f / 9f;
        return (float) Math.max(MIN_RATIO, Math.min(MAX_RATIO, ratio));
    }

    private static int even(double v) {
        return Math.max(2, (int) Math.round(v / 2) * 2);
    }
}
