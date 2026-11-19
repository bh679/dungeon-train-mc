package games.brennan.dungeontrain.event;

import java.util.ArrayList;
import java.util.List;

/**
 * Walk along the track looking for a clear stretch: {@code 2 * buffer + 1} contiguous non-tunnel
 * columns, +X first, then -X. The answer picks where a joining player's cinematic camera starts.
 *
 * <p>The walk asks whether each column's chunk is ready before reading it, and stops with
 * {@link Pending} at the first one that isn't. So the login retry loop can request the chunks and
 * come back next tick rather than generating them inside the tick, which held the server for up to
 * 40 s in player logs (see {@link ChunkColumnCache} for why only {@code getChunkNow} can tell).
 * Reading past an unloaded chunk isn't an option: {@code TunnelGenerator.isColumnUnderground}
 * calls an unloaded column clear, which would corrupt the run count.</p>
 *
 * <p>Pure (no Minecraft types) so it is unit-testable; see {@code BufferedColumnScanTest}.</p>
 */
final class BufferedColumnScan {

    /** Whether chunk column {@code chunkX} on the track row can be read without blocking. */
    @FunctionalInterface
    interface ChunkReady {
        boolean test(int chunkX);
    }

    /** Whether block column {@code x} is buried. Only called once its chunk is ready. */
    @FunctionalInterface
    interface ColumnBuried {
        boolean test(int x);
    }

    sealed interface Result permits Found, None, Pending {}

    /** The middle of the first clear stretch. */
    record Found(int x) implements Result {}

    /** Every column in range was read and no stretch is long enough. */
    record None() implements Result {}

    /**
     * The walk reached a chunk that isn't ready. Lists it and the unready chunks after it in the
     * same direction, so they are all requested at once instead of one per retry.
     */
    record Pending(List<Integer> chunkXs) implements Result {}

    private BufferedColumnScan() {}

    /**
     * Run the walk. +X must finish without a stretch before -X is looked at, so a stretch ahead of
     * the train never causes chunks behind it to be requested.
     */
    static Result scan(int originX, int buffer, int maxScan, ChunkReady ready, ColumnBuried buried) {
        Result forward = scanDir(originX, buffer, maxScan, +1, ready, buried);
        if (!(forward instanceof None)) return forward;
        return scanDir(originX, buffer, maxScan, -1, ready, buried);
    }

    private static Result scanDir(
        int originX, int buffer, int maxScan, int dir, ChunkReady ready, ColumnBuried buried
    ) {
        int needed = 2 * buffer + 1;
        int consecutive = 0;
        int runStart = 0;
        int dxStart = (dir > 0) ? 0 : 1;
        for (int dx = dxStart; dx <= maxScan; dx++) {
            int x = originX + dir * dx;
            if (!ready.test(x >> 4)) {
                return new Pending(unreadyChunks(x, originX + dir * maxScan, dir, ready));
            }
            if (!buried.test(x)) {
                if (consecutive == 0) runStart = x;
                consecutive++;
                if (consecutive >= needed) return new Found(runStart + dir * buffer);
            } else {
                consecutive = 0;
            }
        }
        return new None();
    }

    /** The unready chunks from block column {@code fromX} to {@code toX} inclusive, walk order. */
    private static List<Integer> unreadyChunks(int fromX, int toX, int dir, ChunkReady ready) {
        List<Integer> out = new ArrayList<>();
        int endChunk = toX >> 4;
        for (int cx = fromX >> 4; dir > 0 ? cx <= endChunk : cx >= endChunk; cx += dir) {
            if (!ready.test(cx)) out.add(cx);
        }
        return out;
    }

    /**
     * The nearest clear column among those already readable: origin, then +X, then -X. Used once
     * the wait for {@link Pending} chunks runs out. Returns {@code originX} if none is clear.
     */
    static int firstClearReadyColumn(int originX, int maxScan, ChunkReady ready, ColumnBuried buried) {
        if (isClear(originX, ready, buried)) return originX;
        for (int dx = 1; dx <= maxScan; dx++) {
            if (isClear(originX + dx, ready, buried)) return originX + dx;
        }
        for (int dx = 1; dx <= maxScan; dx++) {
            if (isClear(originX - dx, ready, buried)) return originX - dx;
        }
        return originX;
    }

    private static boolean isClear(int x, ChunkReady ready, ColumnBuried buried) {
        return ready.test(x >> 4) && !buried.test(x);
    }
}
