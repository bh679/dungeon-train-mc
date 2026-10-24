package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.Ahead;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.SignedPut;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The URLs a presign signed in advance ({@code ahead: true}, dp-relay #945): one per upcoming segment,
 * each used once, and one playlist URL that may be PUT again and again until it expires. The uploader
 * spends them while the relay is stalled, so the stream keeps going. Uploader thread only.
 *
 * <p>A URL counts as expired {@value #EXPIRY_MARGIN_MS} ms before the relay's {@code expiresAt}: the
 * clocks may disagree, and a PUT that starts just in time must also finish in time.</p>
 */
final class LiveAheadStock {

    static final long EXPIRY_MARGIN_MS = 15_000;

    private final Map<String, SignedPut> segments = new HashMap<>();
    @Nullable private SignedPut playlist;
    private long from = -1;
    private int batchSize;

    /** A new batch: its URLs replace any for the same files. */
    void add(Ahead ahead) {
        int count = 0;
        for (Map.Entry<String, SignedPut> e : ahead.urls().entrySet()) {
            if (LivePlaylist.FILE_NAME.equals(e.getKey())) {
                playlist = e.getValue();
            } else {
                segments.put(e.getKey(), e.getValue());
                count++;
            }
        }
        batchSize = count;
        from = ahead.from();
    }

    /** The unexpired URL for segment {@code name}, or null. */
    @Nullable
    SignedPut segment(String name, long nowMs) {
        SignedPut p = segments.get(name);
        return p != null && live(p, nowMs) ? p : null;
    }

    /** The unexpired playlist URL, or null. */
    @Nullable
    SignedPut playlist(long nowMs) {
        return playlist != null && live(playlist, nowMs) ? playlist : null;
    }

    /** Segment {@code name} is up (or given up on): its URL is spent. The playlist URL is never spent. */
    void take(String name) {
        segments.remove(name);
    }

    /** The first segment number of the newest batch; −1 before any. */
    long from() {
        return from;
    }

    /** Drops expired URLs and those for segments already up. */
    void prune(Set<String> uploaded, long nowMs) {
        segments.entrySet().removeIf(e -> uploaded.contains(e.getKey()) || !live(e.getValue(), nowMs));
        if (playlist != null && !live(playlist, nowMs)) playlist = null;
    }

    /** Unexpired segment URLs in hand. */
    int usable(long nowMs) {
        int n = 0;
        for (SignedPut p : segments.values()) if (live(p, nowMs)) n++;
        return n;
    }

    /** True when the stock is worth spending: a live playlist URL to publish with. */
    boolean holding(long nowMs) {
        return playlist(nowMs) != null;
    }

    /** Ask the relay for a fresh batch: under half of the last one is left (or none yet). */
    boolean wantsMore(long nowMs) {
        return batchSize == 0 || !holding(nowMs) || usable(nowMs) * 2 < batchSize;
    }

    private static boolean live(SignedPut p, long nowMs) {
        return p.expiresAt() - EXPIRY_MARGIN_MS > nowMs;
    }
}
