package games.brennan.dungeontrain.client.live;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Result;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.SignedPut;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveUploaderTest {

    @TempDir
    Path dir;

    /** Stands in for the relay and R2: signs every file, fails the segments it is told to. */
    private static final class FakeStorage implements LiveUploader.Storage {
        final Set<String> failing = new HashSet<>();
        final List<String> published = new ArrayList<>();
        final List<String> segmentPuts = new ArrayList<>();
        final Set<String> onStorage = new HashSet<>();
        int presignStatus = 200;
        /** Answer presigns at all; false = a stalled relay (the call fails with no status, as a timeout does). */
        boolean answering = true;
        /** Sign ahead when asked (a relay with dp-relay #945); false = an older relay. */
        boolean signsAhead;
        /** How many segments one sign-ahead covers. */
        int aheadCount = 20;
        /** The relay's clock, for the signed URLs' expiry. */
        java.util.function.LongSupplier relayNow = () -> 5_000_000L;
        long aheadExpiresMs = 240_000;
        /** Files the relay refuses to sign, and why. */
        final java.util.Map<String, String> refuse = new java.util.HashMap<>();
        final List<Boolean> askedAhead = new ArrayList<>();
        /** Every PUT target used, by URL: a segment URL used twice would show up here twice. */
        final List<String> putUrls = new ArrayList<>();
        long highest = -1;

        @Override
        public CompletableFuture<Result> presign(String token, List<String> files, boolean ahead) {
            askedAhead.add(ahead);
            if (!answering) return CompletableFuture.completedFuture(new Result(0, null, new java.util.concurrent.TimeoutException("stalled")));
            JsonObject urls = new JsonObject();
            JsonObject refused = new JsonObject();
            for (String f : files) {
                if (refuse.containsKey(f)) { refused.addProperty(f, refuse.get(f)); continue; }
                JsonObject o = new JsonObject();
                o.addProperty("url", "https://r2.invalid/" + f);
                urls.add(f, o);
                long n = LiveUploader.segmentIndex(f);
                highest = Math.max(highest, n);
            }
            JsonObject body = new JsonObject();
            body.add("urls", urls);
            if (refused.size() > 0) body.add("refused", refused);
            if (ahead && signsAhead && presignStatus == 200) body.add("ahead", aheadBlock());
            if (presignStatus == 403) body.addProperty("takenBy", "Someone");
            return CompletableFuture.completedFuture(new Result(presignStatus, body, null));
        }

        private JsonObject aheadBlock() {
            long exp = relayNow.getAsLong() + aheadExpiresMs;
            JsonObject urls = new JsonObject();
            for (long n = highest + 1; n <= highest + aheadCount; n++) {
                String name = String.format("seg%05d.ts", n);
                JsonObject o = new JsonObject();
                o.addProperty("url", "https://r2.invalid/ahead/" + name);
                o.addProperty("expiresAt", exp);
                urls.add(name, o);
            }
            JsonObject pl = new JsonObject();
            pl.addProperty("url", "https://r2.invalid/ahead/" + LivePlaylist.FILE_NAME);
            pl.addProperty("expiresAt", exp);
            urls.add(LivePlaylist.FILE_NAME, pl);
            JsonObject a = new JsonObject();
            a.addProperty("from", highest + 1);
            a.addProperty("expiresAt", exp);
            a.add("urls", urls);
            return a;
        }

        @Override
        public CompletableFuture<Result> upload(SignedPut target, Path file) {
            String name = file.getFileName().toString();
            segmentPuts.add(name);
            putUrls.add(target.url());
            if (failing.contains(name)) return CompletableFuture.completedFuture(new Result(0, null, new IOException("header parser received no bytes")));
            onStorage.add(name);
            return CompletableFuture.completedFuture(new Result(200, null, null));
        }

        @Override
        public CompletableFuture<Result> upload(SignedPut target, String body) {
            published.add(body);
            putUrls.add(target.url());
            return CompletableFuture.completedFuture(new Result(200, null, null));
        }

        LivePlaylist last() {
            return LivePlaylist.parse(published.get(published.size() - 1));
        }
    }

    private long tick = 1_000_000L;

    /** ffmpeg writes segments {@code first..first+count-1} and a fresh playlist naming them. */
    private void encoderWrites(long first, int count, boolean ended) throws IOException {
        StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:" + first + "\n");
        for (long sn = first; sn < first + count; sn++) {
            String name = String.format("seg%05d.ts", sn);
            if (!Files.exists(dir.resolve(name))) Files.writeString(dir.resolve(name), "ts");
            b.append("#EXTINF:10.0,\n").append(name).append('\n');
        }
        if (ended) b.append("#EXT-X-ENDLIST\n");
        Path pl = dir.resolve(LivePlaylist.FILE_NAME);
        Files.writeString(pl, b.toString(), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(pl, FileTime.fromMillis(tick += 1000));
    }

    /** Runs a pass past any failure back-off, as later polls would. */
    private static void passNow(LiveUploader u) throws Exception {
        java.lang.reflect.Field f = LiveUploader.class.getDeclaredField("notBeforeMs");
        f.setAccessible(true);
        f.setLong(u, 0L);
        u.pass(false);
    }

    private LiveUploader uploader(FakeStorage s, AtomicReference<String> cutOffBy) {
        return new LiveUploader(dir, "tok", cutOffBy::set, s, false);
    }

    @Test
    void failedSegmentIsLeftOutOfThePlaylistUntilItIsUp() throws Exception {
        FakeStorage s = new FakeStorage();
        LiveUploader u = uploader(s, new AtomicReference<>());
        encoderWrites(0, 3, false);
        s.failing.add("seg00001.ts");
        u.pass(false);
        assertEquals(List.of("seg00000.ts"), s.last().segments());

        s.failing.clear();
        passNow(u);
        assertEquals(List.of("seg00000.ts", "seg00001.ts", "seg00002.ts"), s.last().segments());
        assertEquals(3, u.segmentsUploaded());
        assertEquals(0, u.segmentsSkipped());
    }

    @Test
    void segmentIsSkippedAfterFourFailedPassesAndLaterOnesGoOut() throws Exception {
        FakeStorage s = new FakeStorage();
        LiveUploader u = uploader(s, new AtomicReference<>());
        encoderWrites(0, 3, false);
        s.failing.add("seg00001.ts");
        for (int i = 0; i < LiveUploader.MAX_FAILED_PASSES; i++) passNow(u);
        assertEquals(LiveUploader.MAX_FAILED_PASSES, s.segmentPuts.stream().filter("seg00001.ts"::equals).count());
        assertEquals(1, u.segmentsSkipped());
        String last = s.published.get(s.published.size() - 1);
        assertEquals(List.of("seg00000.ts", "seg00002.ts"), LivePlaylist.parse(last).segments());
        assertTrue(last.contains("#EXT-X-DISCONTINUITY\n"));

        // Given up for good: no more tries, and the next segments keep flowing.
        encoderWrites(1, 3, false);
        passNow(u);
        assertEquals(LiveUploader.MAX_FAILED_PASSES, s.segmentPuts.stream().filter("seg00001.ts"::equals).count());
        assertEquals(List.of("seg00002.ts", "seg00003.ts"), s.last().segments());
        assertEquals(1, s.last().mediaSequence());
    }

    @Test
    void neverPublishesASegmentThatIsNotUp() throws Exception {
        FakeStorage s = new FakeStorage();
        LiveUploader u = uploader(s, new AtomicReference<>());
        for (int first = 0; first < 10; first++) {
            s.failing.clear();
            if (first % 3 == 1) s.failing.add(String.format("seg%05d.ts", first + 2));
            encoderWrites(first, 3, false);
            passNow(u);
            for (String body : s.published) {
                for (String seg : LivePlaylist.parse(body).segments()) {
                    assertTrue(s.onStorage.contains(seg), seg + " published before it was on storage");
                }
            }
        }
    }

    @Test
    void publishedThroughFollowsWhatIsOnStorage() throws Exception {
        FakeStorage s = new FakeStorage();
        LiveUploader u = uploader(s, new AtomicReference<>());
        assertEquals(-1, u.publishedThrough());
        encoderWrites(0, 3, false);
        s.failing.add("seg00001.ts");
        u.pass(false);
        // seg00002 is up but cannot be listed past the one still retrying.
        assertEquals(0, u.publishedThrough());

        s.failing.clear();
        passNow(u);
        assertEquals(2, u.publishedThrough());

        // A segment given up on leaves a gap; the index still comes from the names.
        encoderWrites(1, 4, false);
        s.failing.add("seg00004.ts");
        for (int i = 0; i < LiveUploader.MAX_FAILED_PASSES; i++) passNow(u);
        assertEquals(3, u.publishedThrough());
        encoderWrites(2, 4, false);
        passNow(u);
        assertEquals(5, u.publishedThrough());
    }

    @Test
    void unchangedPlaylistIsNotUploadedAgain() throws Exception {
        FakeStorage s = new FakeStorage();
        LiveUploader u = uploader(s, new AtomicReference<>());
        encoderWrites(0, 2, false);
        s.failing.add("seg00001.ts");
        u.pass(false);
        passNow(u);
        assertEquals(1, s.published.size());
    }

    @Test
    void finalPassGivesUpOnFailuresSoEndlistGoesOut() throws Exception {
        FakeStorage s = new FakeStorage();
        LiveUploader u = uploader(s, new AtomicReference<>());
        encoderWrites(0, 3, true);
        s.failing.add("seg00002.ts");
        u.pass(true);
        assertTrue(s.last().ended());
        assertEquals(List.of("seg00000.ts", "seg00001.ts"), s.last().segments());
    }

    @Test
    void presign403CutsOff() throws Exception {
        FakeStorage s = new FakeStorage();
        s.presignStatus = 403;
        LiveUploader u = uploader(s, new AtomicReference<>());
        encoderWrites(0, 1, false);
        LiveUploader.CutOff c = assertThrows(LiveUploader.CutOff.class, () -> u.pass(false));
        assertEquals("Someone", c.by);
        assertTrue(s.published.isEmpty());
    }

    @Test
    void missingSegmentFileIsSkipped() throws Exception {
        FakeStorage s = new FakeStorage();
        LiveUploader u = uploader(s, new AtomicReference<>());
        encoderWrites(0, 3, false);
        Files.delete(dir.resolve("seg00001.ts"));
        u.pass(false);
        assertEquals(1, u.segmentsSkipped());
        assertEquals(List.of("seg00000.ts", "seg00002.ts"), s.last().segments());
        assertFalse(s.segmentPuts.contains("seg00001.ts"));
    }

    // ---- presign back-off ---------------------------------------------------------------------

    private final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(5_000_000L);

    private LiveUploader clocked(FakeStorage s) {
        return new LiveUploader(dir, "tok", by -> { }, s, now::get, false);
    }

    @Test
    void unansweredPresignBacksOffExponentiallyUpToTenSeconds() throws Exception {
        FakeStorage s = new FakeStorage();
        s.presignStatus = 503;
        LiveUploader u = clocked(s);
        encoderWrites(0, 1, false);
        List<Long> waits = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            u.pass(false);
            waits.add(u.notBeforeMs() - now.get());
            now.set(u.notBeforeMs());
        }
        assertEquals(List.of(1000L, 2000L, 4000L, 8000L, 10_000L, 10_000L), waits);
    }

    @Test
    void noPresignInsideTheBackOff() throws Exception {
        CountingStorage s = new CountingStorage(new FakeStorage());
        s.inner.presignStatus = 503;
        LiveUploader u = new LiveUploader(dir, "tok", by -> { }, s, now::get, false);
        encoderWrites(0, 1, false);
        u.pass(false);
        now.addAndGet(500);
        u.pass(false);
        u.pass(false);
        assertEquals(1, s.presigns.get(), "presign asked again inside the back-off");
    }

    @Test
    void anAnsweredPresignResetsTheBackOff() throws Exception {
        FakeStorage s = new FakeStorage();
        s.presignStatus = 503;
        LiveUploader u = clocked(s);
        encoderWrites(0, 1, false);
        u.pass(false);
        now.set(u.notBeforeMs());
        u.pass(false);
        assertEquals(2000L, u.presignBackoffMs());

        s.presignStatus = 200;
        now.set(u.notBeforeMs());
        u.pass(false);
        assertEquals(0L, u.presignBackoffMs());
        assertEquals(1, u.segmentsUploaded());
    }

    // ---- close: the final pass belongs to the uploader thread -----------------------------------

    @Test
    void finalFlushRunsOnceOnTheUploaderThreadNeverBesideAPass() throws Exception {
        CountingStorage s = new CountingStorage(new FakeStorage());
        s.presignDelayMs = 150; // keep a pass in flight when close() arrives
        encoderWrites(0, 1, false);
        LiveUploader u = new LiveUploader(dir, "tok", by -> { }, s, true);
        assertTrue(waitFor(() -> s.presigns.get() >= 1), "uploader never polled");

        encoderWrites(0, 2, true); // ffmpeg's last write: ENDLIST
        u.close(true, 5_000);

        assertTrue(u.finished(), "close returned with the uploader still running");
        assertEquals(1, s.maxConcurrent.get(), "two passes overlapped");
        assertTrue(s.inner.last().ended(), "the ENDLIST playlist never went out");
        assertTrue(s.threads.stream().allMatch("dt-live-uploader"::equals), "a pass ran off the uploader thread: " + s.threads);
    }

    @Test
    void closeKeepsItsBudgetWhenTheRelayHangs() throws Exception {
        CountingStorage s = new CountingStorage(new FakeStorage());
        s.hang = true;
        encoderWrites(0, 1, false);
        LiveUploader u = new LiveUploader(dir, "tok", by -> { }, s, true);
        assertTrue(waitFor(() -> s.presigns.get() >= 1), "uploader never polled");

        long start = System.nanoTime();
        u.close(true, 600);
        long tookMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMs < 2_000, "close took " + tookMs + " ms");
        // The deadline also cut the call in flight, so the thread ends soon after.
        assertTrue(waitFor(u::finished), "uploader thread outlived its deadline");
    }

    private static boolean waitFor(java.util.function.BooleanSupplier ok) throws InterruptedException {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            if (ok.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return ok.getAsBoolean();
    }

    /** {@link FakeStorage}, counting presigns and the threads that call, slowed or hung on request. */
    private static final class CountingStorage implements LiveUploader.Storage {
        final FakeStorage inner;
        volatile long presignDelayMs;
        volatile boolean hang;
        final java.util.concurrent.atomic.AtomicInteger presigns = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger maxConcurrent = new java.util.concurrent.atomic.AtomicInteger();
        final List<String> threads = new java.util.concurrent.CopyOnWriteArrayList<>();

        CountingStorage(FakeStorage inner) {
            this.inner = inner;
        }

        @Override
        public CompletableFuture<Result> presign(String token, List<String> files, boolean ahead) {
            // Every pass opens with a presign, held for presignDelayMs: two passes at once overlap here.
            maxConcurrent.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            threads.add(Thread.currentThread().getName());
            presigns.incrementAndGet();
            try {
                if (hang) return new CompletableFuture<>();
                if (presignDelayMs > 0) Thread.sleep(presignDelayMs);
                return inner.presign(token, files, ahead);
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
            return inner.upload(target, file);
        }

        @Override
        public CompletableFuture<Result> upload(SignedPut target, String body) {
            threads.add(Thread.currentThread().getName());
            return inner.upload(target, body);
        }
    }

    @Test
    void aHeldUploaderShipsSegmentsButNotTheOldEncodersEnd() throws Exception {
        FakeStorage s = new FakeStorage();
        LiveUploader u = uploader(s, new AtomicReference<>());
        encoderWrites(0, 2, false);
        u.pass(false);
        int before = s.published.size();

        u.hold();
        encoderWrites(0, 3, true); // the swapped-out encoder's last segment and its ENDLIST
        passNow(u);
        assertTrue(s.onStorage.contains("seg00002.ts"));
        assertEquals(before, s.published.size(), "no playlist while held");

        LivePlaylist.dropEndlist(dir.resolve(LivePlaylist.FILE_NAME));
        u.release();
        passNow(u);
        assertFalse(s.last().ended());
        assertEquals(List.of("seg00000.ts", "seg00001.ts", "seg00002.ts"), s.last().segments());
    }

    @Test
    void theLastPassOfAStopIgnoresTheHold() throws Exception {
        FakeStorage s = new FakeStorage();
        LiveUploader u = uploader(s, new AtomicReference<>());
        u.hold();
        encoderWrites(0, 2, true);
        u.pass(true);
        assertTrue(s.last().ended());
    }

    @Test
    void segmentPutsAreTimedIntoTheHealth() throws Exception {
        FakeStorage s = new FakeStorage();
        LiveUploadHealth health = new LiveUploadHealth(10);
        LiveUploader u = new LiveUploader(dir, "tok", by -> { }, s, System::currentTimeMillis, health, false);
        encoderWrites(0, 3, false);
        u.pass(false);
        assertEquals(3, health.snapshot().samples());
    }

    // ---- sign-ahead: riding out a stalled relay (dp-relay #945) ---------------------------------

    private FakeStorage signingAhead() {
        FakeStorage s = new FakeStorage();
        s.signsAhead = true;
        s.relayNow = now::get;
        return s;
    }

    /** The relay stalls for 70 s ({@code status} 0 = no answer, as a timeout; or a 5xx or 429). */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 503, 429})
    void aStalledRelayIsRiddenOutOnSignedAheadUrls(int status) throws Exception {
        FakeStorage s = signingAhead();
        LiveUploadHealth health = new LiveUploadHealth(10);
        LiveUploader u = new LiveUploader(dir, "tok", by -> { }, s, now::get, health, false);
        encoderWrites(0, 3, false);
        u.pass(false);
        assertEquals(20, u.stockUsable(), "the first presign signs the next 20 segments ahead");

        if (status == 0) s.answering = false; else s.presignStatus = status;
        int presignsBefore = s.askedAhead.size();
        long first = 0;
        for (int poll = 1; poll <= 140; poll++) { // 70 s of 500 ms polls; a 10 s segment every 20
            now.addAndGet(500);
            if (poll % 20 == 0) encoderWrites(++first, 3, false);
            u.pass(false);
            for (String body : s.published) {
                for (String seg : LivePlaylist.parse(body).segments()) {
                    assertTrue(s.onStorage.contains(seg), seg + " published before it was on storage");
                }
            }
        }

        assertEquals(10, u.segmentsUploaded(), "every segment the encoder cut went up");
        assertEquals(0, u.segmentsSkipped());
        assertEquals(List.of("seg00007.ts", "seg00008.ts", "seg00009.ts"), s.last().segments());
        for (int n = 3; n <= 9; n++) {
            assertTrue(s.putUrls.contains(String.format("https://r2.invalid/ahead/seg%05d.ts", n)), "seg " + n + " not on its ahead URL");
        }
        long playlistPuts = s.putUrls.stream().filter(("https://r2.invalid/ahead/" + LivePlaylist.FILE_NAME)::equals).count();
        assertEquals(7, playlistPuts, "the one ahead playlist URL is PUT for every new playlist");
        // Back-off 1, 2, 4, 8, 10, 10 … s: the stalled relay is not asked every poll.
        int stalledPresigns = s.askedAhead.size() - presignsBefore;
        assertTrue(stalledPresigns <= 11, stalledPresigns + " presigns during the stall");
        // Presign failures never read as a slow upload.
        assertEquals(10, health.snapshot().samples());

        // The relay wakes: the next presign after the back-off is answered and refills the stock.
        s.answering = true;
        s.presignStatus = 200;
        now.addAndGet(LiveUploader.PRESIGN_BACKOFF_MAX_MS);
        encoderWrites(++first, 3, false);
        u.pass(false);
        assertEquals(0L, u.presignBackoffMs());
        assertEquals(List.of("seg00008.ts", "seg00009.ts", "seg00010.ts"), s.last().segments());
        assertTrue(u.stockUsable() >= 10);
    }

    @Test
    void expiredStockIsNotUsed() throws Exception {
        FakeStorage s = signingAhead();
        s.aheadExpiresMs = 60_000;
        LiveUploader u = clocked(s);
        encoderWrites(0, 1, false);
        u.pass(false);
        int published = s.published.size();

        s.answering = false;
        now.addAndGet(60_000 - LiveAheadStock.EXPIRY_MARGIN_MS);
        encoderWrites(0, 2, false);
        u.pass(false);
        assertEquals(1, u.segmentsUploaded(), "an expired ahead URL was used");
        assertEquals(published, s.published.size());
        assertEquals(LiveUploader.PRESIGN_BACKOFF_MIN_MS, u.notBeforeMs() - now.get(), "no stock: today's back-off");
    }

    @Test
    void anOlderRelayWithoutSignAheadBehavesAsBefore() throws Exception {
        FakeStorage s = new FakeStorage(); // never sends `ahead`
        LiveUploader u = clocked(s);
        encoderWrites(0, 1, false);
        u.pass(false);
        assertEquals(0, u.stockUsable());
        s.answering = false;
        encoderWrites(0, 2, false);
        now.addAndGet(500);
        u.pass(false);
        assertEquals(1, u.segmentsUploaded());
        assertEquals(LiveUploader.PRESIGN_BACKOFF_MIN_MS, u.notBeforeMs() - now.get());
        now.addAndGet(500);
        u.pass(false);
        assertEquals(2, s.askedAhead.size(), "presigned again inside the back-off");
    }

    @Test
    void aSegmentTheRelayRefusesIsSkippedNotWaitedOn() throws Exception {
        FakeStorage s = new FakeStorage();
        LiveUploader u = uploader(s, new AtomicReference<>());
        s.refuse.put("seg00001.ts", "stale");
        encoderWrites(0, 3, false);
        u.pass(false);
        assertEquals(1, u.segmentsSkipped());
        assertEquals(List.of("seg00000.ts", "seg00002.ts"), s.last().segments());
        assertFalse(s.segmentPuts.contains("seg00001.ts"));
    }

    @Test
    void aStaleSegmentStillInTheStockGoesUpOnItsAheadUrl() throws Exception {
        FakeStorage s = signingAhead();
        LiveUploader u = clocked(s);
        encoderWrites(0, 1, false);
        u.pass(false);
        s.refuse.put("seg00001.ts", "stale"); // a zombie presign from the stall already signed it
        encoderWrites(0, 2, false);
        now.addAndGet(500);
        u.pass(false);
        assertEquals(0, u.segmentsSkipped());
        assertTrue(s.putUrls.contains("https://r2.invalid/ahead/seg00001.ts"));
        assertEquals(List.of("seg00000.ts", "seg00001.ts"), s.last().segments());
    }

    @Test
    void asksToSignAheadOnlyWhenUnderHalfTheStockIsLeft() throws Exception {
        FakeStorage s = signingAhead();
        s.aheadCount = 4;
        LiveUploader u = clocked(s);
        for (int first = 0; first < 5; first++) {
            encoderWrites(first, 1, false);
            now.addAndGet(500);
            u.pass(false);
        }
        // Batch of 4 from the first presign (seg 1-4); each segment that goes up spends its URL. Two of
        // four left is still half; asked again once only seg 4's URL is left.
        assertEquals(List.of(true, false, false, false, true), s.askedAhead);
    }

    @Test
    void aSegmentWithNoAheadUrlDuringAStallIsSkippedSoThePlaylistMovesOn() throws Exception {
        FakeStorage s = signingAhead();
        LiveUploader u = clocked(s);
        encoderWrites(0, 2, false);
        s.failing.add("seg00001.ts"); // signed, but its PUT fails: not in the ahead batch (that starts at 2)
        u.pass(false);
        s.failing.clear();
        s.answering = false;
        encoderWrites(0, 3, false);
        for (int i = 0; i < 8; i++) {
            now.addAndGet(LiveUploader.FAIL_BACKOFF_MS);
            u.pass(false);
        }
        assertEquals(1, u.segmentsSkipped());
        assertEquals(List.of("seg00000.ts", "seg00002.ts"), s.last().segments());
    }
}
