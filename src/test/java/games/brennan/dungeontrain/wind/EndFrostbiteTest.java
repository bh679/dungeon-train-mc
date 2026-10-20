package games.brennan.dungeontrain.wind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndFrostbiteTest {

    @Test
    void firstCycleNeverFreezes() {
        assertEquals(0, EndFrostbite.frostPerTick(1.0, 0L));
        assertEquals(0, EndFrostbite.frostPerTick(1.0, -1L));
    }

    @Test
    void laterCyclesFreezeUnderFullEndSky() {
        assertEquals(EndFrostbite.FROST_AT_FULL_SKY, EndFrostbite.frostPerTick(1.0, 1L));
        assertEquals(EndFrostbite.FROST_AT_FULL_SKY, EndFrostbite.frostPerTick(1.0, 5L));
    }

    @Test
    void fullSkyOutpacesVanillaThaw() {
        assertTrue(EndFrostbite.FROST_AT_FULL_SKY > EndFrostbite.VANILLA_THAW);
    }

    @Test
    void partialSkyScalesDown() {
        assertEquals(2, EndFrostbite.frostPerTick(0.5, 1L));
        assertEquals(0, EndFrostbite.frostPerTick(0.1, 1L));
    }

    @Test
    void noEndSkyNoFrost() {
        assertEquals(0, EndFrostbite.frostPerTick(0.0, 3L));
        assertEquals(0, EndFrostbite.frostPerTick(Double.NaN, 3L));
    }
}
