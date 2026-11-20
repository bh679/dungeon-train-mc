package games.brennan.dungeontrain.client.nap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The shape of the perks constellation behind the nap farewell's "a life ongoing" page: star positions
 * (the editor's 800 x 800 space, star 0 the Engine at the centre) and the solid lines between them.
 * Geometry only, from {@code assets/dungeontrain/nap/sky.json} (see {@code scripts/nap/README.md}):
 * no perk names, so the page hints at the map without showing a perk. Immutable.
 */
public final class NapSky {

    private static final Logger LOG = LoggerFactory.getLogger(NapSky.class);
    private static final String RESOURCE = "/assets/dungeontrain/nap/sky.json";
    public static final double CENTRE = 400.0;
    public static final double SIZE = 800.0;

    private static NapSky cached;
    private static boolean loaded;

    private final double[] xs;
    private final double[] ys;
    private final int[] tiers;
    private final int[][] neighbours;

    NapSky(double[] xs, double[] ys, int[] tiers, int[][] neighbours) {
        this.xs = xs.clone();
        this.ys = ys.clone();
        this.tiers = tiers.clone();
        this.neighbours = neighbours;
    }

    /** The bundled sky, loaded once; null when the file is missing or unreadable (the page then has no sky). */
    public static synchronized NapSky get() {
        if (!loaded) {
            loaded = true;
            try (InputStream in = NapSky.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    LOG.warn("[DungeonTrain] Nap sky geometry {} is missing; the page draws no sky", RESOURCE);
                } else {
                    cached = parse(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                LOG.warn("[DungeonTrain] Nap sky geometry {} is unreadable; the page draws no sky", RESOURCE, e);
                cached = null;
            }
        }
        return cached;
    }

    /** Parse {@code {"stars":[[x,y,tier],...],"lines":[[a,b],...]}}; throws on a malformed file. */
    static NapSky parse(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        JsonArray stars = root.getAsJsonArray("stars");
        int n = stars.size();
        if (n < 2) throw new IllegalArgumentException("sky needs the Engine and at least one star");
        double[] xs = new double[n], ys = new double[n];
        int[] tiers = new int[n];
        for (int i = 0; i < n; i++) {
            JsonArray s = stars.get(i).getAsJsonArray();
            xs[i] = s.get(0).getAsDouble();
            ys[i] = s.get(1).getAsDouble();
            tiers[i] = s.size() > 2 ? s.get(2).getAsInt() : 0;
        }
        List<List<Integer>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (JsonElement e : root.getAsJsonArray("lines")) {
            JsonArray l = e.getAsJsonArray();
            int a = l.get(0).getAsInt(), b = l.get(1).getAsInt();
            if (a < 0 || b < 0 || a >= n || b >= n || a == b) throw new IllegalArgumentException("bad line " + l);
            if (!adj.get(a).contains(b)) adj.get(a).add(b);
            if (!adj.get(b).contains(a)) adj.get(b).add(a);
        }
        int[][] neighbours = new int[n][];
        for (int i = 0; i < n; i++) neighbours[i] = adj.get(i).stream().mapToInt(Integer::intValue).toArray();
        return new NapSky(xs, ys, tiers, neighbours);
    }

    public int size() {
        return xs.length;
    }

    public double x(int i) {
        return xs[i];
    }

    public double y(int i) {
        return ys[i];
    }

    public int tier(int i) {
        return tiers[i];
    }

    /** Stars joined to {@code i} by a solid line (a copy). */
    public int[] neighbours(int i) {
        return neighbours[i].clone();
    }

    public boolean joined(int a, int b) {
        for (int n : neighbours[a]) if (n == b) return true;
        return false;
    }

    public double distanceToCentre(int i) {
        return Math.hypot(xs[i] - CENTRE, ys[i] - CENTRE);
    }
}
