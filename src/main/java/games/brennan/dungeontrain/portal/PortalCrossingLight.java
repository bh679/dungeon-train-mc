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
 * <p>This ramp runs <b>straight through</b>: nothing at the train-side door plane, rising to full at
 * the room-side one. A corridor is the walk between two different places, and it should read as one
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
     * The ramp at {@code localX} in a corridor of this layout and role: {@link #OFF} at the
     * train-side door plane, {@code 1} at the room-side one, and a straight line between.
     *
     * <p>Linear rather than eased. The client is already easing between the values it is sent
     * ({@code ClientPortalCrossing}), and the corridor is nine blocks — a curve on top of that would
     * be shaping something the player crosses in three paces, and would make the two ends
     * asymmetric in a way nothing here means.</p>
     *
     * @param localX corridor-local X; values outside the corridor clamp to its end blocks, matching
     *               {@link PortalFacing#depthFromTrainDoor}
     * @param role   which end of this corridor the train is at
     * @return {@code 0}..{@code 1}, measured from the train end
     */
    public static double intensityAt(double localX, PortalCarriageLayout layout,
                                     PortalCarriageRole role) {
        int length = layout.length();
        // The span between the two door planes. A corridor one block long has no span and no
        // transition to make; PortalCarriageLayout.MIN_LENGTH rules it out, and this is here so that
        // loosening that constant cannot turn into a division by zero.
        int span = length - 1;
        if (span <= 0) return 1.0;

        double depth = PortalFacing.depthFromTrainDoor(localX, length, role);
        return Math.max(0.0, Math.min(1.0, depth / span));
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
