package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PackageFingerprint} against the answers of its Python twin
 * ({@code scripts/modpack/check-mod-ids.py} {@code package_of} / {@code jar_packages}). The two
 * must agree key for key: the catalogue is built with the Python one and the client judges with
 * this one, and a disagreement would make an honest jar read as suspect.
 */
class PackageFingerprintTest {

    /** {entry name, expected key or null} — printed by the Python twin on 2026-10-11. */
    private static final String[][] CASES = {
        {"mezz/jei/common/Foo.class", "mezz.jei"},
        {"mezz/jei/api/Bar.class", "mezz.jei"},
        {"Foo.class", null},                       // default package
        {"net/Baz.class", null},                   // single segment: too generic
        {"org/apache/commons/X.class", null},      // shaded library
        {"com/github/alice/mod/Y.class", "com.github.alice"},
        {"io/github/bob/Z.class", "io.github.bob"},
        {"com/google/gson/G.class", null},
        {"net/minecraft/client/C.class", null},
        {"kotlin/Unit.class", null},
        {"META-INF/jarjar/inner.jar", null},
        {"assets/x/y.json", null},
        {"dev/alpha/A.class", "dev.alpha"},
        {"org/violetmoon/quark/Q.class", "org.violetmoon"},
        {"com/mojang/blaze3d/B.class", null},
    };

    @Test
    @DisplayName("keyOf agrees with the Python twin on every fixture")
    void keysMatchTwin() {
        for (String[] c : CASES) {
            assertEquals(c[1], PackageFingerprint.keyOf(c[0]), c[0]);
        }
    }

    @Test
    @DisplayName("A jar's fingerprint is sorted, de-duplicated, includes embedded jars, and is capped")
    void jarFingerprint() throws Exception {
        byte[] inner = zip(new String[]{"net/xray/Cheat.class", "org/apache/Shaded.class"});
        byte[] outer = zip(new String[]{"mezz/jei/A.class", "mezz/jei/B.class", "dev/alpha/C.class", "assets/a.json"},
            new String[]{"META-INF/jarjar/inner.jar"}, new byte[][]{inner});
        assertEquals(List.of("dev.alpha", "mezz.jei", "net.xray"), PackageFingerprint.ofBytes(outer));

        String[] many = new String[PackageFingerprint.MAX + 20];
        for (int i = 0; i < many.length; i++) many[i] = String.format("p%03d/q/C.class", i);
        List<String> capped = PackageFingerprint.ofBytes(zip(many));
        assertEquals(PackageFingerprint.MAX, capped.size());
        assertEquals("p000.q", capped.get(0), "a sorted prefix, so both sides cut the same way");
    }

    @Test
    @DisplayName("isValid accepts what keyOf produces and refuses junk")
    void validity() {
        assertTrue(PackageFingerprint.isValid("mezz.jei"));
        assertTrue(PackageFingerprint.isValid("com.github.alice"));
        assertFalse(PackageFingerprint.isValid("mezz"));
        assertFalse(PackageFingerprint.isValid("a.b.c.d"));
        assertFalse(PackageFingerprint.isValid("bad key!"));
        assertFalse(PackageFingerprint.isValid(null));
        assertNull(PackageFingerprint.keyOf(null));
    }

    private static byte[] zip(String[] emptyEntries) throws Exception {
        return zip(emptyEntries, new String[0], new byte[0][]);
    }

    private static byte[] zip(String[] emptyEntries, String[] dataNames, byte[][] data) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (String n : emptyEntries) {
                z.putNextEntry(new ZipEntry(n));
                z.write(new byte[]{(byte) 0xCA, (byte) 0xFE});
                z.closeEntry();
            }
            for (int i = 0; i < dataNames.length; i++) {
                z.putNextEntry(new ZipEntry(dataNames[i]));
                z.write(data[i]);
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}
