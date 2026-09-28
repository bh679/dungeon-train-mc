package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BandLabelTest {

    @Test
    void plainPhaseHasNoSuffix() {
        assertEquals("Nether", BandLabel.format("Nether", ""));
        assertEquals("Nether", BandLabel.format("Nether", null));
    }

    @Test
    void styledOccurrenceIsBracketed() {
        assertEquals("Nether (Better Nether)", BandLabel.format("Nether", "Better Nether"));
        assertEquals("Overworld (WWOO)", BandLabel.format("Overworld", "WWOO"));
    }
}
