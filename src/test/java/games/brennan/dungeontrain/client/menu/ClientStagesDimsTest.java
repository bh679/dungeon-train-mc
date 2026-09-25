package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.worldgen.LapBand;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure tests for {@link ClientStages#dims(int)} — the compact gate-summary letters. */
@ExtendWith(MenuTestLanguage.class)
final class ClientStagesDimsTest {

    @Test
    @DisplayName("every phase set reads 'all'")
    void allPhases() {
        assertEquals("all", ClientStages.dims(LapBand.ALL_MASK));
    }

    @Test
    @DisplayName("subset lists each lap's letters in cycle order, laps without a set band left out")
    void subset() {
        int mask = LapBand.L_VOID.bit() | LapBand.V_UPSIDE_DOWN.bit() | LapBand.C_CHUNCKS.bit();
        assertEquals("V:U L:V C:C", ClientStages.dims(mask));
        assertEquals("V:ON", ClientStages.dims(LapBand.V_OVERWORLD_1.bit() | LapBand.V_NETHER.bit()));
    }

    @Test
    @DisplayName("no phase set reads an em dash")
    void none() {
        assertEquals("—", ClientStages.dims(0));
    }
}
