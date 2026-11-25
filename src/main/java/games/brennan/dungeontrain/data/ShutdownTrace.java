package games.brennan.dungeontrain.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * A server shutdown's breadcrumb: one small text file that says how far the last shutdown got.
 *
 * <p>{@code ShutdownDiagnostics} rewrites it as the shutdown moves through its phases and at each
 * watchdog checkpoint, and deletes it once the server has stopped. If the game starts and the file
 * is still there, the last shutdown never finished (the player had to kill a frozen game), and the
 * file holds the last phase reached and the server thread's stack at the last checkpoint.</p>
 *
 * <p>Why a file of its own: a shutdown freeze can be log4j itself blocking (a stalled disk holds the
 * appender's lock and every thread that logs stops with it). That happened in the 2026-11-24 report
 * that prompted this. These writes never go through log4j and never log, so they can still land
 * while logging is stuck. A disk that is fully stalled stops them too; nothing in-process beats that.</p>
 *
 * <p>Lives at {@code <gameDir>/dungeontrain/session/shutdown-trace.txt}, beside
 * {@link CrashRunState}'s file. Minecraft-free and best-effort like that class: every operation takes
 * the data root explicitly (JUnit drives it with {@code @TempDir}), and none throws.</p>
 */
public final class ShutdownTrace {

    public static final String FILE_NAME = "shutdown-trace.txt";
    /** Where {@link #takePrevious} moves an unfinished trace, so it is reported once and kept. */
    public static final String PREVIOUS_FILE_NAME = "shutdown-trace-previous.txt";

    static final String KEY_PHASE = "phase: ";
    static final String KEY_ELAPSED = "elapsedMs: ";

    /**
     * First checkpoints, in ms after the shutdown started; then one every {@link #CHECKPOINT_STEP_MS}.
     * Starts at 10 s because an ordinary dedicated-server stop already takes about 7 s (DT's train
     * cleanup pumps Sable for a second per dimension), and a dump on every normal stop is noise.
     */
    private static final long[] FIRST_CHECKPOINTS_MS = {10_000L, 20_000L, 30_000L, 60_000L};
    static final long CHECKPOINT_STEP_MS = 60_000L;
    /** The watchdog stops after this long: a game frozen for 15 minutes has long been killed. */
    public static final long WATCHDOG_MAX_MS = 15 * 60_000L;

    /** What an unfinished shutdown left behind. */
    public record Previous(String phase, long elapsedMs, String text) {}

    private ShutdownTrace() {}

    public static Path file(Path dataRoot) {
        return dataRoot.resolve(CrashRunState.SESSION_DIR).resolve(FILE_NAME);
    }

    public static Path previousFile(Path dataRoot) {
        return dataRoot.resolve(CrashRunState.SESSION_DIR).resolve(PREVIOUS_FILE_NAME);
    }

    /**
     * The {@code index}th checkpoint in ms after the shutdown started, or -1 once past
     * {@link #WATCHDOG_MAX_MS}: 10 s, 20 s, 30 s, 60 s, then every minute.
     */
    public static long checkpointMs(int index) {
        if (index < 0) return -1L;
        long at = index < FIRST_CHECKPOINTS_MS.length
            ? FIRST_CHECKPOINTS_MS[index]
            : FIRST_CHECKPOINTS_MS[FIRST_CHECKPOINTS_MS.length - 1]
                + (index - FIRST_CHECKPOINTS_MS.length + 1) * CHECKPOINT_STEP_MS;
        return at > WATCHDOG_MAX_MS ? -1L : at;
    }

    /** The file's text: a header the next launch parses, then {@code detail} (stacks), verbatim. */
    public static String render(String startedAt, String phase, long elapsedMs, String detail) {
        StringBuilder sb = new StringBuilder(256 + (detail == null ? 0 : detail.length()));
        sb.append("Dungeon Train shutdown trace. Deleted when the server stops; still here = it never did.\n");
        sb.append("started: ").append(startedAt).append('\n');
        sb.append(KEY_PHASE).append(phase).append('\n');
        sb.append(KEY_ELAPSED).append(elapsedMs).append('\n');
        if (detail != null && !detail.isEmpty()) sb.append('\n').append(detail);
        return sb.toString();
    }

    /**
     * Replace the trace with {@code text}. Written to a temp file and moved over the old one, so a
     * kill mid-write leaves the previous trace rather than half of this one. Never logs, never throws.
     */
    public static boolean write(Path dataRoot, String text) {
        Path file = file(dataRoot);
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The shutdown finished: forget the trace. Idempotent, never throws. */
    public static boolean clear(Path dataRoot) {
        try {
            Files.deleteIfExists(file(dataRoot).resolveSibling(FILE_NAME + ".tmp"));
            return Files.deleteIfExists(file(dataRoot));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * An unfinished trace from the last shutdown, moved to {@link #PREVIOUS_FILE_NAME} so the next
     * launch doesn't report it again. Empty when there is none or it can't be read. Never throws.
     */
    public static Optional<Previous> takePrevious(Path dataRoot) {
        Path file = file(dataRoot);
        if (!Files.isRegularFile(file)) return Optional.empty();
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        try {
            Files.move(file, previousFile(dataRoot), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            // Can't move it: delete it instead, or every launch would report the same shutdown.
            try {
                Files.deleteIfExists(file);
            } catch (IOException | RuntimeException ignored) {
                // Best-effort.
            }
        }
        return Optional.of(parse(text));
    }

    static Previous parse(String text) {
        String phase = "unknown";
        long elapsed = -1L;
        for (String line : text.split("\n", 20)) {
            if (line.startsWith(KEY_PHASE)) {
                phase = line.substring(KEY_PHASE.length()).trim();
            } else if (line.startsWith(KEY_ELAPSED)) {
                try {
                    elapsed = Long.parseLong(line.substring(KEY_ELAPSED.length()).trim());
                } catch (NumberFormatException ignored) {
                    elapsed = -1L;
                }
            }
        }
        return new Previous(phase.isEmpty() ? "unknown" : phase, elapsed, text);
    }
}
