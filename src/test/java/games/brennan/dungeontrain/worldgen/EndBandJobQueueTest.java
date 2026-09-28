package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link EndBandJobQueue} — End-band samples go nearest player first, and abandoned ones are dropped. */
class EndBandJobQueueTest {

    private static EndBandJobQueue.Players playerAt(int cx, int cz) {
        return new EndBandJobQueue.Players(new int[] {cx}, new int[] {cz});
    }

    private static void add(EndBandJobQueue<String> q, int cx, int cz) {
        q.add(((long) cx << 32) ^ (cz & 0xFFFFFFFFL), cx, cz, cx + "," + cz);
    }

    @Test
    @DisplayName("a chunk beside the player is sampled before an earlier-queued prefetch chunk")
    void nearestFirst() {
        EndBandJobQueue<String> q = new EndBandJobQueue<>();
        add(q, 115, 0);   // prefetch strip, queued first
        add(q, 114, 3);
        add(q, 101, 2);   // beside the player, queued last
        q.setPlayers(playerAt(100, 0));
        List<String> dropped = new ArrayList<>();
        assertEquals("101,2", q.pollNearest(20, dropped));
        assertEquals("114,3", q.pollNearest(20, dropped));
        assertEquals("115,0", q.pollNearest(20, dropped));
        assertTrue(dropped.isEmpty());
    }

    @Test
    @DisplayName("jobs no player is near any more are dropped, not sampled")
    void dropsAbandoned() {
        EndBandJobQueue<String> q = new EndBandJobQueue<>();
        add(q, 0, 0);       // where the player was before a /dtp
        add(q, 3000, 1);    // where the player is now
        q.setPlayers(playerAt(3000, 0));
        List<String> dropped = new ArrayList<>();
        assertEquals("3000,1", q.pollNearest(18, dropped));
        assertEquals(List.of("0,0"), dropped);
        assertEquals(0, q.size());
    }

    @Test
    @DisplayName("with no players (headless forceload) jobs run oldest first and nothing is dropped")
    void noPlayersIsFifo() {
        EndBandJobQueue<String> q = new EndBandJobQueue<>();
        add(q, 900, 0);
        add(q, 1, 0);
        List<String> dropped = new ArrayList<>();
        assertEquals("900,0", q.pollNearest(18, dropped));
        assertEquals("1,0", q.pollNearest(18, dropped));
        assertNull(q.pollNearest(18, dropped));
        assertTrue(dropped.isEmpty());
    }

    @Test
    @DisplayName("distance is to the nearest of several players, in chunks")
    void nearestOfSeveralPlayers() {
        EndBandJobQueue.Players two = new EndBandJobQueue.Players(new int[] {0, 500}, new int[] {0, 0});
        assertEquals(3, two.distance(503, -2));
        assertEquals(4, two.distance(-4, 1));
        assertEquals(0, EndBandJobQueue.Players.NONE.distance(12, 34));
    }
}
