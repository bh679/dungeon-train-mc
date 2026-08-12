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
 * <p><b>The role mirrors the rule.</b> An {@link PortalCarriageRole#ENTRY} pair puts the train on
 * the near side of the midpoint: walk past it and you go to the twin. An
 * {@link PortalCarriageRole#EXIT} pair puts the train on the far side: walk past the midpoint in the
 * twin and you arrive on the train, leaving through the carriage's far door. That mirror is what
 * lets a player walk train → room → train without ever turning round, and it lives here rather than
 * in the geometry because a mirrored corridor would flip the view ahead of them mid-swap.</p>
 *
 * @param layout   the corridor layout both frames were stamped from
 * @param carriage world position of the carriage corridor's local origin, read live each tick
 * @param twin     world position of the static twin corridor's local origin
 * @param role     which half of the pair is the train side
 */
public record PortalFrames(PortalCarriageLayout layout, Origin carriage, Origin twin,
                           PortalCarriageRole role) {

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
     * not oscillation <i>around</i> the boundary.</p>
     *
     * <p><b>Why 1.25 and not 0.4.</b> The first attempt used 0.4, sized against the ~0.17 client/server
     * disagreement. That ignored a second, larger source: the carriage frame is read from the live
     * ship AABB, and the train's own jitter tripwire measures drift of about 0.4 blocks — the same
     * size as the band. A player standing near the midpoint then had the jitter alone carrying them
     * over the threshold and back, swapping roughly once a second indefinitely. The band has to clear
     * the jitter amplitude, not just the network's. It stays invisible at any width: the two corridors
     * are identical, and the baffles mean nothing at either end betrays which side of the line you are
     * on.</p>
     *
     * <p>The deeper fix is to read the carriage's canonical/target transform rather than its jittering
     * physics pose, which would remove the drift at source instead of tolerating it.</p>
     */
    public static final double SWAP_HYSTERESIS = 1.25;

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

        // ENTRY: the train is the near half, so past the line belongs to the twin.
        // EXIT: mirrored — the train is the far half, so before the line belongs to the twin.
        int trainSideFrame = FRAME_CARRIAGE;
        boolean onTwinSide = role == PortalCarriageRole.ENTRY ? pastLine : beforeLine;
        boolean onTrainSide = role == PortalCarriageRole.ENTRY ? beforeLine : pastLine;

        int wantFrame;
        if (frame == trainSideFrame && onTwinSide) {
            wantFrame = FRAME_TWIN;
        } else if (frame == FRAME_TWIN && onTrainSide) {
            wantFrame = FRAME_CARRIAGE;
        } else {
            return null;
        }

        Origin to = originOf(wantFrame);
        return new Move(wantFrame, to.x() + localX, to.y() + localY, to.z() + localZ);
    }

    /**
     * The same move, but landing in a different copy of the twin corridor.
     *
     * <p>What {@link PortalExitBindings} needs: a player who left the room through an extra corridor
     * eight rooms out should walk back in to that corridor rather than to the original. Every copy is
     * stamped from this same layout and stands on the same Y lane, so "a different copy" is a
     * translation in X and Z of a destination this record has already worked out — the side rule, the
     * hysteresis band and the preserved local offset all still apply, unchanged, because none of them
     * is a question about <i>which</i> twin.</p>
     *
     * <p><b>Inbound only.</b> A move back to the carriage is returned untouched: the carriage is
     * where it is, and there is nothing to redirect it to. So is a null move, and a null override —
     * both mean "the original twin, as always", which is what makes a stale binding harmless.</p>
     */
    public Move redirectedTo(Move move, Origin twinOverride) {
        if (move == null || twinOverride == null || move.toFrame() != FRAME_TWIN) return move;
        return new Move(move.toFrame(),
            move.x() + (twinOverride.x() - twin.x()),
            move.y() + (twinOverride.y() - twin.y()),
            move.z() + (twinOverride.z() - twin.z()));
    }

    /**
     * The floor surface of the copy a move was redirected into, or of this frame's own twin when it
     * was not.
     *
     * <p>{@link #floorSurfaceY} reads the frame's origin, which is still the original twin's — and a
     * grounded player is placed on the destination's floor rather than at their carried-across local
     * Y, so asking the wrong corridor would drop them by however far the two lanes differ. They never
     * do differ today (every copy of a pair shares one Y lane), which is exactly why this is worth
     * routing through one method: it is a silent failure the moment that stops being true.</p>
     */
    public double floorSurfaceY(int frame, Origin twinOverride) {
        if (frame != FRAME_TWIN || twinOverride == null) return floorSurfaceY(frame);
        return twinOverride.y() + layout.floorY() + 1;
    }

    /**
     * Where a position in one corridor appears in the <b>other</b> one, or {@code null} if it is in
     * neither.
     *
     * <p>What a puppet needs, and deliberately not what {@link #requiredMove} computes. That method
     * answers "should this player be moved?", so it carries the hysteresis band and the
     * {@link PortalCarriageRole} side rule — both of which are decisions about <i>swapping</i>. A
     * stand-in has no such decision to make: an entity anywhere in a corridor has a counterpart
     * position in the twin of that corridor at every moment, on either side of the midpoint, and it
     * must not blink out for the width of the band.</p>
     *
     * <p>The mapping itself is the same one: corridor-local offset out of the source frame and into
     * the destination, which is what keeps a puppet at the same place in the room as the entity it
     * stands for. Rotation needs no equivalent — both frames are axis-aligned, so a yaw carries
     * across unchanged.</p>
     */
    public Move mirror(double wx, double wy, double wz) {
        int frame = frameAt(wx, wy, wz);
        if (frame == FRAME_NONE) return null;

        Origin from = originOf(frame);
        int toFrame = frame == FRAME_CARRIAGE ? FRAME_TWIN : FRAME_CARRIAGE;
        Origin to = originOf(toFrame);

        return new Move(toFrame,
            to.x() + (wx - from.x()),
            to.y() + (wy - from.y()),
            to.z() + (wz - from.z()));
    }
}
