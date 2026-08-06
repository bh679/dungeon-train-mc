package games.brennan.dungeontrain.portal;

/**
 * The side-of-the-midpoint rule generalised from a fixed offset to a mapping between two
 * <b>frames</b>: the portal carriage (moving with the train) and its static twin corridor.
 *
 * <p>The free-standing portal can shift a player by a constant {@code deltaY} because both of its
 * copies stand still. A carriage does not, so the rule here maps the player's <b>corridor-local</b>
 * offset out of one frame and into the other. Two consequences fall out of that:</p>
 *
 * <ul>
 *   <li>Walking in drops you off a moving train into a fixed corridor.</li>
 *   <li>Walking back re-reads the carriage frame <i>at that moment</i>, so you rejoin the train
 *       wherever it has since travelled to — possibly hundreds of blocks on. The jump is invisible
 *       for the same reason the original swap is: inside a sealed, identical corridor there is no
 *       external reference to contradict it.</li>
 * </ul>
 *
 * <p>Both frames are axis-aligned — DT carriages are locked to identity rotation — so a frame is
 * just an origin and the mapping is a translation. Local coordinates are directly comparable
 * between frames because both corridors are stamped from the same
 * {@link PortalCarriageLayout}.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 *
 * @param layout   the corridor layout both frames were stamped from
 * @param carriage world position of the carriage corridor's local origin, read live each tick
 * @param twin     world position of the static twin corridor's local origin
 */
public record PortalFrames(PortalCarriageLayout layout, Origin carriage, Origin twin) {

    /** Position is in neither corridor. */
    public static final int FRAME_NONE = -1;
    /** The corridor riding the train — where a player before the midpoint belongs. */
    public static final int FRAME_CARRIAGE = 0;
    /** The static twin corridor — where a player past the midpoint belongs. */
    public static final int FRAME_TWIN = 1;

    /** World position of a corridor's local origin. */
    public record Origin(double x, double y, double z) {}

    /** A move the invariant demands: the frame to end up in, and the world position to land at. */
    public record Move(int toFrame, double x, double y, double z) {}

    /** Origin of the given frame. */
    public Origin originOf(int frame) {
        return frame == FRAME_TWIN ? twin : carriage;
    }

    /** Which corridor the world position is physically inside, or {@link #FRAME_NONE}. */
    public int frameAt(double wx, double wy, double wz) {
        if (insideFrame(FRAME_CARRIAGE, wx, wy, wz)) return FRAME_CARRIAGE;
        if (insideFrame(FRAME_TWIN, wx, wy, wz)) return FRAME_TWIN;
        return FRAME_NONE;
    }

    private boolean insideFrame(int frame, double wx, double wy, double wz) {
        Origin o = originOf(frame);
        return layout.insideCorridor(wx - o.x(), wy - o.y(), wz - o.z());
    }

    /**
     * The move the invariant demands, or {@code null} when the position is already correct (or in
     * neither corridor).
     *
     * <p>Stated as a condition on the current position rather than as a crossing event, so it is
     * idempotent — applying it twice is a no-op. That is what makes it immune to the failure modes
     * edge-detection has: no ping-pong after the swap, no crossing missed at any speed, and it
     * self-heals after a login or a {@code /tp}.</p>
     */
    public Move requiredMove(double wx, double wy, double wz) {
        int frame = frameAt(wx, wy, wz);
        if (frame == FRAME_NONE) return null;

        Origin from = originOf(frame);
        double localX = wx - from.x();
        double localY = wy - from.y();
        double localZ = wz - from.z();

        int wantFrame = layout.copyForLocalX(localX) == PortalGeometry.COPY_NEAR
            ? FRAME_CARRIAGE
            : FRAME_TWIN;
        if (wantFrame == frame) return null;

        Origin to = originOf(wantFrame);
        return new Move(wantFrame, to.x() + localX, to.y() + localY, to.z() + localZ);
    }
}
