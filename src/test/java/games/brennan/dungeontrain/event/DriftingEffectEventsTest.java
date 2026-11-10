package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DriftingEffectEventsTest {

    /** A jump drops footing for a poll or two; the effect must outlast that or the icon flickers. */
    @Test
    void effectOutlastsTwoMissedPolls() {
        assertTrue(DriftingEffectEvents.EFFECT_TICKS > 3 * DriftingEffectEvents.CHECK_INTERVAL_TICKS);
    }
}
