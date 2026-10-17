package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.live.life.LiveLifeEvents;
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
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * skipped — viewers see a jump, not a crash. An unanswered presign (relay down, 5xx) backs off
 * 1 s, 2 s, 4 s … up to 10 s instead of asking again every poll.</p>
 *
 * <p>Every pass runs on the uploader's own thread — the final one on a clean stop too, so two passes
 * never touch the upload bookkeeping at once; {@link #close(boolean, long)} only waits for it, and
 * cuts every call short at its deadline.</p>
 */
public final class LiveUploader implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long POLL_MS = 500;
    private static final long ERROR_PAUSE_MS = 2000;
    private static final long CALL_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(20);
    /** How often a waiting call re-checks a deadline {@link #close(boolean, long)} may have pulled in. */
    private static final long AWAIT_SLICE_NS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long JOIN_MARGIN_MS = 100;
    static final long FAIL_BACKOFF_MS = 3000;
    static final long PRESIGN_BACKOFF_MIN_MS = 1000;
    static final long PRESIGN_BACKOFF_MAX_MS = 10_000;
    static final int MAX_FAILED_PASSES = 4;
    /** How long a clean stop off the render thread waits for the final pass. */
    public static final long CLOSE_BUDGET_MS = 60_000;
    private static final Pattern SEGMENT_INDEX = Pattern.compile("seg(\\d{5})\\.ts");

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
    private final LongSupplier clock;
    private final Object wake = new Object();
    private final Thread thread;
    private final Set<String> uploaded = new HashSet<>();
    private final Map<String, Integer> failures = new java.util.HashMap<>();
    /** Sequence numbers of segments given up on: never on storage, never in a published playlist. */
    private final NavigableSet<Long> skippedSeqs = new TreeSet<>();
    private String lastPublished;
    private long presignBackoffMs; // uploader thread only
    private volatile boolean running = true;
    private volatile boolean flushRequested;
    private volatile long deadlineNs = Long.MAX_VALUE;
    private volatile long lastPlaylistMtime = -1;
    private volatile long notBeforeMs;
    private volatile int segmentsUploaded;
    private volatile int viewers = -1;
    /** The newest segment index a published playlist names; −1 before the first. */
    private volatile long publishedThrough = -1;

    public LiveUploader(Path dir, String token, Consumer<String> onCutOff) {
        this(dir, token, onCutOff, LIVE, true);
    }

    LiveUploader(Path dir, String token, Consumer<String> onCutOff, Storage storage, boolean start) {
        this(dir, token, onCutOff, storage, System::currentTimeMillis, start);
    }

    /** {@code start} false = the thread is not started; the caller drives {@link #pass} (tests). */
    LiveUploader(Path dir, String token, Consumer<String> onCutOff, Storage storage, LongSupplier clock, boolean start) {
        this.dir = dir;
        this.token = token;
        this.onCutOff = onCutOff;
        this.storage = storage;
        this.clock = clock;
        this.thread = new Thread(this::loop, "dt-live-uploader");
        this.thread.setDaemon(true);
        if (start) this.thread.start();
    }

    public int segmentsUploaded() {
        return segmentsUploaded;
    }

    /**
     * The newest segment ({@code segNNNNN.ts}, numbered from 0, so segment k holds stream time
     * [kS, (k+1)S)) that a playlist on storage names — the furthest any viewer can have got. −1 before
     * the first playlist goes up.
     */
    public long publishedThrough() {
        return publishedThrough;
    }

    /** Viewers as of the last heartbeat; −1 until the relay has said. */
    public int viewers() {
        return viewers;
    }

    long notBeforeMs() {
        return notBeforeMs;
    }

    long presignBackoffMs() {
        return presignBackoffMs;
    }

    /** True once the uploader thread has finished, or was never started (tests). */
    boolean finished() {
        return !thread.isAlive();
    }

    private void loop() {
        try {
            while (running) {
                try {
                    pass();
                    pause(POLL_MS);
                } catch (CutOff c) {
                    running = false;
                    flushRequested = false;
                    onCutOff.accept(c.by);
                    return;
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    LOGGER.warn("[DungeonTrain] live upload pass failed: {}", e.toString());
                    pause(ERROR_PAUSE_MS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (flushRequested) {
            try {
                pass(true);
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] live final upload failed: {}", e.toString());
            }
        }
    }

    /** Sleeps between passes; {@link #close} wakes it at once. */
    private void pause(long ms) throws InterruptedException {
        synchronized (wake) {
            if (running) wake.wait(ms);
        }
    }

    void pass() throws IOException, CutOff {
        pass(false);
    }

    /**
     * One pass; run once more after a clean stop ({@code last}) so the final segments and the
     * ENDLIST playlist go out — viewers behind real time play right up to it, so the last pass
     * ignores a failure backoff.
     */
    void pass(boolean last) throws IOException, CutOff {
        if (!last && clock.getAsLong() < notBeforeMs) return;
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
        if (r.ok()) {
            LiveLifeEvents.heartbeat(count);
            LiveLifeEvents.crowd(LiveFeedClient.parseCrowd(r));
        }
        if (urls.isEmpty()) {
            presignFailed(r);
            return;
        }
        presignBackoffMs = 0;
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
                notBeforeMs = clock.getAsLong() + FAIL_BACKOFF_MS;
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
                publishedThrough = Math.max(publishedThrough, newestSegment(LivePlaylist.parse(lastPublished)));
            } else {
                // Storage said no (or the network did): wait before asking the relay to sign again,
                // or a dead bucket turns into two presign calls a second.
                notBeforeMs = clock.getAsLong() + FAIL_BACKOFF_MS;
                LOGGER.warn("[DungeonTrain] live playlist upload → {} (retry in {} s)", put.status(), FAIL_BACKOFF_MS / 1000);
            }
        }
        // Only once nothing is left to retry: until then every poll comes back for the pending segments.
        if (allDone && published) lastPlaylistMtime = mtime;
    }

    /** The highest {@code segNNNNN.ts} index a playlist names, by name (a skipped one leaves a gap); −1 if none. */
    static long newestSegment(LivePlaylist playlist) {
        long newest = -1;
        for (String seg : playlist.segments()) {
            Matcher m = SEGMENT_INDEX.matcher(seg);
            if (m.matches()) newest = Math.max(newest, Long.parseLong(m.group(1)));
        }
        return newest;
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

    /** The relay did not sign (down, 5xx, garbled): back off 1 s, 2 s, 4 s … 10 s, not every poll. */
    private void presignFailed(Result r) {
        presignBackoffMs = presignBackoffMs == 0 ? PRESIGN_BACKOFF_MIN_MS
            : Math.min(PRESIGN_BACKOFF_MAX_MS, presignBackoffMs * 2);
        notBeforeMs = clock.getAsLong() + presignBackoffMs;
        if (running) {
            LOGGER.info("[DungeonTrain] live presign failed (status {}{}); retrying in {} s", r.status(),
                r.error() == null ? "" : ": " + r.error(), presignBackoffMs / 1000);
        }
    }

    /** Waits for a call until its own timeout or the close deadline, whichever comes first. */
    private Result await(CompletableFuture<Result> f) {
        long callEnd = System.nanoTime() + CALL_TIMEOUT_NS;
        try {
            while (true) {
                long left = Math.min(callEnd, deadlineNs) - System.nanoTime();
                if (left <= 0) return new Result(0, null, new TimeoutException("live call timed out"));
                try {
                    return f.get(Math.min(left, AWAIT_SLICE_NS), TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    // re-check: close() may have pulled the deadline in
                }
            }
        } catch (ExecutionException e) {
            return new Result(0, null, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(0, null, e);
        }
    }

    /**
     * Stop polling. {@code flush} = the uploader thread runs one final pass (clean stop: ships the last
     * segments and the ENDLIST playlist) and this waits up to {@code budgetMs} for it; every call it
     * makes from now on is cut off at that deadline. Without {@code flush} the thread is interrupted
     * and not waited for.
     */
    public void close(boolean flush, long budgetMs) {
        deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, budgetMs));
        flushRequested = flush;
        running = false;
        if (!thread.isAlive()) return;
        if (!flush) {
            thread.interrupt();
            return;
        }
        synchronized (wake) {
            wake.notifyAll();
        }
        try {
            // The deadline ends the thread's last call at budgetMs; a moment more lets it get out.
            thread.join(Math.max(1, budgetMs) + JOIN_MARGIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            LOGGER.warn("[DungeonTrain] live uploader still busy after {} ms; the final playlist may not go out", budgetMs);
        }
    }

    @Override
    public void close() {
        close(false, 0);
    }

    static final class CutOff extends Exception {
        final String by;
        CutOff(String by) { super("cut off"); this.by = by == null ? "" : by; }
    }
}
