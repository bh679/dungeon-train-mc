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

        @Override
        public CompletableFuture<Result> presign(String token, List<String> files) {
            JsonObject urls = new JsonObject();
            for (String f : files) {
                JsonObject o = new JsonObject();
                o.addProperty("url", "https://r2.invalid/" + f);
                urls.add(f, o);
            }
            JsonObject body = new JsonObject();
            body.add("urls", urls);
            if (presignStatus == 403) body.addProperty("takenBy", "Someone");
            return CompletableFuture.completedFuture(new Result(presignStatus, body, null));
        }

        @Override
        public CompletableFuture<Result> upload(SignedPut target, Path file) {
            String name = file.getFileName().toString();
            segmentPuts.add(name);
            if (failing.contains(name)) return CompletableFuture.completedFuture(new Result(0, null, new IOException("header parser received no bytes")));
            onStorage.add(name);
            return CompletableFuture.completedFuture(new Result(200, null, null));
        }

        @Override
        public CompletableFuture<Result> upload(SignedPut target, String body) {
            published.add(body);
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
}
