package games.brennan.dungeontrain.client.live;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ffmpeg download against a real local server: it resumes, retries and only accepts the pinned bytes. */
final class FfmpegDownloadTest {

    private static final byte[] FILE = new byte[300_000];
    private static final FfmpegDownload.Limits FAST =
        new FfmpegDownload.Limits(Duration.ofSeconds(5), 300, 3, new long[] {1});

    static {
        for (int i = 0; i < FILE.length; i++) FILE[i] = (byte) (i * 31);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange, int requestNumber) throws Exception;
    }

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> ranges = new CopyOnWriteArrayList<>();
    private volatile Handler handler;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/ffmpeg.zip", exchange -> {
            int n = requests.incrementAndGet();
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null) ranges.add(range);
            try {
                handler.handle(exchange, n);
            } catch (Exception e) {
                // The client sees the connection drop.
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private FfmpegMirror mirror() throws Exception {
        return new FfmpegMirror("http://127.0.0.1:" + server.getAddress().getPort() + "/ffmpeg.zip", sha256(FILE), FILE.length);
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    /** Serves the file honouring Range; {@code cut} bytes of it then drops the connection if it is above 0. */
    private static void serve(HttpExchange exchange, int cut) throws IOException {
        String range = exchange.getRequestHeaders().getFirst("Range");
        int from = range == null ? 0 : Integer.parseInt(range.replaceAll("\\D", ""));
        if (range != null) {
            exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + (FILE.length - 1) + "/" + FILE.length);
        }
        exchange.sendResponseHeaders(range == null ? 200 : 206, FILE.length - from);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(FILE, from, cut > 0 ? cut : FILE.length - from);
            out.flush();
            if (cut > 0) throw new IOException("dropped");
        }
    }

    @Test
    @DisplayName("a dropped connection carries on from the bytes it already has")
    void resumesAfterDrop(@TempDir Path dir) throws Exception {
        handler = (exchange, n) -> serve(exchange, n == 1 ? 120_000 : 0);
        Path part = dir.resolve("f.part");
        List<Integer> seen = new ArrayList<>();
        FfmpegDownload.fetch(mirror(), part, seen::add, FAST);
        assertArrayEquals(FILE, Files.readAllBytes(part));
        assertEquals(2, requests.get());
        assertEquals(1, ranges.size());
        assertTrue(ranges.get(0).matches("bytes=\\d+-"), ranges.get(0));
        assertEquals(100, seen.get(seen.size() - 1));
    }

    @Test
    @DisplayName("a partial file left by an earlier try is resumed, not thrown away")
    void resumesEarlierPartial(@TempDir Path dir) throws Exception {
        handler = (exchange, n) -> serve(exchange, 0);
        Path part = dir.resolve("f.part");
        Files.write(part, Arrays.copyOf(FILE, 200_000));
        FfmpegDownload.fetch(mirror(), part, p -> {}, FAST);
        assertArrayEquals(FILE, Files.readAllBytes(part));
        assertEquals(List.of("bytes=200000-"), ranges);
    }

    @Test
    @DisplayName("a server that ignores Range sends the whole file, which replaces the partial one")
    void serverIgnoresRange(@TempDir Path dir) throws Exception {
        handler = (exchange, n) -> {
            exchange.sendResponseHeaders(200, FILE.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(FILE);
            }
        };
        Path part = dir.resolve("f.part");
        Files.write(part, Arrays.copyOf(FILE, 200_000));
        FfmpegDownload.fetch(mirror(), part, p -> {}, FAST);
        assertArrayEquals(FILE, Files.readAllBytes(part));
    }

    @Test
    @DisplayName("a corrupted partial file is deleted and downloaded clean")
    void corruptPartialStartsOver(@TempDir Path dir) throws Exception {
        handler = (exchange, n) -> serve(exchange, 0);
        Path part = dir.resolve("f.part");
        byte[] corrupt = Arrays.copyOf(FILE, 200_000);
        corrupt[10] ^= 1;
        Files.write(part, corrupt);
        FfmpegDownload.fetch(mirror(), part, p -> {}, FAST);
        assertArrayEquals(FILE, Files.readAllBytes(part));
        assertEquals(2, requests.get());
    }

    @Test
    @DisplayName("a full partial file needs no request; an oversized one starts over")
    void finishedAndOversizedPartials(@TempDir Path dir) throws Exception {
        handler = (exchange, n) -> serve(exchange, 0);
        Path part = dir.resolve("f.part");
        Files.write(part, FILE);
        FfmpegDownload.fetch(mirror(), part, p -> {}, FAST);
        assertEquals(0, requests.get());
        Files.write(part, Arrays.copyOf(FILE, FILE.length + 1));
        FfmpegDownload.fetch(mirror(), part, p -> {}, FAST);
        assertArrayEquals(FILE, Files.readAllBytes(part));
    }

    @Test
    @DisplayName("a refused range deletes the partial file and asks for the whole one")
    void refusedRangeStartsOver(@TempDir Path dir) throws Exception {
        handler = (exchange, n) -> {
            if (exchange.getRequestHeaders().getFirst("Range") != null) {
                exchange.sendResponseHeaders(416, -1);
            } else {
                serve(exchange, 0);
            }
        };
        Path part = dir.resolve("f.part");
        Files.write(part, Arrays.copyOf(FILE, 200_000));
        FfmpegDownload.fetch(mirror(), part, p -> {}, FAST);
        assertArrayEquals(FILE, Files.readAllBytes(part));
    }

    @Test
    @DisplayName("a whole download with the wrong hash is refused straight away and not kept")
    void wrongFileIsNotRetried(@TempDir Path dir) throws Exception {
        handler = (exchange, n) -> serve(exchange, 0);
        Path part = dir.resolve("f.part");
        FfmpegMirror wrongPin = new FfmpegMirror(mirror().url(), sha256(new byte[] {1}), FILE.length);
        assertThrows(IOException.class, () -> FfmpegDownload.fetch(wrongPin, part, p -> {}, FAST));
        assertEquals(1, requests.get());
        assertFalse(Files.exists(part));
    }

    @Test
    @DisplayName("gives up after the set attempts without a new byte, and keeps what it has")
    void givesUpKeepingPartial(@TempDir Path dir) throws Exception {
        handler = (exchange, n) -> exchange.sendResponseHeaders(503, -1);
        Path part = dir.resolve("f.part");
        Files.write(part, Arrays.copyOf(FILE, 100_000));
        assertThrows(IOException.class, () -> FfmpegDownload.fetch(mirror(), part, p -> {}, FAST));
        assertEquals(3, requests.get());
        assertEquals(100_000, Files.size(part));
    }

    @Test
    @DisplayName("each attempt that adds bytes resets the give-up count")
    void progressResetsAttempts(@TempDir Path dir) throws Exception {
        handler = (exchange, n) -> serve(exchange, n <= 5 ? 20_000 : 0);
        Path part = dir.resolve("f.part");
        FfmpegDownload.fetch(mirror(), part, p -> {}, FAST);
        assertArrayEquals(FILE, Files.readAllBytes(part));
        assertEquals(6, requests.get());
    }

    @Test
    @DisplayName("a connection that goes silent is closed and resumed")
    void stalledConnectionResumes(@TempDir Path dir) throws Exception {
        handler = (exchange, n) -> {
            if (n > 1) {
                serve(exchange, 0);
                return;
            }
            exchange.sendResponseHeaders(200, FILE.length);
            OutputStream out = exchange.getResponseBody();
            out.write(FILE, 0, 120_000);
            out.flush();
            Thread.sleep(3_000);
        };
        Path part = dir.resolve("f.part");
        FfmpegDownload.fetch(mirror(), part, p -> {}, FAST);
        assertArrayEquals(FILE, Files.readAllBytes(part));
    }

    @Test
    @DisplayName("a slow transfer outlasting the connect timeout is not cut off")
    void slowBodyOutlastsConnectTimeout(@TempDir Path dir) throws Exception {
        handler = (exchange, n) -> {
            exchange.sendResponseHeaders(200, FILE.length);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 6; i++) {
                    out.write(FILE, i * 50_000, 50_000);
                    out.flush();
                    Thread.sleep(250);
                }
            }
        };
        FfmpegDownload.Limits shortTimeout = new FfmpegDownload.Limits(Duration.ofMillis(500), 2_000, 1, new long[] {1});
        Path part = dir.resolve("f.part");
        FfmpegDownload.fetch(mirror(), part, p -> {}, shortTimeout);
        assertArrayEquals(FILE, Files.readAllBytes(part));
        assertEquals(1, requests.get());
    }

    @Test
    @DisplayName("server answers map to append, replace, discard or fail")
    void answers() {
        assertEquals(FfmpegDownload.Answer.REPLACE, FfmpegDownload.answer(200, 500, null));
        assertEquals(FfmpegDownload.Answer.APPEND, FfmpegDownload.answer(206, 500, "bytes 500-999/1000"));
        assertEquals(FfmpegDownload.Answer.DISCARD, FfmpegDownload.answer(206, 500, "bytes 400-999/1000"));
        assertEquals(FfmpegDownload.Answer.DISCARD, FfmpegDownload.answer(206, 500, null));
        assertEquals(FfmpegDownload.Answer.DISCARD, FfmpegDownload.answer(206, 500, "nonsense"));
        assertEquals(FfmpegDownload.Answer.DISCARD, FfmpegDownload.answer(416, 500, null));
        assertEquals(FfmpegDownload.Answer.FAIL, FfmpegDownload.answer(503, 500, null));
        assertEquals(FfmpegDownload.Answer.FAIL, FfmpegDownload.answer(404, 0, null));
    }

    @Test
    @DisplayName("copy reports progress from the bytes already held; short is a drop, long is corrupt")
    void copyEdges() throws Exception {
        byte[] data = new byte[100];
        List<Integer> seen = new ArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FfmpegDownload.copy(new ByteArrayInputStream(data), out, 100, 200, seen::add, () -> {});
        assertEquals(100, out.size());
        assertEquals(100, seen.get(seen.size() - 1));
        assertTrue(seen.get(0) > 50, "progress starts from what was already held");

        IOException shortStream = assertThrows(IOException.class, () -> FfmpegDownload.copy(
            new ByteArrayInputStream(data), new ByteArrayOutputStream(), 0, 200, p -> {}, () -> {}));
        assertFalse(shortStream instanceof FfmpegDownload.CorruptDownloadException);
        assertThrows(FfmpegDownload.CorruptDownloadException.class, () -> FfmpegDownload.copy(
            new ByteArrayInputStream(data), new ByteArrayOutputStream(), 150, 200, p -> {}, () -> {}));
    }
}
