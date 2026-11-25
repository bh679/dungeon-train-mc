package games.brennan.dungeontrain.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shutdown breadcrumb's contract: a write can be read back by the next launch exactly once, a
 * clean stop leaves nothing, a damaged file still reports (as "unknown") instead of throwing, and
 * the watchdog's checkpoints run 5 s, 15 s, 30 s, 60 s, then every minute to the cap.
 */
class ShutdownTraceTest {

    @Test
    void unfinishedTraceIsReportedOnceAndKept(@TempDir Path root) throws Exception {
        String text = ShutdownTrace.render("2026-11-24T22:31:31Z", "listeners started", 15_000L,
            "Server thread state=RUNNABLE\n    at Foo.bar(Foo.java:1)\n");
        assertTrue(ShutdownTrace.write(root, text));

        Optional<ShutdownTrace.Previous> previous = ShutdownTrace.takePrevious(root);
        assertTrue(previous.isPresent());
        assertEquals("listeners started", previous.get().phase());
        assertEquals(15_000L, previous.get().elapsedMs());
        assertTrue(previous.get().text().contains("Foo.bar"));

        assertFalse(Files.exists(ShutdownTrace.file(root)), "moved away, so it is reported once");
        assertEquals(text, Files.readString(ShutdownTrace.previousFile(root), StandardCharsets.UTF_8));
        assertTrue(ShutdownTrace.takePrevious(root).isEmpty());
    }

    @Test
    void rewriteReplacesTheTrace(@TempDir Path root) {
        assertTrue(ShutdownTrace.write(root, ShutdownTrace.render("t", "listeners started", 0L, "")));
        assertTrue(ShutdownTrace.write(root, ShutdownTrace.render("t", "listeners done", 5_000L, "")));
        assertEquals("listeners done", ShutdownTrace.takePrevious(root).orElseThrow().phase());
        assertFalse(Files.exists(ShutdownTrace.file(root).resolveSibling(ShutdownTrace.FILE_NAME + ".tmp")));
    }

    @Test
    void cleanStopLeavesNothing(@TempDir Path root) {
        assertTrue(ShutdownTrace.write(root, ShutdownTrace.render("t", "saving", 1_000L, "")));
        assertTrue(ShutdownTrace.clear(root));
        assertFalse(Files.exists(ShutdownTrace.file(root)));
        assertTrue(ShutdownTrace.takePrevious(root).isEmpty());
        assertFalse(ShutdownTrace.clear(root), "idempotent");
    }

    @Test
    void absentOrDamagedFileNeverThrows(@TempDir Path root) throws Exception {
        assertTrue(ShutdownTrace.takePrevious(root).isEmpty());

        Files.createDirectories(ShutdownTrace.file(root).getParent());
        Files.writeString(ShutdownTrace.file(root), "garbage\nelapsedMs: not-a-number\n");
        ShutdownTrace.Previous previous = ShutdownTrace.takePrevious(root).orElseThrow();
        assertEquals("unknown", previous.phase());
        assertEquals(-1L, previous.elapsedMs());
    }

    @Test
    void checkpointScheduleRunsToTheCap() {
        assertEquals(5_000L, ShutdownTrace.checkpointMs(0));
        assertEquals(15_000L, ShutdownTrace.checkpointMs(1));
        assertEquals(30_000L, ShutdownTrace.checkpointMs(2));
        assertEquals(60_000L, ShutdownTrace.checkpointMs(3));
        assertEquals(120_000L, ShutdownTrace.checkpointMs(4));
        assertEquals(ShutdownTrace.WATCHDOG_MAX_MS, ShutdownTrace.checkpointMs(17));
        assertEquals(-1L, ShutdownTrace.checkpointMs(18));
        assertEquals(-1L, ShutdownTrace.checkpointMs(-1));
    }
}
