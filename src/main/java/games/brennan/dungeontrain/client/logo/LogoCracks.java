package games.brennan.dungeontrain.client.logo;

import games.brennan.dungeontrain.client.logo.CrackPath.Sample;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.ToDoubleFunction;

/**
 * Cracks for the in-game ring logo: a depth map the shader reads as a height field. A port of the
 * website's {@code logo-ring/cracks.js}; the two are meant to stay in step.
 *
 * <p>Layout: one row per (segment, long face); each texel holds how deep the surface is cut there
 * (0 = surface, 255 = bottom of the groove). A segment's four rows are stacked in the order its
 * faces go round the tube, so a row's lower edge is the same surface edge as the next row's upper
 * edge (and row 3 wraps back to row 0). Shapes are laid over a segment's whole 4-row block, so they
 * run straight across face edges.</p>
 *
 * <p>Shapes: cracks (short meandering paths of mostly square turns with rounded corners, cut as
 * V-shaped grooves that swell at turns and near edges and taper at their ends), chips (short
 * pill-shaped gouges) and a few rare edge bits (rounded scoops and slivers cut into an edge).
 * Rules: every crack and chip is joined to a surface edge; cracks have a minimum length and never
 * double back; stretches running the same way keep {@link #MIN_GAP} apart, from each other and
 * from the edges. Each segment keeps its shape list, so faces that have turned away from the viewer
 * can be regrown ({@link #regrowHidden}) without touching anything still in view.</p>
 */
public final class LogoCracks {

    public static final int ATLAS_W = 768;
    public static final int ATLAS_ROW = 256;
    /** World length one atlas row covers. */
    public static final double ATLAS_LEN = 1.2;
    public static final int BLOCK = ATLAS_ROW * 4;
    static final int MIN_GAP = 34;
    static final double PARALLEL = 0.6;
    private static final int EDGE_REACH = 48;
    private static final int CHECK_EVERY = 3;
    private static final int ATTEMPTS = 30;

    /** Look settings: amount (1 = 100%), edge bits in the whole ring, width and edge swell (1 = 100%), min crack length. */
    public record Settings(double amount, int edgeBits, double width, double edgeSwell, double minLen) {
        public static final Settings LOGO = new Settings(0.6, 3, 1, 1, 140);
    }

    /** A placed shape: its kind, its samples (empty for bowls), the faces it reaches (bit k = face k) and its cut. */
    record Shape(String kind, List<Sample> samples, int rows, IntConsumer carve) {}

    private static final class Block {
        final Rand rand;
        final List<Shape> shapes = new ArrayList<>();
        int targetCrack, targetChip, targetBit;
        Block(Rand rand) { this.rand = rand; }
    }

    private final int pieces;
    private final double segLen;
    private final Settings look;
    private final int height;
    private final float[] depth;
    private final byte[] data;
    private final Block[] blocks;
    private final boolean[] dirtyPieces;
    private int regenerations;

    /**
     * One crack field for {@code pieces} segments; {@code segLen} is a segment's length in texels,
     * so its cut ends count as edges.
     */
    public LogoCracks(int pieces, double segLen, Settings look) {
        this.pieces = pieces;
        this.segLen = segLen;
        this.look = look;
        this.height = BLOCK * pieces;
        this.depth = new float[ATLAS_W * height];
        this.data = new byte[ATLAS_W * height];
        this.blocks = new Block[pieces];
        this.dirtyPieces = new boolean[pieces];

        Rand edgeRand = new Rand(4242);
        int[] edgePieces = new int[look.edgeBits()];
        for (int i = 0; i < edgePieces.length; i++) edgePieces[i] = (int) Math.floor(edgeRand.next() * pieces);
        for (int piece = 0; piece < pieces; piece++) {
            Block b = new Block(new Rand(1013 + piece * 7919));
            b.targetCrack = (int) Math.round(look.amount() * (8 + b.rand.next() * 8));
            b.targetChip = (int) Math.round(look.amount() * (10 + b.rand.next() * 8));
            for (int p : edgePieces) if (p == piece) b.targetBit++;
            blocks[piece] = b;
            fillBlock(piece, -1);
            carveBlock(piece);
        }
    }

    public int width() { return ATLAS_W; }
    public int height() { return height; }
    public int regenerations() { return regenerations; }

    /** Depth at texel (x, y), 0..1. */
    public double depthAt(int x, int y) { return depth[y * ATLAS_W + x]; }

    /** Depth bytes (0..255), row-major, {@link #width()} by {@link #height()}. */
    public byte[] data() { return data; }

    /** Pieces recut since the last call, cleared by the call: the renderer re-uploads those rows. */
    public boolean[] takeDirty() {
        boolean[] out = dirtyPieces.clone();
        java.util.Arrays.fill(dirtyPieces, false);
        return out;
    }

    /**
     * When faces turn fully away from the viewer, replace every shape lying only on them with new
     * shapes that also stay only on them. Shapes reaching a visible face are kept.
     *
     * @param hiddenFaces bit k set = face k is hidden
     */
    public void regrowHidden(int piece, int hiddenFaces) {
        Block block = blocks[piece];
        block.shapes.removeIf(sh -> rowsWithin(sh.rows(), hiddenFaces));
        fillBlock(piece, hiddenFaces);
        carveBlock(piece);
        regenerations++;
    }

    List<Shape> shapes(int piece) { return blocks[piece].shapes; }

    // ---- geometry helpers ----

    static double wrapY(double y) { return ((y % BLOCK) + BLOCK) % BLOCK; }
    static int wrapRow(double y) { return (int) Math.floor(wrapY(y) / ATLAS_ROW); }
    private static boolean rowsWithin(int rows, int allowed) { return allowed < 0 || (rows & ~allowed) == 0; }

    /** How near a point is to a surface edge: 1 on the edge, easing to 0 by EDGE_REACH texels away. */
    private double edgeNearness(Sample s) {
        double yInRow = wrapY(s.y()) % ATLAS_ROW;
        double toEdge = Math.min(Math.min(yInRow, ATLAS_ROW - yInRow), Math.min(Math.abs(s.x()), Math.abs(segLen - s.x())));
        double t = Math.max(0, 1 - toEdge / EDGE_REACH);
        return t * t * (3 - 2 * t);
    }

    /** Width: thin base, swelling at turns and as the crack nears an edge, tapering at both ends. */
    private double crackRadius(Sample s, double total, double base, double swell) {
        double taper = Math.min(1, Math.min(s.dist() / 14, (total - s.dist()) / 14));
        return base * (0.55 + 0.75 * s.corner() + swell * look.edgeSwell() * edgeNearness(s)) * (0.35 + 0.65 * taper);
    }

    /** Depth: about half in the open, deepening to full near an edge. */
    private double crackDepth(Sample s) { return Math.min(1, 0.55 + 0.45 * look.edgeSwell() * edgeNearness(s)); }

    /** Cut a sampled shape into a block as a V-shaped groove; each texel keeps its deepest cut. */
    private void carveShape(int top, List<Sample> samples, ToDoubleFunction<Sample> radiusAt, ToDoubleFunction<Sample> depthAt) {
        for (Sample s : samples) {
            double r = radiusAt.applyAsDouble(s), peak = depthAt.applyAsDouble(s);
            if (r < 0.3) continue;
            int x0 = Math.max(0, (int) Math.floor(s.x() - r)), x1 = Math.min(ATLAS_W - 1, (int) Math.ceil(s.x() + r));
            for (int y = (int) Math.floor(s.y() - r); y <= (int) Math.ceil(s.y() + r); y++) {
                int row = top + (int) wrapY(y);
                for (int x = x0; x <= x1; x++) {
                    double d = Math.hypot(x - s.x(), y - s.y()) / r;
                    if (d >= 1) continue;
                    float v = (float) (peak * Math.pow(1 - d, 0.75));
                    int i = row * ATLAS_W + x;
                    if (v > depth[i]) depth[i] = v;
                }
            }
        }
    }

    /** Bowl-shaped cut: deepest in the middle, curving up to the rim. */
    private void carveBowl(int top, double cx, double cy, double rx, double ry, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle), reach = Math.max(rx, ry);
        for (int y = (int) Math.floor(cy - reach); y <= (int) Math.ceil(cy + reach); y++) {
            int row = top + (int) wrapY(y);
            for (int x = Math.max(0, (int) Math.floor(cx - reach)); x <= Math.min(ATLAS_W - 1, (int) Math.ceil(cx + reach)); x++) {
                double u = ((x - cx) * c + (y - cy) * s) / rx, v = (-(x - cx) * s + (y - cy) * c) / ry;
                double d2 = u * u + v * v;
                if (d2 >= 1) continue;
                int i = row * ATLAS_W + x;
                depth[i] = (float) Math.max(depth[i], Math.sqrt(1 - d2));
            }
        }
    }

    /** Faces a shape's cut reaches, from its samples and their radius (plus a texel of slack). */
    private static int rowsOf(List<Sample> samples, ToDoubleFunction<Sample> radiusAt) {
        int rows = 0;
        for (Sample s : samples) {
            double r = radiusAt.applyAsDouble(s) + 1;
            rows |= 1 << wrapRow(s.y() - r) | 1 << wrapRow(s.y()) | 1 << wrapRow(s.y() + r);
        }
        return rows;
    }

    // ---- shapes ----

    /**
     * A crack grows out of a surface edge: it starts just over a long edge or at a cut end, and its
     * first leg runs straight in. After that it has a main axis whose legs all head the same way, so
     * it always makes progress; legs on the other axis may zigzag either way, but can't loop.
     */
    private double[][] crackPath(Rand rand) {
        List<double[]> pts = new ArrayList<>();
        boolean horizontal, mainHorizontal;
        int mainSign;
        if (rand.next() < 0.8) {
            double edgeY = Math.floor(rand.next() * 4) * ATLAS_ROW;
            int inward = rand.next() < 0.5 ? -1 : 1;
            double x = 30 + rand.next() * (segLen - 60);
            pts.add(new double[] { x, edgeY - inward * (4 + rand.next() * 18) });
            pts.add(new double[] { x + (rand.next() - 0.5) * 8, edgeY + inward * (35 + rand.next() * 80) });
            mainHorizontal = rand.next() < 0.5;
            mainSign = mainHorizontal ? (rand.next() < 0.5 ? -1 : 1) : inward;
            horizontal = true;
        } else {
            boolean fromStart = rand.next() < 0.5;
            int inward = fromStart ? 1 : -1;
            double x0 = fromStart ? 0 : segLen;
            double y = rand.next() * BLOCK;
            pts.add(new double[] { x0, y });
            pts.add(new double[] { x0 + inward * (40 + rand.next() * 100), y + (rand.next() - 0.5) * 8 });
            mainHorizontal = rand.next() < 0.6;
            mainSign = mainHorizontal ? inward : (rand.next() < 0.5 ? -1 : 1);
            horizontal = false;
        }
        int legs = 1 + (int) Math.floor(rand.next() * 4);
        for (int i = 0; i < legs; i++) {
            double[] last = pts.get(pts.size() - 1);
            double len = horizontal == mainHorizontal
                    ? (40 + rand.next() * 140) * mainSign
                    : (25 + rand.next() * 80) * (rand.next() < 0.5 ? -1 : 1);
            double jitter = (rand.next() - 0.5) * 14; // not perfectly square
            pts.add(horizontal ? new double[] { last[0] + len, last[1] + jitter } : new double[] { last[0] + jitter, last[1] + len });
            horizontal = !horizontal;
        }
        return pts.toArray(new double[0][]);
    }

    private Shape makeCrack(Rand r) {
        List<Sample> samples = CrackPath.sample(crackPath(r), 16);
        double base = 3.2 + r.next() * 1.6, swell = 0.85 + r.next() * 0.35;
        double total = samples.get(samples.size() - 1).dist();
        ToDoubleFunction<Sample> radiusAt = s -> look.width() * crackRadius(s, total, base, swell);
        return new Shape("crack", samples, rowsOf(samples, radiusAt), top -> carveShape(top, samples, radiusAt, this::crackDepth));
    }

    /**
     * Chips: short gouges at least ~4x longer than wide, so they never read as dots. Each sits square
     * across a long edge (a little of it on the neighbouring face) or runs in from a cut end.
     */
    private Shape makeChip(Rand r) {
        double base = 3.5 + r.next() * 2.5, len = base * (8 + r.next() * 8);
        double[][] ends;
        if (r.next() < 0.8) {
            double edgeY = Math.floor(r.next() * 4) * ATLAS_ROW;
            int inward = r.next() < 0.5 ? -1 : 1;
            double x = 20 + r.next() * (segLen - 40), over = len * (0.15 + r.next() * 0.25);
            ends = new double[][] { { x, edgeY - inward * over }, { x + (r.next() - 0.5) * 4, edgeY + inward * (len - over) } };
        } else {
            boolean fromStart = r.next() < 0.5;
            int inward = fromStart ? 1 : -1;
            double x0 = fromStart ? 0 : segLen, y = r.next() * BLOCK;
            ends = new double[][] { { x0, y }, { x0 + inward * len, y + (r.next() - 0.5) * 4 } };
        }
        List<Sample> samples = CrackPath.sample(ends, 0);
        ToDoubleFunction<Sample> radiusAt = s -> look.width() * base * Math.min(1, 0.35 + Math.sin(Math.PI * s.dist() / len)) * (1 + 0.35 * edgeNearness(s));
        return new Shape("chip", samples, rowsOf(samples, radiusAt), top -> carveShape(top, samples, radiusAt, this::crackDepth));
    }

    /**
     * Edge bits: a rounded scoop straddling a long edge, or a sliver cut along one (blunt at one end,
     * pointed at the other). Both go to full depth, a little deeper than any crack.
     */
    private Shape makeEdgeBit(Rand r) {
        double reach = 26; // larger than either kind's half-size
        double edgeY = Math.floor(r.next() * 4) * ATLAS_ROW;
        double y;
        IntConsumer carve;
        String kind;
        if (r.next() < 0.5) {
            double x = 40 + r.next() * (segLen - 80), rx = 13 + r.next() * 9, ry = rx * (0.75 + r.next() * 0.35), angle = (r.next() - 0.5) * 0.6;
            y = edgeY + (r.next() - 0.5) * 10;
            kind = "scoop";
            double cy = y;
            carve = top -> carveBowl(top, x, cy, rx, ry, angle);
        } else {
            double base = 5.5 + r.next() * 2.5, x = 40 + r.next() * (segLen - 120), len = 40 + r.next() * 35;
            double slant = (r.next() - 0.5) * 4;
            boolean flip = r.next() < 0.5;
            y = edgeY + (r.next() - 0.5) * base * 0.66;
            kind = "sliver";
            List<Sample> samples = CrackPath.sample(new double[][] { { x, y }, { x + len, y + slant } }, 0);
            carve = top -> carveShape(top, samples, s -> {
                double t = Math.min(1, Math.max(0, s.dist() / len)), u = flip ? 1 - t : t;
                return base * Math.pow(Math.sin(Math.PI * Math.pow(u, 0.7)), 0.8);
            }, s -> 1);
        }
        int rows = 1 << wrapRow(y - reach) | 1 << wrapRow(y) | 1 << wrapRow(y + reach);
        return new Shape(kind, List.of(), rows, carve);
    }

    // ---- spacing ----

    /**
     * A stretch may not run alongside a surface edge, another shape, or another part of itself closer
     * than MIN_GAP. Crossing at an angle is fine.
     */
    private boolean hugsSurfaceEdge(Sample p) {
        if (Math.abs(p.dx()) > PARALLEL) {
            double yInRow = wrapY(p.y()) % ATLAS_ROW;
            if (Math.min(yInRow, ATLAS_ROW - yInRow) < MIN_GAP) return true;
        }
        return Math.abs(p.dy()) > PARALLEL && Math.min(Math.abs(p.x()), Math.abs(segLen - p.x())) < MIN_GAP;
    }

    static double wrapDy(double dy) { return ((dy % BLOCK) + BLOCK * 1.5) % BLOCK - BLOCK / 2.0; }

    static boolean alongside(Sample p, Sample q) {
        return Math.hypot(q.x() - p.x(), wrapDy(q.y() - p.y())) < MIN_GAP && Math.abs(q.dx() * p.dx() + q.dy() * p.dy()) > PARALLEL;
    }

    private final class SpacingGrid {
        private final Map<Long, List<Sample>> cells = new HashMap<>();
        private final int rowsOfCells = (int) Math.ceil((double) BLOCK / MIN_GAP);

        private long key(int cx, int cy) {
            int wy = ((cy % rowsOfCells) + rowsOfCells) % rowsOfCells;
            return ((long) cx << 32) | (wy & 0xffffffffL);
        }

        boolean fits(List<Sample> samples) {
            List<Sample> own = new ArrayList<>();
            for (int i = 0; i < samples.size(); i += CHECK_EVERY) {
                Sample p = samples.get(i);
                if (hugsSurfaceEdge(p)) return false;
                int cx = (int) Math.floor(p.x() / MIN_GAP), cy = (int) Math.floor(p.y() / MIN_GAP);
                for (int ix = cx - 1; ix <= cx + 1; ix++) {
                    for (int iy = cy - 1; iy <= cy + 1; iy++) {
                        for (Sample q : cells.getOrDefault(key(ix, iy), List.of())) if (alongside(p, q)) return false;
                    }
                }
                for (Sample q : own) if (Math.abs(q.dist() - p.dist()) >= MIN_GAP * 2.5 && alongside(p, q)) return false;
                own.add(p);
            }
            return true;
        }

        void add(List<Sample> samples) {
            for (int i = 0; i < samples.size(); i += CHECK_EVERY) {
                Sample p = samples.get(i);
                cells.computeIfAbsent(key((int) Math.floor(p.x() / MIN_GAP), (int) Math.floor(p.y() / MIN_GAP)), k -> new ArrayList<>()).add(p);
            }
        }
    }

    private boolean crackFits(Shape c) {
        List<Sample> s = c.samples();
        if (s.get(s.size() - 1).dist() < look.minLen()) return false;
        for (Sample p : s) if (p.x() < -1 || p.x() > segLen + 1) return false;
        return true;
    }

    private interface Maker { Shape make(Rand r); }
    private interface Accept { boolean test(Shape s); }

    /** Top a segment back up to its targets. With {@code allowed} (a face mask, -1 for all), new shapes stay on those faces only. */
    private void fillBlock(int piece, int allowed) {
        Block block = blocks[piece];
        SpacingGrid grid = new SpacingGrid();
        for (Shape sh : block.shapes) if (!sh.samples().isEmpty()) grid.add(sh.samples());
        int cracks = 0, chips = 0, bits = 0;
        for (Shape sh : block.shapes) {
            switch (sh.kind()) {
                case "crack" -> cracks++;
                case "chip" -> chips++;
                default -> bits++;
            }
        }
        for (int i = cracks; i < block.targetCrack; i++) place(block, grid, this::makeCrack, c -> crackFits(c) && rowsWithin(c.rows(), allowed));
        for (int i = chips; i < block.targetChip; i++) place(block, grid, this::makeChip, c -> rowsWithin(c.rows(), allowed));
        for (int i = bits; i < block.targetBit; i++) place(block, grid, this::makeEdgeBit, c -> rowsWithin(c.rows(), allowed));
    }

    private static void place(Block block, SpacingGrid grid, Maker make, Accept accept) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Shape sh = make.make(block.rand);
            if (!accept.test(sh) || (!sh.samples().isEmpty() && !grid.fits(sh.samples()))) continue;
            if (!sh.samples().isEmpty()) grid.add(sh.samples());
            block.shapes.add(sh);
            return;
        }
    }

    /** Recut one segment's rows from its shape list into the depth bytes. */
    private void carveBlock(int piece) {
        int top = piece * BLOCK;
        java.util.Arrays.fill(depth, top * ATLAS_W, (top + BLOCK) * ATLAS_W, 0f);
        for (Shape sh : blocks[piece].shapes) sh.carve().accept(top);
        for (int i = top * ATLAS_W; i < (top + BLOCK) * ATLAS_W; i++) data[i] = (byte) Math.round(depth[i] * 255);
        dirtyPieces[piece] = true;
    }

    /** mulberry32, as cracks.js, so the same seeds give the same first cracks. */
    static final class Rand {
        private int seed;
        Rand(int seed) { this.seed = seed; }
        double next() {
            seed = seed + 0x6D2B79F5;
            int t = (seed ^ (seed >>> 15)) * (1 | seed);
            t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
            return Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
        }
    }
}
