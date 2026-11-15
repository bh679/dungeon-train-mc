package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RestStopPlan}: Brennan's "at least one natural bed every 250 carriages". */
final class RestStopPlanTest {

    private static final int RANGE = 100_000;

    private static List<Integer> stops(long seed, IntPredicate roomSized) {
        List<Integer> out = new ArrayList<>();
        for (int i = -RANGE; i <= RANGE; i++) {
            if (RestStopPlan.isRestStop(seed, i, roomSized)) out.add(i);
        }
        return out;
    }

    @Test
    @DisplayName("every gap between rest stops is under 250 carriages, both ways along the train")
    void gapsUnderLimit() {
        assertTrue(RestStopPlan.SPACING + RestStopPlan.JITTER + RestStopPlan.WINDOW <= RestStopPlan.MAX_GAP);
        for (long seed : new long[] {0L, 1L, -7L, 0x5EEDL, Long.MAX_VALUE}) {
            // Every third carriage is a non-room shell (a flatbed or a half shell).
            List<Integer> s = stops(seed, i -> Math.floorMod(i, 3) != 2);
            for (int n = 1; n < s.size(); n++) {
                int gap = s.get(n) - s.get(n - 1);
                assertTrue(gap < RestStopPlan.MAX_GAP, "seed " + seed + " gap " + gap + " at " + s.get(n));
                assertTrue(gap > RestStopPlan.SPACING - RestStopPlan.JITTER - RestStopPlan.WINDOW, "gap " + gap);
            }
            assertEquals(2 * RANGE / RestStopPlan.SPACING, s.size(), 2.0);
        }
    }

    @Test
    @DisplayName("one stop per window: the first room-sized shell, and never a non-room one")
    void firstRoomShellOnly() {
        long seed = 42L;
        int start = RestStopPlan.windowStart(seed, 3);
        IntPredicate firstTwoNotRooms = i -> i != start && i != start + 1;
        assertFalse(RestStopPlan.isRestStop(seed, start, firstTwoNotRooms));
        assertFalse(RestStopPlan.isRestStop(seed, start + 1, firstTwoNotRooms));
        assertTrue(RestStopPlan.isRestStop(seed, start + 2, firstTwoNotRooms));
        assertFalse(RestStopPlan.isRestStop(seed, start + 3, firstTwoNotRooms));
    }

    @Test
    @DisplayName("deterministic in (seed, carriage) and the jitter moves with the seed")
    void deterministic() {
        assertEquals(stops(9L, i -> true), stops(9L, i -> true));
        assertFalse(stops(9L, i -> true).equals(stops(10L, i -> true)));
    }

    @Test
    @DisplayName("no rest stops where beds explode")
    void bedPhases() {
        assertFalse(RestStopPlan.phaseAllowsBeds(TrainPhase.NETHER));
        assertFalse(RestStopPlan.phaseAllowsBeds(TrainPhase.END));
        assertFalse(RestStopPlan.phaseAllowsBeds(TrainPhase.DEEPER_DARK));
        assertTrue(RestStopPlan.phaseAllowsBeds(TrainPhase.OVERWORLD));
        assertTrue(RestStopPlan.phaseAllowsBeds(TrainPhase.LOST_CITY));
    }
}
