package games.brennan.dungeontrain.compat.photo.album;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The name an album picture goes by, here and on the relay: sha256 of its pixels as an unfiltered
 * PNG holds them — each row a 0 filter byte then the palette indices. That is exactly what the relay
 * hashes ({@code playerphotos.inspectImage}, inflated scanlines), and {@code PhotoPngCodec} writes no
 * other filter, so both sides name the same picture the same way without the relay answering first.
 */
public final class AlbumImageHash {

    /** Exposure store id prefix for album pictures in this world. */
    static final String EXPOSURE_ID_PREFIX = "dt_album_";

    private AlbumImageHash() {}

    /** Hex sha256 of {@code pixels} ({@code width * height} palette indices, row by row). */
    public static String of(int width, int height, byte[] pixels) {
        if (width < 1 || height < 1 || pixels.length != width * height) {
            throw new IllegalArgumentException("pixels do not match " + width + "x" + height);
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] filter = {0};
            for (int y = 0; y < height; y++) {
                sha.update(filter);
                sha.update(pixels, y * width, width);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Where this picture is kept in a world's Exposure store. */
    public static String exposureId(String hash) {
        return EXPOSURE_ID_PREFIX + hash;
    }

    public static boolean isHash(String value) {
        return value != null && value.length() == 64 && value.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }
}
