package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LivePboRingTest {

    @Test
    void slotsAreIssuedUntilTheRingIsFull() {
        LivePboRing ring = new LivePboRing(3);
        assertEquals(0, ring.issue());
        assertEquals(1, ring.issue());
        assertEquals(2, ring.issue());
        assertEquals(-1, ring.issue(), "a full ring skips the grab instead of waiting on the GPU");
    }

    @Test
    void collectReturnsTheNewestFinishedReadAndFreesTheOlderOnes() {
        LivePboRing ring = new LivePboRing(3);
        ring.issue();
        ring.issue();
        ring.issue();
        List<Integer> released = new ArrayList<>();
        int newest = ring.collect(Set.of(0, 1)::contains, released::add);
        assertEquals(1, newest);
        assertEquals(List.of(0, 1), released);
        assertEquals(0, ring.issue(), "freed slots are reused");
    }

    @Test
    void collectStopsAtTheFirstUnfinishedRead() {
        LivePboRing ring = new LivePboRing(3);
        ring.issue();
        ring.issue();
        // Fences complete in order: a later one cannot be collected past an unfinished earlier one.
        assertEquals(-1, ring.collect(slot -> slot == 1, slot -> { }));
        assertEquals(2, ring.issue());
    }

    @Test
    void collectFollowsIssueOrderAcrossWrapAround() {
        LivePboRing ring = new LivePboRing(2);
        ring.issue();
        ring.issue();
        ring.collect(slot -> slot == 0, slot -> { });
        assertEquals(0, ring.issue());
        List<Integer> released = new ArrayList<>();
        assertEquals(0, ring.collect(slot -> true, released::add));
        assertEquals(List.of(1, 0), released, "slot 1 was issued before the reused slot 0");
    }

    @Test
    void drainFreesEverything() {
        LivePboRing ring = new LivePboRing(2);
        ring.issue();
        ring.issue();
        List<Integer> released = new ArrayList<>();
        ring.drain(released::add);
        assertEquals(List.of(0, 1), released);
        assertEquals(-1, ring.collect(slot -> true, slot -> { }));
    }
}
