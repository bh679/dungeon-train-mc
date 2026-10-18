package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RunPosition#screened}: a life's displacement is dropped whole when it ended in the Sable plot
 * grid or ran implausibly far past the odometer, so neither the lifetime total nor any payload books
 * it. Pure — no Minecraft bootstrap.
 */
class RunPositionTest {

    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    @Test
    @DisplayName("a life that ended in the plot grid is dropped, whatever the odometer says")
    void plotGridEndDropped() {
        RunPosition pos = new RunPosition(200, 800, "overworld").screened(true, 5_000.0);
        assertSame(RunPosition.NONE, pos);
        assertEquals(0L, pos.forwardMetres());
    }

    @Test
    @DisplayName("the #2080 case: 20.48M displacement over a short odometer is dropped")
    void issue2080Dropped() {
        RunPosition raw = new RunPosition(0, 20_483_097, "overworld");
        assertSame(RunPosition.NONE, raw.screened(false, 4_800.0));
    }

    @Test
    @DisplayName("odometer + slack is the last believed displacement")
    void slackBoundary() {
        int odometer = 3_000;
        int edge = odometer + (int) RunPosition.ODOMETER_SLACK_BLOCKS;
        RunPosition atEdge = new RunPosition(0, edge, "nether");
        RunPosition past = new RunPosition(0, edge + 1, "nether");
        assertSame(atEdge, atEdge.screened(false, odometer));
        assertSame(RunPosition.NONE, past.screened(false, odometer));
    }

    @Test
    @DisplayName("a normal run, a backward run and an uncaptured origin are kept as they are")
    void plausibleKept() {
        RunPosition normal = new RunPosition(200, 800, "overworld");
        RunPosition backward = new RunPosition(4_000, -1_500, "overworld");
        RunPosition noOrigin = new RunPosition(null, null, "upside_down");
        assertSame(normal, normal.screened(false, 2_013.0));
        assertSame(backward, backward.screened(false, 0.0));
        assertSame(noOrigin, noOrigin.screened(false, 0.0));
    }

    @Test
    @DisplayName("a broken odometer counts as zero, not as unlimited headroom")
    void nonFiniteOdometer() {
        assertTrue(RunPosition.isPlausible(false, 1_000L, Double.NaN));
        assertFalse(RunPosition.isPlausible(false, 20_483_097L, Double.POSITIVE_INFINITY));
        assertFalse(RunPosition.isPlausible(false, 20_483_097L, -5.0));
    }

    @Test
    @DisplayName("a dropped life's run summary carries no distanceTravelled, spawnX or band")
    void runSummaryOmitsDroppedDisplacement() {
        RunPosition pos = new RunPosition(0, 20_483_097, "overworld").screened(true, 4_800.0);
        JsonObject out = RunSummaryReporter.buildPayload(UUID, "x", 600L, 12, 4_800, pos, false);
        assertFalse(out.has("distanceTravelled"));
        assertFalse(out.has("spawnX"));
        assertFalse(out.has("band"));
        assertEquals(4_800, out.get("distanceBlocks").getAsInt());
    }
}
