package games.brennan.dungeontrain.portal;

/**
 * How far a chunk dimension room has got through having its sampled chunk written.
 *
 * <p>A whole chunk in one tick was the longest tick in a 2026-11 lag report (752 ms), so
 * {@link PortalChunkDimension} writes it a few rows a tick, bottom up: every row lands on the row
 * below it, so sand has something to rest on and water has nowhere to run to in the meantime.</p>
 *
 * @param slice      the sample being written
 * @param nextRow    the first row of the room's box still to write
 * @param ticks      ticks spent writing so far, for the perf line
 * @param totalNanos time spent writing so far
 * @param maxNanos   the most any one tick has spent
 */
record PortalChunkPour(PortalChunkSlice slice, int nextRow, int ticks, long totalNanos, long maxNanos) {

    /** Rows written per tick. At the box's 32 rows that is eight ticks, under half a second. */
    static final int ROWS_PER_TICK = 4;

    static PortalChunkPour of(PortalChunkSlice slice) {
        return new PortalChunkPour(slice, 0, 0, 0L, 0L);
    }

    /** Whether every row of a box {@code height} tall is down. */
    boolean rowsDone(int height) {
        return nextRow >= height;
    }

    /** The row this tick's writing stops short of. */
    int rowsEnd(int height) {
        return Math.min(nextRow + ROWS_PER_TICK, height);
    }

    /** This pour with this tick's rows written, having taken {@code nanos}. */
    PortalChunkPour advanced(int height, long nanos) {
        return new PortalChunkPour(slice, rowsEnd(height), ticks + 1, totalNanos + nanos, Math.max(maxNanos, nanos));
    }

    /**
     * This pour starting again from the bottom with a newer sample — the decoration landing part way
     * through. Rows the two samples agree on are skipped by the write, so this is mostly reads.
     */
    PortalChunkPour restartedWith(PortalChunkSlice newer) {
        return new PortalChunkPour(newer, 0, ticks, totalNanos, maxNanos);
    }
}
