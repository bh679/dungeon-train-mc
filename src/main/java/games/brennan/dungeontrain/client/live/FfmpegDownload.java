package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches a pinned {@link FfmpegMirror} file that survives a poor connection: a dropped transfer keeps
 * its bytes and carries on with a {@code Range} request, by itself and on the player's next try. Only
 * the pinned size and SHA-256 count as finished; a partial file that fails them is deleted.
 *
 * <p>Blocking; {@link FfmpegInstaller} runs it on the IO pool.
 */
final class FfmpegDownload {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern CONTENT_RANGE_START = Pattern.compile("^bytes (\\d+)-");
    private static final int BUFFER = 64 * 1024;
    static final Limits LIVE = new Limits(Duration.ofSeconds(20), 60_000, 5, new long[] {2_000, 5_000, 10_000, 20_000});

    /**
     * @param connectTimeout how long to wait for a connection and for the response headers
     * @param stallLimitMs   how long a connection may send nothing before it is closed
     * @param idleAttempts   attempts in a row without one new byte before giving up
     * @param backoffMs      wait after the Nth such attempt; the last value repeats
     */
    record Limits(Duration connectTimeout, long stallLimitMs, int idleAttempts, long[] backoffMs) {}

    /** What to do with a server's answer to a request. */
    enum Answer {
        /** It sent the rest of the file: add it to the partial file. */
        APPEND,
        /** It sent the whole file: replace the partial file. */
        REPLACE,
        /** It can't serve the rest of this partial file: delete it and ask for the whole file. */
        DISCARD,
        /** No file came back: try again later. */
        FAIL
    }

    /** A partial file that can't become the pinned file. */
    static final class CorruptDownloadException extends IOException {
        CorruptDownloadException(String message) {
            super(message);
        }
    }

    private FfmpegDownload() {}

    /** Download {@code mirror} to {@code part}, returning only once it holds the pinned bytes. */
    static void fetch(FfmpegMirror mirror, Path part, IntConsumer onProgress) throws IOException {
        fetch(mirror, part, onProgress, LIVE);
    }

    static void fetch(FfmpegMirror mirror, Path part, IntConsumer onProgress, Limits limits) throws IOException {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(limits.connectTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        int idle = 0;
        while (true) {
            long before = sizeOf(part);
            try {
                attempt(client, mirror, part, onProgress, limits);
                return;
            } catch (CorruptDownloadException e) {
                Files.deleteIfExists(part);
                // A wrong file downloaded whole would only come back wrong again.
                if (before == 0) throw e;
                LOGGER.warn("[DungeonTrain] Discarded a partial ffmpeg download: {}", e.getMessage());
            } catch (IOException e) {
                LOGGER.info("[DungeonTrain] ffmpeg download interrupted at {} of {} bytes: {}",
                    sizeOf(part), mirror.size(), e.toString());
                idle = sizeOf(part) > before ? 0 : idle + 1;
                if (idle >= limits.idleAttempts()) throw e;
                pause(limits.backoffMs()[Math.min(idle, limits.backoffMs().length - 1)]);
            }
        }
    }

    /** One request: fills {@code part} as far as the connection lasts, then checks a finished file. */
    private static void attempt(HttpClient client, FfmpegMirror mirror, Path part, IntConsumer onProgress, Limits limits)
            throws IOException {
        long have = sizeOf(part);
        if (have > mirror.size()) throw new CorruptDownloadException("partial file is over the pinned size");
        if (have < mirror.size()) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(mirror.url()))
                .timeout(limits.connectTimeout()).GET();
            if (have > 0) request.header("Range", "bytes=" + have + "-");
            HttpResponse<InputStream> response = send(client, request.build());
            Answer answer = answer(response.statusCode(), have,
                response.headers().firstValue("Content-Range").orElse(null));
            InputStream body = response.body();
            switch (answer) {
                case FAIL -> {
                    body.close();
                    throw new IOException("HTTP " + response.statusCode());
                }
                case DISCARD -> {
                    body.close();
                    throw new CorruptDownloadException("server refused the rest (HTTP " + response.statusCode() + ")");
                }
                default -> transfer(body, part, answer == Answer.APPEND ? have : 0, mirror.size(), onProgress, limits);
            }
        }
        verify(part, mirror.sha256());
    }

    private static HttpResponse<InputStream> send(HttpClient client, HttpRequest request) throws IOException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static void transfer(InputStream body, Path part, long start, long size, IntConsumer onProgress, Limits limits)
            throws IOException {
        // A read with no data blocks without a timeout of its own: closing the stream ends it.
        AtomicLong lastData = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean done = new AtomicBoolean();
        closeWhenStalled(body, lastData, done, limits.stallLimitMs());
        StandardOpenOption mode = start > 0 ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING;
        try (InputStream in = body;
             OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE, mode)) {
            copy(in, out, start, size, onProgress, () -> lastData.set(System.currentTimeMillis()));
        } finally {
            done.set(true);
        }
    }

    private static void closeWhenStalled(InputStream body, AtomicLong lastData, AtomicBoolean done, long limitMs) {
        long left = Math.max(1, lastData.get() + limitMs - System.currentTimeMillis());
        CompletableFuture.runAsync(() -> {
            if (done.get()) return;
            if (System.currentTimeMillis() - lastData.get() >= limitMs) {
                closeQuietly(body);
            } else {
                closeWhenStalled(body, lastData, done, limitMs);
            }
        }, CompletableFuture.delayedExecutor(left, TimeUnit.MILLISECONDS));
    }

    /**
     * Copy {@code in} to {@code out} on top of {@code start} bytes already held, reporting whole
     * percentages of {@code size}. A short stream fails as a dropped connection; a long one as a
     * corrupt file. Pure, for the tests.
     */
    static void copy(InputStream in, OutputStream out, long start, long size, IntConsumer onProgress, Runnable onData)
            throws IOException {
        byte[] buf = new byte[BUFFER];
        long total = start;
        int lastPct = -1;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > size) throw new CorruptDownloadException("download larger than the pinned " + size + " bytes");
            out.write(buf, 0, n);
            onData.run();
            int pct = (int) (total * 100 / size);
            if (pct != lastPct) {
                lastPct = pct;
                onProgress.accept(pct);
            }
        }
        if (total != size) throw new IOException("connection ended at " + total + " of " + size + " bytes");
    }

    /** How to treat a response to a request made holding {@code have} bytes. Pure, for the tests. */
    static Answer answer(int status, long have, @Nullable String contentRange) {
        if (status == 200) return Answer.REPLACE;
        if (status == 416) return Answer.DISCARD;
        if (status != 206) return Answer.FAIL;
        Matcher start = contentRange == null ? null : CONTENT_RANGE_START.matcher(contentRange);
        if (start == null || !start.find()) return Answer.DISCARD;
        try {
            return Long.parseLong(start.group(1)) == have ? Answer.APPEND : Answer.DISCARD;
        } catch (NumberFormatException e) {
            return Answer.DISCARD;
        }
    }

    /** Fail unless {@code file} hashes to {@code sha256}. */
    static void verify(Path file, String sha256) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buf = new byte[BUFFER];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(sha256)) throw new CorruptDownloadException("SHA-256 mismatch: " + actual);
    }

    private static long sizeOf(Path file) throws IOException {
        return Files.exists(file) ? Files.size(file) : 0;
    }

    private static void pause(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
