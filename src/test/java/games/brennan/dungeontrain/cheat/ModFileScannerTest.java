package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-file fingerprint and the "is this a jar we can judge" rule. No live {@code ModList}. */
class ModFileScannerTest {

    @Test
    @DisplayName("A file's fingerprint carries its name, size, SHA-1, CurseForge fingerprint and ids")
    void fingerprintsAFile(@TempDir Path dir) throws Exception {
        byte[] data = new byte[70_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 7);
        Path jar = dir.resolve("somemod-1.2.3.jar");
        Files.write(jar, data);

        ModFileFingerprint f = ModFileScanner.fingerprint(jar, List.of("somemod", "somemod_extra"));

        assertEquals("somemod-1.2.3.jar", f.fileName());
        assertEquals(data.length, f.size());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data)), f.sha1());
        assertEquals(CurseForgeFingerprint.hash(data), f.murmur2());
        assertEquals(List.of("somemod", "somemod_extra"), f.modIds());
        assertEquals("somemod-1.2.3.jar (somemod, somemod_extra)", f.display());
    }

    @Test
    @DisplayName("The wire form is {sha1, murmur2, size, fileName, modIds}")
    void wireForm() {
        ModFileFingerprint f = new ModFileFingerprint("a.jar", 12, "ab".repeat(20), 4294967295L, List.of("a"));
        var o = f.toJson();
        assertEquals("ab".repeat(20), o.get("sha1").getAsString());
        assertEquals(4294967295L, o.get("murmur2").getAsLong());
        assertEquals(12, o.get("size").getAsLong());
        assertEquals("a.jar", o.get("fileName").getAsString());
        assertEquals("a", o.getAsJsonArray("modIds").get(0).getAsString());
    }

    @Test
    @DisplayName("Only a regular .jar on the real file system is judged — directories and other files are not")
    void plainJarRule(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("Mod.JAR");
        Files.writeString(jar, "x");
        Path txt = dir.resolve("notes.txt");
        Files.writeString(txt, "x");
        Path folder = dir.resolve("classes.jar");
        Files.createDirectory(folder);

        assertTrue(ModFileScanner.isPlainJar(jar));
        assertFalse(ModFileScanner.isPlainJar(txt));
        assertFalse(ModFileScanner.isPlainJar(folder), "a directory named .jar is a dev classpath, not a file");
        assertFalse(ModFileScanner.isPlainJar(dir.resolve("missing.jar")));
        assertFalse(ModFileScanner.isPlainJar(null));
    }

    @Test
    @DisplayName("The game, the loader and our own jar are exempt by id")
    void exemptIds() {
        assertTrue(ModFileScanner.EXEMPT_IDS.containsAll(List.of("minecraft", "neoforge", "dungeontrain")));
        assertFalse(ModFileScanner.EXEMPT_IDS.contains("jei"));
    }
}
