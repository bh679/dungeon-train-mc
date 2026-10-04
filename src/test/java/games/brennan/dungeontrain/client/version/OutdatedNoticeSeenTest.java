package games.brennan.dungeontrain.client.version;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The once-per-game memory: survives a restart, stays capped, and shrugs off a broken file. */
class OutdatedNoticeSeenTest {

    @Test
    void remembersAcrossInstances(@TempDir Path dir) {
        Path file = dir.resolve("sub").resolve(OutdatedNoticeSeen.FILE_NAME);
        new OutdatedNoticeSeen(file).add("sp:World:1");
        OutdatedNoticeSeen reopened = new OutdatedNoticeSeen(file);
        assertTrue(reopened.contains("sp:World:1"));
        assertFalse(reopened.contains("sp:World:2"));
    }

    @Test
    void keepsOnlyTheNewest(@TempDir Path dir) {
        Path file = dir.resolve(OutdatedNoticeSeen.FILE_NAME);
        OutdatedNoticeSeen seen = new OutdatedNoticeSeen(file);
        for (int i = 0; i <= OutdatedNoticeSeen.CAP; i++) seen.add("k" + i);
        OutdatedNoticeSeen reopened = new OutdatedNoticeSeen(file);
        assertFalse(reopened.contains("k0"));
        assertTrue(reopened.contains("k1"));
        assertTrue(reopened.contains("k" + OutdatedNoticeSeen.CAP));
    }

    @Test
    void corruptFileReadsAsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(OutdatedNoticeSeen.FILE_NAME);
        Files.writeString(file, "{not json");
        OutdatedNoticeSeen seen = new OutdatedNoticeSeen(file);
        assertFalse(seen.contains("anything"));
        seen.add("a");
        assertTrue(new OutdatedNoticeSeen(file).contains("a"));
    }
}
