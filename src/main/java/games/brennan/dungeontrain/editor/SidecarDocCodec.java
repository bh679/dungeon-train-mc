package games.brennan.dungeontrain.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The wire form of a {@link TemplateSidecars} document: plain JSON when it is small, and
 * {@code {"gz":"<base64 gzip of the plain JSON>"}} when it is not.
 *
 * <p>Exists because a portal room's variants file is routinely past the relay's 200k field cap on
 * its own — and a document over the cap used to be dropped whole, taking the chests' loot links
 * with it. Variant JSON is pretty-printed and endlessly repetitive, so it shrinks by an order of
 * magnitude. Small documents stay plain, so every build that already fit travels byte-for-byte as
 * before, and an older install reading a compressed one simply finds no {@code files} — the same
 * nothing it got for that build before this existed.</p>
 */
final class SidecarDocCodec {

    static final String K_GZ = "gz";

    /**
     * Refuse to inflate past this. A real document is well under a megabyte even uncompressed; a
     * payload that inflates beyond this is a zip bomb or garbage, and is read as carrying nothing.
     */
    static final int MAX_INFLATED_BYTES = 8 * 1024 * 1024;

    private SidecarDocCodec() {}

    /** {@code plain} as a compressed envelope. */
    static String compress(String plain) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(plain.getBytes(StandardCharsets.UTF_8));
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty(K_GZ, Base64.getEncoder().encodeToString(baos.toByteArray()));
        return envelope.toString();
    }

    /**
     * The document's root object, unwrapping a compressed envelope when it is one.
     *
     * @throws IOException or a Gson/argument exception when {@code doc} is neither a plain document
     *                     nor a well-formed envelope within {@link #MAX_INFLATED_BYTES}
     */
    static JsonObject parse(String doc) throws IOException {
        JsonObject root = JsonParser.parseString(doc).getAsJsonObject();
        JsonElement gz = root.get(K_GZ);
        if (gz == null || !gz.isJsonPrimitive()) return root;
        byte[] packed = Base64.getDecoder().decode(gz.getAsString());
        return JsonParser.parseString(inflate(packed)).getAsJsonObject();
    }

    private static String inflate(byte[] packed) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > MAX_INFLATED_BYTES) {
                    throw new IOException("sidecar document inflates past " + MAX_INFLATED_BYTES + " bytes");
                }
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
