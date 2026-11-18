package games.brennan.dungeontrain.portal;

import java.util.Locale;

/**
 * Whether a dimensional carriage's entrance hallway can be walked back out of.
 *
 * <p>{@link #TWO_WAY} is how every room has always behaved: turn round in the entrance hallway and
 * you are back on the train. {@link #ONE_WAY} closes that way back the moment the hallway swaps a
 * traveller into its twin — turning round leaves them in the twin, facing its plugged dummy door
 * ({@code PortalBuilder#plugBehindDoor}), so the only way on is to find the exit.</p>
 *
 * <h2>What it does not close</h2>
 * <ul>
 *   <li><b>The way in.</b> Walking into the entrance from the train is untouched.</li>
 *   <li><b>The exit hallway</b>, in either direction.</li>
 *   <li><b>Every hallway copy</b> an endless room scatters through its tiles
 *       ({@link PortalRoomExits}) — entry copies included, by design: only the original entrance
 *       closes.</li>
 * </ul>
 *
 * <p><b>The one rule that cuts a way out.</b> {@link PortalSever} and {@link PortalWalkThrough}
 * both promise the way out is never cut; this setting is an author choosing to cut exactly one, the
 * original entrance. The exit hallway is never refused outbound, so a room whose exit can be reached
 * can always be left. A room authored so that its exit cannot be reached would trap players — that
 * is the author's call.</p>
 *
 * <p>Stored as the sixteenth segment of the room's {@code mode} tag — see
 * {@link PortalRoomSettings}, which owns the encoding. Two-way is never written, so every tag from
 * before the setting existed reads back as a room whose entrance works both ways.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 */
public enum PortalRoomEntrance {

    /** The entrance hallway leads back to the train. The default. */
    TWO_WAY("two_way", "Two-way"),

    /** Once through the entrance hallway's portal, the way back is closed. */
    ONE_WAY("one_way", "One-way");

    /** What a room with no entrance segment — or an unreadable one — behaves as. */
    public static final PortalRoomEntrance DEFAULT = TWO_WAY;

    private final String id;
    private final String displayName;

    PortalRoomEntrance(String id, String displayName) {
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

    /** True when the entrance hallway refuses the way back. */
    public boolean oneWay() {
        return this == ONE_WAY;
    }

    /**
     * The setting named by {@code segment}, or {@link #DEFAULT} when it is null, blank or
     * unrecognised — total, for the same reason {@link PortalRoomFog#parse} is.
     */
    public static PortalRoomEntrance parse(String segment) {
        if (segment == null) return DEFAULT;
        String key = segment.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return DEFAULT;
        for (PortalRoomEntrance entrance : values()) {
            if (entrance.id.equals(key)) return entrance;
        }
        return DEFAULT;
    }

    /** The setting after this one, wrapping — what the editor's cycling button steps through. */
    public PortalRoomEntrance next() {
        PortalRoomEntrance[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /**
     * Whether this setting refuses {@code toFrame} for a traveller in one particular pairing of a
     * hallway — the one-way rule, in one place, for the player swap and entity transit to share.
     *
     * <p>True only for a {@link #ONE_WAY} room, at its {@link PortalCarriageRole#ENTRY} hallway's
     * original frame (not a copy), on a move back to the carriage.</p>
     *
     * @param toFrame  where the move is going: {@link PortalFrames#FRAME_TWIN} or
     *                 {@link PortalFrames#FRAME_CARRIAGE}
     * @param role     which of the pair's two hallways this is
     * @param copyOnly whether this pairing is one of an endless room's scattered hallway copies
     */
    public boolean blocksMove(int toFrame, PortalCarriageRole role, boolean copyOnly) {
        return oneWay() && !copyOnly && role == PortalCarriageRole.ENTRY
            && toFrame == PortalFrames.FRAME_CARRIAGE;
    }
}
