package games.brennan.dungeontrain.client.live.deathrec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathRecRingTest {

    private static final int P = DeathRecRing.PACKET;

    /** {@code n} packets, each filled with its own number so the order can be checked. */
    private static byte[] packets(int first, int n) {
        byte[] b = new byte[n * P];
        for (int i = 0; i < n; i++) java.util.Arrays.fill(b, i * P, (i + 1) * P, (byte) (first + i));
        return b;
    }

    @Test
    void keepsOnlyTheWindowWhileAlive() {
        DeathRecRing ring = new DeathRecRing(9_000, 180_000);
        for (int s = 0; s <= 20; s++) ring.append(packets(s, 1), P, s * 1_000L);
        // At t=20 s, chunks older than 11 s are gone: 11..20 stay.
        byte[] all = ring.takeAndReset();
        assertEquals(10 * P, all.length);
        assertEquals(11, all[0]);
        assertEquals(20, all[all.length - 1]);
    }

    @Test
    void neverTrimsTheNewestChunk() {
        DeathRecRing ring = new DeathRecRing(1_000, 180_000);
        ring.append(packets(1, 2), 2 * P, 0);
        ring.append(packets(3, 1), P, 60_000); // a long gap: the old one goes, the new one stays
        assertEquals(P, ring.size());
    }

    @Test
    void holdKeepsThePreRollAndEverythingAfter() {
        DeathRecRing ring = new DeathRecRing(9_000, 180_000);
        for (int s = 0; s <= 20; s++) ring.append(packets(s, 1), P, s * 1_000L);
        ring.hold(20_000);
        assertTrue(ring.holding());
        for (int s = 21; s <= 60; s++) ring.append(packets(s, 1), P, s * 1_000L);
        byte[] all = ring.takeAndReset();
        assertEquals((60 - 11 + 1) * P, all.length, "nothing trimmed after the death");
        assertEquals(11, all[0]);
        assertFalse(ring.holding(), "taking resets for the next life");
        assertEquals(0, ring.size());
    }

    @Test
    void snapshotCopiesWithoutEndingTheHold() {
        DeathRecRing ring = new DeathRecRing(9_000, 180_000);
        ring.append(packets(1, 3), 3 * P, 0);
        ring.hold(0);
        byte[] early = ring.snapshot();
        ring.append(packets(4, 1), P, 1_000);
        assertEquals(3 * P, early.length);
        assertTrue(ring.holding(), "the full recording keeps going");
        assertEquals(4 * P, ring.takeAndReset().length);
    }

    @Test
    void capStopsGrowthButKeepsLaterPacketsAligned() {
        DeathRecRing ring = new DeathRecRing(9_000, 5_000);
        ring.append(packets(1, 1), P, 0);
        ring.hold(0);
        ring.append(packets(2, 1), P, 4_000);
        // Past the cap, and a read that ends mid-packet: dropped, but the split packet is still tracked.
        byte[] two = packets(3, 2);
        ring.append(two, P + 10, 6_000);
        assertEquals(2 * P, ring.size());
        byte[] held = ring.takeAndReset();
        assertEquals(2 * P, held.length);
        // The rest of packet 4 arrives after the reset: it completes one whole packet, never a half.
        ring.append(java.util.Arrays.copyOfRange(two, P + 10, 2 * P), P - 10, 7_000);
        assertEquals(P, ring.size());
        byte[] next = ring.takeAndReset();
        assertArrayEquals(java.util.Arrays.copyOfRange(two, P, 2 * P), next);
    }

    @Test
    void splitReadsAreJoinedIntoWholePackets() {
        DeathRecRing ring = new DeathRecRing(9_000, 180_000);
        byte[] three = packets(7, 3);
        ring.append(java.util.Arrays.copyOfRange(three, 0, 100), 100, 0);
        assertEquals(0, ring.size(), "half a packet is not held yet");
        ring.append(java.util.Arrays.copyOfRange(three, 100, 3 * P), 3 * P - 100, 1);
        assertArrayEquals(three, ring.takeAndReset());
    }
}
