package games.brennan.dungeontrain.nap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Off the train's level the gate is judged where the player last stood on it. */
class NapGateDataTest {

    @Test
    @DisplayName("on the train's level the player's own X counts")
    void onTrainUsesOwnX() {
        assertEquals(120.0, NapGateData.gateX(true, 120.0, -400.0, 0.0));
    }

    @Test
    @DisplayName("in a room or another dimension the last train X counts")
    void offTrainUsesLastTrainX() {
        assertEquals(-400.0, NapGateData.gateX(false, 9_000_000.0, -400.0, 0.0));
    }

    @Test
    @DisplayName("with no train X known, the gate itself (counts as ahead)")
    void unknownFallsBackToGate() {
        assertEquals(64.0, NapGateData.gateX(false, 9_000_000.0, Double.NaN, 64.0));
        assertEquals(64.0, NapGateData.gateX(true, Double.NaN, Double.NaN, 64.0));
    }

    @Test
    @DisplayName("the life's nap save count survives the world's save and load")
    void napSavesRoundTrip() {
        NapGateData.Entry e = new NapGateData.Entry(64.0, -1, 120.0, 3);
        assertEquals(e, NapGateData.readEntry(NapGateData.writeEntry(e)));
    }

    @Test
    @DisplayName("an entry holding only a save count is kept; none at all is empty")
    void napSavesAloneIsNotEmpty() {
        assertFalse(new NapGateData.Entry(0.0, 0, Double.NaN, 1).isEmpty());
        assertTrue(new NapGateData.Entry(0.0, 0, Double.NaN, 0).isEmpty());
        assertEquals(0, NapGateData.readEntry(new CompoundTag()).napSaves(), "older worlds read 0");
    }
}
