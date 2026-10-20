package games.brennan.dungeontrain.client.support;

import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.DonationSummaryClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * Whether this player has given enough to unlock the supporter perks — today, the book screen's Copy
 * button ({@code BookCopyButton}). A supporter is anyone whose all-time contribution on the relay's
 * ledger is at least {@link #MIN_TOTAL_AUD}.
 *
 * <p>Read from the relay's {@code GET /donations/summary?name=&uuid=} {@code you} block. Its
 * {@code totalUsd} key is a legacy name: the relay fills it in AUD (dp-relay {@code backers.js}), the
 * same unit Priority Books are counted in ({@link BookCredits}). <b>Only with network consent</b>: the
 * name and uuid leave the machine, so without consent nobody is a supporter and the perk stays locked.</p>
 *
 * <p>Kept here rather than in {@code DonationSummaryCache}: the title-screen prefetch writes an
 * anonymous summary (no {@code you} block) into that cache, which would read as "not a supporter" if it
 * landed after a named one. Asked at most once every ten minutes; a failed fetch keeps the last answer. A supporter
 * never stops being one, so once {@code true} this answer is kept for the session.</p>
 */
public final class SupporterStatus {

    /** All-time contribution, in AUD, that unlocks the perks. */
    public static final int MIN_TOTAL_AUD = 30;

    private static final long TTL_MS = 10 * 60_000L;

    /**
     * Dev builds only: {@code -Ddungeontrain.supporter=true|false} answers without asking the relay, so
     * both sides of the gate can be tried on one machine.
     */
    private static final String DEV_OVERRIDE_PROPERTY = "dungeontrain.supporter";

    private static volatile boolean supporter = false;
    private static volatile long fetchedAt = 0L;

    private SupporterStatus() {}

    /** Whether the relay has said this player gave at least {@link #MIN_TOTAL_AUD}. */
    public static boolean isSupporter() {
        Boolean forced = devOverride();
        if (forced != null) return forced;
        return consented() && supporter;
    }

    /** The rule itself, apart from where the summary came from. */
    static boolean qualifies(DonationSummaryClient.Summary summary) {
        return summary != null && summary.hasYou() && summary.youTotalUsd() >= MIN_TOTAL_AUD;
    }

    /**
     * Ask the relay if the answer is older than ten minutes (or missing). {@code onChange} runs on the
     * client thread, and only when the answer changed.
     */
    public static void refresh(Runnable onChange) {
        if (devOverride() != null || !consented() || supporter) return;
        if (fetchedAt != 0L && System.currentTimeMillis() - fetchedAt < TTL_MS) return;
        User user = Minecraft.getInstance().getUser();
        if (user == null || user.getName() == null || user.getName().isBlank()) return;
        // Stamped at request time: the fetch never calls back on failure, so this alone spaces retries.
        fetchedAt = System.currentTimeMillis();
        DonationSummaryClient.fetch(user.getName(), user.getProfileId(), summary -> {
            boolean now = qualifies(summary);
            boolean before = supporter;
            supporter = now;
            if (now != before && onChange != null) Minecraft.getInstance().execute(onChange);
        });
    }

    private static Boolean devOverride() {
        if (!DungeonTrain.isDevBuild()) return null;
        String v = System.getProperty(DEV_OVERRIDE_PROPERTY);
        if (v == null || v.isBlank()) return null;
        return Boolean.parseBoolean(v.trim());
    }

    private static boolean consented() {
        try {
            return DiscordPresenceClientConfig.isGranted();
        } catch (Throwable t) {
            return false;
        }
    }
}
