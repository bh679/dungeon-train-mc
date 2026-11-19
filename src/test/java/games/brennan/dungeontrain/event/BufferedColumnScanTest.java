package games.brennan.dungeontrain.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.brennan.dungeontrain.event.BufferedColumnScan.Found;
import games.brennan.dungeontrain.event.BufferedColumnScan.None;
import games.brennan.dungeontrain.event.BufferedColumnScan.Pending;
import games.brennan.dungeontrain.event.BufferedColumnScan.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

class BufferedColumnScanTest {

    private static final int BUFFER = 40;
    private static final int MAX_SCAN = 128;

    private static Result scan(int origin, BufferedColumnScan.ChunkReady ready, BufferedColumnScan.ColumnBuried buried) {
        return BufferedColumnScan.scan(origin, BUFFER, MAX_SCAN, ready, buried);
    }

    @Test
    void allClearFindsTheMiddleOfTheFirstRunAhead() {
        assertEquals(new Found(BUFFER), scan(0, cx -> true, x -> false));
    }

    @Test
    void aTunnelRestartsTheRun() {
        assertEquals(new Found(20 + BUFFER), scan(0, cx -> true, x -> x < 20));
    }

    @Test
    void aShortClearGapBetweenTunnelsDoesNotCount() {
        // 5..9 is clear but far short of the 81 needed, so the run that counts starts at 20.
        assertEquals(new Found(20 + BUFFER), scan(0, cx -> true, x -> x < 5 || (x >= 10 && x < 20)));
    }

    @Test
    void fallsBackToBehindWhenAheadIsBuried() {
        assertEquals(new Found(-1 - BUFFER), scan(0, cx -> true, x -> x >= 0));
    }

    @Test
    void buriedBothWaysIsNone() {
        assertEquals(new None(), scan(0, cx -> true, x -> true));
    }

    @Test
    void aMissingChunkAheadIsPendingWithTheRestOfTheWindow() {
        // Window is block 0..128, so chunks 0..8. Only chunk 0 is ready, so the walk stops at block 16.
        Result r = scan(0, cx -> cx == 0, x -> x < 8);
        assertEquals(new Pending(List.of(1, 2, 3, 4, 5, 6, 7, 8)), r);
    }

    @Test
    void readyChunksInsideTheWindowAreNotRequestedAgain() {
        assertEquals(new Pending(List.of(1, 3, 4, 5, 6, 7, 8)), scan(0, cx -> cx == 0 || cx == 2, x -> x < 8));
    }

    @Test
    void chunksBehindAreNotRequestedWhileAheadIsPending() {
        Result r = scan(0, cx -> cx == 0 || cx < 0, x -> x < 8);
        assertTrue(r instanceof Pending p && p.chunkXs().stream().allMatch(cx -> cx > 0), "got " + r);
    }

    @Test
    void chunksBehindAreRequestedOnceAheadHasNoWindow() {
        // All of +X is ready and buried. -X window is block -1..-128: chunks -1..-8.
        Result r = scan(0, cx -> cx >= 0, x -> x >= 0);
        assertEquals(new Pending(List.of(-1, -2, -3, -4, -5, -6, -7, -8)), r);
    }

    @Test
    void aWindowInsideReadyChunksNeedsNothingFurtherLoaded() {
        // Run of 81 from block 0 ends at 80, chunk 5. Chunks past it are never asked for.
        assertEquals(new Found(BUFFER), scan(0, cx -> cx <= 5, x -> false));
    }

    @Test
    void anUnreadyColumnIsNeverRead() {
        scan(0, cx -> cx == 0, x -> {
            assertTrue(x >> 4 == 0, "read unready column " + x);
            return false;
        });
    }

    @Test
    void theAnswerOnceChunksArriveMatchesAFullyLoadedWalk() {
        BufferedColumnScan.ColumnBuried buried = x -> x < 30;
        assertTrue(scan(0, cx -> cx <= 1, buried) instanceof Pending);
        assertEquals(scan(0, cx -> true, buried), scan(0, cx -> cx <= 8, buried));
    }

    @Test
    void fallbackPicksTheNearestClearReadyColumn() {
        // Origin and the rest of chunk 0 are buried and chunk 1 isn't ready, so the first readable clear is behind.
        assertEquals(-1, BufferedColumnScan.firstClearReadyColumn(0, MAX_SCAN, cx -> cx <= 0, x -> x >= 0));
    }

    @Test
    void fallbackPrefersOriginThenAhead() {
        assertEquals(0, BufferedColumnScan.firstClearReadyColumn(0, MAX_SCAN, cx -> true, x -> false));
        assertEquals(5, BufferedColumnScan.firstClearReadyColumn(0, MAX_SCAN, cx -> true, x -> x < 5 && x > -50));
    }

    @Test
    void fallbackWithNothingReadyIsOriginAndReadsNothing() {
        assertEquals(7, BufferedColumnScan.firstClearReadyColumn(7, MAX_SCAN, cx -> false, x -> {
            throw new AssertionError("read column " + x);
        }));
    }
}
