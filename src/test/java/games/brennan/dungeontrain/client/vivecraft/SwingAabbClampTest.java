package games.brennan.dungeontrain.client.vivecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * Verifies the VR-melee clamp (deliverable 2) off the render thread — no VR / Vivecraft needed. Uses the
 * exact ~20M-block AABB from the reported latest.log and a realistic player box.
 */
class SwingAabbClampTest {

    /** The abnormally-large swing box straight from daviddespot's log (player pos -> sub-level pivot). */
    private static final AABB MISMATCH_BOX =
            new AABB(1451.11, 78.14, 3.98, 2.0481051E7, 173.25, 2.0509705E7);

    /** A realistic standing-player bounding box (~0.6 x 1.8 x 0.6). */
    private static final AABB PLAYER_BOX = new AABB(10.0, 64.0, 10.0, 10.6, 65.8, 10.6);

    @Test
    void oversizedBoxIsRebuiltFromPlayerBox() {
        assertTrue(SwingAabbClamp.isOversized(MISMATCH_BOX), "the 20M-block box must be flagged oversized");
        AABB result = SwingAabbClamp.forSwingQuery(MISMATCH_BOX, PLAYER_BOX);
        assertEquals(PLAYER_BOX.inflate(SwingAabbClamp.MELEE_REACH), result,
                "oversized query must become the player box inflated to melee reach");
    }

    @Test
    void rebuiltBoxPassesSableAbortThreshold() {
        // Sable aborts (and log-spams) when AABB.getSize() > 100000. The rebuilt box must be well under it.
        AABB result = SwingAabbClamp.forSwingQuery(MISMATCH_BOX, PLAYER_BOX);
        assertTrue(result.getSize() < 100_000.0,
                "rebuilt box must be small enough that Sable does not abort (was " + result.getSize() + ")");
    }

    @Test
    void normalSwingBoxIsPassedThroughUnchanged() {
        // A realistic VR swing box (a few blocks) must be returned by identity — zero behaviour change.
        AABB normal = new AABB(10.0, 64.0, 10.0, 13.0, 67.0, 13.0); // getSize() == 3
        assertFalse(SwingAabbClamp.isOversized(normal));
        assertSame(normal, SwingAabbClamp.forSwingQuery(normal, PLAYER_BOX),
                "a normal swing box must be returned unchanged (same reference)");
    }

    @Test
    void thresholdBoundaryUsesStrictGreaterThan() {
        // getSize() exactly at the threshold is NOT oversized (strict >), so it passes through.
        AABB atThreshold = new AABB(0, 0, 0,
                SwingAabbClamp.MAX_SANE_SWING_SIZE, SwingAabbClamp.MAX_SANE_SWING_SIZE,
                SwingAabbClamp.MAX_SANE_SWING_SIZE); // getSize() == MAX_SANE_SWING_SIZE
        assertFalse(SwingAabbClamp.isOversized(atThreshold));
        assertSame(atThreshold, SwingAabbClamp.forSwingQuery(atThreshold, PLAYER_BOX));
    }
}
