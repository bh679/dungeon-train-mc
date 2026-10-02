package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("a sampler token's poll hands dropped jobs on and returns null once the queue is empty")
    void pollHandsOnDropsAndNeverBlocks() {
        EndBandJobQueue<String> q = new EndBandJobQueue<>();
        add(q, 0, 0);
        add(q, 3000, 1);
        q.setPlayers(playerAt(3000, 0));
        List<String> dropped = new ArrayList<>();
        assertEquals("3000,1", q.poll(18, dropped::add));
        assertEquals(List.of("0,0"), dropped);
        assertNull(q.poll(18, dropped::add));      // a spare token (job replaced or dropped) does nothing
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
    @DisplayName("a job nothing would re-request is never dropped for being far from every player")
    void mustKeepSurvivesKeepRadius() {
        EndBandJobQueue<String> q = new EndBandJobQueue<>();
        q.offer("sphere", 0, 0, "sphere", false, evicted -> { });
        add(q, 1, 0);
        q.setPlayers(playerAt(3000, 0));
        List<String> dropped = new ArrayList<>();
        assertEquals("sphere", q.pollNearest(18, dropped));
        assertEquals(List.of("1,0"), dropped);
    }

    @Test
    @DisplayName("a full queue makes room by evicting the droppable job furthest from every player")
    void fullQueueEvictsFurthestDroppable() {
        EndBandJobQueue<String> q = new EndBandJobQueue<>(3);
        q.setPlayers(playerAt(0, 0));
        List<String> evicted = new ArrayList<>();
        q.offer("keep", 900, 0, "keep", false, evicted::add);
        q.offer("far", 40, 0, "far", true, evicted::add);
        q.offer("near", 2, 0, "near", true, evicted::add);

        assertFalse(q.offer("further", 41, 0, "further", true, evicted::add), "further than what it would evict");
        assertTrue(q.offer("nearer", 9, 0, "nearer", true, evicted::add));
        assertEquals(List.of("far"), evicted);

        assertTrue(q.offer("keep2", 950, 0, "keep2", false, evicted::add), "a must-keep job always takes a droppable one's place");
        assertEquals(List.of("far", "nearer"), evicted);
        assertTrue(q.offer("keep3", 960, 0, "keep3", false, evicted::add));
        assertEquals(List.of("far", "nearer", "near"), evicted);

        assertFalse(q.offer("keep4", 1, 0, "keep4", false, evicted::add), "nothing droppable left: refused, nothing lost");
        assertFalse(q.offer("drop", 1, 0, "drop", true, evicted::add));
        assertEquals(3, q.size());
        assertEquals(List.of("far", "nearer", "near"), evicted);
    }

    @Test
    @DisplayName("re-queuing a waiting job in a full queue replaces it without evicting anything")
    void replaceInFullQueue() {
        EndBandJobQueue<String> q = new EndBandJobQueue<>(2);
        List<String> evicted = new ArrayList<>();
        q.offer("a", 0, 0, "a1", true, evicted::add);
        q.offer("b", 1, 0, "b1", true, evicted::add);
        assertTrue(q.offer("a", 0, 0, "a2", true, evicted::add));
        assertTrue(evicted.isEmpty());
        assertEquals("a2", q.pollNearest(18, new ArrayList<>()));
    }

    @Test
    @DisplayName("with no players a full queue keeps its older jobs and turns the newcomer away")
    void fullQueueNoPlayersKeepsOldest() {
        EndBandJobQueue<String> q = new EndBandJobQueue<>(2);
        List<String> evicted = new ArrayList<>();
        q.offer("a", 0, 0, "a", true, evicted::add);
        q.offer("b", 500, 0, "b", true, evicted::add);
        assertFalse(q.offer("c", 1, 0, "c", true, evicted::add));
        assertTrue(q.offer("keep", 1, 0, "keep", false, evicted::add));
        assertEquals(List.of("b"), evicted, "the newest droppable job gives way");
    }

    @Test
    @DisplayName("removeIf drops the matching jobs, droppable or not, and hands them on")
    void removeIfDropsMatches() {
        EndBandJobQueue<String> q = new EndBandJobQueue<>();
        q.offer("s1", 7, 3, "sphere-7,3", false, evicted -> { });
        q.offer("s2", 8, 3, "sphere-8,3", false, evicted -> { });
        add(q, 7, 3);
        List<String> removed = new ArrayList<>();
        q.removeIf(job -> job.startsWith("sphere-7"), removed::add);
        assertEquals(List.of("sphere-7,3"), removed);
        assertEquals(2, q.size());
        q.clear(removed::add);
        assertEquals(List.of("sphere-7,3", "sphere-8,3", "7,3"), removed);
        assertEquals(0, q.size());
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
