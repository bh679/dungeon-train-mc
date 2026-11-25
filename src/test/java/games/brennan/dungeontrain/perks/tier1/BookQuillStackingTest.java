package games.brennan.dungeontrain.perks.tier1;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The once-a-second book sweep: never walks the inventory while perks are off. */
class BookQuillStackingTest {

    @Test
    void perksOffNeverScans() {
        AtomicInteger scans = new AtomicInteger();
        assertFalse(BookQuillStacking.sweepDue(false, 20, () -> 3, () -> scans.incrementAndGet() > 0));
        assertFalse(BookQuillStacking.sweepDue(false, 40, () -> { scans.incrementAndGet(); return 0; }, () -> true));
        assertEquals(0, scans.get());
    }

    @Test
    void onlyOnceASecond() {
        assertFalse(BookQuillStacking.sweepDue(true, 21, () -> 3, () -> true));
        assertTrue(BookQuillStacking.sweepDue(true, 40, () -> 3, () -> false));
    }

    @Test
    void withThePerkItSweepsWithoutTheStampScan() {
        AtomicInteger scans = new AtomicInteger();
        assertTrue(BookQuillStacking.sweepDue(true, 20, () -> 1, () -> scans.incrementAndGet() > 0));
        assertEquals(0, scans.get());
    }

    @Test
    void withoutThePerkAStampedBookStillGetsStripped() {
        assertTrue(BookQuillStacking.sweepDue(true, 20, () -> 0, () -> true));
        assertFalse(BookQuillStacking.sweepDue(true, 20, () -> 0, () -> false));
    }

    @Test
    void stackSizeFollowsRank() {
        assertEquals(1, BookQuillStacking.stackSizeFor(0));
        assertEquals(4, BookQuillStacking.stackSizeFor(3));
        assertEquals(BookQuillStacking.MAX_STACK, BookQuillStacking.stackSizeFor(500));
    }
}
