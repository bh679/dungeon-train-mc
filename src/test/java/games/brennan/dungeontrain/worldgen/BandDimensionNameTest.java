package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BandDimensionNameTest {

    @Test
    @DisplayName("the bands standing in for vanilla dimensions borrow their real ids")
    void vanillaBandsUseVanillaIds() {
        assertEquals("minecraft:overworld", BandDimensionName.of(TrainPhase.OVERWORLD));
        assertEquals("minecraft:the_nether", BandDimensionName.of(TrainPhase.NETHER));
        assertEquals("minecraft:the_end", BandDimensionName.of(TrainPhase.END));
    }

    @Test
    @DisplayName("the other bands are named from their token")
    void otherBandsUseTheirToken() {
        assertEquals("dungeontrain:upside_down", BandDimensionName.of(TrainPhase.UPSIDE_DOWN));
        assertEquals("dungeontrain:lost_city", BandDimensionName.of(TrainPhase.LOST_CITY));
        assertEquals("dungeontrain:deeper_dark", BandDimensionName.of(TrainPhase.DEEPER_DARK));
    }

    @ParameterizedTest
    @EnumSource(TrainPhase.class)
    @DisplayName("every phase reads as a well-formed dimension id")
    void everyPhaseIsAValidId(TrainPhase phase) {
        assertTrue(BandDimensionName.of(phase).matches("[a-z_]+:[a-z_]+"), phase.name());
    }
}
