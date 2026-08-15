package games.brennan.dungeontrain.portal;

/**
 * Which copy of a corridor a <b>player</b> is in, decided by which way they are looking.
 *
 * <p>The rule this replaces (for players — see below) was positional: {@link PortalFrames#requiredMove}
 * compares corridor-local X against the midpoint, so you had to physically walk past the middle to
 * cross. The rule here asks a different question, and it works because of one property of the
 * corridor: <b>you always face the door you are walking toward</b>, and each copy has exactly one
 * real door. Look toward the room and you are put in the copy whose far door opens into it; turn
 * round and you are put in the copy whose near door opens onto the train. Neither can present you
 * with the plugged dummy.</p>
 *
 * <p><b>One dividing line, no undecided region.</b> Every direction belongs to one copy or the
 * other; what changes with depth is <i>where the line sits</i>. It starts 30° off the room axis at
 * the train door — so near the train almost every direction keeps you on the train — sweeps through
 * <b>90° at the exact middle of the corridor</b>, where the split is a clean perpendicular, and ends
 * 150° at the room door, where almost every direction keeps you in the room.</p>
 *
 * <pre>
 *   train door                     middle                      room door
 *   ────────────────────────────────────────────────────────────────────
 *        30°                         90°                         150°
 *    ◐ mostly train              ◑ half and half             ◕ mostly room
 * </pre>
 *
 * <p><b>One step per block.</b> The line moves when you cross into the next block and not otherwise
 * — see {@link #depthFromTrainDoor}. It is the unit the corridor is built in, it is what a player
 * can actually perceive themselves moving through, and it is what makes the rule immune to sub-block
 * position noise.</p>
 *
 * <p><b>Lerped in the cosine, not in the angle.</b> The comparison the rule actually makes is
 * against a dot product, so easing the cosine is the arithmetic without a round trip through
 * {@code acos}. It swings the line quickly near the two doors and slowly through the middle, and it
 * still lands on exactly 90° halfway along — {@code cos 150° = -cos 30°}, so the ease crosses zero
 * at the midpoint whichever way it is written.</p>
 *
 * <p><b>Players only.</b> {@link PortalEntityTransit} keeps the midpoint rule, because a mob's yaw is
 * whatever its pathfinding is doing this tick and a thrown pearl's yaw tracks nothing at all. The
 * cost is that a villager led in is briefly in a different copy from the player who led it, until it
 * crosses the midpoint itself.</p>
 *
 * <p><b>Depth is measured from the TRAIN-side door, not the nearest one.</b> That is what makes the
 * sweep directional rather than symmetric: the room end has to be the room end.</p>
 *
 * <p><b>Yaw only, never the full look vector.</b> Looking at the floor while walking forward must
 * still count as facing forward; taking the 3D unit vector's X would scale it by {@code cos(pitch)}
 * and release a player who glanced down.</p>
 *
 * <p><b>Why this is stable without a hysteresis band.</b> {@link PortalFrames#SWAP_HYSTERESIS} is
 * 1.25 blocks because a rider's <i>position</i> jitters — client and server disagree by ~0.17 and the
 * Sable ship's pose drifts ~0.4. Three things keep that from mattering here:</p>
 *
 * <ul>
 *   <li><b>The swap preserves yaw exactly.</b> {@code ServerGamePacketListenerImpl.teleport} takes
 *       absolute values and derives the packet's deltas itself — with {@code Y_ROT} in the relative
 *       set it sends {@code yaw - player.getYRot()}. {@code PortalCarriageEvents} passes
 *       {@code player.getYRot()}, so the delta is 0 and {@code absMoveTo} re-sets the same yaw. A
 *       swap cannot spin anyone.</li>
 *   <li><b>Yaw cannot drift during the round trip.</b> That same method sets
 *       {@code awaitingPositionFromClient}, and until the client acknowledges, {@code handleMovePlayer}
 *       drops its packets — so the server-side yaw is frozen at the value that caused the swap.</li>
 *   <li><b>Depth is quantised to the block.</b> Sub-block drift does not move the line at all, so
 *       the jitter the band was sized against cannot tip anyone across it — see
 *       {@link #depthFromTrainDoor}. And even at a block boundary the two frames cannot ping-pong:
 *       the twin is stamped block-aligned and stands still, so the moment a player lands in it both
 *       their block and their yaw stop moving.</li>
 * </ul>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class PortalFacing {

    private PortalFacing() {}

    /**
     * Where the facing rule says a player belongs. Two values and no third: every direction decides,
     * which is what makes the corridor free of any spot a player can stand and be neither.
     */
    public enum Verdict {
        /** The static twin — the corridor on the room's side of the crossing. */
        COPY,
        /** The carriage riding the train. */
        ORIGINAL
    }

    /**
     * How far off the room axis the dividing line sits at the <b>train</b> door plane.
     *
     * <p>30°, so only a look nearly straight down the corridor at the room counts as the room there.
     * This is what stops a player being taken across in the doorway, where the frame in front of them
     * is the one thing that could betray the teleport — and it is why the rule needs no separate
     * "must be N blocks in" gate: the narrow wedge does that job continuously instead of as a
     * step.</p>
     */
    public static final double CONE_AT_TRAIN_DOOR_DEGREES = 30.0;

    /**
     * And at the <b>room</b> door plane — 150°, the mirror of the above, so only a look nearly
     * straight back down the corridor at the train counts as the train there.
     *
     * <p>Being the mirror is not decoration: it is what puts the split at exactly 90° halfway along,
     * since the ease below crosses zero when {@code cos} of the two ends cancel.</p>
     */
    public static final double CONE_AT_ROOM_DOOR_DEGREES = 150.0;

    private static final double COS_AT_TRAIN_DOOR =
        Math.cos(Math.toRadians(CONE_AT_TRAIN_DOOR_DEGREES));
    private static final double COS_AT_ROOM_DOOR =
        Math.cos(Math.toRadians(CONE_AT_ROOM_DOOR_DEGREES));

    /**
     * Which way along corridor-local X points away from the train — {@code +1} for an
     * {@link PortalCarriageRole#ENTRY} corridor, {@code -1} for an {@link PortalCarriageRole#EXIT}
     * one.
     *
     * <p>The same mirror {@link PortalCarriageLayout#isTwinSideLocalX} and
     * {@link PortalFrames#requiredMove} already carry, and for the same reason: an ENTRY pair puts
     * the train on the low-X side, an EXIT pair on the high-X side, which is what lets a player walk
     * train → room → train without ever turning round.</p>
     */
    public static int axisTowardRoom(PortalCarriageRole role) {
        return role == PortalCarriageRole.ENTRY ? 1 : -1;
    }

    /**
     * How far past the train-side door plane the <b>block a player is standing in</b> is, counted in
     * whole blocks. Clamped to the corridor, so the half block of pad outside either door reads as
     * the door block it is outside of.
     *
     * <p><b>Quantised, and that is the point.</b> The line's position is a property of the block you
     * are in, not of your sub-block coordinate, so it does not move while you stand still and cannot
     * be nudged by the carriage's pose drift — the ~0.4 blocks of Sable jitter that
     * {@link PortalFrames#SWAP_HYSTERESIS} exists to absorb changes nothing at all here unless it
     * carries you over a block boundary, which is a real half-block excursion rather than noise.
     * Quantising is doing the same job that band does, without an undecided region to do it in.</p>
     *
     * <p>The floor is taken on {@code localX} before the role mirrors it, not on the depth
     * afterwards. Those are different: at {@code localX 3.7} in a 9-block corridor, the block is 3
     * either way, which is depth 3 from an ENTRY corridor's train door and depth 5 from an EXIT
     * one's. Flooring the mirrored value would give 4, naming a block the player is not in.</p>
     */
    public static double depthFromTrainDoor(double localX, int length, PortalCarriageRole role) {
        int block = (int) Math.max(0, Math.min(length - 1, Math.floor(localX)));
        return role == PortalCarriageRole.ENTRY ? block : (length - 1) - block;
    }

    /**
     * The dot product a look must beat at this depth for the room to claim it: eased from
     * {@link #CONE_AT_TRAIN_DOOR_DEGREES} at the train door plane to
     * {@link #CONE_AT_ROOM_DOOR_DEGREES} at the room door plane, and clamped outside them so the
     * half block of pad either side reads as the door it is outside of.
     *
     * <p>Zero at the exact middle of the corridor, which is the perpendicular split.</p>
     */
    public static double thresholdAt(double depth, int length) {
        double span = length - 1;
        if (!(span > 0)) return COS_AT_TRAIN_DOOR;
        double f = Math.max(0.0, Math.min(1.0, depth / span));
        return COS_AT_TRAIN_DOOR + f * (COS_AT_ROOM_DOOR - COS_AT_TRAIN_DOOR);
    }

    /**
     * Where the dividing line sits at this depth, in degrees off the room axis — the same number
     * {@link #thresholdAt} carries, in the form a person can picture.
     *
     * <p>For {@code /dungeontrain portal diagnose} and for tests. Nothing on the hot path calls it:
     * the rule itself compares dot products and never needs the angle back.</p>
     */
    public static double coneDegreesAt(double depth, int length) {
        double t = Math.max(-1.0, Math.min(1.0, thresholdAt(depth, length)));
        return Math.toDegrees(Math.acos(t));
    }

    /**
     * The X component of the <b>horizontal</b> unit look vector for a Minecraft yaw, in degrees.
     *
     * <p>Minecraft's yaw runs {@code 0} = {@code +Z} and increases clockwise seen from above, so the
     * look direction is {@code (-sin θ, cos θ)} — yaw {@code -90} faces {@code +X}, which is the
     * direction a train travels and an ENTRY corridor's room lies in.</p>
     */
    public static double lookX(float yawDegrees) {
        return -Math.sin(Math.toRadians(yawDegrees));
    }

    /**
     * Which copy a player at {@code localX} in a corridor of this {@code length} and {@code role},
     * looking at {@code yawDegrees}, belongs in.
     *
     * <p>Total: there is no direction it declines to answer for. Pure, and a function of exactly the
     * things the swap preserves — local X, yaw and the corridor's own shape — which is what makes it
     * idempotent: a player moved by it lands with the same local offset and the same yaw, so asking
     * again gives the same answer and nothing re-fires.</p>
     */
    public static Verdict verdict(double localX, int length, PortalCarriageRole role,
                                  float yawDegrees) {
        double depth = depthFromTrainDoor(localX, length, role);
        // +1 when looking straight at the room, -1 when looking straight back at the train.
        double alongAxis = axisTowardRoom(role) * lookX(yawDegrees);
        // Ties go to the train. Arbitrary, but it has to go somewhere, and the side that keeps a
        // player where they physically are is the quieter one to land on.
        return alongAxis > thresholdAt(depth, length) ? Verdict.COPY : Verdict.ORIGINAL;
    }
}
