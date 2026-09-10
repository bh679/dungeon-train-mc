package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure tests for {@link ClientStages#dims(int)} — the compact gate-summary letters. */
final class ClientStagesDimsTest {

    @Test
    @DisplayName("every phase set reads 'all'")
    void allPhases() {
        assertEquals("all", ClientStages.dims(TrainPhase.ALL_MASK));
    }

    @Test
    @DisplayName("subset lists one letter per phase in ordinal order, including U and C")
    void subset() {
        int mask = TrainPhase.VOID.bit() | TrainPhase.UPSIDE_DOWN.bit() | TrainPhase.CHUNCKS.bit();
        assertEquals("VUC", ClientStages.dims(mask));
        assertEquals("ON", ClientStages.dims(TrainPhase.OVERWORLD.bit() | TrainPhase.NETHER.bit()));
    }

    @Test
    @DisplayName("no phase set reads an em dash")
    void none() {
        assertEquals("—", ClientStages.dims(0));
    }
}
