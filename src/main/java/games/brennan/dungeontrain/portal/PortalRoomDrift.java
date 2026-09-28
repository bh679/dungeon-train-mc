package games.brennan.dungeontrain.portal;

import java.util.Locale;

/**
 * Whether a portal room drifts — takes part in the shared-carriage relay the way a drifting carriage
 * does: a copy a player edits in play is uploaded, and another world planning the same room may be
 * handed that edited copy instead of stamping the template.
 *
 * <p>Only a {@link PortalRoomMode#BEDROCK_LOCK} room can drift, whatever this says — see
 * {@link PortalRoomSettings#effectiveDrift}. A locked room is one sealed box that a single blob
 * describes, exactly like a carriage; an endless room is a window of copies with no single thing to
 * capture, and a chunk dimension's interior was never authored. The setting is an author's veto on
 * the one mode where drifting is possible, not a way to make it possible elsewhere.</p>
 *
 * <p>Stored as the fourteenth segment of the room's {@code mode} tag — see
 * {@link PortalRoomSettings}, which owns the encoding. On is never written, so every tag from before
 * the setting existed reads back as a room that drifts — a locked room drifts unless its author says
 * otherwise.</p>
 */
public enum PortalRoomDrift {

    /** The room may be uploaded when edited and served from the pool. The default. */
    ON("on", "On"),

    /** The room is always the local template; nothing about it goes to or comes from the relay. */
    OFF("off", "Off");

    /** What a room with no drift segment — or an unreadable one — behaves as. */
    public static final PortalRoomDrift DEFAULT = ON;

    private final String id;
    private final String displayName;

    PortalRoomDrift(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The on-disk / command-line token. */
    public String id() {
        return id;
    }

    /** Human-readable label for the editor row. */
    public String displayName() {
        return displayName;
    }

    /**
     * The setting named by {@code segment}, or {@link #ON} when it is null, blank or unrecognised —
     * total, for the same reason {@link PortalRoomFog#parse} is.
     */
    public static PortalRoomDrift parse(String segment) {
        if (segment == null) return DEFAULT;
        String key = segment.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return DEFAULT;
        for (PortalRoomDrift drift : values()) {
            if (drift.id.equals(key)) return drift;
        }
        return DEFAULT;
    }

    /** The setting after this one, wrapping — what the editor's cycling button steps through. */
    public PortalRoomDrift next() {
        PortalRoomDrift[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
