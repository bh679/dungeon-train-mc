package games.brennan.dungeontrain.client.deathphotos;

/**
 * Pure layout maths for the photo strip: one row of thumbnails, each as tall as the row and as wide
 * as its photo's aspect allows (clamped so a panorama or a sliver can't dominate the row).
 *
 * <p>Offsets are relative to the row's left edge before scrolling; {@link #maxScroll} and
 * {@link #indexAt} turn them into the viewport's terms.</p>
 */
public record StripLayout(int[] xs, int[] widths, int height, int totalWidth) {

    /** Narrowest / widest a thumbnail may be, as a multiple of the row height. */
    static final float MIN_ASPECT = 0.5f;
    static final float MAX_ASPECT = 2.0f;

    public static StripLayout of(float[] aspects, int height, int gap) {
        int n = aspects.length;
        int[] xs = new int[n];
        int[] ws = new int[n];
        int x = 0;
        for (int i = 0; i < n; i++) {
            float a = Float.isFinite(aspects[i]) && aspects[i] > 0 ? aspects[i] : 1.0f;
            a = Math.max(MIN_ASPECT, Math.min(MAX_ASPECT, a));
            xs[i] = x;
            ws[i] = Math.max(1, Math.round(height * a));
            x += ws[i] + (i < n - 1 ? gap : 0);
        }
        return new StripLayout(xs, ws, height, n == 0 ? 0 : x);
    }

    /** How far the row can scroll inside a viewport {@code viewportWidth} wide (0 when it fits). */
    public int maxScroll(int viewportWidth) {
        return Math.max(0, totalWidth - viewportWidth);
    }

    /**
     * Left inset that centres a row narrower than the viewport; 0 when it overflows (then it scrolls
     * from the left edge instead).
     */
    public int centreInset(int viewportWidth) {
        return Math.max(0, (viewportWidth - totalWidth) / 2);
    }

    /** The thumbnail whose centre is nearest the viewport's centre at {@code scroll} (-1 if empty). */
    public int centredIndex(int scroll, int viewportWidth) {
        int mid = scroll + viewportWidth / 2 - centreInset(viewportWidth);
        int best = -1, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            int d = Math.abs(xs[i] + widths[i] / 2 - mid);
            if (d < bestDist) { best = i; bestDist = d; }
        }
        return best;
    }

    /** The scroll that centres thumbnail {@code index} in the viewport, clamped to the row's ends. */
    public int scrollToCentre(int index, int viewportWidth) {
        if (index < 0 || index >= xs.length) return 0;
        int s = xs[index] + widths[index] / 2 - viewportWidth / 2;
        return Math.max(0, Math.min(maxScroll(viewportWidth), s));
    }

    /**
     * The thumbnail under viewport-relative {@code localX} at scroll {@code scroll}, or -1 for a gap
     * or past either end. The caller checks the vertical bounds.
     */
    public int indexAt(int localX, int scroll, int viewportWidth) {
        int rowX = localX + scroll - centreInset(viewportWidth);
        for (int i = 0; i < xs.length; i++) {
            if (rowX >= xs[i] && rowX < xs[i] + widths[i]) return i;
        }
        return -1;
    }
}
