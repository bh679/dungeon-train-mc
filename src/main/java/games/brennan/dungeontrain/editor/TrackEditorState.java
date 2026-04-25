package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.variant.TrackKind;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-server "currently active variant name" for each {@link TrackKind}.
 * The track-side editors are single-plot (one fixed location per kind, not
 * per-name), so authoring named variants needs an out-of-band marker that
 * tells the plot which name to stamp + which name save() should write
 * through. Defaults to {@link TrackKind#DEFAULT_NAME} for every kind.
 *
 * <p>Cleared on {@code ServerStoppedEvent} alongside the other editor
 * caches via {@link games.brennan.dungeontrain.event.WorldLifecycleEvents}.
 * Single-occupancy assumption: the editor is built around one OP at a time,
 * so per-server (not per-player) state is sufficient.</p>
 */
public final class TrackEditorState {

    private static final Map<TrackKind, String> ACTIVE = new EnumMap<>(TrackKind.class);

    private TrackEditorState() {}

    /** Active variant name for {@code kind} ({@link TrackKind#DEFAULT_NAME} if unset). */
    public static synchronized String activeName(TrackKind kind) {
        String name = ACTIVE.get(kind);
        return name != null ? name : TrackKind.DEFAULT_NAME;
    }

    /** Set the active variant for {@code kind}. */
    public static synchronized void setActive(TrackKind kind, String name) {
        if (name == null || TrackKind.DEFAULT_NAME.equals(name)) {
            ACTIVE.remove(kind);
        } else {
            ACTIVE.put(kind, name);
        }
    }

    /** Reset everything — wired to {@code ServerStoppedEvent}. */
    public static synchronized void clear() {
        ACTIVE.clear();
    }
}
