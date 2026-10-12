package games.brennan.dungeontrain.discord;

/**
 * Lays a photo on the paper it was printed on, the way Exposure draws it in game: the picture inset
 * on a 64-texel paper with {@link #PICTURE_INSET} texels of border each side, and — on DT's worn
 * papers — the picture cut away wherever the paper is torn (as {@code client.TornEdgeEffect} does).
 * Used for the tributed-photo post, so the community sees the photo as the tributer held it.
 *
 * <p>The picture keeps every pixel exactly; only the paper is stretched, nearest-neighbour, to fit
 * around it. Pure array work, any thread.</p>
 */
public final class PhotoPaperComposite {

    /** Exposure's paper texture is this many texels square, with the picture inset on every side. */
    public static final int PAPER_SIZE = 64;
    static final int PICTURE_INSET = 4;
    private static final int PICTURE_SIZE = PAPER_SIZE - 2 * PICTURE_INSET;

    /** The composite: {@code argb[y * width + x]}. */
    public record Composite(int width, int height, int[] argb) {}

    private PhotoPaperComposite() {}

    /** Output pixels of border each side of a picture {@code pictureSize} across. */
    static int border(int pictureSize) {
        return Math.round(pictureSize * (float) PICTURE_INSET / PICTURE_SIZE);
    }

    /**
     * @param paper   the paper texture, {@code PAPER_SIZE * PAPER_SIZE} ARGB; alpha 0 is a tear
     * @param picture the photo, {@code width * height} ARGB
     */
    public static Composite compose(int[] paper, int[] picture, int width, int height) {
        if (paper.length != PAPER_SIZE * PAPER_SIZE) {
            throw new IllegalArgumentException("paper has " + paper.length + " texels, expected " + PAPER_SIZE * PAPER_SIZE);
        }
        if (width <= 0 || height <= 0 || picture.length != width * height) {
            throw new IllegalArgumentException("picture is " + width + "x" + height + " but has " + picture.length + " pixels");
        }
        int borderX = border(width);
        int borderY = border(height);
        int outWidth = width + 2 * borderX;
        int outHeight = height + 2 * borderY;
        int[] out = new int[outWidth * outHeight];
        for (int y = 0; y < outHeight; y++) {
            int py = y - borderY;
            int texelY = py >= 0 && py < height
                    ? PICTURE_INSET + py * PICTURE_SIZE / height
                    : Math.min(PAPER_SIZE - 1, y * PAPER_SIZE / outHeight);
            for (int x = 0; x < outWidth; x++) {
                int px = x - borderX;
                boolean inPicture = px >= 0 && px < width && py >= 0 && py < height;
                // Inside the picture, map exactly as TornEdgeEffect does, so tears line up with the game.
                int texelX = px >= 0 && px < width
                        ? PICTURE_INSET + px * PICTURE_SIZE / width
                        : Math.min(PAPER_SIZE - 1, x * PAPER_SIZE / outWidth);
                int texel = paper[texelY * PAPER_SIZE + texelX];
                boolean torn = (texel >>> 24) == 0;
                out[y * outWidth + x] = torn ? 0 : inPicture ? picture[py * width + px] : texel;
            }
        }
        return new Composite(outWidth, outHeight, out);
    }
}
