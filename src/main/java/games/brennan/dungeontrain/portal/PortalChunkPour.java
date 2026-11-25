package games.brennan.dungeontrain.portal;

import java.util.Arrays;

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
 * and four of those a tick would be more than the whole chunk that made the 752 ms tick. A tick also
 * stops on time ({@link #POUR_NANOS}): what a strip costs varies tenfold.</p>
 *
 * <h2>The patch</h2>
 * <p>The decoration pass lands as a list of the cells it changed
 * ({@link PortalChunkDecorationDiff}), never as a second pour. Landing part-way through, the pour
 * carries on from where it was with the decorated slice, and only the changed cells it has already
 * passed are the {@link #patch}. Landing after the room is down, the pour is nothing but the patch,
 * and {@link #dressed} says the room's frame is already standing, so it is not dressed again.</p>
 *
 * @param slice      the sample being written
 * @param nextStrip  the first strip of the room still to write
 * @param ticks      ticks spent writing so far, for the perf line
 * @param totalNanos time spent writing so far
 * @param maxNanos   the most any one tick has spent
 * @param rush       someone is in the room: write for {@link #RUSH_NANOS} a tick
 * @param patch      slice cell indices still to rewrite out of strip order, ascending; empty for none
 * @param patchNext  the first of {@link #patch} still to write
 * @param dressed    the room already stands in its frame: finishing touches it up rather than dressing it
 */
record PortalChunkPour(PortalChunkSlice slice, int nextStrip, int ticks, long totalNanos, long maxNanos,
                       boolean rush, int[] patch, int patchNext, boolean dressed) {

    private static final int[] NO_PATCH = new int[0];

    /**
     * How long a pour in a hurry keeps writing in one tick: someone is already standing in the room
     * ({@code PortalChunkDimension#finishAround}), and the part they have not reached has to land
     * before they walk into it. Most of a 50 ms tick, so the server keeps its pace while the room
     * lands in a second or so rather than four.
     */
    static final long RUSH_NANOS = 30_000_000L;

    /**
     * How long an unhurried pour writes in one tick, whatever its cell budget still allows: a fifth
     * of a tick. Cells alone let a Nether room's lava and lighting reach 104 ms in a tick.
     */
    static final long POUR_NANOS = 10_000_000L;

    PortalChunkPour(PortalChunkSlice slice, int nextStrip, int ticks, long totalNanos, long maxNanos) {
        this(slice, nextStrip, ticks, totalNanos, maxNanos, false, NO_PATCH, 0, false);
    }

    /**
     * Cells written per tick: a one-chunk room's four rows (4 x 16 x 16), so its pour is the eight
     * ticks it always was, and a wider room's takes longer rather than costing more per tick.
     */
    static final int CELLS_PER_TICK = 1024;

    static PortalChunkPour of(PortalChunkSlice slice) {
        return new PortalChunkPour(slice, 0, 0, 0L, 0L, false, NO_PATCH, 0, false);
    }

    /**
     * A room already standing from its ground, and dressed, that only needs {@code decorated}'s
     * changed cells written: no strips, and no second dress.
     */
    static PortalChunkPour patchOnly(PortalChunkSlice decorated, int height) {
        return new PortalChunkPour(decorated, height * decorated.width(), 0, 0L, 0L, false,
            decorated.changes(), 0, true);
    }

    /** This pour in a hurry, from here on. */
    PortalChunkPour rushed() {
        return new PortalChunkPour(slice, nextStrip, ticks, totalNanos, maxNanos, true, patch, patchNext, dressed);
    }

    /**
     * This pour carrying on with {@code decorated} — the decoration landing part way through. The
     * strips still to come are written from it as they are reached; the changed cells already passed
     * are queued as the patch, so nothing is written twice and nothing starts again.
     */
    PortalChunkPour swappedTo(PortalChunkSlice decorated) {
        int[] changes = decorated.changes();
        int passed = 0;
        while (passed < changes.length && decorated.stripOf(changes[passed]) < nextStrip) passed++;
        int[] behind = mergedPatch(Arrays.copyOf(changes, passed));
        return new PortalChunkPour(decorated, nextStrip, ticks, totalNanos, maxNanos, rush, behind, 0, dressed);
    }

    /** What is left of this pour's patch, with {@code more} added, ascending and without repeats. */
    private int[] mergedPatch(int[] more) {
        int[] left = Arrays.copyOfRange(patch, Math.min(patchNext, patch.length), patch.length);
        if (left.length == 0) return more;
        int[] all = Arrays.copyOf(left, left.length + more.length);
        System.arraycopy(more, 0, all, left.length, more.length);
        return Arrays.stream(all).sorted().distinct().toArray();
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
    boolean stripsDone(int height) {
        return nextStrip >= totalStrips(height);
    }

    /** Whether the patch is all written. */
    boolean patchDone() {
        return patchNext >= patch.length;
    }

    /** Whether the strips and the patch are both down. */
    boolean done(int height) {
        return stripsDone(height) && patchDone();
    }

    /** The strip this tick's writing stops short of, by the cell budget. */
    int stripsEnd(int height) {
        return Math.min(nextStrip + stripsPerTick(slice.width()), totalStrips(height));
    }

    /** This pour with this tick's whole strip budget written, having taken {@code nanos}. */
    PortalChunkPour advanced(int height, long nanos) {
        return stepped(stripsEnd(height), patchNext, nanos);
    }

    /**
     * This pour after one tick that wrote up to strip {@code toStrip} and patch entry
     * {@code toPatch}, having taken {@code nanos} — however far the tick's time let it get.
     */
    PortalChunkPour stepped(int toStrip, int toPatch, long nanos) {
        return new PortalChunkPour(slice, Math.max(nextStrip, toStrip), ticks + 1, totalNanos + nanos,
            Math.max(maxNanos, nanos), rush, patch, Math.max(patchNext, toPatch), dressed);
    }

    /**
     * This pour starting again from the bottom with a newer sample — a decoration that carries no
     * diff against what is being poured. Cells the two samples agree on are skipped by the write, so
     * this is mostly reads.
     */
    PortalChunkPour restartedWith(PortalChunkSlice newer) {
        return new PortalChunkPour(newer, 0, ticks, totalNanos, maxNanos, rush, NO_PATCH, 0, false);
    }

    /** Patch entries written so far, for the perf line. */
    int patched() {
        return Math.min(patchNext, patch.length);
    }
}
