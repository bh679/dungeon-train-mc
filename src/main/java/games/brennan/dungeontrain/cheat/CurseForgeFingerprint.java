package games.brennan.dungeontrain.cheat;

import java.io.IOException;
import java.io.InputStream;

/**
 * CurseForge's file fingerprint: MurmurHash2 (32-bit, Austin Appleby's original) over the file with
 * every whitespace byte removed — tab (9), LF (10), CR (13) and space (32) — seeded with 1. It is
 * what {@code POST /v1/fingerprints} matches on, so the relay can ask CurseForge "which published
 * file is this?" for a jar Modrinth has never seen (see {@code mod-file-resolver.js}).
 *
 * <p>Two passes are needed because MurmurHash2 folds the input <em>length</em> into the seed, and
 * the length that counts is the whitespace-stripped one — unknown until the whole file has been
 * read. {@link ModFileScanner} gets that count for free from its SHA-1 pass, then calls
 * {@link #hash(InputStream, long)} for the second. Streaming, so a large jar never has to be
 * held in memory.</p>
 *
 * <p>Reference: {@code scripts/modpack/cf_fingerprint.py}, the Python twin the unit-test fixtures
 * are generated from. The two must agree byte for byte.</p>
 */
public final class CurseForgeFingerprint {

    private static final int M = 0x5bd1e995;
    private static final int R = 24;
    /** CurseForge's seed. */
    static final int SEED = 1;

    private CurseForgeFingerprint() {}

    /** Is this byte one CurseForge strips before hashing? */
    static boolean isWhitespace(int b) {
        return b == 9 || b == 10 || b == 13 || b == 32;
    }

    /** Count the bytes that survive {@link #isWhitespace} — the length MurmurHash2 is seeded with. */
    public static long normalizedLength(InputStream in) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long n = 0;
        int read;
        while ((read = in.read(buf)) >= 0) {
            for (int i = 0; i < read; i++) {
                if (!isWhitespace(buf[i] & 0xff)) n++;
            }
        }
        return n;
    }

    /**
     * The fingerprint of {@code in}, given its {@link #normalizedLength}. Returned as an unsigned
     * 32-bit value in a {@code long}, which is how CurseForge's API presents it.
     */
    public static long hash(InputStream in, long normalizedLength) throws IOException {
        int h = SEED ^ (int) normalizedLength;
        int k = 0;
        int kBytes = 0;
        byte[] buf = new byte[64 * 1024];
        int read;
        while ((read = in.read(buf)) >= 0) {
            for (int i = 0; i < read; i++) {
                int b = buf[i] & 0xff;
                if (isWhitespace(b)) continue;
                // Little-endian 4-byte block, assembled a byte at a time.
                k |= b << (8 * kBytes);
                if (++kBytes == 4) {
                    k *= M;
                    k ^= k >>> R;
                    k *= M;
                    h *= M;
                    h ^= k;
                    k = 0;
                    kBytes = 0;
                }
            }
        }
        if (kBytes > 0) {
            // The 1–3 byte tail: k holds exactly those bytes in their LE positions, which is the
            // same as the reference's byte-by-byte XORs.
            h ^= k;
            h *= M;
        }
        h ^= h >>> 13;
        h *= M;
        h ^= h >>> 15;
        return Integer.toUnsignedLong(h);
    }

    /** Convenience for tests and small inputs: both passes over a byte array. */
    public static long hash(byte[] data) {
        try {
            long len = normalizedLength(new java.io.ByteArrayInputStream(data));
            return hash(new java.io.ByteArrayInputStream(data), len);
        } catch (IOException e) {
            throw new IllegalStateException(e); // a byte array cannot fail to read
        }
    }
}
