package games.brennan.dungeontrain.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoRequestPolicyTest {

    @Test
    void neutralOutOfCombatCameraGiftPhotographs() {
        assertTrue(PhotoRequestPolicy.shouldPhotograph(true, false, 5.0F));
    }

    @Test
    void lovedGiverPhotographs() {
        assertTrue(PhotoRequestPolicy.shouldPhotograph(true, false, 10.0F));
    }

    @Test
    void dislikedGiverIsNotPhotographed() {
        assertFalse(PhotoRequestPolicy.shouldPhotograph(true, false, 4.9F));
        assertFalse(PhotoRequestPolicy.shouldPhotograph(true, false, 0.0F));
    }

    @Test
    void inCombatNeverPhotographs() {
        assertFalse(PhotoRequestPolicy.shouldPhotograph(true, true, 10.0F));
    }

    @Test
    void nonCameraGiftNeverPhotographs() {
        assertFalse(PhotoRequestPolicy.shouldPhotograph(false, false, 10.0F));
    }
}
