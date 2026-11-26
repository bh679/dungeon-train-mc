package games.brennan.dungeontrain.client.crash;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/**
 * Whether the game really crashed during a run: it left a crash report or a JVM crash log written
 * after the run started. A force-quit or a power cut leaves neither, and so a crashed run is turned
 * into a nap ({@link CrashNapRecovery}) only on this evidence; without it a force-quit would save a
 * run anywhere, with no bed. Those get the Salvage card instead.
 *
 * <p>Minecraft writes {@code crash-reports/crash-*.txt} for a Java crash; the JVM writes
 * {@code hs_err_pid*.log} to its working directory for a native one (an OpenAL or driver crash).
 * Pure apart from reading directory listings, so it is pinned in a test.</p>
 */
public final class CrashEvidence {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String CRASH_REPORTS_DIR = "crash-reports";
    private static final String REPORT_GLOB = "crash-*.txt";
    private static final String JVM_LOG_GLOB = "hs_err_pid*.log";

    private CrashEvidence() {}

    /**
     * The first crash report under {@code gameDir}, or JVM crash log in any of {@code jvmLogDirs},
     * modified at or after {@code sinceEpochMs}.
     */
    public static Optional<Path> find(Path gameDir, Collection<Path> jvmLogDirs, long sinceEpochMs) {
        Optional<Path> report = newest(gameDir.resolve(CRASH_REPORTS_DIR), REPORT_GLOB, sinceEpochMs);
        if (report.isPresent()) return report;
        for (Path dir : jvmLogDirs) {
            Optional<Path> log = newest(dir, JVM_LOG_GLOB, sinceEpochMs);
            if (log.isPresent()) return log;
        }
        return Optional.empty();
    }

    private static Optional<Path> newest(Path dir, String glob, long sinceEpochMs) {
        if (!Files.isDirectory(dir)) return Optional.empty();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, glob)) {
            for (Path file : files) {
                if (Files.isRegularFile(file) && Files.getLastModifiedTime(file).toMillis() >= sinceEpochMs) {
                    return Optional.of(file);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Crash recovery: couldn't list {}: {}", dir, e.toString());
        }
        return Optional.empty();
    }
}
