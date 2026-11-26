package games.brennan.dungeontrain.client.crash;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only a crash report or JVM crash log written after the run started counts as a crash. */
class CrashEvidenceTest {

    private static final long RUN_START = 1_764_118_560_000L;

    private static Path file(Path dir, String name, long modified) throws Exception {
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        Files.writeString(f, "x");
        Files.setLastModifiedTime(f, FileTime.fromMillis(modified));
        return f;
    }

    @Test
    void nothingWrittenIsNoCrash(@TempDir Path game) {
        assertFalse(CrashEvidence.find(game, List.of(game), RUN_START).isPresent());
    }

    @Test
    void aCrashReportAfterTheStartCounts(@TempDir Path game) throws Exception {
        Path report = file(game.resolve(CrashEvidence.CRASH_REPORTS_DIR), "crash-2026-11-25_08.59.30-client.txt",
            RUN_START + 60_000);
        assertEquals(report, CrashEvidence.find(game, List.of(game), RUN_START).orElseThrow());
    }

    @Test
    void anOlderCrashReportDoesNotCount(@TempDir Path game) throws Exception {
        file(game.resolve(CrashEvidence.CRASH_REPORTS_DIR), "crash-2026-11-20_10.00.00-client.txt", RUN_START - 1);
        assertFalse(CrashEvidence.find(game, List.of(game), RUN_START).isPresent());
    }

    @Test
    void aJvmCrashLogAfterTheStartCounts(@TempDir Path game, @TempDir Path cwd) throws Exception {
        file(cwd, "hs_err_pid4242.log", RUN_START + 5_000);
        assertTrue(CrashEvidence.find(game, List.of(game, cwd), RUN_START).isPresent());
    }

    @Test
    void otherFilesDoNotCount(@TempDir Path game) throws Exception {
        file(game.resolve(CrashEvidence.CRASH_REPORTS_DIR), "notes.txt", RUN_START + 5_000);
        file(game, "latest.log", RUN_START + 5_000);
        assertFalse(CrashEvidence.find(game, List.of(game), RUN_START).isPresent());
    }
}
