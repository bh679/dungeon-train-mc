package games.brennan.dungeontrain.client.gl;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Remembers which errors came out of a failed {@code VertexBuffer.upload}, and how many of them the
 * section rebuild may forgive before letting vanilla's "Rendering section" crash through.
 *
 * <p>A one-off failure (a GPU allocation that fails once on a low-VRAM card) is worth surviving: the
 * section is re-queued and rebuilt. A failure that keeps happening is real trouble, so after
 * {@link #MAX_FORGIVEN} failures within {@link #WINDOW_MS} the next one crashes, with the real error
 * in the report. The budget is global on purpose — many sections failing together means the GPU, not
 * one mesh.</p>
 */
public final class SectionUploadFailures {

    public static final int MAX_FORGIVEN = 5;
    public static final long WINDOW_MS = 60_000L;
    private static final int MAX_CAUSE_DEPTH = 16;

    private static final SectionUploadFailures SHARED = new SectionUploadFailures(MAX_FORGIVEN, WINDOW_MS);

    private final int maxForgiven;
    private final long windowMs;
    // Weak keys so a forgiven or crashed-with error never pins memory; Throwable doesn't override
    // equals/hashCode, so this is identity-based.
    private final Set<Throwable> recorded = Collections.newSetFromMap(new WeakHashMap<>());
    private final Deque<Long> forgivenAt = new ArrayDeque<>();

    SectionUploadFailures(int maxForgiven, long windowMs) {
        this.maxForgiven = maxForgiven;
        this.windowMs = windowMs;
    }

    public static SectionUploadFailures shared() {
        return SHARED;
    }

    /** Called by the upload wrapper with the error it is about to rethrow. */
    public synchronized void record(Throwable error) {
        if (error != null) recorded.add(error);
    }

    /** Whether {@code error}, or anything in its cause chain, is an error {@link #record} saw. */
    public synchronized boolean isUploadFailure(Throwable error) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        Throwable link = error;
        for (int depth = 0; link != null && depth < MAX_CAUSE_DEPTH && seen.put(link, Boolean.TRUE) == null; depth++) {
            if (recorded.contains(link)) return true;
            link = link.getCause();
        }
        return false;
    }

    /** Spends one forgiveness if the window has any left; false means let the crash through. */
    public synchronized boolean tryForgive(long nowMs) {
        while (!forgivenAt.isEmpty() && nowMs - forgivenAt.peekFirst() >= windowMs) forgivenAt.pollFirst();
        if (forgivenAt.size() >= maxForgiven) return false;
        forgivenAt.addLast(nowMs);
        return true;
    }

    /** Forgiveness spent in the current window, for the log line. */
    public synchronized int forgivenInWindow() {
        return forgivenAt.size();
    }
}
