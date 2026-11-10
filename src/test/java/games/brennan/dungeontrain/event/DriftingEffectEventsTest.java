package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DriftingEffectEventsTest {

    /** A jump drops footing for a poll or two; the effect must not come off for that. */
    @Test
    void graceOutlastsTwoMissedPolls() {
        assertTrue(DriftingEffectEvents.OFF_GRACE_POLLS > 2);
    }
}
