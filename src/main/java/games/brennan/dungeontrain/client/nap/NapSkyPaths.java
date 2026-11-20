package games.brennan.dungeontrain.client.nap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The branching paths drawn on the nap sky ({@link NapSky}): each walks outward from the Engine along
 * solid lines, forking two ways at a star {@link #FORK} of the time, never visiting a star twice and
 * never deeper than {@link #MAX_DEPTH}. Pure (no Minecraft types), so it is unit-tested.
 *
 * <p>A life with perks is drawn from the stars it activated ({@link #fromStars}). A life from before
 * perks, which has no record, gets a random path seeded by the player and the life's number
 * ({@link #pastLife}), so it is the same every time the page is opened.</p>
 */
public final class NapSkyPaths {

    public static final double FORK = 0.75;
    public static final int MAX_DEPTH = 6;
    static final int MIN_DEPTH = 3;
    /** A star counts as "outward" unless it is this much nearer the Engine (editor units). */
    private static final double OUTWARD_SLACK = 10.0;
    private static final int TRIES = 40;

    private NapSkyPaths() {}

    /** One line of a path: from a star nearer the Engine to the next, {@code depth} steps out (1 = off the Engine). */
    public record Edge(int from, int to, int depth) {}

    /** A path: its lines in tracing order, how deep it goes, and the star it leaves the Engine by. */
    public record Path(List<Edge> edges, int depth, int first) {
        public Path {
            edges = List.copyOf(edges);
        }
    }

    /** A regrown path: the new path, the lines dropped from the old one, and the lines added. */
    public record Regrowth(Path path, List<Edge> removed, List<Edge> added) {
        public Regrowth {
            removed = List.copyOf(removed);
            added = List.copyOf(added);
        }
    }

    /** The path a past life drew: the same for the same player and life, every time. */
    public static Path pastLife(NapSky sky, UUID player, long life) {
        long seed = (player == null ? 0L : player.getMostSignificantBits() ^ Long.rotateLeft(player.getLeastSignificantBits(), 17))
            ^ (life * 0x9E3779B97F4A7C15L);
        return random(sky, rng(seed), -1);
    }

    /**
     * The path through {@code lit} (the stars a life activated): out from the Engine along solid lines
     * through lit stars, each line's depth its step from the Engine. A lit star no lit star leads to is
     * joined by the shortest run of lines from the path so far (the stars on that run are drawn too),
     * so every activation shows. Stars out of range and the Engine itself are ignored; nothing lit draws
     * nothing.
     */
    public static Path fromStars(NapSky sky, Set<Integer> lit) {
        Set<Integer> want = new HashSet<>();
        for (Integer s : lit) if (s != null && s > 0 && s < sky.size()) want.add(s);
        Map<Integer, Integer> depth = new LinkedHashMap<>();
        depth.put(0, 0);
        List<Edge> edges = new ArrayList<>();
        spread(sky, want, depth, edges, List.of(0));
        while (!depth.keySet().containsAll(want)) {
            List<Integer> bridge = shortestBridge(sky, want, depth);
            if (bridge.isEmpty()) break;
            for (int i = 1; i < bridge.size(); i++) {
                int from = bridge.get(i - 1), to = bridge.get(i);
                depth.put(to, depth.get(from) + 1);
                edges.add(new Edge(from, to, depth.get(to)));
            }
            spread(sky, want, depth, edges, bridge.subList(1, bridge.size()));
        }
        return new Path(edges, depthOf(edges), edges.isEmpty() ? -1 : edges.get(0).to());
    }

    /** Breadth first from {@code starts} through wanted stars not yet on the path. */
    private static void spread(NapSky sky, Set<Integer> want, Map<Integer, Integer> depth, List<Edge> edges,
                               List<Integer> starts) {
        Deque<Integer> queue = new ArrayDeque<>(starts);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            for (int n : sky.neighbours(cur)) {
                if (!want.contains(n) || depth.containsKey(n)) continue;
                depth.put(n, depth.get(cur) + 1);
                edges.add(new Edge(cur, n, depth.get(n)));
                queue.add(n);
            }
        }
    }

    /**
     * The fewest lines from a star on the path to a wanted star not on it: the run of stars, starting
     * with the one on the path. Empty when no such star can be reached.
     */
    private static List<Integer> shortestBridge(NapSky sky, Set<Integer> want, Map<Integer, Integer> depth) {
        Map<Integer, Integer> prev = new HashMap<>();
        Deque<Integer> queue = new ArrayDeque<>();
        for (int s : depth.keySet()) {
            prev.put(s, -1);
            queue.add(s);
        }
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            if (want.contains(cur) && !depth.containsKey(cur)) {
                List<Integer> run = new ArrayList<>();
                for (int s = cur; s != -1; s = prev.get(s)) run.add(0, s);
                return run;
            }
            for (int n : sky.neighbours(cur)) {
                if (prev.containsKey(n)) continue;
                prev.put(n, cur);
                queue.add(n);
            }
        }
        return List.of();
    }

    /**
     * A {@link Random} for {@code seed}, the seed scrambled first: {@code java.util.Random} gives nearly
     * the same opening numbers for nearby seeds (two visits a few ms apart drew the same path).
     */
    public static Random rng(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 33)) * 0xFF51AFD7ED558CCDL;
        z = (z ^ (z >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return new Random(z ^ (z >>> 33));
    }

    /**
     * A path from {@code rng}, at least {@link #MIN_DEPTH} deep where it can be, leaving the Engine by
     * a different star than {@code avoidFirst} when another exists.
     */
    public static Path random(NapSky sky, Random rng, int avoidFirst) {
        int[] roots = sky.neighbours(0);
        if (roots.length == 0) return new Path(List.of(), 0, -1);
        Path best = null;
        for (int k = 0; k < TRIES; k++) {
            int first = roots[rng.nextInt(roots.length)];
            if (first == avoidFirst && roots.length > 1 && k < TRIES - 1) continue;
            Set<Integer> seen = new HashSet<>(Set.of(0, first));
            List<Edge> edges = new ArrayList<>();
            edges.add(new Edge(0, first, 1));
            grow(sky, rng, first, 1, seen, edges);
            Path p = new Path(edges, depthOf(edges), first);
            if (best == null || p.depth() > best.depth()) best = p;
            if (p.depth() >= MIN_DEPTH) return p;
        }
        return best;
    }

    /**
     * Drop the branch below one star at depth 3 or more and grow it again from that star's parent:
     * the open, still-changing path of this life. Unchanged when no branch is deep enough.
     */
    public static Regrowth regrow(NapSky sky, Path path, Random rng) {
        List<Edge> nearer = path.edges().stream().filter(e -> e.depth() >= 2).toList();
        List<Edge> deep = path.edges().stream().filter(e -> e.depth() >= MIN_DEPTH).toList();
        if (deep.isEmpty()) deep = nearer;
        // A regrow that comes back the same shape shows nothing: try a few cuts for one that changes,
        // then cut nearer the Engine (a branch with only one way on can only grow back the same).
        for (int tries = 0; tries < 16 && !nearer.isEmpty(); tries++) {
            Regrowth r = regrowOnce(sky, path, rng, tries < 8 ? deep : nearer);
            if (!links(r.removed()).equals(links(r.added()))) return r;
        }
        // Nothing on this path can grow another way: draw it afresh from the Engine.
        Path fresh = random(sky, rng, path.first());
        return new Regrowth(fresh, path.edges(), fresh.edges());
    }

    private static Set<List<Integer>> links(List<Edge> edges) {
        Set<List<Integer>> out = new HashSet<>();
        for (Edge e : edges) out.add(List.of(e.from(), e.to()));
        return out;
    }

    private static Regrowth regrowOnce(NapSky sky, Path path, Random rng, List<Edge> deep) {
        Edge cut = deep.get(rng.nextInt(deep.size()));
        Set<Integer> dropped = below(path.edges(), cut.to());
        List<Edge> kept = new ArrayList<>(), removed = new ArrayList<>();
        for (Edge e : path.edges()) (dropped.contains(e.to()) ? removed : kept).add(e);
        Set<Integer> seen = new HashSet<>();
        seen.add(0);
        kept.forEach(e -> seen.add(e.to()));
        List<Edge> added = new ArrayList<>();
        grow(sky, rng, cut.from(), cut.depth() - 1, seen, added);
        List<Edge> all = new ArrayList<>(kept);
        all.addAll(added);
        return new Regrowth(new Path(all, depthOf(all), path.first()), removed, added);
    }

    /** Grow outward from {@code from} (at {@code depth}) breadth first, appending lines to {@code out}. */
    private static void grow(NapSky sky, Random rng, int from, int depth, Set<Integer> seen, List<Edge> out) {
        List<int[]> front = new ArrayList<>();
        front.add(new int[] {from, depth});
        while (!front.isEmpty()) {
            List<int[]> next = new ArrayList<>();
            for (int[] f : front) {
                int cur = f[0], d = f[1];
                if (d >= MAX_DEPTH) continue;
                List<Integer> options = new ArrayList<>();
                for (int n : sky.neighbours(cur)) {
                    if (!seen.contains(n) && sky.distanceToCentre(n) > sky.distanceToCentre(cur) - OUTWARD_SLACK) options.add(n);
                }
                if (options.isEmpty()) continue;
                int count = rng.nextDouble() < FORK && options.size() > 1 ? 2 : 1;
                for (int j = 0; j < count; j++) {
                    int pick = options.remove(rng.nextInt(options.size()));
                    seen.add(pick);
                    out.add(new Edge(cur, pick, d + 1));
                    next.add(new int[] {pick, d + 1});
                }
            }
            front = next;
        }
    }

    /** {@code star} and every star the path reaches through it. */
    private static Set<Integer> below(List<Edge> edges, int star) {
        Set<Integer> out = new HashSet<>(Set.of(star));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Edge e : edges) if (out.contains(e.from()) && out.add(e.to())) grew = true;
        }
        return out;
    }

    private static int depthOf(List<Edge> edges) {
        int d = 0;
        for (Edge e : edges) d = Math.max(d, e.depth());
        return d;
    }
}
