package games.brennan.dungeontrain.worldgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The seed-stable field of floating spheres behind the <b>spheres</b> band (see {@code SpheresBand}
 * / {@code WorldSpheresEvents}). Pure — no Minecraft types — so it is unit-testable and safe to
 * evaluate from any worldgen worker.
 *
 * <p>Space is tiled into cubes of {@link Params#cell} blocks in X, Y and Z, the Y layers covering
 * the lifted-centre range {@code [centerMinY, centerMaxY]}. Each cube rolls at most one sphere from
 * a salted splitmix64 hash of {@code (seed, cellX, cellY, cellZ)} — the {@code ChuncksBand.hash01}
 * idiom extended to three coordinates — so the field is identical on every machine and thread and
 * needs <b>no neighbour-chunk state</b>: any chunk can rebuild the spheres that touch it from its
 * own coordinates alone (the same constraint the End erosion solves with palette matching).</p>
 *
 * <p>A sphere is a ball of the natural overworld terrain at its own X/Z, <em>lifted</em> to its
 * rolled centre height. {@link Sphere#dy} is that lift: the block shown at {@code (x, y, z)} inside
 * the sphere is the vanilla block generated at {@code (x, y − dy, z)} — a pure vertical shift within
 * the same column, which is what keeps the carve pass chunk-local. The <b>source</b> centre is
 * anchored to the natural surface (sampled through the injected {@link SurfaceSampler}) so every
 * sphere carries a surface cap: {@code sourceCenterY = surfaceY(cx, cz) − r·(2·surfaceBias − 1)},
 * i.e. {@code surfaceBias} of the diameter sits underground.</p>
 *
 * <p>Per-cell rolls are memoised (bounded, thread-safe) because a large sphere touches dozens of
 * chunks and the surface sample is the one non-trivial cost.</p>
 */
public final class SphereField {

    /**
     * Field parameters. {@code rMax ≥ rMin} and {@code centerMaxY ≥ centerMinY} are the caller's job
     * (the config getters clamp); {@code cell ≥ 1}.
     *
     * @param seed        per-world generation seed
     * @param cell        placement cube edge (blocks)
     * @param density     chance {@code 0..1} a cube holds a sphere
     * @param rMin        smallest radius (blocks)
     * @param rMax        largest radius (blocks)
     * @param centerMinY  lowest lifted-centre world Y
     * @param centerMaxY  highest lifted-centre world Y
     * @param surfaceBias fraction {@code 0..1} of the diameter below the natural surface at the cut
     */
    public record Params(long seed, int cell, double density, int rMin, int rMax,
                         int centerMinY, int centerMaxY, double surfaceBias) {}

    /**
     * One sphere: lifted (display) centre {@code (cx, cy, cz)}, radius {@code r}, the vertical lift
     * {@code dy = cy − sourceCenterY} applied to its terrain, the dimension its terrain is cut from, and
     * whether it is built around a structure. An overworld sphere with no structure is carved inline
     * from the chunk's own terrain; every other sphere is generated off-thread
     * ({@code ForeignSphereSampler}).
     */
    public record Sphere(int cx, int cy, int cz, int r, int dy, SphereSource source, boolean structure) {

        /** A plain overworld sphere — the shape every sphere had before the band mixed dimensions. */
        public Sphere(int cx, int cy, int cz, int r, int dy) {
            this(cx, cy, cz, r, dy, SphereSource.OVERWORLD, false);
        }

        /** True if this sphere's terrain comes from the off-thread sampler rather than the chunk itself. */
        public boolean offline() {
            return structure || source != SphereSource.OVERWORLD;
        }

        /**
         * A stable 64-bit id from the centre — unique per sphere, because each placement cube rolls at
         * most one and the centre lies inside its cube. Used to track which spheres a chunk still owes.
         */
        public long id() {
            return ((long) cx & 0x3FFFFFFL) | (((long) cz & 0x3FFFFFFL) << 26) | (((long) cy & 0xFFFL) << 52);
        }

        /** True if the world position lies inside the sphere (inclusive surface). */
        public boolean contains(int x, int y, int z) {
            long dx = x - cx, dyy = y - cy, dz = z - cz;
            return dx * dx + dyy * dyy + dz * dz <= (long) r * r;
        }

        /** True if the column {@code (x, z)} passes within the sphere's horizontal footprint. */
        public boolean touchesColumn(int x, int z) {
            long dx = x - cx, dz = z - cz;
            return dx * dx + dz * dz <= (long) r * r;
        }

        /** Squared distance from the centre divided by {@code r²} — {@code ≤ 1} inside. */
        public double normDistSq(int x, int y, int z) {
            long dx = x - cx, dyy = y - cy, dz = z - cz;
            return (double) (dx * dx + dyy * dyy + dz * dz) / ((double) r * r);
        }

        /** Source world Y the displayed block at {@code y} is copied from. */
        public int sourceY(int y) {
            return y - dy;
        }
    }

    /** Natural-surface height at a column, from the world's chunk generator (or a constant in tests). */
    @FunctionalInterface
    public interface SurfaceSampler {
        int surfaceY(int x, int z);
    }

    /** Returned by {@link Mixer#endSurfaceY} for an End column with no island — the sphere turns Nether. */
    public static final int NO_SURFACE = Integer.MIN_VALUE;

    /**
     * Where along the band a sphere may come from another dimension, and how its foreign terrain is
     * anchored. {@link #OVERWORLD_ONLY} reproduces the pre-mix band exactly.
     */
    public interface Mixer {
        /** The dimension a sphere centred at world-X {@code cx} is cut from, given a uniform roll {@code u}. */
        SphereSource sourceAt(int cx, double u);

        /** Chance {@code 0..1} a sphere centred at world-X {@code cx} is built around a structure. */
        double structureChanceAt(int cx);

        /** End surface Y at a column (island top), or {@link #NO_SURFACE} over the End void. */
        int endSurfaceY(int x, int z);

        /**
         * Exit taper {@code 0..1} at world-X {@code cx}: scales both the chance a cube holds a sphere and
         * the sphere's radius, so spheres thin out and shrink toward the band's end. {@code 1} = untouched.
         */
        default double taperAt(int cx) {
            return 1.0;
        }

        Mixer OVERWORLD_ONLY = new Mixer() {
            @Override public SphereSource sourceAt(int cx, double u) { return SphereSource.OVERWORLD; }
            @Override public double structureChanceAt(int cx) { return 0.0; }
            @Override public int endSurfaceY(int x, int z) { return NO_SURFACE; }
        };
    }

    /** Nether spheres are cut around a source centre in this Y range — cavern floors, walls, the lava sea. */
    static final int NETHER_SOURCE_MIN_Y = 40;
    static final int NETHER_SOURCE_MAX_Y = 100;

    private static final int PRESENCE_SALT = 11;
    private static final int JITTER_X_SALT = 12;
    private static final int JITTER_Y_SALT = 13;
    private static final int JITTER_Z_SALT = 14;
    private static final int RADIUS_SALT = 15;
    /** Smallest radius a tapered sphere shrinks to — below this a ball is a few stray blocks. */
    static final int MIN_TAPERED_RADIUS = 3;
    private static final int SOURCE_SALT = 16;
    private static final int STRUCTURE_SALT = 17;
    private static final int NETHER_Y_SALT = 18;

    /** Sentinel for "this cell rolled no sphere" in the memo (a {@code null} value can't be stored). */
    private static final Sphere NONE = new Sphere(0, 0, 0, 0, 0);
    private static final int MAX_MEMO = 1 << 16;

    private final Params p;
    private final SurfaceSampler surface;
    private final Mixer mixer;
    private final ConcurrentHashMap<Long, Sphere> memo = new ConcurrentHashMap<>();

    public SphereField(Params params, SurfaceSampler surface) {
        this(params, surface, Mixer.OVERWORLD_ONLY);
    }

    public SphereField(Params params, SurfaceSampler surface, Mixer mixer) {
        this.p = params;
        this.surface = surface;
        this.mixer = mixer;
    }

    public Params params() {
        return p;
    }

    /**
     * Every sphere whose horizontal footprint touches the 16×16 chunk at {@code (chunkX, chunkZ)},
     * in a deterministic order (cell-major). Scans the placement cubes within {@code rMax} of the
     * chunk in X/Z and every Y layer, then keeps those whose circle meets the chunk's square. Empty
     * when the chunk sits over open void.
     */
    public List<Sphere> candidatesFor(int chunkX, int chunkZ) {
        int minX = chunkX << 4, maxX = minX + 15;
        int minZ = chunkZ << 4, maxZ = minZ + 15;
        int cell = Math.max(1, p.cell());
        int reach = Math.max(0, p.rMax());
        int cx0 = Math.floorDiv(minX - reach, cell), cx1 = Math.floorDiv(maxX + reach, cell);
        int cz0 = Math.floorDiv(minZ - reach, cell), cz1 = Math.floorDiv(maxZ + reach, cell);
        int cy0 = Math.floorDiv(p.centerMinY(), cell), cy1 = Math.floorDiv(p.centerMaxY(), cell);
        List<Sphere> out = null;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                for (int cy = cy0; cy <= cy1; cy++) {
                    Sphere s = sphereInCell(cx, cy, cz);
                    if (s == null || !circleMeetsSquare(s, minX, maxX, minZ, maxZ)) continue;
                    if (out == null) out = new ArrayList<>(4);
                    out.add(s);
                }
            }
        }
        return out == null ? Collections.emptyList() : Collections.unmodifiableList(out);
    }

    /**
     * Of {@code candidates}, the sphere that owns the block at {@code (x, y, z)} — the one whose
     * centre is nearest in units of its own radius — or {@code null} when the block is outside every
     * sphere. Deterministic where spheres overlap, so both sides of a chunk seam agree.
     */
    public static Sphere bestAt(List<Sphere> candidates, int x, int y, int z) {
        Sphere best = null;
        double bestD = Double.MAX_VALUE;
        for (Sphere s : candidates) {
            if (!s.contains(x, y, z)) continue;
            double d = s.normDistSq(x, y, z);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    /** The sphere rolled for a placement cube, or {@code null}; memoised. */
    Sphere sphereInCell(int cellX, int cellY, int cellZ) {
        long key = ((long) cellX & 0x1FFFFFL) | (((long) cellZ & 0x1FFFFFL) << 21) | (((long) cellY & 0xFFFFFL) << 42);
        Sphere hit = memo.get(key);
        if (hit == null) {
            hit = roll(cellX, cellY, cellZ);
            if (hit == null) hit = NONE;
            if (memo.size() >= MAX_MEMO) memo.clear();          // bound memory; re-rolling is cheap
            memo.put(key, hit);
        }
        return hit == NONE ? null : hit;
    }

    /** Pure per-cell roll (no memo): presence, centre jitter, radius, then the surface-anchored lift. */
    private Sphere roll(int cellX, int cellY, int cellZ) {
        long seed = p.seed();
        int cell = Math.max(1, p.cell());
        int cx = cellX * cell + (int) Math.floor(hash01(seed, cellX, cellY, cellZ, JITTER_X_SALT) * cell);
        // Hashes are pure, so rolling cx first changes nothing where the taper is 1.
        double taper = Math.max(0.0, Math.min(1.0, mixer.taperAt(cx)));
        if (hash01(seed, cellX, cellY, cellZ, PRESENCE_SALT) >= p.density() * taper) return null;
        int cz = cellZ * cell + (int) Math.floor(hash01(seed, cellX, cellY, cellZ, JITTER_Z_SALT) * cell);
        // Jitter Y inside the cube ∩ [centerMinY, centerMaxY] so edge layers never spill out of range.
        int lo = Math.max(cellY * cell, p.centerMinY());
        int hi = Math.min(cellY * cell + cell - 1, p.centerMaxY());
        if (hi < lo) return null;
        int cy = lo + (int) Math.floor(hash01(seed, cellX, cellY, cellZ, JITTER_Y_SALT) * (hi - lo + 1));
        double u = hash01(seed, cellX, cellY, cellZ, RADIUS_SALT);
        int rMin = Math.max(1, p.rMin());
        int rMax = Math.max(rMin, p.rMax());
        int r = rMin + (int) Math.floor((rMax - rMin + 1) * u * u);  // biased small: most spheres are little
        if (r > rMax) r = rMax;
        if (taper < 1.0) r = Math.max(Math.min(r, MIN_TAPERED_RADIUS), (int) Math.round(r * taper));
        SphereSource source = mixer.sourceAt(cx, hash01(seed, cellX, cellY, cellZ, SOURCE_SALT));
        boolean structure = hash01(seed, cellX, cellY, cellZ, STRUCTURE_SALT) < mixer.structureChanceAt(cx);
        int sourceCenterY;
        int bias = (int) Math.round(r * (2.0 * p.surfaceBias() - 1.0));
        if (source == SphereSource.END) {
            int endY = mixer.endSurfaceY(cx, cz);
            if (endY == NO_SURFACE) {
                source = SphereSource.NETHER;                 // over the End void: nothing to cut
            } else {
                sourceCenterY = endY - bias;                  // an island cap, like an overworld sphere
                return new Sphere(cx, cy, cz, r, cy - sourceCenterY, source, structure);
            }
        }
        if (source == SphereSource.NETHER) {
            // The Nether has a roof, so there is no surface to anchor to: cut around a hashed height.
            double u2 = hash01(seed, cellX, cellY, cellZ, NETHER_Y_SALT);
            sourceCenterY = NETHER_SOURCE_MIN_Y
                    + (int) Math.floor(u2 * (NETHER_SOURCE_MAX_Y - NETHER_SOURCE_MIN_Y + 1));
        } else {
            sourceCenterY = surface.surfaceY(cx, cz) - bias;
        }
        return new Sphere(cx, cy, cz, r, cy - sourceCenterY, source, structure);
    }

    private static boolean circleMeetsSquare(Sphere s, int minX, int maxX, int minZ, int maxZ) {
        int nx = Math.max(minX, Math.min(s.cx(), maxX));
        int nz = Math.max(minZ, Math.min(s.cz(), maxZ));
        long dx = s.cx() - nx, dz = s.cz() - nz;
        return dx * dx + dz * dz <= (long) s.r() * s.r();
    }

    /**
     * A uniform {@code [0,1)} value per {@code (seed, a, b, c, salt)} — the splitmix64 finaliser
     * {@code ChuncksBand.hash01} uses, with a third coordinate folded in. Package-private for tests.
     */
    static double hash01(long seed, int a, int b, int c, int salt) {
        long h = seed * 0x9E3779B97F4A7C15L + salt * 0xD1B54A32D192ED03L;
        h ^= (long) a * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h ^= (long) b * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (long) c * 0x9FB21C651E98DF25L;
        h = (h ^ (h >>> 31)) * 0xD6E8FEB86659FD93L;
        h ^= (h >>> 32);
        return (h >>> 11) * 0x1.0p-53;
    }
}
