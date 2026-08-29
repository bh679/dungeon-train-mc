package games.brennan.dungeontrain.portal;

/**
 * How far a room's shared walkway line sits from dead centre of the room's own width, in blocks —
 * positive toward {@code +Z}, negative toward {@code -Z}.
 *
 * <p><b>What this is not.</b> The corridor itself is fixed by {@code CarriageDims} and never
 * authored per template — see {@link PortalRoomLayout#roomOrigin(net.minecraft.core.BlockPos,
 * games.brennan.dungeontrain.train.CarriageDims, PortalCarriageLayout, int, int)}. This value only
 * says how a room wider than {@link PortalRoomLayout#minWidth} splits its slack either side of that
 * fixed line, which is what an author standing in the finished room actually experiences as "the
 * door is off to one side."</p>
 *
 * <p><b>Not clamped here.</b> The legal range depends on the room's own width and the world's
 * {@code CarriageDims} — neither of which this value carries — so an out-of-range offset parses and
 * stores exactly as written, and {@link PortalRoomLayout#clampDoorOffset} is what every consumer
 * that actually places blocks runs it through. That is also why a template saved wide and later
 * trimmed narrower recovers on its own: the stored number does not change, only what it clamps to.</p>
 *
 * <p>Stored as the eighth segment of the room's {@code mode} tag — see {@link PortalRoomSettings},
 * which owns the encoding.</p>
 *
 * @param value the raw offset, unclamped
 */
public record PortalRoomDoorOffset(int value) {

    /** What a variant with no door-offset segment — or an unreadable one — behaves as. */
    public static final PortalRoomDoorOffset DEFAULT = new PortalRoomDoorOffset(0);

    /** True when this is the centred default — the value most rooms will carry forever. */
    public boolean isCentred() {
        return value == 0;
    }

    /** The on-disk / command-line token. */
    public String id() {
        return Integer.toString(value);
    }

    /**
     * The value named by {@code segment}, or {@link #DEFAULT} when it is null, blank or
     * unreadable.
     *
     * <p>Total, for the same reason every other segment parser in this package is: the tag is free
     * text on disk, and a hand-edited typo should stamp a centred door rather than fail the pair's
     * stamp.</p>
     */
    public static PortalRoomDoorOffset parse(String segment) {
        if (segment == null) return DEFAULT;
        String trimmed = segment.trim();
        if (trimmed.isEmpty()) return DEFAULT;
        try {
            return new PortalRoomDoorOffset(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            return DEFAULT;
        }
    }
}
