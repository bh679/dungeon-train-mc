package games.brennan.dungeontrain.client.live;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The real size of each decoded frame. The decoders pipe frames as PAM ({@code -c:v pam}): every frame
 * opens with a short text header naming its own width and height, then raw RGBA. So the picture's
 * shape comes with the picture, whatever shape the streamer's window was, and no viewer needs telling
 * it in advance.
 *
 * <p>A header looks like {@code P7\nWIDTH 640\nHEIGHT 480\nDEPTH 4\nMAXVAL 255\nTUPLTYPE
 * RGB_ALPHA\nENDHDR\n}. Pure parsing, any thread.</p>
 */
public final class LiveFrameShape {

    /** A header longer than this is not one of ffmpeg's. */
    static final int MAX_HEADER_BYTES = 256;
    /** The largest side a frame may claim; anything bigger is junk, not a stream. */
    static final int MAX_SIDE = 4096;
    private static final byte[] END = "ENDHDR\n".getBytes(StandardCharsets.US_ASCII);

    private LiveFrameShape() {}

    /** Width and height of one frame, in pixels. */
    public record Size(int width, int height) {
        /** Width over height. */
        public float ratio() { return (float) width / height; }
    }

    /**
     * Reads one header off {@code in}, up to and including its {@code ENDHDR} line. Null at a clean end
     * of stream before any byte.
     *
     * @throws IOException on a header that is cut off, too long or not RGBA (the run ends and retries)
     */
    @Nullable
    static Size read(InputStream in) throws IOException {
        byte[] buf = new byte[MAX_HEADER_BYTES];
        int n = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (n == 0) return null;
                throw new IOException("frame header cut off");
            }
            if (n == buf.length) throw new IOException("frame header too long");
            buf[n++] = (byte) b;
            if (endsWithEnd(buf, n)) break;
        }
        Size s = parse(new String(buf, 0, n, StandardCharsets.US_ASCII));
        if (s == null) throw new IOException("not an RGBA frame header");
        return s;
    }

    private static boolean endsWithEnd(byte[] buf, int n) {
        if (n < END.length) return false;
        for (int i = 0; i < END.length; i++) {
            if (buf[n - END.length + i] != END[i]) return false;
        }
        return true;
    }

    /** One whole header's text; null unless it is an 8-bit RGBA frame of a sane size. */
    @Nullable
    static Size parse(@Nullable String header) {
        if (header == null || !header.startsWith("P7\n")) return null;
        int w = -1, h = -1, depth = -1, maxval = -1;
        String type = null;
        for (String line : header.split("\n")) {
            String[] kv = line.trim().split("\\s+", 2);
            if (kv.length < 2) continue;
            try {
                switch (kv[0]) {
                    case "WIDTH" -> w = Integer.parseInt(kv[1]);
                    case "HEIGHT" -> h = Integer.parseInt(kv[1]);
                    case "DEPTH" -> depth = Integer.parseInt(kv[1]);
                    case "MAXVAL" -> maxval = Integer.parseInt(kv[1]);
                    case "TUPLTYPE" -> type = kv[1];
                    default -> { }
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (depth != 4 || maxval != 255 || !"RGB_ALPHA".equals(type)) return null;
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) return null;
        return new Size(w, h);
    }
}
