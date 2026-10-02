package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link SamplerPool} — one shared pool, auto-sized to a quarter of the cores. */
class SamplerPoolTest {

    @Test
    @DisplayName("auto gives a small server a single sampler thread")
    void autoSmallServer() {
        assertEquals(1, SamplerPool.resolveThreads(0, 1));
        assertEquals(1, SamplerPool.resolveThreads(0, 2));
        assertEquals(1, SamplerPool.resolveThreads(0, 4));
        assertEquals(1, SamplerPool.resolveThreads(0, 7));
    }

    @Test
    @DisplayName("auto scales with cores up to the old combined default of four")
    void autoScalesAndCaps() {
        assertEquals(2, SamplerPool.resolveThreads(0, 8));
        assertEquals(3, SamplerPool.resolveThreads(0, 12));
        assertEquals(4, SamplerPool.resolveThreads(0, 16));
        assertEquals(4, SamplerPool.resolveThreads(0, 64));
    }

    @Test
    @DisplayName("a configured count is used as-is")
    void configuredWins() {
        assertEquals(3, SamplerPool.resolveThreads(3, 2));
        assertEquals(8, SamplerPool.resolveThreads(8, 64));
    }
}
