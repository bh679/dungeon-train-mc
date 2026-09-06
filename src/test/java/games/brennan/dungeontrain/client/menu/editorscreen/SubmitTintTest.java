package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Submit icon's colour, which is a claim about where somebody's work stands: blue while it is
 * only saved, green once it is with the reviewers. Green too early would say a build had been offered
 * when it had not.
 */
final class SubmitTintTest {

    /** The dimmest the shared pulse goes — EditorSaveStatus.PULSE_MIN, which lives in its package. */
    private static final float PULSE_FLOOR = 0.55f;

    @Test
    @DisplayName("submitted is steady green — the same green a submitted tile is ringed with")
    void submittedIsSteadyGreen() {
        int first = SubmitTint.of(true, 0L);
        assertEquals(SubmitTint.SUBMITTED, first);
        // Steady: the same colour at every point of the pulse's cycle, so nothing moves once it is in.
        for (long t : new long[] {0L, 250L, 500L, 750L, 1_000L, 123_456L}) {
            assertEquals(first, SubmitTint.of(true, t), "t=" + t);
        }
    }

    @Test
    @DisplayName("ready to submit pulses, and never brighter than the colour it is pulsing")
    void readyPulsesBlue() {
        boolean moved = false;
        int reference = SubmitTint.of(false, 0L);
        for (long t = 0; t <= 1_000; t += 100) {
            int argb = SubmitTint.of(false, t);
            assertEquals(0xFF, (argb >>> 24) & 0xFF, "the pulse dims the colour, never the alpha");
            assertTrue(channel(argb, 0) <= channel(SubmitTint.READY, 0), "blue stays the strongest");
            assertTrue(channel(argb, 16) >= Math.round(channel(SubmitTint.READY, 16) * PULSE_FLOOR),
                "never dimmer than the pulse floor");
            if (argb != reference) moved = true;
        }
        assertTrue(moved, "a build waiting to be submitted has to actually breathe");
        assertNotEquals(SubmitTint.SUBMITTED, SubmitTint.of(false, 0L),
            "not yet submitted must never read as submitted");
    }

    private static int channel(int argb, int shift) {
        return (argb >> shift) & 0xFF;
    }
}
