package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SableImpactDamageGuardTest {

    @Test
    void sableHitOnMobIsCancelled() {
        assertTrue(SableImpactDamageGuard.shouldCancel(true, false, false));
    }

    @Test
    void sableHitOnPlayerStillLands() {
        assertTrue(SableImpactDamageGuard.isSableImpact(true, false));
        assertFalse(SableImpactDamageGuard.shouldCancel(true, false, true));
    }

    @Test
    void elytraCrashIsVanillaAndUntouched() {
        assertFalse(SableImpactDamageGuard.isSableImpact(true, true));
        assertFalse(SableImpactDamageGuard.shouldCancel(true, true, false));
    }

    @Test
    void otherDamageIsUntouched() {
        assertFalse(SableImpactDamageGuard.isSableImpact(false, false));
        assertFalse(SableImpactDamageGuard.shouldCancel(false, false, false));
    }
}
