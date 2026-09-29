package games.brennan.dungeontrain.ship.sable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static games.brennan.dungeontrain.ship.sable.RecentFullSyncTracker.ORDERED_SNAPSHOT_WINDOW_TICKS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RecentFullSyncTrackerTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @BeforeEach
    @AfterEach
    void reset() {
        RecentFullSyncTracker.clear();
    }

    @Test
    void isWithinWindow_coversSyncTickThroughWindowEnd() {
        assertTrue(RecentFullSyncTracker.isWithinWindow(100, 100, 10));
        assertTrue(RecentFullSyncTracker.isWithinWindow(110, 100, 10));
        assertFalse(RecentFullSyncTracker.isWithinWindow(111, 100, 10));
    }

    @Test
    void isWithinWindow_futureSyncTickIsOutside() {
        assertFalse(RecentFullSyncTracker.isWithinWindow(5, 5000, 10));
    }

    @Test
    void neverSyncedPlayerKeepsUdp() {
        assertFalse(RecentFullSyncTracker.needsOrderedSnapshots(ALICE, 100));
    }

    @Test
    void syncedPlayerIsOrderedForTheWindowThenReleased() {
        RecentFullSyncTracker.recordFullSync(ALICE, 100);

        assertTrue(RecentFullSyncTracker.needsOrderedSnapshots(ALICE, 100));
        assertTrue(RecentFullSyncTracker.needsOrderedSnapshots(ALICE, 100 + ORDERED_SNAPSHOT_WINDOW_TICKS));
        assertFalse(RecentFullSyncTracker.needsOrderedSnapshots(ALICE, 101 + ORDERED_SNAPSHOT_WINDOW_TICKS));
        assertFalse(RecentFullSyncTracker.needsOrderedSnapshots(BOB, 100));
    }

    @Test
    void laterSyncExtendsTheWindow() {
        RecentFullSyncTracker.recordFullSync(ALICE, 100);
        RecentFullSyncTracker.recordFullSync(ALICE, 108);

        assertTrue(RecentFullSyncTracker.needsOrderedSnapshots(ALICE, 108 + ORDERED_SNAPSHOT_WINDOW_TICKS));
    }

    @Test
    void staleEntryFromAPreviousServerDoesNotPinThePlayer() {
        RecentFullSyncTracker.recordFullSync(ALICE, 90_000);

        assertFalse(RecentFullSyncTracker.needsOrderedSnapshots(ALICE, 3));
    }
}
