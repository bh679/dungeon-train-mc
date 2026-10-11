package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CurseForgeFingerprint} against fixtures from its Python twin,
 * {@code scripts/modpack/cf_fingerprint.py --fixtures}. The two implementations must agree exactly:
 * the relay sends this number to CurseForge's fingerprint API, and a wrong one just means a
 * CurseForge-only file never resolves (it shows as unverified in the review queue), so a drift
 * here is quiet in production and loud only in this test.
 */
class CurseForgeFingerprintTest {

    /** {hex input, expected fingerprint}, straight from the Python reference. */
    private static final String[][] FIXTURES = {
        {"", "1540447798"},
        {"61", "626045324"},
        {"6162", "1692487918"},
        {"616263", "1621425345"},
        {"61626364", "3376380438"},
        {"68656c6c6f20776f726c64", "2824650221"},                       // "hello world"
        {"6c696e65206f6e650a6c696e652074776f0d0a09646f6e65", "1950129047"}, // lines with \n \r\n \t
    };

    @Test
    @DisplayName("Matches the Python reference on every fixture, tails of 0–3 bytes included")
    void matchesReference() {
        for (String[] f : FIXTURES) {
            byte[] data = HexFormat.of().parseHex(f[0]);
            assertEquals(Long.parseLong(f[1]), CurseForgeFingerprint.hash(data), "input " + f[0]);
        }
    }

    @Test
    @DisplayName("All 256 byte values, and a longer run with a 3-byte tail")
    void fullByteRange() {
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) all[i] = (byte) i;
        assertEquals(2094645347L, CurseForgeFingerprint.hash(all));
        byte[] longer = new byte[256 * 3 + 3];
        for (int i = 0; i < 256 * 3; i++) longer[i] = (byte) (i & 0xff);
        longer[768] = 'x';
        longer[769] = 'y';
        longer[770] = 'z';
        assertEquals(1595702719L, CurseForgeFingerprint.hash(longer));
    }

    @Test
    @DisplayName("Whitespace is stripped before hashing, so spacing differences do not change the fingerprint")
    void whitespaceIsIgnored() {
        assertEquals(CurseForgeFingerprint.hash("helloworld".getBytes()),
            CurseForgeFingerprint.hash("hello world".getBytes()));
        assertEquals(CurseForgeFingerprint.hash("helloworld".getBytes()),
            CurseForgeFingerprint.hash("\thello\r\n world\n".getBytes()));
    }

    @Test
    @DisplayName("The streaming form agrees with the byte-array form across buffer boundaries")
    void streamingMatches() throws Exception {
        byte[] big = new byte[200_000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) ((i * 31) ^ (i >> 7));
        long expected = CurseForgeFingerprint.hash(big);
        long len = CurseForgeFingerprint.normalizedLength(new ByteArrayInputStream(big));
        assertEquals(expected, CurseForgeFingerprint.hash(new ByteArrayInputStream(big), len));
    }
}
