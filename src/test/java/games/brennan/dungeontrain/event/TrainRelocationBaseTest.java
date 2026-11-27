package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.train.TrainSeedNumberingProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The carriage number a /dtp or a run restore's train continues from, instead of restarting at 0. */
final class TrainRelocationBaseTest {

    @Test
    @DisplayName("/dtp's carriage number is the X over the calibrated carriage length, signed and floored")
    void impliedCarriage() {
        assertEquals(1041, TrainRelocation.impliedCarriage(35_676));
        assertEquals(0, TrainRelocation.impliedCarriage(0));
        assertEquals(0, TrainRelocation.impliedCarriage(30));
        assertEquals(-1, TrainRelocation.impliedCarriage(-1), "behind spawn floors toward -infinity");
        assertEquals(-1041, TrainRelocation.impliedCarriage(-35_676));
    }

    @Test
    @DisplayName("a restore continues from the run's own travelled count, the X estimate only when it booked none")
    void restoreBase() {
        assertEquals(900, TrainRelocation.restoreBase(900, 35_676));
        assertEquals(-40, TrainRelocation.restoreBase(-40, 35_676), "a backward run keeps its sign");
        assertEquals(1041, TrainRelocation.restoreBase(0, 35_676));
        assertEquals(0, TrainRelocation.restoreBase(0, 0));
    }

    @Test
    @DisplayName("/dtp moves the travelled count onto the destination's number, either way")
    void travelDelta() {
        assertEquals(1036, TrainRelocation.travelDelta(5, 1041));
        assertEquals(-995, TrainRelocation.travelDelta(1041, 46));
        assertEquals(0, TrainRelocation.travelDelta(7, 7));
        assertEquals(-50, TrainRelocation.travelDelta(10, -40));
    }

    @Test
    @DisplayName("the carriage the player lands on reads the base, rounded down to a whole group")
    void landingNumber() {
        // The player lands at local pIdx 0 (the train is seeded at the destination X).
        assertEquals(1041, TrainSeedNumberingProbe.landingPIdx(0, TrainRelocation.impliedCarriage(35_676), 3));
        assertEquals(1040, TrainSeedNumberingProbe.landingPIdx(0, 1041, 4));
        assertEquals(900, TrainSeedNumberingProbe.landingPIdx(0, TrainRelocation.restoreBase(900, 30_000), 3));
        assertEquals(-42, TrainSeedNumberingProbe.landingPIdx(0, -40, 3));
    }
}
