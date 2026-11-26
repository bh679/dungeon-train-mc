package games.brennan.dungeontrain.portal;

/**
 * How many blocks of open floor stand between an Endless Open room's box and each of its two
 * corridor mouths — the authored gap that moves both doorways out into the plain, symmetrically.
 *
 * <h2>The corridors move, the room does not</h2>
 * <p>The gap is added on each side of the room along the walk: the room's box starts {@code value}
 * blocks further from the entry twin, and the exit twin stands {@code 2 × value} further on. So the
 * box keeps its authored size — which is what lets a chunk dimension keep its one-chunk box (pour,
 * frame and door fit all assume box = chunk) while its doors stand a chunk away on the grass.</p>
 *
 * <p>Read only under a mode whose walls are open ({@link PortalRoomMode#ENDLESS_OPEN} and the chunk
 * dimension a frame encloses as Endless open): a walled room has nothing between its face and its
 * corridor for the gap to be made of. {@link #DEFAULT} is zero, which is every room authored before
 * the setting existed, so no saved pair moves.</p>
 */
public record PortalRoomDoorDistance(int value) {

    /** The most an author may ask for — four chunks of plain on each side. */
    public static final int MAX = 64;

    /** No gap: the corridors open straight onto the room's own faces, as they always have. */
    public static final PortalRoomDoorDistance DEFAULT = new PortalRoomDoorDistance(0);

    public PortalRoomDoorDistance {
        value = Math.max(0, Math.min(MAX, value));
    }

    /** The on-disk / command-line token. */
    public String id() {
        return Integer.toString(value);
    }

    /** {@code segment} as a distance; {@link #DEFAULT} for anything blank or unreadable. Total. */
    public static PortalRoomDoorDistance parse(String segment) {
        if (segment == null) return DEFAULT;
        String trimmed = segment.trim();
        if (trimmed.isEmpty()) return DEFAULT;
        try {
            return new PortalRoomDoorDistance(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            return DEFAULT;
        }
    }
}
