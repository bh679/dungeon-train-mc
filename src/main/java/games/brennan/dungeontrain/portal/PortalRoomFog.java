package games.brennan.dungeontrain.portal;

import java.util.Locale;

/**
 * Whether a portal room hides its boundary behind fog — an author's override of what the walls
 * mode decides on its own.
 *
 * <p>The fog used to be a consequence of the walls alone: {@link PortalRoomMode#fogs} says yes for
 * the endless modes and Bedrockless, no for Bedrock Lock and Chunk Dimension, and that was the whole
 * answer. It is still the whole answer under {@link #AUTO}, which is what every room says unless
 * told otherwise. {@link #ON} and {@link #OFF} are the author saying they know better — a sealed
 * room built to be murky, or an endless plain built for the sightlines the fog would take away.</p>
 *
 * <p>Stored as the thirteenth segment of the room's {@code mode} tag — see
 * {@link PortalRoomSettings}, which owns the encoding. Auto is never written, so every tag from
 * before the setting existed reads back to the behaviour it always had.</p>
 */
public enum PortalRoomFog {

    /** Whatever the walls mode says — {@link PortalRoomMode#fogs}. The default. */
    AUTO("auto", "Auto"),

    /** Fogged whatever the walls do. */
    ON("on", "On"),

    /** Never fogged, even in an endless room. */
    OFF("off", "Off");

    /** What a room with no fog segment — or an unreadable one — behaves as. */
    public static final PortalRoomFog DEFAULT = AUTO;

    private final String id;
    private final String displayName;

    PortalRoomFog(String id, String displayName) {
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
     * Whether a room with this setting and these walls is fogged — the one question the server
     * asks before describing the fogged region to a client.
     */
    public boolean fogs(PortalRoomMode mode) {
        return this == AUTO ? mode.fogs() : this == ON;
    }

    /**
     * The setting named by {@code segment}, or {@link #AUTO} when it is null, blank or
     * unrecognised — total, for the same reason {@link PortalRoomSky#parse} is.
     */
    public static PortalRoomFog parse(String segment) {
        if (segment == null) return DEFAULT;
        String key = segment.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return DEFAULT;
        for (PortalRoomFog fog : values()) {
            if (fog.id.equals(key)) return fog;
        }
        return DEFAULT;
    }

    /** The setting after this one, wrapping — what the editor's cycling button steps through. */
    public PortalRoomFog next() {
        PortalRoomFog[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
