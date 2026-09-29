package games.brennan.dungeontrain.tunnel;

import games.brennan.dungeontrain.template.TemplateGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a tunnel one template group from entrance to exit even though worldgen builds it one chunk
 * at a time and no chunk in the middle of a long tunnel can see either end.
 *
 * <p>Each chunk splits its qualified columns into {@link Run runs}, and each run takes its group
 * from the first of these that answers:</p>
 * <ol>
 *   <li>the group this same chunk X already recorded for that edge (another chunk-Z row of the
 *       corridor got here first);</li>
 *   <li>a run continuing west inherits what chunk {@code x-1} recorded at its <b>east</b> edge;</li>
 *   <li>a run continuing east inherits what chunk {@code x+1} recorded at its <b>west</b> edge;</li>
 *   <li>a run whose entrance lies in this chunk rolls, keyed by the entrance X — deterministic per
 *       tunnel;</li>
 *   <li>otherwise (a middle chunk generated before both neighbours) it rolls keyed by a coarse X
 *       band — still deterministic.</li>
 * </ol>
 * <p>The edge answers are then recorded in an {@link EdgeBook} ({@link TunnelGroupData} in play).
 * Known limit: if both neighbours of a middle chunk were generated first with different groups, the
 * west one wins and one seam remains — only possible when a long tunnel generates out of order.</p>
 *
 * <p>Groups travel as tokens: {@code "*"} = no group filter (nothing to roll between), {@code ""} =
 * {@link TemplateGroup#UNGROUPED}, anything else = a group id.</p>
 */
public final class TunnelRunGroups {

    /** Width of the fallback roll band, in blocks. */
    static final int FALLBACK_BAND = 1024;
    /** Keeps fallback band keys clear of entrance-X keys. */
    private static final long FALLBACK_KEY_BASE = 1L << 40;

    static final String NO_FILTER = "*";

    private TunnelRunGroups() {}

    /** Where resolved edge groups are remembered between chunks. Implementations must be thread-safe. */
    public interface EdgeBook {
        /** Token recorded for the run touching {@code chunkX}'s west edge, or null if none. */
        String westOf(int chunkX);

        /** Token recorded for the run touching {@code chunkX}'s east edge, or null if none. */
        String eastOf(int chunkX);

        /** Record edge tokens (null = nothing to record); never overwrites an existing answer. */
        void record(int chunkX, String west, String east);
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

    /**
     * Resolve every run's group and record the chunk's edge answers, atomically against
     * {@code book}. Returns one entry per column: the column's group, or null for an unqualified
     * column <em>or</em> a run with no group filter.
     */
    public static TemplateGroup[] resolve(EdgeBook book, int chunkX, List<Run> runs, Roller roller) {
        TemplateGroup[] perColumn = new TemplateGroup[16];
        int chunkMinX = chunkX << 4;
        synchronized (book) {
            String westToken = null;
            String eastToken = null;
            for (Run run : runs) {
                String token = inherited(book, chunkX, run);
                if (token == null) {
                    boolean entranceHere = !run.continuesWest();
                    int worldX = chunkMinX + run.startDx();
                    long key = entranceHere
                        ? worldX
                        : FALLBACK_KEY_BASE + Math.floorDiv(worldX, FALLBACK_BAND);
                    token = toToken(roller.roll(key, worldX));
                }
                if (run.continuesWest()) westToken = token;
                if (run.continuesEast()) eastToken = token;
                TemplateGroup g = fromToken(token);
                for (int dx = run.startDx(); dx <= run.endDx(); dx++) perColumn[dx] = g;
            }
            book.record(chunkX, westToken, eastToken);
        }
        return perColumn;
    }

    private static String inherited(EdgeBook book, int chunkX, Run run) {
        if (run.continuesWest()) {
            String own = book.westOf(chunkX);
            if (own != null) return own;
            String west = book.eastOf(chunkX - 1);
            if (west != null) return west;
        }
        if (run.continuesEast()) {
            String own = book.eastOf(chunkX);
            if (own != null) return own;
            return book.westOf(chunkX + 1);
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
