package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.BugReportLogsPacket.LogBlob;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
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
import java.util.Arrays;
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
 *
 * <p><b>Full mode</b> ({@code /bug full}, see {@code LogRequestManager}) sends the whole session
 * instead: all of {@code latest.log}, then as much of {@code debug.log} as still fits. Both share one
 * gzipped budget ({@link #FULL_BUDGET_GZ}) sized under the relay's per-request body limit, and a log
 * that does not fit keeps its NEWEST bytes. A cut file is named {@code *.tail.log.gz} so the reader
 * knows its start is missing; a whole one is {@code *.full.log.gz}.</p>
 */
public final class LogCollector {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TAIL_BYTES = 512 * 1024;       // last 512 KB of an active log
    private static final int MAX_CRASH_BYTES = 512 * 1024;  // a crash report, capped
    /**
     * Full mode's gzipped total for both logs. The upload rides one relay hook, whose body limit is
     * 5 MB in production ({@code MAX_BODY_BYTES}); this leaves room for the crash report, the
     * multipart framing and the message itself.
     */
    static final int FULL_BUDGET_GZ = 4 * 1024 * 1024;
    /** The most of one log full mode reads into memory (logs compress ~10x, so this is never the binding cap). */
    static final int FULL_MAX_RAW = 96 * 1024 * 1024;
    /** Below this much budget a log is left out rather than sent as a sliver. */
    static final int FULL_MIN_GZ = 16 * 1024;
    /** How many times a too-big log is re-cut before giving up on it. */
    private static final int FIT_ATTEMPTS = 6;

    private LogCollector() {}

    /** Collect the log set off the render thread; completes with the (possibly empty) gzipped blobs. */
    public static CompletableFuture<List<LogBlob>> collectAsync() {
        return collectAsync(false);
    }

    /** As {@link #collectAsync()}; {@code full} sends the whole session's logs (see the class note). */
    public static CompletableFuture<List<LogBlob>> collectAsync(boolean full) {
        return CompletableFuture.supplyAsync(full ? LogCollector::collectFull : LogCollector::collect);
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

    static List<LogBlob> collectFull() {
        List<LogBlob> out = new ArrayList<>();
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path logs = gameDir.resolve("logs");
        int usedByLatest = addFitted(out, logs.resolve("latest.log"), "latest", FULL_BUDGET_GZ);
        addFitted(out, logs.resolve("debug.log"), "debug", FULL_BUDGET_GZ - usedByLatest);
        addNewestCrashReport(out, gameDir.resolve("crash-reports"));
        return out;
    }

    /** Add {@code file} fitted into {@code budget} gzipped bytes; returns the bytes it used (0 when left out). */
    private static int addFitted(List<LogBlob> out, Path file, String stem, int budget) {
        try {
            if (budget < FULL_MIN_GZ || !Files.isRegularFile(file)) return 0;
            boolean readAll = Files.size(file) <= FULL_MAX_RAW;
            byte[] raw = readTail(file, FULL_MAX_RAW);
            if (raw.length == 0) return 0;
            byte[] redacted = redactHome(raw);
            Fitted fitted = fitTail(redacted, budget);
            if (fitted == null) return 0;
            boolean whole = readAll && fitted.keptBytes() == redacted.length;
            out.add(new LogBlob(stem + (whole ? ".full.log.gz" : ".tail.log.gz"), fitted.gz()));
            return fitted.gz().length;
        } catch (IOException | UncheckedIOException e) {
            LOGGER.warn("[DungeonTrain] LogCollector: couldn't read {}: {}", file, e.toString());
            return 0;
        }
    }

    /** A log cut to fit: its gzipped bytes and how many raw bytes of the log they hold. */
    record Fitted(byte[] gz, int keptBytes) {}

    /**
     * Gzip {@code data}, cutting its START until the result fits in {@code budget} bytes, so the newest
     * lines always survive. Each cut scales by how far over it was, so a few passes settle it. Null
     * when even the cut copy will not fit. Package-private for tests.
     */
    @Nullable
    static Fitted fitTail(byte[] data, int budget) throws IOException {
        int keep = data.length;
        for (int attempt = 0; attempt < FIT_ATTEMPTS && keep > 0; attempt++) {
            byte[] gz = gzip(keep == data.length ? data : Arrays.copyOfRange(data, data.length - keep, data.length));
            if (gz.length <= budget) return new Fitted(gz, keep);
            keep = (int) (keep * ((double) budget / gz.length) * 0.95);
        }
        return null;
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
