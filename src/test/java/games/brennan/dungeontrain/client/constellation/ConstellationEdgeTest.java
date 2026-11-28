package games.brennan.dungeontrain.client.constellation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstellationEdgeTest {

    @Test
    void softAndHardAreTheSkyRim() {
        assertTrue(ConstellationEdge.SOFT.soft());
        assertFalse(ConstellationEdge.HARD.soft());
    }

    @Test
    void softFeathersOnlyTheRim() {
        assertEquals(1f, ConstellationEdge.alphaAt(0f, true));
        assertEquals(1f, ConstellationEdge.alphaAt(ConstellationEdge.FEATHER_START, true));
        float mid = ConstellationEdge.alphaAt((1f + ConstellationEdge.FEATHER_START) / 2f, true);
        assertEquals(0.5f, mid, 1e-4f);
        assertEquals(0f, ConstellationEdge.alphaAt(1f, true));
    }

    @Test
    void hardIsFullToTheRimThenNothing() {
        assertEquals(1f, ConstellationEdge.alphaAt(0.999f, false));
        assertEquals(0f, ConstellationEdge.alphaAt(1f, false));
        assertEquals(0f, ConstellationEdge.alphaAt(1.5f, false));
    }

    @Test
    void parsesNamesAnyCase() {
        assertEquals(ConstellationEdge.HARD, ConstellationEdge.parse(" Hard "));
        assertNull(ConstellationEdge.parse("fuzzy"));
    }
}
