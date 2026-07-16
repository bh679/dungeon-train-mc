package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GenProfiler} — the [gen.timing] accumulator. Covers the enabled/disabled gate,
 * bucket accumulation + reset, and the {@link GenProfiler.Sample} normalisation math (notably that
 * {@code dtTotalMs} excludes {@code CORE_REPLACE}, which is a sub-portion of {@code NETHER_FEATURE}).
 * No Minecraft types — the profiler is intentionally decoupled from the mod at load time.
 */
final class GenProfilerTest {

    @Test
    @DisplayName("disabled: t0() returns 0 and add()/countChunk() are no-ops")
    void disabledIsNoOp() {
        GenProfiler.setEnabled(true);
        GenProfiler.sampleAndReset();                 // clear any residue from other tests
        GenProfiler.setEnabled(false);

        long t0 = GenProfiler.t0();
        assertEquals(0L, t0);
        GenProfiler.add(GenProfiler.Bucket.DF, t0);   // no-op (start == 0)
        GenProfiler.countChunk();                     // no-op (disabled)

        GenProfiler.setEnabled(true);
        GenProfiler.Sample s = GenProfiler.sampleAndReset();
        GenProfiler.setEnabled(false);
        assertEquals(0L, s.chunks());
        assertEquals(0.0, s.ms(GenProfiler.Bucket.DF), 0.0);
    }

    @Test
    @DisplayName("enabled: buckets + chunk counter accumulate, then sampleAndReset() drains to zero")
    void accumulateAndReset() {
        GenProfiler.setEnabled(true);
        GenProfiler.sampleAndReset();                 // clean slate

        long t0 = GenProfiler.t0();
        assertTrue(t0 != 0L, "t0 should be a live nanoTime stamp when enabled");
        GenProfiler.add(GenProfiler.Bucket.DF, t0);
        GenProfiler.countChunk();
        GenProfiler.countChunk();

        GenProfiler.Sample s = GenProfiler.sampleAndReset();
        assertEquals(2L, s.chunks());
        assertTrue(s.ms(GenProfiler.Bucket.DF) >= 0.0);

        GenProfiler.Sample after = GenProfiler.sampleAndReset();   // reset happened → empty
        assertEquals(0L, after.chunks());
        assertEquals(0.0, after.ms(GenProfiler.Bucket.DF), 0.0);
        GenProfiler.setEnabled(false);
    }

    @Test
    @DisplayName("Sample math: dtTotalMs includes DISINTEGRATION but excludes CORE_REPLACE + EROSION")
    void sampleMathTotalBuckets() {
        long[] nanos = new long[GenProfiler.Bucket.values().length];
        nanos[GenProfiler.Bucket.DF.ordinal()] = 1_000_000;             // 1.0 ms
        nanos[GenProfiler.Bucket.NETHER_FEATURE.ordinal()] = 2_000_000; // 2.0 ms (includes core)
        nanos[GenProfiler.Bucket.CORE_REPLACE.ordinal()] = 5_000_000;   // 5.0 ms — sub-portion of nether, NOT re-added
        nanos[GenProfiler.Bucket.BIOME_FORCE.ordinal()] = 500_000;      // 0.5 ms
        nanos[GenProfiler.Bucket.DISINTEGRATION.ordinal()] = 3_000_000; // 3.0 ms — worker-thread, counted in total
        nanos[GenProfiler.Bucket.EROSION.ordinal()] = 7_000_000;        // 7.0 ms — main-thread, NOT in worker total
        GenProfiler.Sample s = new GenProfiler.Sample(4, nanos);

        // df + nether + biome + disint; core (sub of nether) and erosion (main-thread) excluded
        assertEquals(1.0 + 2.0 + 0.5 + 3.0, s.dtTotalMs(), 1e-9);
        assertEquals((1.0 + 2.0 + 0.5 + 3.0) / 4.0, s.dtTotalPerChunkMs(), 1e-9);
        assertEquals(5.0 / 4.0, s.perChunkMs(GenProfiler.Bucket.CORE_REPLACE), 1e-9);
        assertEquals(3.0 / 4.0, s.perChunkMs(GenProfiler.Bucket.DISINTEGRATION), 1e-9);
        assertEquals(7.0 / 4.0, s.perChunkMs(GenProfiler.Bucket.EROSION), 1e-9);
        assertEquals(0.0, s.perChunkMs(GenProfiler.Bucket.MIRROR_PRECOMPUTE), 1e-9);
    }

    @Test
    @DisplayName("Sample math: zero chunks → perChunk is 0, not a divide-by-zero")
    void sampleMathZeroChunks() {
        long[] nanos = new long[GenProfiler.Bucket.values().length];
        nanos[GenProfiler.Bucket.DF.ordinal()] = 9_000_000;
        GenProfiler.Sample s = new GenProfiler.Sample(0, nanos);
        assertEquals(0.0, s.perChunkMs(GenProfiler.Bucket.DF), 0.0);
        assertEquals(0.0, s.dtTotalPerChunkMs(), 0.0);
        assertEquals(9.0, s.ms(GenProfiler.Bucket.DF), 1e-9);          // raw total still reported
    }
}
