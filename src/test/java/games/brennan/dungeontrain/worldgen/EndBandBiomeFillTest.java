package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The End-band column memo and its flatness probe, over plain lambdas — no registry, no world. */
class EndBandBiomeFillTest {

    /** A "biome" that depends only on the column. */
    private static String flat(int qx, int qy, int qz) {
        return "b" + ((qx * 31 + qz * 17) & 7);
    }

    @Test
    @DisplayName("a column-only source is flat; one that reads Y is not")
    void probeTellsFlatFromNot() {
        assertTrue(EndBandBiomeFill.isFlat(EndBandBiomeFillTest::flat, 4242L, 1000, -40));
        assertFalse(EndBandBiomeFill.isFlat((qx, qy, qz) -> flat(qx, qy, qz) + (qy >= 16 ? "+" : ""), 4242L, 1000, -40));
        // A source that varies with Y only in one row still fails.
        assertFalse(EndBandBiomeFill.isFlat((qx, qy, qz) -> qy == 31 ? "top" : flat(qx, qy, qz), 4242L, 1000, -40));
    }

    @Test
    @DisplayName("the probe is deterministic in its seed and never asks outside its window")
    void probeIsDeterministicAndBounded() {
        AtomicInteger calls = new AtomicInteger();
        int[] minQx = {Integer.MAX_VALUE}, maxQx = {Integer.MIN_VALUE};
        EndBandBiomeFill.Lookup<String> counting = (qx, qy, qz) -> {
            calls.incrementAndGet();
            minQx[0] = Math.min(minQx[0], qx);
            maxQx[0] = Math.max(maxQx[0], qx);
            return flat(qx, qy, qz);
        };
        assertTrue(EndBandBiomeFill.isFlat(counting, 7L, 0, 0));
        assertEquals(EndBandBiomeFill.PROBE_COLUMNS * EndBandBiomeFill.PROBE_QUART_YS.length, calls.get());
        assertTrue(minQx[0] >= -EndBandBiomeFill.PROBE_RADIUS_QUARTS && maxQx[0] <= EndBandBiomeFill.PROBE_RADIUS_QUARTS);
        int first = calls.get();
        EndBandBiomeFill.isFlat(counting, 7L, 0, 0);
        assertEquals(first * 2, calls.get(), "same seed, same number of reads");
    }

    @Test
    @DisplayName("the memo answers from the source once per column, whatever the height asked")
    void memoAsksEachColumnOnce() {
        AtomicInteger calls = new AtomicInteger();
        EndBandBiomeFill.ColumnMemo<String> memo = new EndBandBiomeFill.ColumnMemo<>((qx, qy, qz) -> {
            calls.incrementAndGet();
            return flat(qx, qy, qz);
        });
        // A 16x16-quart chunk column stack, 64 quart rows: 1024 asks, 256 columns.
        for (int qy = 0; qy < 64; qy++) {
            for (int qx = 0; qx < 16; qx++) {
                for (int qz = 0; qz < 16; qz++) {
                    assertEquals(flat(qx, qy, qz), memo.at(qx, qy, qz));
                }
            }
        }
        assertEquals(256, calls.get());
        assertEquals(256, memo.misses());
        // Columns outside the chunk (the surface rules' fuzzed lookups) are memoised too.
        memo.at(-1, 5, 3);
        memo.at(-1, 9, 3);
        assertEquals(257, calls.get());
    }

    @Test
    @DisplayName("negative quart coordinates do not collide in the memo key")
    void memoKeysNegativeColumns() {
        EndBandBiomeFill.ColumnMemo<String> memo = new EndBandBiomeFill.ColumnMemo<>((qx, qy, qz) -> qx + "," + qz);
        assertEquals("-1,0", memo.at(-1, 0, 0));
        assertEquals("0,-1", memo.at(0, 0, -1));
        assertEquals("-1,-1", memo.at(-1, 3, -1));
        assertEquals("1,1", memo.at(1, 3, 1));
        assertEquals(4, memo.misses());
    }
}
