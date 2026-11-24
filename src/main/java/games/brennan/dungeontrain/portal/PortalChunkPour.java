package games.brennan.dungeontrain.portal;

/**
 * How far a chunk dimension room has got through having its sampled terrain written.
 *
 * <p>A whole chunk in one tick was the longest tick in a 2026-11 lag report (752 ms), so
 * {@link PortalChunkDimension} writes it a little at a time, bottom up: every row lands on the row
 * below it, so sand has something to rest on and water has nowhere to run to in the meantime.</p>
 *
 * <p>The unit is a <b>strip</b>: one row of the slice at one Z, {@code width} cells along X. Strips
 * run Z-fastest, so strip {@code s} is row {@code s / width}, column {@code s % width}, and writing
 * strips in order is writing rows in order. Budgeting by strips rather than rows keeps a tick's
 * cost the same whatever the room's footprint: a 48-wide room's row is nine times a 16-wide one's,
 * and four of those a tick would be more than the whole chunk that made the 752 ms tick.</p>
 *
 * @param slice      the sample being written
 * @param nextStrip  the first strip of the room still to write
 * @param ticks      ticks spent writing so far, for the perf line
 * @param totalNanos time spent writing so far
 * @param maxNanos   the most any one tick has spent
 */
record PortalChunkPour(PortalChunkSlice slice, int nextStrip, int ticks, long totalNanos, long maxNanos,
                       boolean rush) {

    /**
     * How long a pour in a hurry keeps writing in one tick: someone is already standing in the room
     * ({@code PortalChunkDimension#finishAround}), and the part they have not reached has to land
     * before they walk into it. Most of a 50 ms tick, so the server keeps its pace while the room
     * lands in a second or so rather than four.
     */
    static final long RUSH_NANOS = 30_000_000L;

    PortalChunkPour(PortalChunkSlice slice, int nextStrip, int ticks, long totalNanos, long maxNanos) {
        this(slice, nextStrip, ticks, totalNanos, maxNanos, false);
    }

    /**
     * Cells written per tick: a one-chunk room's four rows (4 x 16 x 16), so its pour is the eight
     * ticks it always was, and a wider room's takes longer rather than costing more per tick.
     */
    static final int CELLS_PER_TICK = 1024;

    static PortalChunkPour of(PortalChunkSlice slice) {
        return new PortalChunkPour(slice, 0, 0, 0L, 0L, false);
    }

    /** This pour in a hurry, from here on. */
    PortalChunkPour rushed() {
        return new PortalChunkPour(slice, nextStrip, ticks, totalNanos, maxNanos, true);
    }

    /** Strips written per tick for a slice {@code width} wide: never less than one. */
    static int stripsPerTick(int width) {
        return Math.max(1, CELLS_PER_TICK / Math.max(1, width));
    }

    /** Every strip of a room {@code height} tall. */
    int totalStrips(int height) {
        return height * slice.width();
    }

    /** Whether every strip of a box {@code height} tall is down. */
    boolean done(int height) {
        return nextStrip >= totalStrips(height);
    }

    /** The strip this tick's writing stops short of. */
    int stripsEnd(int height) {
        return Math.min(nextStrip + stripsPerTick(slice.width()), totalStrips(height));
    }

    /** This pour with this tick's strips written, having taken {@code nanos}. */
    PortalChunkPour advanced(int height, long nanos) {
        return new PortalChunkPour(slice, stripsEnd(height), ticks + 1, totalNanos + nanos, Math.max(maxNanos, nanos),
            rush);
    }

    /** This pour with another budget's strips written in the same tick, {@code nanos} into it. */
    PortalChunkPour continued(int height, long nanos) {
        return new PortalChunkPour(slice, stripsEnd(height), ticks, totalNanos, Math.max(maxNanos, nanos), rush);
    }

    /**
     * This pour starting again from the bottom with a newer sample — the decoration landing part way
     * through. Cells the two samples agree on are skipped by the write, so this is mostly reads.
     */
    PortalChunkPour restartedWith(PortalChunkSlice newer) {
        return new PortalChunkPour(newer, 0, ticks, totalNanos, maxNanos, rush);
    }
}
