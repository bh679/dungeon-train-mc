package games.brennan.dungeontrain.builder.relay;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One first upload per build at a time.
 *
 * <p>A build's first save {@code submit}s it, and the relay's id for it is only recorded when that
 * call answers. A second save of the same build before then found no record and submitted again,
 * so the player got two relay rows for one build, seconds apart. While a build's submit is out, a
 * save of it waits here instead. Only the newest waiting save is kept, because it is the one that
 * matters. It runs once the submit has settled, and by then it finds the record and saves through
 * it onto the same row.</p>
 *
 * <p>Keyed by the same {@link BuilderRelayBuilds#keyOf} key the world's record uses. Synchronized
 * because relay answers arrive off the server thread, although the callers hop back before acting.</p>
 */
final class BuilderSubmitGate {

    private final Set<String> submitting = new HashSet<>();
    private final Map<String, Runnable> waiting = new HashMap<>();

    /**
     * Claim {@code key} for a first upload.
     *
     * @return true when nothing else is submitting it, and the caller must later call {@link #finish}
     */
    synchronized boolean begin(String key) {
        return submitting.add(key);
    }

    /** Whether a first upload of {@code key} is out right now. */
    synchronized boolean isSubmitting(String key) {
        return submitting.contains(key);
    }

    /** Hold {@code save} until {@code key}'s submit settles, replacing any older one held for it. */
    synchronized void holdLatest(String key, Runnable save) {
        if (save != null) waiting.put(key, save);
    }

    /**
     * Release {@code key}, whatever the submit's outcome.
     *
     * @return the save that waited for it, which the caller runs now, or null
     */
    synchronized Runnable finish(String key) {
        submitting.remove(key);
        return waiting.remove(key);
    }
}
