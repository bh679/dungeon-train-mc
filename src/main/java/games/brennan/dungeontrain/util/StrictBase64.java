package games.brennan.dungeontrain.util;

/**
 * The mod's only Base64 decoder. It is plain Java and never calls {@link java.util.Base64.Decoder}.
 *
 * <p><b>Why not the JDK's:</b> CVE-2026-114938 ("Java4Shell"). From OpenJDK 17.0.6, on x86-64 CPUs with
 * AVX-512 VBMI, the C2-compiled Base64 intrinsic writes past its output array when it decodes
 * malformed padding, before it throws. Players run whatever JDK their launcher ships, and some of the
 * Base64 we decode was written by another player and relayed to us (shared carriages, build
 * sidecars, death-note gear). So a hostile string must never reach the intrinsic. This decoder also
 * accepts nothing but the canonical output of {@code Base64.getEncoder()}, so a malformed string is
 * rejected outright rather than half-decoded.</p>
 *
 * <p>Accepted: the standard alphabet {@code A-Z a-z 0-9 + /}, length a multiple of 4, and at most
 * two {@code =}, only at the very end. No URL-safe {@code -_}, whitespace or line breaks. Anything
 * else throws {@link IllegalArgumentException}, the same type {@code Base64.getDecoder().decode}
 * throws, so existing catch blocks keep working.</p>
 *
 * <p>{@code StrictBase64Test} fails the build if any other class in {@code src/main/java} calls a
 * JDK Base64 decoder.</p>
 */
public final class StrictBase64 {

    private static final int INVALID = -1;
    private static final int[] DECODE_TABLE = buildDecodeTable();

    private StrictBase64() {}

    /**
     * Decode canonical standard Base64.
     *
     * @throws IllegalArgumentException if {@code text} is null or not canonical standard Base64
     */
    public static byte[] decode(String text) {
        if (text == null) throw new IllegalArgumentException("base64: null input");
        int length = text.length();
        if (length % 4 != 0) throw new IllegalArgumentException("base64: length " + length + " is not a multiple of 4");
        if (length == 0) return new byte[0];

        int padding = paddingCount(text, length);
        byte[] out = new byte[length / 4 * 3 - padding];
        int outPos = 0;
        for (int i = 0; i < length; i += 4) {
            boolean lastQuad = i + 4 == length;
            int a = sextet(text, i);
            int b = sextet(text, i + 1);
            int c = lastQuad && padding == 2 ? 0 : sextet(text, i + 2);
            int d = lastQuad && padding >= 1 ? 0 : sextet(text, i + 3);
            int bits = (a << 18) | (b << 12) | (c << 6) | d;
            out[outPos++] = (byte) (bits >> 16);
            if (outPos < out.length) out[outPos++] = (byte) (bits >> 8);
            if (outPos < out.length) out[outPos++] = (byte) bits;
        }
        return out;
    }

    /** Trailing {@code =} count (0-2); throws if there are more, or if any appear before the tail. */
    private static int paddingCount(String text, int length) {
        int padding = 0;
        while (padding < length && text.charAt(length - 1 - padding) == '=') padding++;
        if (padding > 2) throw new IllegalArgumentException("base64: " + padding + " padding characters");
        return padding;
    }

    private static int sextet(String text, int index) {
        char ch = text.charAt(index);
        int value = ch < DECODE_TABLE.length ? DECODE_TABLE[ch] : INVALID;
        if (value == INVALID) {
            throw new IllegalArgumentException("base64: illegal character 0x" + Integer.toHexString(ch) + " at " + index);
        }
        return value;
    }

    private static int[] buildDecodeTable() {
        int[] table = new int[128];
        java.util.Arrays.fill(table, INVALID);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < alphabet.length(); i++) table[alphabet.charAt(i)] = i;
        return table;
    }
}
