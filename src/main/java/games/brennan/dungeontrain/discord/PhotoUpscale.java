package games.brennan.dungeontrain.discord;

/**
 * Makes a small photo big enough to look at in Discord. An embed shows an image at its own size
 * when that is small, so a 320 px print lands as a thumbnail; this blows it up by a whole-number
 * factor, nearest-neighbour, so each pixel becomes a clean square — bigger, not blurrier, and no
 * sharper than it ever was. Pure array work on the palette indices, before the PNG is encoded.
 */
public final class PhotoUpscale {

    /** The long edge the posted image should reach. */
    static final int TARGET_LONG_EDGE = 1024;
    /** Never past this — a 640 px photo doubles to 1280 and stops. */
    static final int MAX_LONG_EDGE = 2048;

    private PhotoUpscale() {}

    /**
     * The smallest whole factor that brings the long edge to {@link #TARGET_LONG_EDGE}, without
     * passing {@link #MAX_LONG_EDGE}; 1 for a photo already that large (or a nonsense size).
     */
    public static int factorFor(int width, int height) {
        int longEdge = Math.max(width, height);
        if (longEdge <= 0 || longEdge >= TARGET_LONG_EDGE) return 1;
        int factor = (TARGET_LONG_EDGE + longEdge - 1) / longEdge;      // ceil
        while (factor > 1 && longEdge * factor > MAX_LONG_EDGE) factor--;
        return Math.max(1, factor);
    }

    /**
     * {@code pixels} ({@code pixels[y * width + x]}) repeated {@code factor} times each way, as a new
     * array of {@code width * factor} by {@code height * factor}. The input is not modified.
     */
    public static byte[] nearest(byte[] pixels, int width, int height, int factor) {
        if (factor <= 1) return pixels.clone();
        int outWidth = width * factor;
        byte[] out = new byte[outWidth * height * factor];
        for (int y = 0; y < height; y++) {
            int rowStart = y * factor * outWidth;
            for (int x = 0; x < width; x++) {
                byte value = pixels[y * width + x];
                int at = rowStart + x * factor;
                for (int i = 0; i < factor; i++) out[at + i] = value;
            }
            // The first scaled row is built; the rest of this pixel row's block are copies of it.
            for (int i = 1; i < factor; i++) {
                System.arraycopy(out, rowStart, out, rowStart + i * outWidth, outWidth);
            }
        }
        return out;
    }
}
