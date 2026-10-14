package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Result;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.SignedPut;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Ships what the encoder writes: watches {@code live.m3u8}, and whenever it changes, asks the relay
 * for signed URLs for the new segments plus the playlist, PUTs the segments, then the playlist
 * (that order matters — a playlist must never name a segment that is not there yet). The playlist
 * that goes up is {@link LivePlaylist#publishable}: cut off before a segment still being retried,
 * and without a segment given up on, so it only ever names what is on storage.
 *
 * <p>Each presign is the relay heartbeat. A 403 means someone else took the channel: the uploader
 * stops and tells the controller who. A failed segment is retried on later polls (after a
 * {@value #FAIL_BACKOFF_MS} ms back-off); after {@value #MAX_FAILED_PASSES} failed passes it is
 * skipped — viewers see a jump, not a crash.</p>
 */
public final class LiveUploader implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long POLL_MS = 500;
    private static final long CALL_TIMEOUT_S = 20;
    static final long FAIL_BACKOFF_MS = 3000;
    static final int MAX_FAILED_PASSES = 4;

    /** The relay and storage calls, so tests can stand in for them. */
    interface Storage {
        CompletableFuture<Result> presign(String token, List<String> files);
        CompletableFuture<Result> upload(SignedPut target, Path file);
        CompletableFuture<Result> upload(SignedPut target, String body);
    }

    private static final Storage LIVE = new Storage() {
        @Override public CompletableFuture<Result> presign(String token, List<String> files) { return LiveFeedClient.presign(token, files); }
        @Override public CompletableFuture<Result> upload(SignedPut target, Path file) { return LiveFeedClient.upload(target, file); }
        @Override public CompletableFuture<Result> upload(SignedPut target, String body) { return LiveFeedClient.upload(target, body); }
    };

    private final Path dir;
    private final String token;
    private final Consumer<String> onCutOff;
    private final Storage storage;
    private final Thread thread;
    private final Set<String> uploaded = new HashSet<>();
    private final Map<String, Integer> failures = new java.util.HashMap<>();
    /** Sequence numbers of segments given up on: never on storage, never in a published playlist. */
    private final NavigableSet<Long> skippedSeqs = new TreeSet<>();
    private String lastPublished;
    private volatile boolean running = true;
    private volatile long lastPlaylistMtime = -1;
    private volatile long notBeforeMs;
    private volatile int segmentsUploaded;
    private volatile int viewers = -1;

    public LiveUploader(Path dir, String token, Consumer<String> onCutOff) {
        this(dir, token, onCutOff, LIVE, true);
    }

    LiveUploader(Path dir, String token, Consumer<String> onCutOff, Storage storage, boolean start) {
        this.dir = dir;
        this.token = token;
        this.onCutOff = onCutOff;
        this.storage = storage;
        this.thread = new Thread(this::loop, "dt-live-uploader");
        this.thread.setDaemon(true);
        if (start) this.thread.start();
    }

    public int segmentsUploaded() {
        return segmentsUploaded;
    }

    /** Viewers as of the last heartbeat; −1 until the relay has said. */
    public int viewers() {
        return viewers;
    }

    private void loop() {
        while (running) {
            try {
                pass();
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (CutOff c) {
                running = false;
                onCutOff.accept(c.by);
                return;
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] live upload pass failed: {}", e.toString());
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    void pass() throws IOException, CutOff {
        pass(false);
    }

    /**
     * One pass; also called once more on clean stop ({@code last}) so the final segments and the
     * ENDLIST playlist go out — viewers behind real time play right up to it, so the last pass
     * ignores a failure backoff.
     */
    void pass(boolean last) throws IOException, CutOff {
        if (!last && System.currentTimeMillis() < notBeforeMs) return;
        Path playlist = dir.resolve(LivePlaylist.FILE_NAME);
        if (!Files.exists(playlist)) return;
        long mtime = Files.getLastModifiedTime(playlist).toMillis();
        if (mtime == lastPlaylistMtime) return;
        String text = Files.readString(playlist, StandardCharsets.UTF_8);
        LivePlaylist parsed = LivePlaylist.parse(text);
        Map<String, Long> pending = new LinkedHashMap<>();
        long seq = parsed.mediaSequence();
        for (String seg : parsed.segments()) {
            long sn = seq++;
            if (uploaded.contains(seg) || skippedSeqs.contains(sn)) continue;
            if (Files.exists(dir.resolve(seg))) {
                pending.put(seg, sn);
            } else {
                skip(seg, sn, "its file is gone");
            }
        }
        List<String> ask = new ArrayList<>(pending.keySet());
        ask.add(LivePlaylist.FILE_NAME);
        Result r = await(storage.presign(token, ask));
        if (r.forbidden()) throw new CutOff(r.str("takenBy"));
        Map<String, SignedPut> urls = LiveFeedClient.parsePresign(r);
        int count = LiveFeedClient.parseViewerCount(r);
        if (count >= 0) viewers = count;
        if (urls.isEmpty()) {
            LOGGER.debug("[DungeonTrain] live presign unanswered (status {}), retrying next poll", r.status());
            return;
        }
        boolean allDone = true;
        for (Map.Entry<String, Long> e : pending.entrySet()) {
            String seg = e.getKey();
            SignedPut target = urls.get(seg);
            if (target == null) { allDone = false; continue; }
            Result put = await(storage.upload(target, dir.resolve(seg)));
            if (put.status() >= 200 && put.status() < 300) {
                uploaded.add(seg);
                segmentsUploaded++;
                continue;
            }
            int failed = failures.merge(seg, 1, Integer::sum);
            LOGGER.warn("[DungeonTrain] live segment {} upload → {} {}", seg, put.status(), put.error() == null ? "" : put.error().toString());
            // The last pass has no later pass to retry in: give up now so the ENDLIST playlist still goes out.
            if (last || failed >= MAX_FAILED_PASSES) {
                skip(seg, e.getValue(), "gave up after " + failed + " failed uploads");
            } else {
                allDone = false;
                notBeforeMs = System.currentTimeMillis() + FAIL_BACKOFF_MS;
            }
        }
        Optional<String> publish = LivePlaylist.publishable(text, uploaded, skippedSeqs);
        boolean published = publish.isEmpty() || publish.get().equals(lastPublished);
        SignedPut pl = urls.get(LivePlaylist.FILE_NAME);
        if (!published && pl != null) {
            Result put = await(storage.upload(pl, publish.get()));
            if (put.status() >= 200 && put.status() < 300) {
                lastPublished = publish.get();
                published = true;
            } else {
                // Storage said no (or the network did): wait before asking the relay to sign again,
                // or a dead bucket turns into two presign calls a second.
                notBeforeMs = System.currentTimeMillis() + FAIL_BACKOFF_MS;
                LOGGER.warn("[DungeonTrain] live playlist upload → {} (retry in {} s)", put.status(), FAIL_BACKOFF_MS / 1000);
            }
        }
        // Only once nothing is left to retry: until then every poll comes back for the pending segments.
        if (allDone && published) lastPlaylistMtime = mtime;
    }

    private void skip(String seg, long sn, String why) {
        skippedSeqs.add(sn);
        failures.remove(seg);
        LOGGER.info("[DungeonTrain] live segment {} skipped: {}", seg, why);
    }

    /** Segments given up on so far. */
    int segmentsSkipped() {
        return skippedSeqs.size();
    }

    private static Result await(CompletableFuture<Result> f) {
        try {
            return f.get(CALL_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            return new Result(0, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(0, null, e);
        }
    }

    /**
     * Stop polling. {@code flush} = run one final pass first (clean stop: ships the last segments and
     * the ENDLIST playlist), after the polling thread has let go so the two never upload at once.
     */
    public void close(boolean flush) {
        running = false;
        thread.interrupt();
        if (flush) {
            try { thread.join(TimeUnit.SECONDS.toMillis(CALL_TIMEOUT_S)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try { pass(true); } catch (Exception e) { LOGGER.warn("[DungeonTrain] live final upload failed: {}", e.toString()); }
        }
    }

    @Override
    public void close() {
        close(false);
    }

    static final class CutOff extends Exception {
        final String by;
        CutOff(String by) { super("cut off"); this.by = by == null ? "" : by; }
    }
}
