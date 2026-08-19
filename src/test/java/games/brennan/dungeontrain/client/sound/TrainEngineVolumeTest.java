package games.brennan.dungeontrain.client.sound;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Train Engine Volume setting scales the curve without reshaping it, and tells "off" apart from
 * "inaudible from here".
 */
class TrainEngineVolumeTest {

    private static final float EPS = 1.0e-6f;

    @Test
    @DisplayName("the default setting leaves the computed volume untouched")
    void fullSettingIsATransparentPassThrough() {
        assertEquals(1.0f, TrainEngineVolume.scale(1.0f, 1.0), EPS);
        assertEquals(0.5f, TrainEngineVolume.scale(0.5f, 1.0), EPS);
        assertEquals(TrainEngineVolume.KEEP_ALIVE_FLOOR,
            TrainEngineVolume.scale(TrainEngineVolume.KEEP_ALIVE_FLOOR, 1.0), EPS);
    }

    @Test
    @DisplayName("the setting multiplies the curve, so its shape survives")
    void settingScalesEveryPointOfTheCurve() {
        // Riding a carriage (1.0) and standing half a falloff away (0.5) stay in proportion.
        assertEquals(0.5f, TrainEngineVolume.scale(1.0f, 0.5), EPS);
        assertEquals(0.25f, TrainEngineVolume.scale(0.5f, 0.5), EPS);
        assertEquals(0.2f, TrainEngineVolume.scale(1.0f, 0.2), EPS);
    }

    @Test
    @DisplayName("zero is off — a true zero, not the keep-alive floor")
    void zeroSettingIsOff() {
        assertFalse(TrainEngineVolume.audible(0.0));
        assertEquals(0.0f, TrainEngineVolume.scale(1.0f, 0.0), EPS);
        // Any setting the config can store above zero is audible, floor included.
        assertTrue(TrainEngineVolume.audible(0.1));
        assertTrue(TrainEngineVolume.audible(1.0));
    }

    @Test
    @DisplayName("an audible setting never reports zero, however quiet the curve got")
    void audibleResultsKeepTheInstanceAlive() {
        // A player at the very edge of the falloff, with the setting right down: still registered,
        // because SoundEngine drops an instance that reaches zero and the sound has to survive
        // walking back toward the train.
        float faint = TrainEngineVolume.scale(TrainEngineVolume.KEEP_ALIVE_FLOOR, 0.1);
        assertEquals(TrainEngineVolume.KEEP_ALIVE_FLOOR, faint, EPS);
        assertTrue(faint > 0.0f);
    }

    @Test
    @DisplayName("a base of zero stays zero — nothing to play is not a quiet moment")
    void silentBaseIsNotLifted() {
        assertEquals(0.0f, TrainEngineVolume.scale(0.0f, 1.0), EPS);
        assertEquals(0.0f, TrainEngineVolume.scale(0.0f, 0.0), EPS);
    }

    @Test
    @DisplayName("out-of-range input is clamped rather than trusted")
    void resultIsClamped() {
        // A hand-edited toml is the realistic source of an out-of-range setting.
        assertEquals(1.0f, TrainEngineVolume.scale(1.0f, 4.0), EPS);
        assertEquals(0.0f, TrainEngineVolume.scale(1.0f, -1.0), EPS);
    }
}
