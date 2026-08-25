package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Severing closes the way in and never the way out.
 *
 * <p>The asymmetry is load-bearing rather than a nicety: a player standing in the pocket room when
 * somebody breaks the shell — or who breaks it themselves from inside — has to be able to walk back
 * onto the train, and in an endless room the corridor they reach may well be one of the copies
 * scattered through the tiling rather than the pair's own. A copy is a way out and nothing else, so
 * a severed pair must not refuse one.</p>
 *
 * <p>Asserted here rather than left to the two call sites — the player swap in
 * {@code PortalCarriageEvents} and {@link PortalEntityTransit} — because both used to spell the rule
 * out inline, where a later edit could quietly make it symmetric and strand somebody in a sealed
 * room with no way to notice.</p>
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

    @Test
    @DisplayName("an unsevered pair is gated in neither direction")
    void intactPairIsNeverBlocked() {
        assertFalse(PortalSever.blocksMove(PortalFrames.FRAME_TWIN, false));
        assertFalse(PortalSever.blocksMove(PortalFrames.FRAME_CARRIAGE, false));
    }
}
