package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.train.CarriagePets.Fate;
import static games.brennan.dungeontrain.train.CarriagePets.fate;
import static games.brennan.dungeontrain.train.CarriagePets.mayUpload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for what happens to pets on a removed carriage. No Minecraft bootstrap. */
class CarriagePetsTest {

    @Test
    void sittingPetThatUploadedDriftsWithTheCarriage() {
        assertEquals(Fate.DRIFTS, fate(true, true));
        assertEquals(Fate.DRIFTS, fate(true, false));
    }

    @Test
    void anyOtherPetGoesToItsOnlineOwner() {
        // Standing, sitting in a carriage that does not drift, or a drift whose capture was deferred.
        assertEquals(Fate.SENT_HOME, fate(false, true));
    }

    @Test
    void petOfAnOfflineOwnerIsLeftInPlace() {
        assertEquals(Fate.LEFT, fate(false, false));
    }

    @Test
    void onlySittingPetsGoIntoAnUpload() {
        assertTrue(mayUpload(true, true), "a sitting pet stays in the build");
        assertFalse(mayUpload(true, false), "a standing pet belongs with its owner");
    }

    @Test
    void wildEntitiesAreUnaffected() {
        assertTrue(mayUpload(false, false));
        assertTrue(mayUpload(false, true));
    }
}
