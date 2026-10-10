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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Ships what the encoder writes: watches {@code live.m3u8}, and whenever it changes, asks the relay
 * for signed URLs for the new segments plus the playlist, PUTs the segments, then the playlist
 * (that order matters — a playlist must never name a segment that is not there yet).
 *
 * <p>Each presign is the relay heartbeat. A 403 means someone else took the channel: the uploader
 * stops and tells the controller who. Transport failures are retried on the next poll; a segment
 * that fails twice is skipped (viewers see a hiccup, not a stall).</p>
 */
public final class LiveUploader implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long POLL_MS = 500;
    private static final long CALL_TIMEOUT_S = 20;

    private final Path dir;
    private final String token;
    private final Consumer<String> onCutOff;
    private final Thread thread;
    private final Set<String> uploaded = new HashSet<>();
    private final Map<String, Integer> failures = new java.util.HashMap<>();
    private volatile boolean running = true;
    private volatile long lastPlaylistMtime = -1;
    private volatile long notBeforeMs;
    private volatile int segmentsUploaded;
    private volatile int viewers = -1;
    private static final long FAIL_BACKOFF_MS = 3000;

    public LiveUploader(Path dir, String token, Consumer<String> onCutOff) {
        this.dir = dir;
        this.token = token;
        this.onCutOff = onCutOff;
        this.thread = new Thread(this::loop, "dt-live-uploader");
        this.thread.setDaemon(true);
        this.thread.start();
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

    /** One pass; also called once more on clean stop so the ENDLIST playlist goes out. */
    void pass() throws IOException, CutOff {
        if (System.currentTimeMillis() < notBeforeMs) return;
        Path playlist = dir.resolve(LivePlaylist.FILE_NAME);
        if (!Files.exists(playlist)) return;
        long mtime = Files.getLastModifiedTime(playlist).toMillis();
        if (mtime == lastPlaylistMtime) return;
        LivePlaylist parsed = LivePlaylist.parse(Files.readString(playlist, StandardCharsets.UTF_8));
        List<String> pending = new ArrayList<>();
        for (String seg : parsed.segments()) {
            if (!uploaded.contains(seg) && failures.getOrDefault(seg, 0) < 2 && Files.exists(dir.resolve(seg))) pending.add(seg);
        }
        List<String> ask = new ArrayList<>(pending);
        ask.add(LivePlaylist.FILE_NAME);
        Result r = await(LiveFeedClient.presign(token, ask));
        if (r.forbidden()) throw new CutOff(r.str("takenBy"));
        Map<String, SignedPut> urls = LiveFeedClient.parsePresign(r);
        int count = LiveFeedClient.parseViewerCount(r);
        if (count >= 0) viewers = count;
        if (urls.isEmpty()) {
            LOGGER.debug("[DungeonTrain] live presign unanswered (status {}), retrying next poll", r.status());
            return;
        }
        boolean allOk = true;
        for (String seg : pending) {
            SignedPut target = urls.get(seg);
            if (target == null) continue;
            Result put = await(LiveFeedClient.upload(target, dir.resolve(seg)));
            if (put.status() >= 200 && put.status() < 300) {
                uploaded.add(seg);
                segmentsUploaded++;
            } else {
                allOk = false;
                notBeforeMs = System.currentTimeMillis() + FAIL_BACKOFF_MS;
                failures.merge(seg, 1, Integer::sum);
                LOGGER.warn("[DungeonTrain] live segment {} upload → {} {}", seg, put.status(), put.error() == null ? "" : put.error().toString());
            }
        }
        SignedPut pl = urls.get(LivePlaylist.FILE_NAME);
        if (pl != null) {
            Result put = await(LiveFeedClient.upload(pl, playlist));
            if (put.status() >= 200 && put.status() < 300 && allOk) {
                lastPlaylistMtime = mtime;
            } else {
                // Storage said no (or the network did): wait before asking the relay to sign again,
                // or a dead bucket turns into two presign calls a second.
                notBeforeMs = System.currentTimeMillis() + FAIL_BACKOFF_MS;
                LOGGER.warn("[DungeonTrain] live playlist upload → {} (retry in {} s)", put.status(), FAIL_BACKOFF_MS / 1000);
            }
        }
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

    /** Stop polling. {@code flush} = run one final pass first (clean stop: ships the ENDLIST playlist). */
    public void close(boolean flush) {
        running = false;
        thread.interrupt();
        if (flush) {
            try { pass(); } catch (Exception ignored) { /* best effort */ }
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
