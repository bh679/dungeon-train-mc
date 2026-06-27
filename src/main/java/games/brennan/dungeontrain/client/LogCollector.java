package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.BugReportLogsPacket.LogBlob;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Client-side collector of "potentially helpful" logs for a bug report: the tail of
 * {@code logs/latest.log} and {@code logs/debug.log}, plus the most recent crash report, each lightly
 * redacted (the OS home-dir path) and gzipped, ready to ship to the server via
 * {@code BugReportLogsPacket}.
 *
 * <p>Best-effort and exception-safe: any read failure simply drops that file from the set, never
 * throwing into the death-screen flow. {@code latest.log} is actively written by the game, so we tail
 * a bounded copy of the trailing bytes rather than locking it.</p>
 */
public final class LogCollector {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TAIL_BYTES = 512 * 1024;       // last 512 KB of an active log
    private static final int MAX_CRASH_BYTES = 512 * 1024;  // a crash report, capped

    private LogCollector() {}

    /** Collect the log set off the render thread; completes with the (possibly empty) gzipped blobs. */
    public static CompletableFuture<List<LogBlob>> collectAsync() {
        return CompletableFuture.supplyAsync(LogCollector::collect);
    }

    static List<LogBlob> collect() {
        List<LogBlob> out = new ArrayList<>();
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path logs = gameDir.resolve("logs");
        addGzippedTail(out, logs.resolve("latest.log"), "latest.log.gz", TAIL_BYTES);
        addGzippedTail(out, logs.resolve("debug.log"), "debug.log.gz", TAIL_BYTES);
        addNewestCrashReport(out, gameDir.resolve("crash-reports"));
        return out;
    }

    private static void addGzippedTail(List<LogBlob> out, Path file, String name, int tailBytes) {
        try {
            if (!Files.isRegularFile(file)) return;
            byte[] tail = readTail(file, tailBytes);
            if (tail.length == 0) return;
            out.add(new LogBlob(name, gzip(redactHome(tail))));
        } catch (IOException | UncheckedIOException e) {
            LOGGER.warn("[DungeonTrain] LogCollector: couldn't read {}: {}", file, e.toString());
        }
    }

    private static void addNewestCrashReport(List<LogBlob> out, Path crashDir) {
        if (!Files.isDirectory(crashDir)) return;
        try (Stream<Path> s = Files.list(crashDir)) {
            Path newest = s.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .max(Comparator.comparingLong(LogCollector::lastModified))
                    .orElse(null);
            if (newest == null) return;
            byte[] bytes = readTail(newest, MAX_CRASH_BYTES);
            if (bytes.length == 0) return;
            out.add(new LogBlob("crash-" + newest.getFileName(), gzip(redactHome(bytes))));
        } catch (IOException | UncheckedIOException e) {
            LOGGER.warn("[DungeonTrain] LogCollector: couldn't scan crash reports: {}", e.toString());
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Read at most the last {@code maxBytes} of a file (the log is live-written; tail what's there). */
    static byte[] readTail(Path file, int maxBytes) throws IOException {
        long size = Files.size(file);
        if (size <= maxBytes) {
            return Files.readAllBytes(file);
        }
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            ch.position(size - maxBytes);
            ByteBuffer buf = ByteBuffer.allocate(maxBytes);
            while (buf.hasRemaining() && ch.read(buf) > 0) {
                // fill from the tail position to EOF
            }
            byte[] arr = new byte[buf.position()];
            buf.flip();
            buf.get(arr);
            return arr;
        }
    }

    /** Replace the OS home-dir path with {@code ~} so file paths don't leak the user's account name. */
    static byte[] redactHome(byte[] data) {
        String home = System.getProperty("user.home", "");
        if (home.isBlank()) {
            return data;
        }
        String text = new String(data, StandardCharsets.UTF_8);
        return text.replace(home, "~").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(data);
        }
        return baos.toByteArray();
    }
}
