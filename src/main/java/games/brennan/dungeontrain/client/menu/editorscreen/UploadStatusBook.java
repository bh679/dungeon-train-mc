package games.brennan.dungeontrain.client.menu.editorscreen;

import java.util.HashMap;
import java.util.Map;

/**
 * Which templates have a relay upload in flight, and how the last one ended — the state behind the
 * editor screen's small "Uploading…" note beside Save.
 *
 * <p>Pure and its own class so the timings can be tested without a client: a note that never clears
 * would say an upload is still going when it is not, which is worse than saying nothing.</p>
 *
 * <p>Every answer expires. A finished upload is shown briefly and then dropped; an upload that never
 * reported back (the server went away, a callback threw somewhere unguarded) is given up on after
 * {@link #STARTED_TIMEOUT_MS} rather than left pulsing forever.</p>
 */
public final class UploadStatusBook {

    /** What the note beside Save says. */
    public enum Shown { UPLOADING, UPLOADED, FAILED }

    /** Longest an upload is shown as in flight without hearing back. */
    static final long STARTED_TIMEOUT_MS = 45_000L;
    /** How long a finished upload's tick stays up. */
    static final long UPLOADED_SHOWN_MS = 3_000L;
    /** How long a failure stays up — longer, since it is the one worth reading. */
    static final long FAILED_SHOWN_MS = 6_000L;

    private record Status(Shown shown, long atMillis) {}

    private final Map<String, Status> byKey = new HashMap<>();

    /** The key a template is filed under: its photo kind and id, which is what the tile art knows. */
    public static String key(String photoKind, String id) {
        return (photoKind == null ? "" : photoKind) + ":" + (id == null ? "" : id);
    }

    public void started(String key, long now) {
        byKey.put(key, new Status(Shown.UPLOADING, now));
    }

    public void finished(String key, boolean ok, long now) {
        byKey.put(key, new Status(ok ? Shown.UPLOADED : Shown.FAILED, now));
    }

    /** What to show for {@code key} at {@code now}, or null for nothing. Drops what has expired. */
    public Shown shown(String key, long now) {
        Status status = byKey.get(key);
        if (status == null) return null;
        long age = now - status.atMillis();
        long life = switch (status.shown()) {
            case UPLOADING -> STARTED_TIMEOUT_MS;
            case UPLOADED -> UPLOADED_SHOWN_MS;
            case FAILED -> FAILED_SHOWN_MS;
        };
        if (age >= life) {
            byKey.remove(key);
            return null;
        }
        return status.shown();
    }

    public void clear() {
        byKey.clear();
    }
}
