package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A disconnected pair closes the way in and never the way out.
 *
 * <p>The asymmetry is load-bearing rather than a nicety: a player standing in the pocket room when
 * somebody breaks the shell — or who breaks it themselves from inside — has to be able to walk back
 * onto the train, and in an endless room the corridor they reach may well be one of the copies
 * scattered through the tiling rather than the pair's own. A copy is a way out and nothing else, so
 * a severed pair must not refuse one.</p>
 *
 * <p>Asserted here rather than left to the call sites — the player swap in
 * {@code PortalCarriageEvents} and {@link PortalEntityTransit} — because they used to spell the rule
 * out inline, where a later edit could quietly make it symmetric and strand somebody in a sealed
 * room with no way to notice.</p>
 *
 * <p>The predicate now answers for both ways a pair stops taking people in — a severed shell, and
 * the transient give-up {@link PortalWalkThrough} decides after a run of refusals — which is the
 * point of it being one predicate: the second state was added without the way out being able to
 * close behind it.</p>
 */
class PortalSeverDirectionTest {

    @Test
    @DisplayName("a severed pair refuses the way in")
    void severedBlocksInbound() {
        assertTrue(PortalSever.blocksMove(PortalFrames.FRAME_TWIN, true));
    }

    @Test
    @DisplayName("a severed pair still lets everything out — this is the copy's case")
    void severedAllowsOutbound() {
        assertFalse(PortalSever.blocksMove(PortalFrames.FRAME_CARRIAGE, true));
    }

    /**
     * The same, said of the other disconnected state, because it reaches the same predicate: a pair
     * that has given up must still let out whoever is inside its room — through either twin, and
     * through every copy an endless room scattered through its tiles.
     */
    @Test
    @DisplayName("a pair that gave up refuses the way in and still lets everything out")
    void gaveUpIsOneWayToo() {
        boolean gaveUp = true;
        assertTrue(PortalSever.blocksMove(PortalFrames.FRAME_TWIN, gaveUp));
        assertFalse(PortalSever.blocksMove(PortalFrames.FRAME_CARRIAGE, gaveUp));
    }

    @Test
    @DisplayName("an unsevered pair is gated in neither direction")
    void intactPairIsNeverBlocked() {
        assertFalse(PortalSever.blocksMove(PortalFrames.FRAME_TWIN, false));
        assertFalse(PortalSever.blocksMove(PortalFrames.FRAME_CARRIAGE, false));
    }
}
