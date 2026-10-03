package games.brennan.dungeontrain.compat.photo;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Writes an Exposure photo — one palette index per pixel — as an 8-bit palette-indexed PNG for the
 * relay, and reads one back. Lossless: the pixel bytes in the file are Exposure's own indices, so a
 * shared photo is the photographer's print exactly, and the relay can recognise a photo by its
 * pixels however a given machine compressed them.
 *
 * <p>Pure: no Minecraft, Exposure or AWT types, so it runs on any thread and under plain JUnit.
 * Both directions are meant to be called off the server thread.</p>
 */
public final class PhotoPngCodec {

    /** Largest side the relay accepts. */
    public static final int MAX_DIMENSION = 640;

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final int PALETTE_SIZE = 256;

    /** A decoded photo: {@code pixels[y * width + x]} is an index into Exposure's palette. */
    public record Decoded(int width, int height, byte[] pixels) {}

    private PhotoPngCodec() {}

    /**
     * @param pixels  palette indices, row by row, {@code width * height} long
     * @param palette ARGB colours by index — written so any image viewer shows the photo correctly
     */
    public static byte[] encode(int width, int height, byte[] pixels, int[] palette) throws IOException {
        if (width <= 0 || height <= 0 || pixels.length != width * height) {
            throw new IOException("photo is " + width + "x" + height + " but has " + pixels.length + " pixels");
        }
        byte[] rows = new byte[height * (width + 1)];
        for (int y = 0; y < height; y++) {
            // Each scanline starts with its filter byte; 0 (none) keeps the indices verbatim.
            System.arraycopy(pixels, y * width, rows, y * (width + 1) + 1, width);
        }
        byte[] colours = new byte[PALETTE_SIZE * 3];
        byte[] alpha = new byte[PALETTE_SIZE];
        for (int i = 0; i < PALETTE_SIZE; i++) {
            int argb = i < palette.length ? palette[i] : 0;
            colours[i * 3] = (byte) (argb >> 16);
            colours[i * 3 + 1] = (byte) (argb >> 8);
            colours[i * 3 + 2] = (byte) argb;
            alpha[i] = (byte) (argb >>> 24);
        }
        ByteBuffer header = ByteBuffer.allocate(13);
        header.putInt(width).putInt(height).put((byte) 8).put((byte) 3).put((byte) 0).put((byte) 0).put((byte) 0);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(rows.length / 3);
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(SIGNATURE);
        writeChunk(out, "IHDR", header.array());
        writeChunk(out, "PLTE", colours);
        writeChunk(out, "tRNS", alpha);
        writeChunk(out, "IDAT", deflate(rows));
        writeChunk(out, "IEND", new byte[0]);
        return bytes.toByteArray();
    }

    /** Read a PNG this codec wrote. Anything else — another colour type, a filtered row — is refused. */
    public static Decoded decode(byte[] png) throws IOException {
        if (png.length < SIGNATURE.length || !Arrays.equals(Arrays.copyOf(png, SIGNATURE.length), SIGNATURE)) {
            throw new IOException("not a PNG");
        }
        ByteBuffer in = ByteBuffer.wrap(png, SIGNATURE.length, png.length - SIGNATURE.length);
        int width = 0;
        int height = 0;
        ByteArrayOutputStream data = new ByteArrayOutputStream(png.length);
        while (in.remaining() >= 12) {
            int length = in.getInt();
            byte[] type = new byte[4];
            in.get(type);
            if (length < 0 || length > in.remaining() - 4) throw new IOException("truncated PNG");
            byte[] body = new byte[length];
            in.get(body);
            in.getInt(); // CRC
            String name = new String(type, StandardCharsets.US_ASCII);
            if (name.equals("IHDR")) {
                ByteBuffer header = ByteBuffer.wrap(body);
                width = header.getInt();
                height = header.getInt();
                if (header.get() != 8 || header.get() != 3 || header.get(12) != 0) {
                    throw new IOException("not an 8-bit palette PNG");
                }
            } else if (name.equals("IDAT")) {
                data.write(body);
            }
        }
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException("photo is " + width + "x" + height + ", outside 1.." + MAX_DIMENSION);
        }
        byte[] rows = inflate(data.toByteArray(), height * (width + 1));
        byte[] pixels = new byte[width * height];
        for (int y = 0; y < height; y++) {
            if (rows[y * (width + 1)] != 0) throw new IOException("filtered scanline");
            System.arraycopy(rows, y * (width + 1) + 1, pixels, y * width, width);
        }
        return new Decoded(width, height, pixels);
    }

    private static void writeChunk(DataOutputStream out, String type, byte[] body) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(body);
        out.writeInt(body.length);
        out.write(name);
        out.write(body);
        out.writeInt((int) crc.getValue());
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 3);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] compressed, int expected) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] raw = new byte[expected];
            int read = 0;
            while (read < expected && !inflater.finished()) {
                int n = inflater.inflate(raw, read, expected - read);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                read += n;
            }
            if (read != expected) throw new IOException("pixel data is " + read + " bytes, expected " + expected);
            return raw;
        } catch (DataFormatException e) {
            throw new IOException("corrupt pixel data", e);
        } finally {
            inflater.end();
        }
    }
}
