package games.brennan.dungeontrain.portal;

/**
 * How far through a portal corridor a point is, as a single transition from the world the train is
 * running in to the portal room at the other end — the ramp that carries the corridor's lighting
 * between them, and that stops the swap from popping on the way.
 *
 * <h2>The problem this exists for</h2>
 * <p>A pair's carriage and its twin are stamped from one source and are identical block for block
 * ({@link PortalCarriageBuilder#stateAt}), but they are not lit identically: the carriage's
 * train-side door is real and opens onto the next carriage, and the twin's is a plugged dummy. Light
 * leaks into one and not the other, so the brightness changes when a player is swapped — the same
 * failure {@link PortalGeometry} names, and solves by keeping the free-standing portal's midpoint
 * more than 15 blocks from any doorway. A nine-block carriage has no such room.</p>
 *
 * <p>{@link PortalCarriageLayout} answers it with <b>saturation</b> instead: floor the crossing zone
 * with light-15 lanterns in both copies, and leakage cannot change what is already at maximum. That
 * holds for the built-in geometry and does not hold for what players actually walk through — the
 * shipped corridors come from authored templates whose light sources live in the <i>contents</i>
 * layer ({@code contents/portal.nbt} is sea lanterns; {@code contents/portal_short.nbt} is wall
 * torches, which are light 14, not 15), drawn from a variant group. The guarantee is an authoring
 * convention, and conventions drift.</p>
 *
 * <p>And the swap is not confined to the crossing zone in any case. For players it fires on
 * <i>facing</i> ({@link PortalFacing}), anywhere from one block inside the train door to one block
 * inside the room door — so a player can be carried across while standing where the two copies
 * differ most, simply by turning round.</p>
 *
 * <h2>One transition, not two</h2>
 * <p>This ramp runs <b>straight through</b>: nothing in the train-side door block, rising to full by
 * the block inside the room-side one, and eased at both ends so neither the leaving nor the
 * arriving is a step. A corridor is the walk between two different places, and it should read as one
 * change of lighting between them — the outside world at one end, the room at the other.</p>
 *
 * <p><b>It used to be symmetric</b>, holding at full across the middle and falling away at both
 * ends, on the grounds that an ENTRY corridor and an EXIT corridor are stamped from the same source
 * and lit the same, so a ramp that told the ends apart would be describing something that is not
 * there. That was true about the <i>blocks</i> and wrong about the corridor: a player walking one
 * crossed the ramp twice, up and then down, and felt two transitions per carriage where the place
 * only has one boundary to cross. What is behind each door is not alike at all.</p>
 *
 * <p><b>So the role matters here</b>, exactly as it does in {@link PortalFacing}: an
 * {@link PortalCarriageRole#ENTRY} corridor puts the train at low local X and an
 * {@link PortalCarriageRole#EXIT} corridor at high, and this is measured from the train end either
 * way. That mirror is what lets a player walk train → room → train without the lighting ever running
 * backwards under them.</p>
 *
 * <h2>Why the swap still cannot change it</h2>
 * <p>A swap preserves the corridor-local offset exactly, and a pair's two frames are built from one
 * {@link PortalCarriageLayout} and carry one {@link PortalCarriageRole} — so the ramp reads the same
 * number either side of it, at every position. Whatever the two copies' own lighting is doing, the
 * part of it this replaces is identical in both.</p>
 *
 * <p><b>Quantised to the block</b>, for the reason {@link PortalFacing#depthFromTrainDoor} is: a
 * rider's position on a Sable carriage jitters by a few tenths of a block between client and server,
 * and a ramp read off the raw coordinate would shimmer while the player stood still.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class PortalCrossingLight {

    private PortalCrossingLight() {}

    /** The train end of the ramp — the corridor lit as the world outside it is. */
    public static final double OFF = 0.0;

    /**
     * How far <b>outside</b> the train-side door the ramp begins, in blocks.
     *
     * <p><b>The ramp used to start at the door and that was too late.</b> A player is swapped by the
     * facing rule from one block inside it ({@link PortalFacing}), which is where the ramp still read
     * zero — so walking into a corridor whose contents carry no lamps of their own landed the player
     * in the dark, and the lift then rose under them as they walked on. A dimensional carriage is
     * entered from the neighbouring carriage, so the transition has somewhere to begin: five blocks
     * of the approach, which is a pace or two before the doorway and well inside the range at which
     * the pair is being ticked at all ({@code PortalCarriageEvents.APPROACH_RANGE}).</p>
     *
     * <p>It changes where the ramp starts, not what it is: still one transition, still eased at both
     * ends, still identical either side of a swap.</p>
     */
    public static final int LEAD_IN_BLOCKS = 5;

    /**
     * How far through the transition the train-side <b>door block</b> is.
     *
     * <p>Fixed rather than falling out of the arithmetic, and that is the point: the approach and the
     * corridor are mapped onto their own halves of the ramp, so the doorway reads the same wherever
     * it is and a sixteen-block corridor does not start its player darker than a seven-block one.
     * Half, because the doorway is the boundary — the walk up to it and the walk down it are the two
     * halves of one crossing, and the swap fires a block inside, comfortably above this.</p>
     */
    private static final double AT_TRAIN_DOOR = 0.5;

    /**
     * The ramp at {@code localX} in a corridor of this layout and role: {@link #OFF} through the
     * train-side door block, {@code 1} from the room-side one, and a straight line between.
     *
     * <p><b>It finishes a block early.</b> The top of the ramp is {@link PortalFacing#lastRampBlock},
     * one in from the room-side door, so the last step through that doorway changes nothing at all —
     * which is the whole point of the exercise. The bottom is the train-side door block itself
     * rather than the block inside it: holding two blocks at zero and then starting meant the first
     * change a player saw arrived all at once, and a walk that begins with a jolt is not a smooth
     * walk however even the rest of it is.</p>
     *
     * <p><b>Eased at both ends, not linear.</b> {@code t²(3-2t)}, the standard smoothstep: it leaves
     * the train gently, arrives gently, and spends its steepness in the middle where there is no
     * boundary for the player to notice it against. A straight line divides the change evenly per
     * block, which sounds smooth and is not what it feels like — the steps that matter are the two
     * at the ends, next to somewhere that is not ramping at all, and a line makes those the same
     * size as the ones in the middle. The corridor is short enough that the whole curve is crossed
     * in a few paces, so the cost of the steeper middle is not something a player can pick out.</p>
     *
     * @param localX corridor-local X; values outside the corridor clamp to its end blocks, matching
     *               {@link PortalFacing#depthFromTrainDoor}
     * @param role   which end of this corridor the train is at
     * @return {@code 0}..{@code 1}, measured from the train end
     */
    public static double intensityAt(double localX, PortalCarriageLayout layout,
                                     PortalCarriageRole role) {
        return intensityAtDepth(depthWithLeadIn(localX, layout.length(), role), layout.length());
    }

    /**
     * The ramp at a given depth from the train-side door, where negative depths are the approach
     * outside it.
     *
     * <p>Split out because the approach is not in the corridor at all: {@link PortalFrames} has to
     * measure the depth itself for a position in the carriage in front of the doorway, and both
     * callers must land on one curve or the walk through the door would have a step in it.</p>
     */
    public static double intensityAtDepth(double depth, int length) {
        // Train-side door block to one in from the room-side one, plus the approach ahead of it.
        int span = PortalFacing.lastRampBlock(length);
        // A corridor with nothing to cross has no transition to make; MIN_LENGTH rules it out, and
        // this is here so that loosening that constant cannot turn into a division by zero.
        if (span <= 0) return depth > 0 ? 1.0 : OFF;

        // Two segments onto one curve: the approach fills the first half, the corridor the second,
        // meeting at the doorway. Monotone across the join, so the walk has no step in it.
        double t;
        if (depth >= 0) {
            t = AT_TRAIN_DOOR + (1.0 - AT_TRAIN_DOOR) * (depth / span);
        } else {
            t = AT_TRAIN_DOOR * (depth + LEAD_IN_BLOCKS) / LEAD_IN_BLOCKS;
        }
        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * Corridor-local X as a depth from the train-side door, running <b>negative</b> through the
     * approach and clamping to {@code -LEAD_IN_BLOCKS} beyond it.
     *
     * <p>{@link PortalFacing#depthFromTrainDoor} clamps to the corridor's own end blocks, which is
     * right for the facing rule — a swap decision is about the corridor — and wrong here, where the
     * whole point is the blocks in front of it. Mirrored by role the same way: {@code ENTRY} has the
     * train at low local X, {@code EXIT} at high.</p>
     */
    public static double depthWithLeadIn(double localX, int length, PortalCarriageRole role) {
        double block = Math.floor(localX);
        double depth = role == PortalCarriageRole.ENTRY ? block : (length - 1) - block;
        return Math.max(-LEAD_IN_BLOCKS, Math.min(length - 1, depth));
    }

    /** The wire form: {@code 0}..{@code 255}, which is all the resolution an eased lift can show. */
    public static int toWire(double intensity) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, intensity)) * 255.0);
    }

    /** And back. Total, so a byte from a different build cannot throw on the packet thread. */
    public static float fromWire(int wire) {
        return Math.max(0.0f, Math.min(1.0f, wire / 255.0f));
    }
}
