package games.brennan.dungeontrain.portal;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PortalTwinRandomTicksTest {

    @Test
    @DisplayName("range is vanilla's: chunk centre strictly within 128 blocks of the player")
    void rangeMatchesVanilla() {
        // Chunk 0's centre is block 8. A player at x=8 is 0 away; at 8+127.9 inside; at 8+128 out.
        assertTrue(PortalTwinRandomTicks.inPlayerRange(0, 0, 8.0, 8.0));
        assertTrue(PortalTwinRandomTicks.inPlayerRange(0, 0, 8.0 + 127.9, 8.0));
        assertFalse(PortalTwinRandomTicks.inPlayerRange(0, 0, 8.0 + 128.0, 8.0));
        // Diagonal: 90.6 on each axis is ~128.1 away, out; 90.5 is ~127.99, in.
        assertFalse(PortalTwinRandomTicks.inPlayerRange(0, 0, 8.0 + 90.6, 8.0 + 90.6));
        assertTrue(PortalTwinRandomTicks.inPlayerRange(0, 0, 8.0 + 90.5, 8.0 + 90.5));
        // Negative chunk coordinates: chunk -1's centre is block -8.
        assertTrue(PortalTwinRandomTicks.inPlayerRange(-1, -1, -8.0, -8.0));
        assertFalse(PortalTwinRandomTicks.inPlayerRange(-9, 0, 8.0, 8.0));
    }

    @Test
    @DisplayName("ticks only where vanilla would, but for the missing player")
    void gatesMirrorVanilla() {
        assertTrue(PortalTwinRandomTicks.shouldTick(true, true, true, false));
        // Vanilla already ticks it (somebody on the train overhead): never twice.
        assertFalse(PortalTwinRandomTicks.shouldTick(true, true, true, true));
        // Any other vanilla gate closed: stays closed.
        assertFalse(PortalTwinRandomTicks.shouldTick(false, true, true, false));
        assertFalse(PortalTwinRandomTicks.shouldTick(true, false, true, false));
        assertFalse(PortalTwinRandomTicks.shouldTick(true, true, false, false));
    }

    @Test
    @DisplayName("pass is off under /tick freeze, at randomTickSpeed 0, and with nobody held")
    void passRunsOnlyWhenVanillaWould() {
        assertTrue(PortalTwinRandomTicks.passRuns(true, 3, 1));
        assertFalse(PortalTwinRandomTicks.passRuns(false, 3, 1));
        assertFalse(PortalTwinRandomTicks.passRuns(true, 0, 1));
        assertFalse(PortalTwinRandomTicks.passRuns(true, 3, 0));
    }

    @Test
    @DisplayName("reach square: 19x19 around the player's chunk, reused until they change chunk")
    void reachIsCachedPerChunk() {
        PortalTwinRandomTicks.Reach a = PortalTwinRandomTicks.Reach.forChunk(null, 3, -2);
        assertEquals(361, a.chunks().length);
        assertSame(a, PortalTwinRandomTicks.Reach.forChunk(a, 3, -2));
        PortalTwinRandomTicks.Reach b = PortalTwinRandomTicks.Reach.forChunk(a, 4, -2);
        assertNotSame(a, b);
        assertEquals(4, b.cx());
        assertEquals(new ChunkPos(4 - 9, -2 - 9), b.chunks()[0]);
        assertEquals(new ChunkPos(4 + 9, -2 + 9), b.chunks()[360]);
    }

    @Test
    @DisplayName("cached square + per-tick range test picks exactly the chunks the old per-tick set did")
    void cachedReachMatchesFreshCompute() {
        // Inside one chunk the edge chunks change with the exact position, so both ends of chunk 0.
        double[][] positions = {{0.0, 0.0}, {15.9, 15.9}, {0.0, 15.9}, {7.5, 3.25}, {-0.1, -16.0}};
        PortalTwinRandomTicks.Reach cached = null;
        for (double[] p : positions) {
            int pcx = Math.floorDiv((int) Math.floor(p[0]), 16);
            int pcz = Math.floorDiv((int) Math.floor(p[1]), 16);
            cached = PortalTwinRandomTicks.Reach.forChunk(cached, pcx, pcz);
            Set<Long> viaCache = new HashSet<>();
            for (ChunkPos pos : cached.chunks()) {
                if (PortalTwinRandomTicks.inPlayerRange(pos.x, pos.z, p[0], p[1])) viaCache.add(pos.toLong());
            }
            Set<Long> fresh = new HashSet<>();
            for (int cx = pcx - 9; cx <= pcx + 9; cx++) {
                for (int cz = pcz - 9; cz <= pcz + 9; cz++) {
                    if (PortalTwinRandomTicks.inPlayerRange(cx, cz, p[0], p[1])) fresh.add(ChunkPos.asLong(cx, cz));
                }
            }
            assertEquals(fresh, viaCache, "at " + p[0] + "," + p[1]);
            assertFalse(fresh.isEmpty());
        }
    }
}
