package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.live.LivePillSlot.Bounds;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.live.LivePillSlot.GAP;
import static games.brennan.dungeontrain.client.live.LivePillSlot.bounds;
import static games.brennan.dungeontrain.client.live.LivePillSlot.shift;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The live pill owns the top-middle: a box over it moves down, one beside it stays put. */
class LivePillSlotTest {

    private static final Bounds PILL = new Bounds(100, 200, 18);

    @Test
    void thePillIsCentredAtTheTop() {
        Bounds b = bounds(400, 100);
        assertEquals(150, b.left());
        assertEquals(250, b.right());
        assertEquals(StreamNameTag.TOP_OFFSET + StreamNameTag.pillGuiHeight(), b.bottom());
    }

    @Test
    void noPillMovesNothing() {
        assertEquals(0, shift(120, 260, 1, null));
    }

    @Test
    void anOverlappingBoxMovesBelowThePill() {
        assertEquals(18 + GAP - 1, shift(120, 260, 1, PILL));
    }

    @Test
    void aBoxWiderThanThePillStillMoves() {
        assertEquals(18 + GAP, shift(0, 400, 0, PILL));
    }

    @Test
    void aBoxOffToTheSideStaysPut() {
        assertEquals(0, shift(0, 100, 1, PILL), "touching the left edge");
        assertEquals(0, shift(200, 300, 1, PILL), "touching the right edge");
    }

    @Test
    void aBoxAlreadyBelowStaysPut() {
        assertEquals(0, shift(120, 260, 40, PILL));
    }
}
