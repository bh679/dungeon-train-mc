package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
