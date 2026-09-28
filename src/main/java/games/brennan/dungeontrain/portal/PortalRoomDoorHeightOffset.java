package games.brennan.dungeontrain.portal;

/**
 * How far the corridor's fixed floor line sits above the room's own bottom edge, in blocks — the
 * vertical twin of {@link PortalRoomDoorOffset}.
 *
 * <p><b>Unsigned, unlike {@link PortalRoomDoorOffset}.</b> {@code Z} centres by default because
 * there is no reason to prefer one side over the other on a horizontal axis. {@code Y} is not
 * symmetric: every room built before this existed put its corridor at the room's own floor — the
 * only Y a room can walk on — so {@code 0} has to keep meaning exactly that. A room built taller
 * than {@link PortalRoomLayout#minHeight} has slack above the corridor (an attic) that this spends
 * upward, into a basement below the corridor instead; it never has anywhere to spend downward,
 * because there is nothing under the corridor's floor to begin with.</p>
 *
 * <p>Stored as the ninth segment of the room's {@code mode} tag — see {@link PortalRoomSettings},
 * which owns the encoding.</p>
 *
 * @param value the raw offset, unclamped and never negative once clamped by
 *              {@link PortalRoomLayout#clampDoorHeightOffset}
 */
public record PortalRoomDoorHeightOffset(int value) {

    /** What a variant with no door-height segment — or an unreadable one — behaves as: the floor. */
    public static final PortalRoomDoorHeightOffset DEFAULT = new PortalRoomDoorHeightOffset(0);

    /** True when this is the floor-anchored default — the value most rooms will carry forever. */
    public boolean isAtFloor() {
        return value == 0;
    }

    /** The on-disk / command-line token. */
    public String id() {
        return Integer.toString(value);
    }

    /**
     * The value named by {@code segment}, or {@link #DEFAULT} when it is null, blank or
     * unreadable. Total, for the same reason every other segment parser in this package is.
     */
    public static PortalRoomDoorHeightOffset parse(String segment) {
        if (segment == null) return DEFAULT;
        String trimmed = segment.trim();
        if (trimmed.isEmpty()) return DEFAULT;
        try {
            return new PortalRoomDoorHeightOffset(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            return DEFAULT;
        }
    }
}
