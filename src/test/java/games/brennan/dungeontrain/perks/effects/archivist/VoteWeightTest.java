package games.brennan.dungeontrain.perks.effects.archivist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoteWeightTest {

    @Test
    void onePlusRank() {
        assertEquals(1, VoteWeight.weight(0));
        assertEquals(2, VoteWeight.weight(1));
        assertEquals(4, VoteWeight.weight(3));
    }

    @Test
    void negativeIsOneAndThereIsNoCap() {
        assertEquals(1, VoteWeight.weight(-5));
        assertEquals(501, VoteWeight.weight(500));
    }
}
