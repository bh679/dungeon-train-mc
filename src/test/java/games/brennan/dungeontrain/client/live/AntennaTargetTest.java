package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntennaTargetTest {

    @Test
    void hostileEntityIsRedEvenWithABlockBehind() {
        assertEquals(AntennaTarget.HOSTILE, AntennaTarget.classify(true, true, true));
        assertEquals(AntennaTarget.HOSTILE, AntennaTarget.classify(true, true, false));
    }

    @Test
    void anyOtherLivingThingIsGreen() {
        assertEquals(AntennaTarget.FRIENDLY, AntennaTarget.classify(true, false, true));
        assertEquals(AntennaTarget.FRIENDLY, AntennaTarget.classify(true, false, false));
    }

    @Test
    void blockAloneIsBlueAndNothingIsOff() {
        assertEquals(AntennaTarget.BLOCK, AntennaTarget.classify(false, false, true));
        assertEquals(AntennaTarget.OFF, AntennaTarget.classify(false, false, false));
        // "hostile" without an entity hit is meaningless and must not light the tip red
        assertEquals(AntennaTarget.OFF, AntennaTarget.classify(false, true, false));
    }

    @Test
    void propertyValuesClimbFromZeroToOne() {
        AntennaTarget[] values = AntennaTarget.values();
        assertEquals(0f, values[0].propertyValue());
        assertEquals(1f, values[values.length - 1].propertyValue());
        for (int i = 1; i < values.length; i++) {
            assertTrue(values[i].propertyValue() > values[i - 1].propertyValue(),
                values[i] + " must sit above " + values[i - 1]);
        }
    }
}
