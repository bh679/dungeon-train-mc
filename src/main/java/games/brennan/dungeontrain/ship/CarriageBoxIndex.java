package games.brennan.dungeontrain.ship;

import java.util.List;

/**
 * An immutable snapshot of carriage world boxes, laid out for a cheap "could this point be inside
 * a carriage?" test.
 *
 * <p>Built once per server tick by {@link ResidentCarriageBoxes} and read thousands of times by the
 * fluid barrier: vanilla fires a fluid tick for every water block of a chunk it promotes, and each
 * of those asks {@code canSpreadTo} up to five times. Before this index, each ask walked every
 * sub-level in the level and allocated a list plus one AABB per carriage. Now the common case, a
 * fluid tick nowhere near the train, is one point-in-box test against {@link #mayContain the
 * union box} with no allocation.</p>
 *
 * <p>The boxes held here are <b>coarse</b>: each is the carriage's box at snapshot time, grown by
 * {@code margin} on every side, so a carriage that moves between the snapshot and a later test in
 * the same tick still covers its true position. A hit here is only a candidate; the caller settles
 * it against the carriage's live box. Both answers of this class are therefore safe in one
 * direction: {@link #mayContain} and {@link #nextCandidate} never miss a carriage that is inside
 * the margin, and may name one that the live test then rejects.</p>
 *
 * <p>Degenerate boxes (see {@link ShipAabbs#isDegenerate}) are left out: Sable reports one for a
 * sub-level before its first physics tick, and a point can never be inside a box with no extent.</p>
 *
 * <p>No Minecraft, JOML or Sable types, so it unit-tests without a NeoForge bootstrap. The handle
 * type {@code T} is whatever the caller wants back for a candidate (a {@code ManagedShip} in
 * production).</p>
 *
 * @param <T> the handle stored alongside each box
 */
public final class CarriageBoxIndex<T> {

    /** Reads one carriage's world box into loose bounds. */
    @FunctionalInterface
    public interface BoxReader<T> {
        /**
         * Writes {@code minX, minY, minZ, maxX, maxY, maxZ} of {@code handle}'s box into
         * {@code out[0..5]}.
         */
        void read(T handle, double[] out);
    }

    private static final CarriageBoxIndex<?> EMPTY = new CarriageBoxIndex<>(
        new Object[0], new double[0], new double[0], new double[0], new double[0], new double[0], new double[0],
        Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);

    private final Object[] handles;
    private final double[] minX;
    private final double[] minY;
    private final double[] minZ;
    private final double[] maxX;
    private final double[] maxY;
    private final double[] maxZ;
    private final double unionMinX;
    private final double unionMinY;
    private final double unionMinZ;
    private final double unionMaxX;
    private final double unionMaxY;
    private final double unionMaxZ;

    private CarriageBoxIndex(Object[] handles, double[] minX, double[] minY, double[] minZ,
                             double[] maxX, double[] maxY, double[] maxZ,
                             double unionMinX, double unionMinY, double unionMinZ,
                             double unionMaxX, double unionMaxY, double unionMaxZ) {
        this.handles = handles;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.unionMinX = unionMinX;
        this.unionMinY = unionMinY;
        this.unionMinZ = unionMinZ;
        this.unionMaxX = unionMaxX;
        this.unionMaxY = unionMaxY;
        this.unionMaxZ = unionMaxZ;
    }

    /** The index with no carriages: {@link #mayContain} is always false. */
    @SuppressWarnings("unchecked")
    public static <T> CarriageBoxIndex<T> empty() {
        return (CarriageBoxIndex<T>) EMPTY;
    }

    /**
     * Snapshot {@code handles}' boxes, each grown by {@code margin} on every side.
     *
     * @param handles the carriages to index; order is kept for {@link #handle}
     * @param reader  how to read a handle's box
     * @param margin  blocks of growth per side, at least the farthest a carriage can move between
     *                this snapshot and the last test that reads it
     * @throws IllegalArgumentException if {@code margin} is negative or not a number
     */
    public static <T> CarriageBoxIndex<T> of(List<T> handles, BoxReader<T> reader, double margin) {
        if (!(margin >= 0.0)) {
            throw new IllegalArgumentException("margin must be a non-negative number, got " + margin);
        }
        if (handles.isEmpty()) {
            return empty();
        }
        int n = handles.size();
        Object[] keep = new Object[n];
        double[] minX = new double[n];
        double[] minY = new double[n];
        double[] minZ = new double[n];
        double[] maxX = new double[n];
        double[] maxY = new double[n];
        double[] maxZ = new double[n];
        double[] box = new double[6];
        double uMinX = Double.POSITIVE_INFINITY;
        double uMinY = Double.POSITIVE_INFINITY;
        double uMinZ = Double.POSITIVE_INFINITY;
        double uMaxX = Double.NEGATIVE_INFINITY;
        double uMaxY = Double.NEGATIVE_INFINITY;
        double uMaxZ = Double.NEGATIVE_INFINITY;
        int count = 0;
        for (T handle : handles) {
            reader.read(handle, box);
            if (ShipAabbs.isDegenerate(box[0], box[1], box[2], box[3], box[4], box[5])) {
                continue;
            }
            keep[count] = handle;
            minX[count] = box[0] - margin;
            minY[count] = box[1] - margin;
            minZ[count] = box[2] - margin;
            maxX[count] = box[3] + margin;
            maxY[count] = box[4] + margin;
            maxZ[count] = box[5] + margin;
            uMinX = Math.min(uMinX, minX[count]);
            uMinY = Math.min(uMinY, minY[count]);
            uMinZ = Math.min(uMinZ, minZ[count]);
            uMaxX = Math.max(uMaxX, maxX[count]);
            uMaxY = Math.max(uMaxY, maxY[count]);
            uMaxZ = Math.max(uMaxZ, maxZ[count]);
            count++;
        }
        if (count == 0) {
            return empty();
        }
        return new CarriageBoxIndex<>(
            trim(keep, count), trim(minX, count), trim(minY, count), trim(minZ, count),
            trim(maxX, count), trim(maxY, count), trim(maxZ, count),
            uMinX, uMinY, uMinZ, uMaxX, uMaxY, uMaxZ);
    }

    /** How many carriages are indexed (degenerate boxes excluded). */
    public int size() {
        return handles.length;
    }

    /**
     * False when the point is outside every indexed box, which is the answer for almost every
     * fluid tick in the world. True only means some carriage's grown box contains the point.
     */
    public boolean mayContain(double x, double y, double z) {
        return x >= unionMinX && x <= unionMaxX
            && y >= unionMinY && y <= unionMaxY
            && z >= unionMinZ && z <= unionMaxZ;
    }

    /**
     * The first index at or after {@code from} whose grown box contains the point, or {@code -1}.
     * Iterate with {@code for (int i = nextCandidate(x,y,z,0); i >= 0; i = nextCandidate(x,y,z,i+1))}.
     */
    public int nextCandidate(double x, double y, double z, int from) {
        for (int i = Math.max(from, 0); i < handles.length; i++) {
            if (x >= minX[i] && x <= maxX[i]
                && y >= minY[i] && y <= maxY[i]
                && z >= minZ[i] && z <= maxZ[i]) {
                return i;
            }
        }
        return -1;
    }

    /** The handle indexed at {@code i}, as returned by {@link #nextCandidate}. */
    @SuppressWarnings("unchecked")
    public T handle(int i) {
        return (T) handles[i];
    }

    private static Object[] trim(Object[] a, int n) {
        if (n == a.length) return a;
        Object[] out = new Object[n];
        System.arraycopy(a, 0, out, 0, n);
        return out;
    }

    private static double[] trim(double[] a, int n) {
        if (n == a.length) return a;
        double[] out = new double[n];
        System.arraycopy(a, 0, out, 0, n);
        return out;
    }
}
