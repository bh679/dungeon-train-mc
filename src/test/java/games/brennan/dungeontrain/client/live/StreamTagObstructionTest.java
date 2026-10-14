package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.live.StreamTagObstruction.Box;
import games.brennan.dungeontrain.client.live.StreamTagObstruction.Placement;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.live.StreamTagObstruction.FRESH_MS;
import static games.brennan.dungeontrain.client.live.StreamTagObstruction.GAP;
import static games.brennan.dungeontrain.client.live.StreamTagObstruction.LINGER_MS;
import static games.brennan.dungeontrain.client.live.StreamTagObstruction.RISE_PX_PER_MS;
import static games.brennan.dungeontrain.client.live.StreamTagObstruction.next;
import static games.brennan.dungeontrain.client.live.StreamTagObstruction.target;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The stream's name pill drops below Jade's box, stays put briefly, then eases back. */
class StreamTagObstructionTest {

    private static final int BASE = 4;

    @Test
    void noBoxKeepsThePillAtTheTop() {
        assertEquals(BASE, target(null, 100, 200, BASE, 1000));
    }

    @Test
    void anOverlappingBoxPushesThePillBelowIt() {
        assertEquals(40 + GAP, target(new Box(120, 260, 40, 1000), 100, 200, BASE, 1000));
    }

    @Test
    void aBoxOffToTheSideLeavesThePillAlone() {
        assertEquals(BASE, target(new Box(0, 90, 40, 1000), 100, 200, BASE, 1000));
        assertEquals(BASE, target(new Box(210, 300, 40, 1000), 100, 200, BASE, 1000));
    }

    @Test
    void aStaleBoxIsIgnored() {
        assertEquals(BASE, target(new Box(120, 260, 40, 1000), 100, 200, BASE, 1000 + FRESH_MS + 1));
    }

    @Test
    void aShortBoxNeverLiftsThePillAboveItsBase() {
        assertEquals(BASE, target(new Box(120, 260, -10, 1000), 100, 200, BASE, 1000));
    }

    @Test
    void dropsAtOnce() {
        Placement p = next(new Placement(BASE, 0, 0), 43, 1000);
        assertEquals(43, p.top());
        assertEquals(1000 + LINGER_MS, p.holdUntilMs());
    }

    @Test
    void holdsWhileLingering() {
        Placement down = next(null, 43, 1000);
        assertEquals(43, next(down, BASE, 1000 + LINGER_MS - 1).top());
    }

    @Test
    void easesBackUpAfterLingering() {
        Placement down = next(null, 43, 1000);
        long t = 1000 + LINGER_MS - 1;
        Placement held = next(down, BASE, t);
        Placement rising = next(held, BASE, t + 50);
        assertEquals(43, held.top());
        assertEquals(43 - 50 * RISE_PX_PER_MS, rising.top(), 1e-4);
        Placement settled = next(rising, BASE, t + 100_000);
        for (int i = 0; i < 100; i++) settled = next(settled, BASE, settled.atMs() + 100);
        assertEquals(BASE, settled.top());
    }

    @Test
    void comingBackMidRiseDropsAgainAndRestartsTheHold() {
        Placement rising = new Placement(20, 0, 5000);
        Placement p = next(rising, 43, 5010);
        assertEquals(43, p.top());
        assertEquals(5010 + LINGER_MS, p.holdUntilMs());
    }
}
