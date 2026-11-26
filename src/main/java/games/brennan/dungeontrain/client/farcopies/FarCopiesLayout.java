package games.brennan.dungeontrain.client.farcopies;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Where the far copies of an endless room go: the boxes to hand Distant Horizons, relative to the
 * room's base-tile origin, in two groups so the part that changes often stays small.
 *
 * <h2>Rings, by tile distance from the centre (Chebyshev)</h2>
 * <ul>
 *   <li><b>near</b> ({@code <= nearRadius}): every tile with no real copy standing in it gets the
 *       detailed model. This is where the stamped copies are, so it changes as they come and go.</li>
 *   <li><b>mid</b> ({@code <= midRadius}): the coarse model per tile.</li>
 *   <li><b>far</b> (to {@code farRadius}): only what runs the tile's whole length or width, each
 *       stretched across as many tiles as it can in one box.</li>
 * </ul>
 * <p>No ring draws into a tile a real copy stands in, nor into anything {@code blocked} — the
 * pair's own corridors and every other pair's structure.</p>
 */
public final class FarCopiesLayout {

    /** The boxes, in two groups: the near ring, and everything beyond it. */
    public record Result(List<FarBox> near, List<FarBox> outer, int farRadius) {
        public Result {
            near = List.copyOf(near);
            outer = List.copyOf(outer);
        }

        public int size() {
            return near.size() + outer.size();
        }
    }

    /** An inclusive rectangle of tiles. */
    public record TileRect(int x0, int z0, int x1, int z1) {
        boolean contains(int x, int z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }

        boolean intersects(TileRect o) {
            return x0 <= o.x1 && x1 >= o.x0 && z0 <= o.z1 && z1 >= o.z0;
        }
    }

    /**
     * Everything the layout depends on.
     *
     * @param stamped tiles a real copy stands in, as {@link #key}
     * @param blocked tile rectangles nothing may be drawn into
     */
    public record Input(RoomBoxModel model, int centreX, int centreZ, Set<Long> stamped,
                        List<TileRect> blocked, int nearRadius, int midRadius, int farRadius,
                        int maxBoxes) {
        public Input {
            stamped = Set.copyOf(stamped);
            blocked = List.copyOf(blocked);
        }
    }

    private FarCopiesLayout() {}

    public static long key(int tileX, int tileZ) {
        return ((long) tileX << 32) | (tileZ & 0xFFFFFFFFL);
    }

    /**
     * Lay the copies out within {@code maxBoxes}: the far ring shrinks first, then the middle one,
     * until the total fits.
     */
    public static Result build(Input in) {
        int near = Math.max(0, in.nearRadius());
        int mid = Math.max(near, in.midRadius());
        int far = Math.max(mid, in.farRadius());
        List<FarBox> nearBoxes = tiles(in, 0, near, in.model().detail());
        // The near ring is never cut: it is what fills the holes next to the player.
        int budget = Math.max(0, in.maxBoxes() - nearBoxes.size());
        while (true) {
            List<FarBox> outer = new ArrayList<>(tiles(in, near + 1, mid, in.model().coarse()));
            if (outer.size() <= budget) {
                List<FarBox> strips = farStrips(in, mid, far);
                if (outer.size() + strips.size() <= budget) {
                    outer.addAll(strips);
                    return new Result(nearBoxes, outer, far);
                }
                if (far > mid) {
                    far = mid + (far - mid) * 3 / 4;
                    continue;
                }
                return new Result(nearBoxes, outer, mid);
            }
            if (mid <= near) return new Result(nearBoxes, List.of(), near);
            mid = near + (mid - near) / 2;
            far = mid;
        }
    }

    /** One copy of {@code model} per free tile in the square annulus {@code from..to}. */
    private static List<FarBox> tiles(Input in, int from, int to, List<FarBox> model) {
        List<FarBox> out = new ArrayList<>();
        if (to < from || model.isEmpty()) return out;
        RoomBoxModel m = in.model();
        for (int dz = -to; dz <= to; dz++) {
            for (int dx = -to; dx <= to; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) < from) continue;
                int tx = in.centreX() + dx;
                int tz = in.centreZ() + dz;
                if (in.stamped().contains(key(tx, tz)) || blocked(in, tx, tz)) continue;
                float ox = (float) tx * m.sizeX();
                float oz = (float) tz * m.sizeZ();
                for (FarBox b : model) out.add(b.offset(ox, 0.0f, oz));
            }
        }
        return out;
    }

    private static boolean blocked(Input in, int tx, int tz) {
        for (TileRect r : in.blocked()) if (r.contains(tx, tz)) return true;
        return false;
    }

    /** The far ring: four rectangles round the middle square, each filled with stretched strips. */
    private static List<FarBox> farStrips(Input in, int mid, int far) {
        List<FarBox> out = new ArrayList<>();
        if (far <= mid || in.model().strips().size() == 0) return out;
        int cx = in.centreX();
        int cz = in.centreZ();
        List<TileRect> rects = List.of(
            new TileRect(cx - far, cz + mid + 1, cx + far, cz + far),
            new TileRect(cx - far, cz - far, cx + far, cz - mid - 1),
            new TileRect(cx - far, cz - mid, cx - mid - 1, cz + mid),
            new TileRect(cx + mid + 1, cz - mid, cx + far, cz + mid));
        for (TileRect rect : rects) fillRect(in, rect, out);
        return out;
    }

    private static void fillRect(Input in, TileRect rect, List<FarBox> out) {
        RoomBoxModel m = in.model();
        RoomBoxModel.Strips s = m.strips();
        List<TileRect> cuts = new ArrayList<>();
        for (TileRect b : in.blocked()) if (b.intersects(rect)) cuts.add(b);

        if (cuts.isEmpty()) {
            // Nothing in the way: each plane is one box across the whole rectangle.
            for (RoomBoxModel.Span p : s.planes()) {
                out.add(new FarBox(rect.x0() * (float) m.sizeX(), p.y0(), rect.z0() * (float) m.sizeZ(),
                    (rect.x1() + 1) * (float) m.sizeX(), p.y1(), (rect.z1() + 1) * (float) m.sizeZ(), p.rgb()));
            }
        }
        for (int tz = rect.z0(); tz <= rect.z1(); tz++) {
            for (int[] seg : segments(rect.x0(), rect.x1(), cuts, tz, true)) {
                float x0 = seg[0] * (float) m.sizeX();
                float x1 = (seg[1] + 1) * (float) m.sizeX();
                float oz = tz * (float) m.sizeZ();
                if (!cuts.isEmpty()) {
                    for (RoomBoxModel.Span p : s.planes()) {
                        out.add(new FarBox(x0, p.y0(), oz, x1, p.y1(), oz + m.sizeZ(), p.rgb()));
                    }
                }
                for (RoomBoxModel.Span st : s.xStrips()) {
                    out.add(new FarBox(x0, st.y0(), oz + st.a0(), x1, st.y1(), oz + st.a1(), st.rgb()));
                }
            }
        }
        for (int tx = rect.x0(); tx <= rect.x1(); tx++) {
            for (int[] seg : segments(rect.z0(), rect.z1(), cuts, tx, false)) {
                float z0 = seg[0] * (float) m.sizeZ();
                float z1 = (seg[1] + 1) * (float) m.sizeZ();
                float ox = tx * (float) m.sizeX();
                for (RoomBoxModel.Span st : s.zStrips()) {
                    out.add(new FarBox(ox + st.a0(), st.y0(), z0, ox + st.a1(), st.y1(), z1, st.rgb()));
                }
            }
        }
    }

    /**
     * The runs of {@code from..to} (inclusive) left once every cut crossing line {@code line} is
     * taken out. {@code alongX}: the run is in X and the line is a Z row; otherwise the reverse.
     */
    static List<int[]> segments(int from, int to, List<TileRect> cuts, int line, boolean alongX) {
        List<int[]> runs = new ArrayList<>();
        runs.add(new int[] {from, to});
        for (TileRect c : cuts) {
            int lineLo = alongX ? c.z0() : c.x0();
            int lineHi = alongX ? c.z1() : c.x1();
            if (line < lineLo || line > lineHi) continue;
            int cutLo = alongX ? c.x0() : c.z0();
            int cutHi = alongX ? c.x1() : c.z1();
            List<int[]> next = new ArrayList<>();
            for (int[] r : runs) {
                if (cutHi < r[0] || cutLo > r[1]) {
                    next.add(r);
                    continue;
                }
                if (cutLo > r[0]) next.add(new int[] {r[0], cutLo - 1});
                if (cutHi < r[1]) next.add(new int[] {cutHi + 1, r[1]});
            }
            runs = next;
        }
        return runs;
    }

    /**
     * The tiles a world box touches, or {@code null} when it misses the room's height entirely.
     * Bounds inclusive.
     */
    public static TileRect tilesOf(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                   int roomX, int roomY, int roomZ, int sizeX, int sizeY, int sizeZ) {
        if (maxY < roomY || minY >= roomY + sizeY) return null;
        return new TileRect(
            Math.floorDiv(minX - roomX, sizeX), Math.floorDiv(minZ - roomZ, sizeZ),
            Math.floorDiv(maxX - roomX, sizeX), Math.floorDiv(maxZ - roomZ, sizeZ));
    }
}
