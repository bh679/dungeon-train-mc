package games.brennan.dungeontrain.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The pure half of {@link ExposureCaptureState}: which combination of signals means "Exposure is
 * taking a photo this frame". The reflective probe and the snapshot deferral are verified in-game.
 */
class ExposureCaptureStateTest {

    @Test
    @DisplayName("HUD hidden and FOV overridden with Exposure installed is a capture")
    void captureNeedsBothSignals() {
        assertTrue(ExposureCaptureState.captureInFlight(true, true, () -> true));
    }

    @Test
    @DisplayName("The viewfinder alone (FOV overridden, HUD shown) is not a capture")
    void viewfinderAloneIsNotACapture() {
        assertFalse(ExposureCaptureState.captureInFlight(true, false, () -> true));
    }

    @Test
    @DisplayName("F1 alone (HUD hidden, no FOV override) is not a capture")
    void hiddenHudAloneIsNotACapture() {
        assertFalse(ExposureCaptureState.captureInFlight(true, true, () -> false));
    }

    @Test
    @DisplayName("Without Exposure nothing is a capture, and Exposure is never probed")
    void withoutExposureTheProbeIsNeverCalled() {
        assertFalse(ExposureCaptureState.captureInFlight(false, true, () -> fail("probed without Exposure")));
    }

    @Test
    @DisplayName("With the HUD shown Exposure is never probed")
    void shownHudSkipsTheProbe() {
        assertFalse(ExposureCaptureState.captureInFlight(true, false, () -> fail("probed with the HUD shown")));
    }
}
