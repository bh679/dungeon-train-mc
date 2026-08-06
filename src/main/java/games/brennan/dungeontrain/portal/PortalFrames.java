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

    /**
     * How far past the midpoint a player must be before the swap fires, in blocks.
     *
     * <p>Without a band this oscillates. Preserving the local offset exactly — which is what makes
     * the swap invisible — lands the player barely past the line in the destination frame, and on a
     * Sable carriage the client and server disagree about a rider's position by more than that, so
     * the next tick's correction knocks them back across it and the swap fires again. Observed live:
     * three swaps in 0.13s, at local X 4.64 → 4.47 → 4.64 around a 4.5 midpoint.</p>
     *
     * <p>Idempotence alone cannot fix this: it prevents a repeat fire at the <i>same</i> position,
     * not oscillation <i>around</i> the boundary. 0.4 comfortably exceeds the observed ~0.17 drift
     * while staying far too small to notice — the two corridors are identical, and the baffles mean
     * there is nothing to see at either end that would betray which side of the line you are on.</p>
     */
    public static final double SWAP_HYSTERESIS = 0.4;

    /** World position of a corridor's local origin. */
    public record Origin(double x, double y, double z) {}

    /** A move the invariant demands: the frame to end up in, and the world position to land at. */
    public record Move(int toFrame, double x, double y, double z) {}

    /**
     * Y of the destination corridor's floor surface — where a player who was standing on the floor
     * belongs after the move.
     *
     * <p>Needed because the two frames' block grids do not share a fractional offset: a carriage's
     * origin rides the ship's pose ({@code 77.99}) while its twin is stamped block-aligned
     * ({@code 173}). Carrying the local offset across verbatim therefore lands a grounded player
     * slightly inside or above the destination floor — and since a twin hangs in open air, "slightly
     * inside" means Minecraft resolves the overlap by dropping them through it. Observed live: feet
     * at local Y {@code 0.98} landing at {@code 173.98} against a floor whose surface is {@code 174},
     * and the player fell out of the sky.</p>
     */
    public double floorSurfaceY(int frame) {
        return originOf(frame).y() + layout.floorY() + 1;
    }

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

        // Hysteresis band: fire only when clearly on the wrong side. See SWAP_HYSTERESIS — landing
        // barely past the line is inherent to preserving the local offset, so without a band the
        // client's position correction knocks the player back over it and the swap oscillates.
        boolean pastLine = localX > layout.midX() + SWAP_HYSTERESIS;
        boolean beforeLine = localX < layout.midX() - SWAP_HYSTERESIS;

        int wantFrame;
        if (frame == FRAME_CARRIAGE && pastLine) {
            wantFrame = FRAME_TWIN;
        } else if (frame == FRAME_TWIN && beforeLine) {
            wantFrame = FRAME_CARRIAGE;
        } else {
            return null;
        }

        Origin to = originOf(wantFrame);
        return new Move(wantFrame, to.x() + localX, to.y() + localY, to.z() + localZ);
    }
}
