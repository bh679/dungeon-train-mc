package games.brennan.dungeontrain.client.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Pure-logic tests for {@link SnapshotRetention}: the in-memory + total caps that decide which
 * ride photos the gallery drops. No NeoForge bootstrap — the class takes plain {@code boolean[]}.
 * Lists are oldest → newest; a {@code true} flag means that photo is already flushed to disk.
 */
final class SnapshotRetentionTest {

    private static boolean[] mem(int n) {
        return new boolean[n]; // all false = all in memory
    }

    @Test
    @DisplayName("under both caps removes nothing")
    void underCaps() {
        assertArrayEquals(new int[]{}, SnapshotRetention.toRemove(mem(0), 12, 64));
        assertArrayEquals(new int[]{}, SnapshotRetention.toRemove(mem(12), 12, 64));
        assertArrayEquals(new int[]{}, SnapshotRetention.toRemove(mem(5), 12, 64));
    }

    @Test
    @DisplayName("with no disk offload, behaves like the old fixed ring (drop oldest over maxStored)")
    void allInMemory_dropsOldest() {
        // 13 in memory, cap 12 → drop the single oldest (index 0).
        assertArrayEquals(new int[]{0}, SnapshotRetention.toRemove(mem(13), 12, 64));
        // 15 in memory, cap 12 → drop the 3 oldest.
        assertArrayEquals(new int[]{0, 1, 2}, SnapshotRetention.toRemove(mem(15), 12, 64));
    }

    @Test
    @DisplayName("in-memory cap drops only the oldest UNflushed photo, never a flushed (disk) one")
    void inMemoryCap_skipsFlushed() {
        // [disk, disk, mem, mem, mem] — in-memory count 3, cap 2 → drop the oldest in-memory (index 2),
        // leaving the two flushed prefix photos (0,1) on disk untouched.
        boolean[] shots = {true, true, false, false, false};
        assertArrayEquals(new int[]{2}, SnapshotRetention.toRemove(shots, 2, 64));
    }

    @Test
    @DisplayName("total cap discards the oldest overall once memory + disk exceeds maxOnDisk")
    void totalCap_discardsOldest() {
        // 4 flushed + 2 in memory = 6 total, in-memory cap 4 (not hit), total cap 5 → discard oldest (index 0).
        boolean[] shots = {true, true, true, true, false, false};
        assertArrayEquals(new int[]{0}, SnapshotRetention.toRemove(shots, 4, 5));
    }

    @Test
    @DisplayName("maxOnDisk is clamped to at least maxInMemory so it can't undercut the memory cap")
    void totalCapClampedToInMemory() {
        // Misconfig: total cap 3 < in-memory cap 12. Clamp total to 12; 10 in memory → nothing removed.
        assertArrayEquals(new int[]{}, SnapshotRetention.toRemove(mem(10), 12, 3));
    }

    @Test
    @DisplayName("the newest photo is always retained")
    void neverDropsNewest() {
        int[] removed = SnapshotRetention.toRemove(mem(20), 12, 16);
        for (int idx : removed) {
            assert idx != 19 : "the newest (index 19) must never be removed";
        }
    }

    @Test
    @DisplayName("both caps together: drop oldest in-memory, then discard oldest overall")
    void bothCapsCombine() {
        // [disk x3, mem x3] = 6 total. inMemCap 2 → drop oldest in-memory (index 3). Remaining 5.
        // totalCap 4 → discard oldest overall (index 0). Result: {0, 3}.
        boolean[] shots = {true, true, true, false, false, false};
        assertArrayEquals(new int[]{0, 3}, SnapshotRetention.toRemove(shots, 2, 4));
    }
}
