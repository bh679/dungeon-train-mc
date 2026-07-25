package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.relay.DonationSummaryClient;
import org.jetbrains.annotations.Nullable;

/**
 * Client-only holder for the most recent donation summary fetched from the relay (see
 * {@link DonationSummaryClient}). The death screen kicks a fetch on open and reads this live each
 * render frame — a late reply just fills in, exactly like {@link DeathStatsCache}.
 *
 * <p>No expiry: overwritten by each fetch and kept for the JVM lifetime so a re-opened death
 * screen shows the last-known ledger immediately while a fresh fetch is in flight.</p>
 */
public final class DonationSummaryCache {

    private static volatile @Nullable DonationSummaryClient.Summary last;

    private DonationSummaryCache() {}

    public static void set(DonationSummaryClient.Summary summary) {
        last = summary;
    }

    public static @Nullable DonationSummaryClient.Summary get() {
        return last;
    }
}
