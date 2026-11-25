package games.brennan.dungeontrain.portal;

/**
 * How far a pair's twin may stand from its carriage before it is re-stamped, and how far ahead of
 * the carriage a re-stamp lays it.
 *
 * <p>The bound is what the swap needs: the twin's corridor has to be in columns the server holds and
 * the client has been sent, or walking in lands nowhere. The old fixed 24 blocks kept it inside a
 * chunk or two of the carriage at the smallest render distances, and paid for it with a full
 * re-stamp every 12 to 18 s while anybody stood near the carriage — for a 3x3 sampled room, a stamp,
 * a pour of 70+ ticks, a frame and the doorways each time.</p>
 *
 * <p>Two things stretch that without loosening it:</p>
 * <ul>
 *   <li>The allowance follows what is actually loaded: the smallest effective view distance of the
 *   players near the pair, less a chunk of margin and the room's own width (the far side of the room
 *   has to be loaded too). Render distance 2 to 4 still gets 24.</li>
 *   <li>A re-stamp lays the twin ahead of the carriage in its direction of travel, so the carriage
 *   rolls up to it, under it and past it before the drift reaches the allowance again — twice the
 *   distance per stamp with the twin never further than the allowance on either side.</li>
 * </ul>
 * Pure arithmetic, so it is unit-tested without a level.
 */
public final class PortalTwinDrift {

    /** The old fixed limit, and the floor: the smallest render distances keep exactly what they had. */
    public static final double MIN_ALLOWANCE = 24.0;

    /** Never further than this, whatever the view distance: four chunks. */
    public static final double MAX_ALLOWANCE = 64.0;

    /**
     * Kept back from the allowance when leading, so a twin stamped ahead starts well inside it. The
     * EXIT corridor measures the same anchor the same tick, and a lead of exactly the allowance would
     * floor to just over it and send the room straight back.
     */
    static final double LEAD_MARGIN = 2.0;

    /** One chunk of the view distance is not counted on: the client drops its outer ring first. */
    private static final int EDGE_CHUNKS = 1;

    private PortalTwinDrift() {}

    /**
     * How far the twin may drift from the carriage.
     *
     * @param viewDistanceChunks the smallest effective view distance among the pair's nearby players,
     *                           or a non-positive value for nobody (then the floor)
     * @param roomExtent         the room's widest horizontal side, in blocks
     */
    public static double allowance(int viewDistanceChunks, int roomExtent) {
        if (viewDistanceChunks <= 0) return MIN_ALLOWANCE;
        double loaded = 16.0 * (viewDistanceChunks - EDGE_CHUNKS) - Math.max(0, roomExtent);
        return Math.max(MIN_ALLOWANCE, Math.min(MAX_ALLOWANCE, loaded));
    }

    /**
     * How far ahead of the carriage, along X, a re-stamp lays the twin.
     *
     * @param allowance      from {@link #allowance}
     * @param directionSign  the sign of the carriage's travel along X; 0 for unknown or stopped
     */
    public static double lead(double allowance, int directionSign) {
        if (directionSign == 0) return 0.0;
        double ahead = Math.max(0.0, allowance - LEAD_MARGIN);
        return directionSign > 0 ? ahead : -ahead;
    }
}
