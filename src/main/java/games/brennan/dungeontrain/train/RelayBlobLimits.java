package games.brennan.dungeontrain.train;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * How big a shared-carriage blob from the relay may be, encoded and decoded.
 *
 * <p>A blob comes from another player, and the relay never opens it: it checks only the encoded
 * length ({@code CARRIAGES_MAX_CHARS}, 700k by default). Gzip can inflate a blob that size roughly a
 * thousandfold, so the mod bounds what it decodes itself.</p>
 *
 * <p>The numbers are measured, not guessed. Read through Minecraft's own {@code NbtAccounter}, the
 * largest real snapshots in dev and bench worlds (2026-11-18) were a 96k-char room at 7.4 MB accounted,
 * and a 361k-char room tile at 29.5 MB. The worst ratio seen was 126 accounted bytes per Base64 char.
 * At that ratio a blob at the relay's 700k cap accounts about 88 MB, so {@link #MAX_ACCOUNTED_BYTES}
 * leaves ~45% headroom over the largest build the relay can legitimately hold.</p>
 */
public final class RelayBlobLimits {

    /** Longest encoded blob accepted: the relay's 700k default, plus room for an env override. */
    public static final int MAX_ENCODED_CHARS = 1_000_000;

    /** The {@code NbtAccounter} budget for one decoded blob. */
    public static final long MAX_ACCOUNTED_BYTES = 128L * 1024 * 1024;

    /** The budget for one build's base and every delta folded onto it, together. */
    public static final long MAX_FOLD_ACCOUNTED = 256L * 1024 * 1024;

    /** Most pending deltas one build may carry: the relay's own {@code MAX_DELTAS}. */
    public static final int MAX_DELTAS = 512;

    /** Distinct things {@link #firstReport} remembers before it starts forgetting the oldest. */
    private static final int MAX_REMEMBERED_REPORTS = 256;

    private static final Set<String> REPORTED = Collections.synchronizedSet(Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_REMEMBERED_REPORTS;
                }
            }));

    private RelayBlobLimits() {}

    /**
     * Whether this is the first time {@code key} is reported, so a build that keeps coming back from
     * the pool is logged once rather than on every lease.
     */
    public static boolean firstReport(String key) {
        return REPORTED.add(key);
    }

    /** Throws when a build carries more pending deltas than the relay itself allows. */
    public static void checkDeltaCount(int pending) throws RelayBlobTooLargeException {
        if (pending > MAX_DELTAS) {
            throw new RelayBlobTooLargeException(
                    pending + " pending deltas, over the " + MAX_DELTAS + " cap", true);
        }
    }

    /**
     * What is left to spend decoding one build: its base and every delta on it.
     *
     * <p>Mutable on purpose. It is a running meter, like the {@code NbtAccounter} it feeds, and it lives
     * only for the length of one fold.</p>
     */
    public static final class FoldBudget {
        private long remaining;

        public FoldBudget() {
            this(MAX_FOLD_ACCOUNTED);
        }

        /** A budget of {@code total} accounted bytes. Package-private for tests. */
        FoldBudget(long total) {
            this.remaining = total;
        }

        /** The accounter cap for the next blob: the per-blob cap, or what is left if that is less. */
        long capForNext() {
            return Math.min(MAX_ACCOUNTED_BYTES, remaining);
        }

        void charge(long used) {
            remaining = Math.max(0L, remaining - Math.max(0L, used));
        }

        public long remaining() {
            return remaining;
        }
    }
}
