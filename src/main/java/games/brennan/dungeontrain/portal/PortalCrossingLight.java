package games.brennan.dungeontrain.portal;

/**
 * How strongly a portal corridor's lightmap should be held at a fixed brightness at a given point
 * along it — the ramp that stops the swap from popping.
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
 * <h2>What this does instead</h2>
 * <p>Hands the client a number that says how much of the corridor's own lighting to replace with a
 * <b>constant</b>: {@code 0} at either door plane, ramping to {@code 1} at the baffles and holding
 * there across the middle. A constant is by definition the same in both copies, so wherever the ramp
 * is at full strength the swap cannot change what is drawn — and where it is not, the ramp is fading
 * out toward a doorway the player is about to leave through anyway.</p>
 *
 * <p><b>Symmetric, so it needs no {@link PortalCarriageRole}.</b> The measure is the distance to the
 * <i>nearer</i> door plane, which is the same number whichever end the train is at. That is not a
 * shortcut: an ENTRY corridor and an EXIT corridor are stamped from the same source and lit the
 * same, so a ramp that told them apart would be describing something that is not there.</p>
 *
 * <p><b>Quantised to the block</b>, for the reason {@link PortalFacing#depthFromTrainDoor} is: a
 * rider's position on a Sable carriage jitters by a few tenths of a block between client and server,
 * and a ramp read off the raw coordinate would shimmer while the player stood still.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class PortalCrossingLight {

    private PortalCrossingLight() {}

    /** Nothing to hold — the position is outside any corridor, or on a door plane. */
    public static final double OFF = 0.0;

    /**
     * The ramp at {@code localX} in a corridor of this layout.
     *
     * <p>Reaches {@code 1} at {@link PortalCarriageLayout#nearBaffleX()} rather than at some
     * constant of its own, so the plateau is exactly the baffle-to-baffle stretch the lantern floor
     * already claims ({@link PortalCarriageLayout#isCrossingZone}) and the two cannot drift apart.
     * The baffles are also where the sight-line breaks, which is the same reason that stretch is the
     * one that has to be indistinguishable between copies.</p>
     *
     * @param localX corridor-local X; values outside the corridor clamp to its end blocks, matching
     *               {@link PortalFacing#depthFromTrainDoor}
     * @return {@code 0}..{@code 1}
     */
    public static double intensityAt(double localX, PortalCarriageLayout layout) {
        int length = layout.length();
        int block = (int) Math.max(0, Math.min(length - 1, Math.floor(localX)));
        int edge = Math.min(block, (length - 1) - block);

        int plateau = layout.nearBaffleX();
        // A corridor short enough to have no ramp at all is held flat rather than divided by zero.
        // MIN_LENGTH keeps nearBaffleX at 2, so this is unreachable today and is here so that
        // loosening that constant cannot turn into a crash.
        if (plateau <= 0) return 1.0;

        return Math.min(1.0, (double) edge / plateau);
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
