package games.brennan.dungeontrain.portal;

/**
 * Which copy of a corridor a <b>player</b> belongs in, decided by which way they are looking.
 *
 * <p>The rule this replaces (for players — see below) was positional: {@link PortalFrames#requiredMove}
 * compares corridor-local X against the midpoint, so you had to physically walk past the middle to
 * cross. The rule here asks a different question, and it works because of one property of the
 * corridor: <b>you always face the door you are walking toward</b>, and each copy has exactly one
 * real door. Look toward the room and you are put in the copy whose far door opens into it; turn
 * round and you are put in the copy whose near door opens onto the train. Neither can present you
 * with the plugged dummy.</p>
 *
 * <pre>
 *   ← train                                                    room →
 *   │D│ · · · · · · · · · · · · · · · · · · · · · · · · · · · ·│D│
 *    ^  ^                                                       ^
 *    │  └ depth 1: the gate. Below this, facing decides nothing.
 *    └ depth 0, the TRAIN-side door plane
 *
 *   required cone, by depth:   30° ————————————————————————→ 85°
 *                            (strict at the way in)      (loose at the way out)
 * </pre>
 *
 * <p><b>Players only.</b> {@link PortalEntityTransit} keeps the midpoint rule, because a mob's yaw is
 * whatever its pathfinding is doing this tick and a thrown pearl's yaw tracks nothing at all. The
 * cost is that a villager led in is briefly in a different copy from the player who led it, until it
 * crosses the midpoint itself.</p>
 *
 * <p><b>Depth is measured from the TRAIN-side door, not the nearest one.</b> Measuring from the
 * nearest would tighten the cone again at the room end — exactly where a player has to be decided
 * <i>before</i> walking out, or they leave through the wrong copy's door. Strictest where you come
 * in, loosest where you go out, is the shape that matches how the corridor is used.</p>
 *
 * <p><b>Yaw only, never the full look vector.</b> Looking at the floor while walking forward must
 * still count as facing forward; taking the 3D unit vector's X would scale it by {@code cos(pitch)}
 * and release a player who glanced down.</p>
 *
 * <p><b>No hysteresis band, and none needed.</b> {@link PortalFrames#SWAP_HYSTERESIS} is 1.25 blocks
 * because a rider's <i>position</i> jitters — the client and server disagree by ~0.17 and the Sable
 * ship's own pose drifts ~0.4. Yaw does none of that, and the two reasons are worth writing down
 * because the whole rule rests on them:</p>
 *
 * <ul>
 *   <li><b>The swap preserves it exactly.</b> {@code ServerGamePacketListenerImpl.teleport} takes
 *       <i>absolute</i> values and derives the packet's deltas itself — with {@code Y_ROT} in the
 *       relative set it sends {@code yaw - player.getYRot()}. {@code PortalCarriageEvents} passes
 *       {@code player.getYRot()}, so the delta is 0 and {@code absMoveTo} re-sets the same yaw. A
 *       swap cannot spin anyone.</li>
 *   <li><b>It cannot drift during the round trip.</b> That same method sets
 *       {@code awaitingPositionFromClient}, and until the client acknowledges, {@code handleMovePlayer}
 *       drops its packets — so the server-side yaw is frozen at exactly the value that caused the
 *       swap, which by idempotence already agrees with where the player now is.</li>
 * </ul>
 *
 * <p>So the verdict is stable without a band, and adding one would only widen the
 * {@link Verdict#HOLD} zone.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class PortalFacing {

    private PortalFacing() {}

    /** Where the facing rule says a player belongs. */
    public enum Verdict {
        /** The static twin — the corridor on the room's side of the crossing. */
        COPY,
        /** The carriage riding the train. */
        ORIGINAL,
        /** Not decisive: leave the player in whichever copy they are already in. */
        HOLD
    }

    /**
     * How far in from the train-side door the rule starts answering at all, in blocks.
     *
     * <p>Below this a player is still in the doorway, and a doorway is the one place where being
     * yanked across would be felt — you would step through the door and be teleported in the same
     * moment, with the door frame right in front of you to notice it by. It also gives the two
     * copies' door planes a block of clearance from the decision, which matters because that is the
     * one block of a corridor a player is ever pressed right up against.</p>
     */
    public static final double MIN_DEPTH = 1.0;

    /**
     * The cone a player must look within, at {@link #MIN_DEPTH}, for the rule to fire — 30° off the
     * corridor axis.
     *
     * <p>Deliberately strict here. A player stepping through the door at an angle, or glancing at the
     * frame on the way past, is not asking to be moved.</p>
     */
    public static final double CONE_AT_GATE_DEGREES = 30.0;

    /**
     * The cone at the far end of the corridor — 85°, so only a look within about 5° of dead
     * perpendicular is undecided.
     *
     * <p>This is what makes {@link Verdict#HOLD} safe as a fallback rather than a trap. Holding means
     * a player who never commits keeps whatever copy they are in, and could in principle walk the
     * length of a corridor and meet a dummy door. By the room end the undecided band is so narrow
     * that getting there requires shuffling sideways down the whole corridor on purpose.</p>
     */
    public static final double CONE_AT_FAR_END_DEGREES = 85.0;

    private static final double COS_AT_GATE = Math.cos(Math.toRadians(CONE_AT_GATE_DEGREES));
    private static final double COS_AT_FAR_END = Math.cos(Math.toRadians(CONE_AT_FAR_END_DEGREES));

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
     * How far past the train-side door plane a corridor-local X is, in blocks. Negative in the pad
     * outside the door; grows toward the room.
     */
    public static double depthFromTrainDoor(double localX, int length, PortalCarriageRole role) {
        return role == PortalCarriageRole.ENTRY ? localX : (length - 1) - localX;
    }

    /**
     * The cosine a look must beat at this depth: {@link #CONE_AT_GATE_DEGREES} at {@link #MIN_DEPTH},
     * easing to {@link #CONE_AT_FAR_END_DEGREES} at the far door plane.
     *
     * <p>Returns the gate value for a corridor too short to have a ramp at all, which cannot happen
     * at any legal {@link games.brennan.dungeontrain.train.CarriageDims} but is one division by zero
     * if it ever did.</p>
     */
    public static double thresholdAt(double depth, int length) {
        double span = (length - 1) - MIN_DEPTH;
        if (!(span > 0)) return COS_AT_GATE;
        double f = Math.max(0.0, Math.min(1.0, (depth - MIN_DEPTH) / span));
        return COS_AT_GATE + f * (COS_AT_FAR_END - COS_AT_GATE);
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
     * The verdict for a player at {@code localX} in a corridor of this {@code length} and
     * {@code role}, looking at {@code yawDegrees}.
     *
     * <p>Pure, and a function of exactly the three things the swap preserves — local X, yaw, and the
     * corridor's own shape. That is what makes it idempotent: a player moved by it lands with the
     * same local offset and the same yaw, so asking again gives the same answer and nothing
     * re-fires.</p>
     */
    public static Verdict verdict(double localX, int length, PortalCarriageRole role,
                                  float yawDegrees) {
        double depth = depthFromTrainDoor(localX, length, role);
        if (depth < MIN_DEPTH) return Verdict.HOLD;

        // +1 when looking toward the room, -1 when looking back at the train.
        double alongAxis = axisTowardRoom(role) * lookX(yawDegrees);
        double threshold = thresholdAt(depth, length);

        if (alongAxis > threshold) return Verdict.COPY;
        if (alongAxis < -threshold) return Verdict.ORIGINAL;
        return Verdict.HOLD;
    }
}
