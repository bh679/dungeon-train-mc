package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The local report keys: pending until accepted, then current; junk never read back. */
final class ReportKeyStoreTest {

    private static final String U = "069a79f444e94726a5befca90e38aaf5";
    private static final String K1 = "A".repeat(43);
    private static final String K2 = "b".repeat(42) + "_";

    @TempDir Path dir;

    @BeforeEach
    void setUp() {
        ReportKeyStore.useFile(dir.resolve("dungeontrain").resolve(ReportKeyStore.FILE));
    }

    @AfterEach
    void tearDown() {
        ReportKeyStore.useFile(null);
    }

    @Test
    @DisplayName("nothing saved reads as no key")
    void empty() {
        assertFalse(ReportKeyStore.load(U).any());
    }

    @Test
    @DisplayName("a new code's key is pending; the relay accepting it makes it current; the next code's key waits beside it")
    void pendingThenCurrent() {
        ReportKeyStore.savePending(U, K1);
        assertEquals(new ReportKeyStore.Keys(null, K1), ReportKeyStore.load(U));
        ReportKeyStore.promote(U, K1);
        assertEquals(new ReportKeyStore.Keys(K1, null), ReportKeyStore.load(U));
        ReportKeyStore.savePending(U, K2);
        assertEquals(new ReportKeyStore.Keys(K1, K2), ReportKeyStore.load(U));
        ReportKeyStore.promote(U, K2);
        assertEquals(new ReportKeyStore.Keys(K2, null), ReportKeyStore.load(U));
    }

    @Test
    @DisplayName("bad keys and uuids are never saved; a corrupt file reads as no key")
    void junk() throws Exception {
        ReportKeyStore.savePending(U, "short");
        ReportKeyStore.savePending("not-a-uuid", K1);
        assertFalse(ReportKeyStore.load(U).any());
        Path f = dir.resolve("dungeontrain").resolve(ReportKeyStore.FILE);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{not json");
        assertFalse(ReportKeyStore.load(U).any());
        Files.writeString(f, "{\"" + U + "\":{\"current\":\"x\",\"pending\":\"" + K1 + "\"}}");
        assertNull(ReportKeyStore.load(U).current());
        assertEquals(K1, ReportKeyStore.load(U).pending());
        assertTrue(ReportKeyStore.validKey(K2));
    }

    @Test
    @DisplayName("the file sits beside the install key under a hidden name, away from config")
    void location() {
        assertEquals(".live-report-key.json", ReportKeyStore.FILE);
        assertEquals("live-report-key.json", ReportKeyStore.LEGACY_FILE);
    }

    @Test
    @DisplayName("a key in the old config file moves to the new one and the old file is deleted")
    void migrates() throws Exception {
        Path now = dir.resolve("dungeontrain").resolve(ReportKeyStore.FILE);
        Path old = dir.resolve("config").resolve("dungeontrain").resolve(ReportKeyStore.LEGACY_FILE);
        Files.createDirectories(old.getParent());
        Files.writeString(old, "{\"" + U + "\":{\"current\":\"" + K1 + "\"}}");
        Files.writeString(old.resolveSibling(ReportKeyStore.LEGACY_FILE + ".tmp"), "partial");
        ReportKeyStore.useFiles(now, old);

        assertEquals(new ReportKeyStore.Keys(K1, null), ReportKeyStore.load(U));
        assertTrue(Files.isRegularFile(now));
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(old.resolveSibling(ReportKeyStore.LEGACY_FILE + ".tmp")));
    }

    @Test
    @DisplayName("a newer key already in the new file wins; the old file is still deleted")
    void newFileWins() throws Exception {
        Path now = dir.resolve("dungeontrain").resolve(ReportKeyStore.FILE);
        Path old = dir.resolve("config").resolve(ReportKeyStore.LEGACY_FILE);
        Files.createDirectories(old.getParent());
        Files.writeString(old, "{\"" + U + "\":{\"current\":\"" + K1 + "\"}}");
        ReportKeyStore.useFiles(now, null);
        ReportKeyStore.savePending(U, K2);
        ReportKeyStore.useFiles(now, old);

        assertEquals(new ReportKeyStore.Keys(null, K2), ReportKeyStore.load(U));
        assertFalse(Files.exists(old));
    }

    @Test
    @DisplayName("if the new file can't be written the old one stays, so the key is never lost")
    void failedMoveKeepsOld() throws Exception {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "a file where the folder should be");
        Path now = blocker.resolve(ReportKeyStore.FILE);
        Path old = dir.resolve("config").resolve(ReportKeyStore.LEGACY_FILE);
        Files.createDirectories(old.getParent());
        Files.writeString(old, "{\"" + U + "\":{\"current\":\"" + K1 + "\"}}");
        ReportKeyStore.useFiles(now, old);

        ReportKeyStore.load(U);
        assertTrue(Files.isRegularFile(old));
    }

    @Test
    @DisplayName("the key file is owner read/write only where the disk has POSIX permissions")
    void ownerOnly() throws Exception {
        Path now = dir.resolve("dungeontrain").resolve(ReportKeyStore.FILE);
        Files.createDirectories(now.getParent());
        assumeTrue(Files.getFileStore(now.getParent()).supportsFileAttributeView("posix"));
        Files.writeString(now, "{}");
        Files.setPosixFilePermissions(now, PosixFilePermissions.fromString("rw-r--r--"));
        ReportKeyStore.savePending(U, K1);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(now)));
    }
}
