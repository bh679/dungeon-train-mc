package games.brennan.dungeontrain.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Pins the End-band feature spill to opt-in: existing worlds keep the look they were generated with. */
class EndBandConfigDefaultsTest {

    @Test
    @DisplayName("feature spill between sampled End-band chunks is off unless a server turns it on")
    void featureSpillDefaultsOff() {
        assertFalse(EndBandConfig.DEFAULT_FEATURE_SPILL);
        assertFalse(EndBandConfig.featureSpill(), "before the config loads the hardcoded default applies");
    }
}
