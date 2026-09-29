package games.brennan.dungeontrain.event;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SweepSampleLog#top} — which carriage groups a {@code [sweep.sample]} line names. */
class SweepSampleLogTest {

    private static SweepSampleLog.Tally tally(int pIdx, long cells) {
        SweepSampleLog.Sample s = new SweepSampleLog.Sample(pIdx, 1, "minecraft:stone",
            BlockPos.ZERO, BlockPos.ZERO, 13, 7, 9);
        return new SweepSampleLog.Tally(s, cells);
    }

    private static List<Integer> pIdxs(List<SweepSampleLog.Tally> tallies) {
        return tallies.stream().map(t -> t.sample().pIdx()).toList();
    }

    @Test
    void ranksByCellsDescending() {
        List<SweepSampleLog.Tally> in = List.of(tally(1, 5), tally(2, 900), tally(3, 40), tally(4, 1));
        assertEquals(List.of(2, 3, 1), pIdxs(SweepSampleLog.top(in, 3)));
    }

    @Test
    void breaksTiesByPIdxSoTheLineIsStable() {
        List<SweepSampleLog.Tally> in = List.of(tally(9, 10), tally(3, 10), tally(6, 10));
        assertEquals(List.of(3, 6), pIdxs(SweepSampleLog.top(in, 2)));
    }

    @Test
    void fewerThanNReturnsAll() {
        assertEquals(List.of(7), pIdxs(SweepSampleLog.top(List.of(tally(7, 2)), 3)));
    }

    @Test
    void nonPositiveNReturnsNothing() {
        assertTrue(SweepSampleLog.top(List.of(tally(7, 2)), 0).isEmpty());
        assertTrue(SweepSampleLog.top(List.of(tally(7, 2)), -1).isEmpty());
    }
}
