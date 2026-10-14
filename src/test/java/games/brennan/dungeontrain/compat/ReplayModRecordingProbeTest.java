package games.brennan.dungeontrain.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Without ReForgedPlay (and without a mod list at all, as here) the probe must be inert: every
 * answer false, nothing thrown — it runs inside Sable's tracking tick.
 */
final class ReplayModRecordingProbeTest {

    @Test
    void absentModIsIdle() {
        assertDoesNotThrow(() -> {
            assertFalse(ReplayModRecordingProbe.isPresent());
            assertFalse(ReplayModRecordingProbe.isRecordingLocally());
            assertFalse(ReplayModRecordingProbe.isReplaying());
        });
    }
}
