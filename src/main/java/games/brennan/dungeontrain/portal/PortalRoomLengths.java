package games.brennan.dungeontrain.portal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How long each named portal room is, without needing a {@code ServerLevel} to ask.
 *
 * <p>A room's length lives in its template and nowhere else — that is the point of the free length
 * axis. But the editor's plot layout has to size a room's plot from
 * {@code TrackSidePlots.locate(pos, dims)}, which resolves a block position to a plot and has no
 * level to load templates through. So every load and every save records the length here, and the
 * layout reads it back.</p>
 *
 * <p>A name with no entry is {@link PortalRoomLayout#BUILT_IN_LENGTH} — either nothing has been
 * authored for it yet (the built-in room stamps) or nothing has looked at it yet, and the built-in
 * length is what would be stamped in both cases.</p>
 *
 * <p>{@link #pendingLength} is the editor's override: {@code /dt editor portals length <n>}
 * restamps the plot at a new length before there is a template to read it from. It becomes the
 * real length the moment that plot is saved.</p>
 */
public final class PortalRoomLengths {

    private static final Map<String, Integer> LENGTHS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> PENDING = new ConcurrentHashMap<>();

    private PortalRoomLengths() {}

    /**
     * Record the length of a template that was just loaded.
     *
     * <p>Deliberately does <b>not</b> clear a pending override: loading is what the plot stamp does
     * on its way to reading the length, so clearing here would undo
     * {@code /dt editor portals length} before it ever took effect. Only {@link #settle} — a save —
     * spends the override.</p>
     */
    public static void observe(String name, int length) {
        if (name == null) return;
        LENGTHS.put(name, PortalRoomLayout.clampLength(length));
    }

    /** Record a length that has just been written to disk. The template is now the authority. */
    public static void settle(String name, int length) {
        if (name == null) return;
        LENGTHS.put(name, PortalRoomLayout.clampLength(length));
        PENDING.remove(name);
    }

    /** The length {@code name}'s plot and stamp should use. */
    public static int lengthOf(String name) {
        if (name == null) return PortalRoomLayout.BUILT_IN_LENGTH;
        Integer pending = PENDING.get(name);
        if (pending != null) return pending;
        Integer known = LENGTHS.get(name);
        return known != null ? known : PortalRoomLayout.BUILT_IN_LENGTH;
    }

    /** Editor override — the plot restamps at this length until the next save bakes it in. */
    public static void pendingLength(String name, int length) {
        if (name == null) return;
        PENDING.put(name, PortalRoomLayout.clampLength(length));
    }

    /** Drop everything known about {@code name} — it has been deleted. */
    public static void forget(String name) {
        if (name == null) return;
        LENGTHS.remove(name);
        PENDING.remove(name);
    }

    /** Drop every cached length. Called when the variant registry reloads on server start. */
    public static void clear() {
        LENGTHS.clear();
        PENDING.clear();
    }
}
