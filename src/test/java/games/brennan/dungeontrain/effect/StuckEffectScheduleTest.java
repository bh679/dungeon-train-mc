package games.brennan.dungeontrain.effect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static games.brennan.dungeontrain.effect.StuckEffectSchedule.GRACE_TICKS;
import static games.brennan.dungeontrain.effect.StuckEffectSchedule.RETRY_TICKS;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class StuckEffectScheduleTest {

    @Test
    void anEffectIsDueOnlyAfterTheGraceAtZero() {
        StuckEffectSchedule<String> schedule = new StuckEffectSchedule<>();
        assertEquals(List.of(), schedule.due(List.of("wither"), 100));
        assertEquals(List.of(), schedule.due(List.of("wither"), 100 + GRACE_TICKS - 1));
        assertEquals(List.of("wither"), schedule.due(List.of("wither"), 100 + GRACE_TICKS));
    }

    @Test
    void aStuckEffectIsAskedAboutAgainOnlyAfterTheRetryGap() {
        StuckEffectSchedule<String> schedule = new StuckEffectSchedule<>();
        schedule.due(List.of("wither"), 0);
        assertEquals(List.of("wither"), schedule.due(List.of("wither"), GRACE_TICKS));
        assertEquals(List.of(), schedule.due(List.of("wither"), GRACE_TICKS + RETRY_TICKS - 1));
        assertEquals(List.of("wither"), schedule.due(List.of("wither"), GRACE_TICKS + RETRY_TICKS));
    }

    @Test
    void anEffectThatLeavesZeroStartsItsGraceOver() {
        StuckEffectSchedule<String> schedule = new StuckEffectSchedule<>();
        schedule.due(List.of("wither"), 0);
        schedule.due(List.of(), GRACE_TICKS - 1);
        assertEquals(List.of(), schedule.due(List.of("wither"), GRACE_TICKS));
        assertEquals(List.of("wither"), schedule.due(List.of("wither"), 2 * GRACE_TICKS));
    }

    @Test
    void effectsAreTimedSeparately() {
        StuckEffectSchedule<String> schedule = new StuckEffectSchedule<>();
        schedule.due(List.of("wither"), 0);
        schedule.due(List.of("wither", "poison"), 10);
        assertEquals(List.of("wither"), schedule.due(List.of("wither", "poison"), GRACE_TICKS));
        assertEquals(List.of("poison"), schedule.due(List.of("wither", "poison"), GRACE_TICKS + 10));
    }

    @Test
    void clearForgetsEverything() {
        StuckEffectSchedule<String> schedule = new StuckEffectSchedule<>();
        schedule.due(List.of("wither"), 0);
        schedule.clear();
        assertEquals(List.of(), schedule.due(List.of("wither"), GRACE_TICKS));
    }
}
