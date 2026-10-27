package games.brennan.dungeontrain.client.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointsGraphTest {

    @Test
    @DisplayName("a few blocks share the width")
    void blocksShareWidth() {
        PointsGraph.Layout l = new PointsGraph(1, 2, 1, 3).layout(43);
        assertFalse(l.bar());
        assertEquals(4, l.segments());
        assertEquals(10, l.segmentWidth());
        assertFalse(l.plus());
    }

    @Test
    @DisplayName("past twenty blocks: twenty and a plus")
    void twentyAndPlus() {
        PointsGraph.Layout l = new PointsGraph(5, 30, 1, 35).layout(300);
        assertFalse(l.bar());
        assertEquals(PointsGraph.MAX_SEGMENTS, l.segments());
        assertTrue(l.plus());
    }

    @Test
    @DisplayName("twenty filled, or blocks too thin: a progress bar")
    void barWhenFullOrThin() {
        assertTrue(new PointsGraph(20, 5, 0, 25).layout(300).bar());
        assertTrue(new PointsGraph(2, 15, 0, 17).layout(40).bar());
        assertFalse(new PointsGraph(19, 1, 0, 20).layout(300).bar());
    }

    @Test
    @DisplayName("clicks land on the block under them, not the gaps")
    void hits() {
        PointsGraph g = new PointsGraph(1, 2, 1, 3); // 10 px blocks, 1 px gaps
        assertEquals(PointsGraph.Kind.FILLED, g.at(43, 4));
        assertNull(g.at(43, 10.5));
        assertEquals(PointsGraph.Kind.OPEN, g.at(43, 12));
        assertEquals(PointsGraph.Kind.OPEN, g.at(43, 25));
        assertEquals(PointsGraph.Kind.LOCKED, g.at(43, 38));
        assertNull(g.at(43, 60));
        assertNull(new PointsGraph(20, 5, 0, 25).at(300, 5), "a bar has no blocks");
    }
}
