package games.brennan.dungeontrain.compat.photo;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns an Exposure photo — one palette index per pixel — into a small JPEG for the relay, and a
 * JPEG from the relay back into palette indices. Exposure can only draw its own palette, so the
 * shared copy is lossy twice (JPEG, then nearest palette colour); the photographer's own print is
 * never touched.
 *
 * <p>Pure: no Minecraft or Exposure types, so it runs on any thread and under plain JUnit. Both
 * directions are meant to be called off the server thread.</p>
 */
public final class PhotoJpegCodec {

    /** JPEG quality for shared photos — about 5 KB for a 320×320 print. */
    public static final float QUALITY = 0.4f;

    /** Largest side the relay accepts. */
    public static final int MAX_DIMENSION = 320;

    /** A decoded photo: {@code pixels[y * width + x]} is an index into the palette it was decoded against. */
    public record Decoded(int width, int height, byte[] pixels) {}

    private PhotoJpegCodec() {}

    /**
     * @param pixels  palette indices, row by row, {@code width * height} long
     * @param palette ARGB colours by index
     */
    public static byte[] encode(int width, int height, byte[] pixels, int[] palette, float quality) throws IOException {
        if (width <= 0 || height <= 0 || pixels.length != width * height) {
            throw new IOException("photo is " + width + "x" + height + " but has " + pixels.length + " pixels");
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < pixels.length; i++) {
            image.setRGB(i % width, i / width, palette[pixels[i] & 0xFF] & 0xFFFFFF);
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8 * 1024);
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** Decode a relay JPEG and snap every pixel to the nearest opaque colour of {@code palette}. */
    public static Decoded decode(byte[] jpeg, int[] palette) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (image == null) throw new IOException("not a readable image");
        int width = image.getWidth();
        int height = image.getHeight();
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException("photo is " + width + "x" + height + ", larger than " + MAX_DIMENSION);
        }
        byte[] pixels = new byte[width * height];
        Map<Integer, Byte> nearest = new HashMap<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                pixels[y * width + x] = nearest.computeIfAbsent(rgb, c -> nearestIndex(c, palette));
            }
        }
        return new Decoded(width, height, pixels);
    }

    /** Index of the opaque palette colour closest to {@code rgb}; transparent entries are never chosen. */
    static byte nearestIndex(int rgb, int[] palette) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int colour = palette[i];
            if ((colour >>> 24) == 0) continue;
            int dr = r - ((colour >> 16) & 0xFF), dg = g - ((colour >> 8) & 0xFF), db = b - (colour & 0xFF);
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return (byte) best;
    }
}
