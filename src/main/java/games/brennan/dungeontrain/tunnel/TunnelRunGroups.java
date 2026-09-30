package games.brennan.dungeontrain.tunnel;

import games.brennan.dungeontrain.template.TemplateGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a tunnel one template group from entrance to exit even though worldgen builds it one chunk
 * at a time and no chunk in the middle of a long tunnel can see either end.
 *
 * <p><b>What counts as one tunnel.</b> The group only changes after a stretch of at least
 * {@link #GROUP_GAP_CHUNKS} chunks with no tunnel in it: qualified {@link Run runs} closer than
 * {@link #BRIDGE} columns are one tunnel, both within a chunk and across any number of chunk
 * edges — so a string of short tunnels a little apart keeps one look.</p>
 *
 * <p>Each chunk groups its runs into such tunnels and takes each tunnel's group from the first of
 * these that answers:</p>
 * <ol>
 *   <li>the answer this same chunk X already recorded for that edge (another chunk-Z row of the
 *       corridor got here first);</li>
 *   <li>for the westmost tunnel, the <b>east</b> edge recorded by the nearest chunk to the west
 *       (up to {@link #GROUP_GAP_CHUNKS} away; chunks without a tunnel record nothing and are
 *       walked past), when the two are within {@link #BRIDGE} columns of each other;</li>
 *   <li>for the eastmost tunnel, likewise the nearest chunk to the east's <b>west</b> edge;</li>
 *   <li>otherwise a roll keyed by where the tunnel starts in this chunk — or, for a run carrying on
 *       straight from the west edge, by a coarse X band — so every roll is deterministic.</li>
 * </ol>
 * <p>The edge answers are then recorded in an {@link EdgeBook} ({@link TunnelGroupData} in play),
 * with how far the tunnel sits from that edge. Known limit: if both neighbours of a middle chunk
 * were generated first with different groups, the west one wins and one seam remains — only
 * possible when a long tunnel generates out of order.</p>
 *
 * <p>Groups travel as tokens: {@code "*"} = no group filter (nothing to roll between), {@code ""} =
 * {@link TemplateGroup#UNGROUPED}, anything else = a group id.</p>
 */
public final class TunnelRunGroups {

    /** A tunnel's group may only change after a tunnel-free gap of at least this many chunks. */
    static final int GROUP_GAP_CHUNKS = 5;
    /** Runs fewer than this many columns apart are one tunnel and share its group. */
    static final int BRIDGE = GROUP_GAP_CHUNKS * 16;
    /** Width of the fallback roll band, in blocks. */
    static final int FALLBACK_BAND = 1024;
    /** Keeps fallback band keys clear of start-X keys. */
    private static final long FALLBACK_KEY_BASE = 1L << 40;

    static final String NO_FILTER = "*";

    private TunnelRunGroups() {}

    /** A recorded edge answer: the tunnel's group token and how many columns it stops short of the edge. */
    public record Edge(String token, int gap) {}

    /** Where resolved edge groups are remembered between chunks. Implementations must be thread-safe. */
    public interface EdgeBook {
        /** The tunnel nearest {@code chunkX}'s west edge, or null if none was recorded. */
        Edge westOf(int chunkX);

        /** The tunnel nearest {@code chunkX}'s east edge, or null if none was recorded. */
        Edge eastOf(int chunkX);

        /** Record edge answers (null = nothing to record); never overwrites an existing answer. */
        void record(int chunkX, Edge west, Edge east);
    }

    /** Rolls a tunnel's group for {@code key}, gated at {@code worldX}; null = no group filter. */
    @FunctionalInterface
    public interface Roller {
        TemplateGroup roll(long key, int worldX);
    }

    /** A maximal stretch of qualified columns within one chunk, {@code [startDx, endDx]} inclusive. */
    public record Run(int startDx, int endDx, boolean continuesWest, boolean continuesEast) {}

    /**
     * Split a chunk's 16 qualification flags into runs. {@code prevQualified} / {@code nextQualified}
     * are the probes one column outside the chunk, which decide whether an edge run carries on.
     */
    public static List<Run> runs(boolean[] qualified, boolean prevQualified, boolean nextQualified) {
        List<Run> out = new ArrayList<>();
        int n = qualified.length;
        int dx = 0;
        while (dx < n) {
            if (!qualified[dx]) {
                dx++;
                continue;
            }
            int start = dx;
            while (dx + 1 < n && qualified[dx + 1]) dx++;
            out.add(new Run(start, dx, start == 0 && prevQualified, dx == n - 1 && nextQualified));
            dx++;
        }
        return out;
    }

    /** Runs bridged into one tunnel: {@code runs} from its first to its last, west to east. */
    private record Tunnel(List<Run> runs) {
        int startDx() {
            return runs.get(0).startDx();
        }

        int endDx() {
            return runs.get(runs.size() - 1).endDx();
        }

        /** Columns between this tunnel's east end and the chunk's east edge. */
        int eastGap() {
            return 15 - endDx();
        }
    }

    /** Group {@code runs} (west to east) into tunnels, bridging gaps under {@link #BRIDGE}. */
    static List<List<Run>> bridge(List<Run> runs) {
        List<List<Run>> out = new ArrayList<>();
        List<Run> cur = null;
        for (Run run : runs) {
            if (cur != null && run.startDx() - cur.get(cur.size() - 1).endDx() - 1 < BRIDGE) {
                cur.add(run);
            } else {
                cur = new ArrayList<>();
                cur.add(run);
                out.add(cur);
            }
        }
        return out;
    }

    /**
     * Resolve every tunnel's group and record the chunk's edge answers, atomically against
     * {@code book}. Returns one entry per column: the column's group, or null for an unqualified
     * column <em>or</em> a tunnel with no group filter.
     */
    public static TemplateGroup[] resolve(EdgeBook book, int chunkX, List<Run> runs, Roller roller) {
        TemplateGroup[] perColumn = new TemplateGroup[16];
        List<List<Run>> grouped = bridge(runs);
        if (grouped.isEmpty()) return perColumn;
        int chunkMinX = chunkX << 4;
        synchronized (book) {
            Edge westRecord = null;
            Edge eastRecord = null;
            for (int i = 0; i < grouped.size(); i++) {
                Tunnel t = new Tunnel(grouped.get(i));
                String token = null;
                if (i == 0) token = fromWest(book, chunkX, t);
                if (token == null && i == grouped.size() - 1) token = fromEast(book, chunkX, t);
                if (token == null) {
                    int worldX = chunkMinX + t.startDx();
                    long key = t.runs().get(0).continuesWest()
                        ? FALLBACK_KEY_BASE + Math.floorDiv(worldX, FALLBACK_BAND)
                        : worldX;
                    token = toToken(roller.roll(key, worldX));
                }
                if (i == 0 && t.startDx() < BRIDGE) westRecord = new Edge(token, t.startDx());
                if (i == grouped.size() - 1 && t.eastGap() < BRIDGE) eastRecord = new Edge(token, t.eastGap());
                TemplateGroup g = fromToken(token);
                for (Run run : t.runs()) {
                    for (int dx = run.startDx(); dx <= run.endDx(); dx++) perColumn[dx] = g;
                }
            }
            book.record(chunkX, westRecord, eastRecord);
        }
        return perColumn;
    }

    /**
     * The westmost tunnel's inherited token: this chunk's own west answer, else the east answer of
     * the nearest chunk to the west that recorded one, if it lies within {@link #BRIDGE} columns.
     */
    private static String fromWest(EdgeBook book, int chunkX, Tunnel t) {
        if (t.startDx() >= BRIDGE) return null;
        Edge own = book.westOf(chunkX);
        if (own != null) return own.token();
        for (int k = 1; k <= GROUP_GAP_CHUNKS; k++) {
            Edge west = book.eastOf(chunkX - k);
            if (west == null) continue;
            return west.gap() + 16 * (k - 1) + t.startDx() < BRIDGE ? west.token() : null;
        }
        return null;
    }

    /** The eastmost tunnel's inherited token — {@link #fromWest} mirrored. */
    private static String fromEast(EdgeBook book, int chunkX, Tunnel t) {
        if (t.eastGap() >= BRIDGE) return null;
        Edge own = book.eastOf(chunkX);
        if (own != null) return own.token();
        for (int k = 1; k <= GROUP_GAP_CHUNKS; k++) {
            Edge east = book.westOf(chunkX + k);
            if (east == null) continue;
            return east.gap() + 16 * (k - 1) + t.eastGap() < BRIDGE ? east.token() : null;
        }
        return null;
    }

    static String toToken(TemplateGroup g) {
        return g == null ? NO_FILTER : g.id();
    }

    static TemplateGroup fromToken(String token) {
        if (token == null || NO_FILTER.equals(token)) return null;
        return TemplateGroup.of(token);
    }
}
