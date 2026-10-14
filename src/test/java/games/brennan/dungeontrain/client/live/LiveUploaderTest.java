package games.brennan.dungeontrain.client.live;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Result;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.SignedPut;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The uploader against a stand-in relay and bucket: an unanswered presign backs off instead of
 * hammering the relay, a 403 is a cut-off, a failed segment never gets named in the published
 * playlist, and the final flush runs on the uploader's own thread — never beside another pass.
 */
class LiveUploaderTest {

    @TempDir Path dir;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private FakeTransport net;

    @BeforeEach
    void setUp() {
        net = new FakeTransport();
    }

    @Test
    void unansweredPresignBacksOffExponentiallyUpToTenSeconds() throws Exception {
        writeStream(0, false, "seg00000.ts");
        net.presignReply = files -> new Result(0, null, new IOException("connection refused"));
        LiveUploader up = uploader();

        List<Long> waits = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            up.pass();
            waits.add(up.notBeforeMs() - now.get());
            now.set(up.notBeforeMs());
        }
        assertEquals(List.of(1000L, 2000L, 4000L, 8000L, 10_000L, 10_000L), waits);
        assertEquals(6, net.presigns.get());
    }

    @Test
    void aPassBeforeTheBackoffEndsAsksNothing() throws Exception {
        writeStream(0, false, "seg00000.ts");
        net.presignReply = files -> new Result(503, null, null);
        LiveUploader up = uploader();
        up.pass();
        now.addAndGet(500);
        up.pass();
        up.pass();
        assertEquals(1, net.presigns.get(), "presign called again inside the backoff");
    }

    @Test
    void anAnsweredPresignResetsTheBackoff() throws Exception {
        writeStream(0, false, "seg00000.ts");
        net.presignReply = files -> new Result(503, null, null);
        LiveUploader up = uploader();
        up.pass();
        now.set(up.notBeforeMs());
        up.pass();
        assertEquals(2000L, up.presignBackoffMs());

        now.set(up.notBeforeMs());
        net.presignReply = FakeTransport::signAll;
        up.pass();
        assertEquals(0L, up.presignBackoffMs());
        assertEquals(1, up.segmentsUploaded());
    }

    @Test
    void forbiddenIsACutOffNamingWhoTookTheChannel() throws Exception {
        writeStream(0, false, "seg00000.ts");
        JsonObject body = new JsonObject();
        body.addProperty("takenBy", "Steve");
        net.presignReply = files -> new Result(403, body, null);
        LiveUploader up = uploader();
        LiveUploader.CutOff c = assertThrows(LiveUploader.CutOff.class, up::pass);
        assertEquals("Steve", c.by);
    }

    @Test
    void aFailedSegmentIsLeftOutOfThePlaylistUntilItIsUp() throws Exception {
        writeStream(0, false, "seg00000.ts", "seg00001.ts", "seg00002.ts");
        net.failing.add("seg00002.ts");
        LiveUploader up = uploader();
        up.pass();

        String first = net.lastPlaylist();
        assertEquals(List.of("seg00000.ts", "seg00001.ts"), LivePlaylist.parse(first).segments());
        assertEquals(LiveUploader.FAIL_BACKOFF_MS, up.notBeforeMs() - now.get());

        // Same local playlist (its mtime was not recorded): the retry sends the segment, then all of it.
        net.failing.clear();
        now.set(up.notBeforeMs());
        up.pass();
        assertEquals(List.of("seg00000.ts", "seg00001.ts", "seg00002.ts"), LivePlaylist.parse(net.lastPlaylist()).segments());
        assertEquals(3, up.segmentsUploaded());

        // Recorded now: nothing changed, nothing asked.
        int presigns = net.presigns.get();
        up.pass();
        assertEquals(presigns, net.presigns.get());
    }

    @Test
    void aSegmentGivenUpCutsTheHeadAndKeepsSequenceNumbers() throws Exception {
        writeStream(7, false, "seg00007.ts", "seg00008.ts", "seg00009.ts");
        net.failing.add("seg00007.ts");
        LiveUploader up = uploader();
        up.pass(); // first failure: the whole list waits on seg7
        assertTrue(net.playlists.isEmpty(), "a playlist naming a missing first segment went up");

        now.set(up.notBeforeMs());
        up.pass(); // second failure: given up
        String published = net.lastPlaylist();
        assertEquals(List.of("seg00008.ts", "seg00009.ts"), LivePlaylist.parse(published).segments());
        assertTrue(published.contains("#EXT-X-MEDIA-SEQUENCE:8"), published);
    }

    @Test
    void finalFlushRunsOnceOnTheUploaderThreadNeverBesideAPass() throws Exception {
        writeStream(0, false, "seg00000.ts");
        net.presignDelayMs = 150; // keep a pass in flight when close() arrives
        LiveUploader up = new LiveUploader(dir, "tok", by -> { }, net, System::currentTimeMillis, true);
        assertTrue(waitFor(() -> net.presigns.get() >= 1), "uploader never polled");

        writeStream(0, true, "seg00000.ts", "seg00001.ts"); // ffmpeg's last write: ENDLIST
        up.close(true, 5_000);

        assertTrue(up.finished(), "close returned with the uploader still running");
        assertEquals(1, net.maxConcurrent.get(), "two passes overlapped");
        String last = net.lastPlaylist();
        assertTrue(LivePlaylist.parse(last).ended(), "the ENDLIST playlist never went out: " + last);
        assertTrue(net.threads.stream().allMatch(t -> t.equals("dt-live-uploader")), "a pass ran off the uploader thread: " + net.threads);
    }

    @Test
    void closeKeepsItsBudgetWhenTheRelayHangs() throws Exception {
        writeStream(0, false, "seg00000.ts");
        net.presignReply = null; // never answers
        LiveUploader up = new LiveUploader(dir, "tok", by -> { }, net, System::currentTimeMillis, true);
        assertTrue(waitFor(() -> net.presigns.get() >= 1), "uploader never polled");

        long start = System.nanoTime();
        up.close(true, 600);
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMs < 2_000, "close took " + tookMs + " ms");
        // The deadline also cut the in-flight call, so the thread ends soon after.
        assertTrue(waitFor(up::finished), "uploader thread outlived its deadline");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private LiveUploader uploader() {
        return new LiveUploader(dir, "tok", by -> { }, net, now::get, false);
    }

    /** Writes the segments (a few bytes each) and an ffmpeg-shaped playlist naming them; bumps its mtime. */
    private void writeStream(int firstSeq, boolean ended, String... segs) throws IOException {
        StringBuilder m3u8 = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:")
            .append(firstSeq).append("\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        for (String seg : segs) {
            Files.writeString(dir.resolve(seg), "ts:" + seg);
            m3u8.append("#EXTINF:10.000000,\n").append(seg).append('\n');
        }
        if (ended) m3u8.append("#EXT-X-ENDLIST\n");
        Path pl = dir.resolve(LivePlaylist.FILE_NAME);
        long prev = Files.exists(pl) ? Files.getLastModifiedTime(pl).toMillis() : 0;
        Files.writeString(pl, m3u8.toString());
        Files.setLastModifiedTime(pl, FileTime.fromMillis(Math.max(prev + 1000, System.currentTimeMillis())));
    }

    private static boolean waitFor(java.util.function.BooleanSupplier ok) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            if (ok.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return ok.getAsBoolean();
    }

    /** A relay that signs everything (unless told otherwise) and a bucket that keeps what it is sent. */
    private static final class FakeTransport implements LiveUploader.Transport {
        /** null = the call never completes. */
        volatile Function<List<String>, Result> presignReply = FakeTransport::signAll;
        volatile long presignDelayMs;
        final Set<String> failing = ConcurrentHashMap.newKeySet();
        final AtomicInteger presigns = new AtomicInteger();
        final List<String> playlists = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();

        static Result signAll(List<String> files) {
            JsonObject urls = new JsonObject();
            for (String f : files) {
                JsonObject o = new JsonObject();
                o.addProperty("url", "https://bucket.test/" + f);
                urls.add(f, o);
            }
            JsonObject body = new JsonObject();
            body.add("urls", urls);
            return new Result(200, body, null);
        }

        String lastPlaylist() {
            assertFalse(playlists.isEmpty(), "no playlist was uploaded");
            return playlists.get(playlists.size() - 1);
        }

        @Override
        public CompletableFuture<Result> presign(String token, List<String> files) {
            // Every pass opens with a presign, held for presignDelayMs: two passes at once would overlap here.
            maxConcurrent.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            threads.add(Thread.currentThread().getName());
            presigns.incrementAndGet();
            Function<List<String>, Result> reply = presignReply;
            try {
                if (reply == null) return new CompletableFuture<>();
                if (presignDelayMs > 0) Thread.sleep(presignDelayMs);
                return CompletableFuture.completedFuture(reply.apply(files));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CompletableFuture.completedFuture(new Result(0, null, e));
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public CompletableFuture<Result> upload(SignedPut target, Path file) {
            threads.add(Thread.currentThread().getName());
            String name = file.getFileName().toString();
            if (failing.contains(name)) return CompletableFuture.completedFuture(new Result(500, null, null));
            return CompletableFuture.completedFuture(new Result(200, null, null));
        }

        @Override
        public CompletableFuture<Result> upload(SignedPut target, byte[] body) {
            threads.add(Thread.currentThread().getName());
            playlists.add(new String(body, StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(new Result(200, null, null));
        }
    }
}
