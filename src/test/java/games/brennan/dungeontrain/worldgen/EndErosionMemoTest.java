package games.brennan.dungeontrain.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The End erosion memo: exact by (x, z), blind to y, bounded, and only ever wrapped around the End-islands function. */
class EndErosionMemoTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A density function that counts its evaluations and answers x + z. */
    private static final class Counting implements DensityFunction.SimpleFunction {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public double compute(FunctionContext context) {
            calls.incrementAndGet();
            return context.blockX() + context.blockZ();
        }

        @Override
        public double minValue() {
            return -1.0;
        }

        @Override
        public double maxValue() {
            return 1.0;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.zero().codec();
        }
    }

    @Test
    @DisplayName("the same column is computed once whatever y is asked")
    void memoisesByColumn() {
        Counting inner = new Counting();
        EndErosionMemo memo = new EndErosionMemo(inner);
        assertEquals(12.0, memo.compute(new DensityFunction.SinglePointContext(5, 0, 7)));
        assertEquals(12.0, memo.compute(new DensityFunction.SinglePointContext(5, 200, 7)));
        assertEquals(12.0, memo.compute(new DensityFunction.SinglePointContext(5, -40, 7)));
        assertEquals(1, inner.calls.get());
        assertEquals(13.0, memo.compute(new DensityFunction.SinglePointContext(6, 0, 7)));
        assertEquals(2, inner.calls.get());
        assertEquals(inner.minValue(), memo.minValue());
        assertEquals(inner.maxValue(), memo.maxValue());
    }

    @Test
    @DisplayName("past its cap the memo starts over rather than growing")
    void bounded() {
        Counting inner = new Counting();
        EndErosionMemo memo = new EndErosionMemo(inner, 2);
        memo.compute(new DensityFunction.SinglePointContext(1, 0, 0));
        memo.compute(new DensityFunction.SinglePointContext(2, 0, 0));
        assertEquals(2, memo.size());
        memo.compute(new DensityFunction.SinglePointContext(3, 0, 0));
        assertEquals(1, memo.size(), "the third column drops the first two");
        memo.compute(new DensityFunction.SinglePointContext(1, 0, 0));
        assertEquals(4, inner.calls.get(), "column 1 is computed again after the reset");
    }

    @Test
    @DisplayName("only vanilla's End-islands erosion is wrapped; anything else is handed back untouched")
    void wrapsOnlyEndIslands() {
        DensityFunction islands = DensityFunctions.endIslands(1L);
        assertTrue(EndErosionMemo.isEndIslands(islands));
        DensityFunction wrapped = EndErosionMemo.wrap(islands);
        assertNotSame(islands, wrapped);
        assertTrue(wrapped instanceof EndErosionMemo);
        assertSame(wrapped, EndErosionMemo.wrap(wrapped), "wrapping twice is a no-op");

        Counting other = new Counting();
        assertFalse(EndErosionMemo.isEndIslands(other));
        assertSame(other, EndErosionMemo.wrap(other));
        DensityFunction zero = DensityFunctions.zero();
        assertSame(zero, EndErosionMemo.wrap(zero));
    }

    @Test
    @DisplayName("the memoised End erosion answers exactly what the real function does")
    void exact() {
        DensityFunction islands = DensityFunctions.endIslands(8675309031337L);
        DensityFunction memo = EndErosionMemo.wrap(islands);
        for (int i = 0; i < 6; i++) {
            DensityFunction.SinglePointContext at = new DensityFunction.SinglePointContext(1000 * i + 40, 60, -300 * i);
            assertEquals(islands.compute(at), memo.compute(at));
            assertEquals(islands.compute(at), memo.compute(new DensityFunction.SinglePointContext(at.blockX(), 0, at.blockZ())));
        }
    }
}
