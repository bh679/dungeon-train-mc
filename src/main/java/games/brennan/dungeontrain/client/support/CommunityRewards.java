package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.live.life.LiveRewards;

import javax.annotation.Nullable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * The community rewards shelf: the five gifts that keep something on for everyone for a month,
 * and the rules every screen showing them shares (which one the Contribute panel features, when
 * the next free month starts, what the status lines say).
 *
 * <p>A reward is a donation with a thank-you attached, not a product: the copy says "Give" and
 * "keeps it on a month", never buy or price. Amounts live in lang keys
 * ({@code gui.dungeontrain.rewards.<id>.amount}); {@link Reward#costAud} is a reference copy and must
 * match those keys.</p>
 *
 * <p>Live state ({@code activeUntil}, who kept it on, queued months) comes from the relay's
 * {@code rewards[]} block, already parsed by {@link LiveRewards}. Pure apart from the lang-key and
 * link lookups at the bottom, so the rules unit-test without Minecraft.</p>
 */
public final class CommunityRewards {

    /** One reward month, as the relay stacks them. */
    static final long MONTH_MS = 30L * 24 * 60 * 60 * 1000;

    private static final String KEY = "gui.dungeontrain.rewards.";

    /** Dev builds only: forces every Give button on, so the purchase path can be tried before the relay sells. */
    static volatile boolean devForceGive = false;

    private CommunityRewards() {}

    /** The shelf, in its display order. */
    public enum Reward {
        QUICKER_STREAMS(LiveRewards.QUICKER_STREAMS, Group.STREAMS, 94),
        TWO_STREAMS(LiveRewards.TWO_STREAMS, Group.STREAMS, 140),
        VOICE_ON_STREAM(LiveRewards.VOICE_ON_STREAM, Group.STREAMS, 160),
        HD_STREAMS(LiveRewards.HD_STREAMS, Group.EXTRA, 90),
        REPLAYS_KEPT(LiveRewards.REPLAYS_KEPT, Group.EXTRA, 80);

        private final String id;
        private final Group group;
        private final int costAud;

        Reward(String id, Group group, int costAud) {
            this.id = id;
            this.group = group;
            this.costAud = costAud;
        }

        public String id() { return id; }
        public Group group() { return group; }
        /** Whole AUD: the figure converted into the player's chosen currency. Must match {@link #amountKey()}. */
        public int costAud() { return costAud; }

        public String nameKey() { return KEY + id + ".name"; }
        /** One line: "Reduce delay, from 30s to 5s. For EVERYONE, 1 month." */
        public String lineKey() { return KEY + id + ".line"; }
        /** The gift with its currency, "$94 AUD": the shelf's cost line and the payment confirm. */
        public String amountKey() { return KEY + id + ".amount"; }
        /** The bare figure, "$94", for the featured card's "Give $94". */
        public String amountShortKey() { return KEY + id + ".amount_short"; }

        /** The {@link UiAnalytics} target for this reward's Give button; relay ui-events.js must know it. */
        public String analyticsTarget() { return "reward_" + id; }

        @Nullable
        public static Reward byTarget(@Nullable String target) {
            if (target == null) return null;
            for (Reward r : values()) if (r.analyticsTarget().equals(target)) return r;
            return null;
        }
    }

    /** The shelf's two groups, shown as a small label on each card. */
    public enum Group {
        STREAMS, EXTRA;

        public String labelKey() { return KEY + "group." + name().toLowerCase(java.util.Locale.ROOT); }
    }

    // ---- live state -------------------------------------------------------------------------------

    /** Whether {@code reward} is on at {@code now}. */
    static boolean active(LiveRewards state, Reward reward, long now) {
        return state.active(reward.id(), now);
    }

    /**
     * When the next gift to {@code reward} would start: now when it is off, else after its current
     * month and every month already queued behind it. Mirrors the relay's stacking
     * ({@code from = max(now, until)}, each gift adding 30 days).
     */
    static long nextFreeMs(LiveRewards state, Reward reward, long now) {
        LiveRewards.Reward live = state.find(reward.id());
        if (live == null || live.activeUntilMs() <= now) return now;
        return live.activeUntilMs() + MONTH_MS * live.queuedBy().size();
    }

    /**
     * Brennan's order for the Contribute panel's featured reward (19 Oct 2026): the first one here
     * that is off now is the one shown. Separate from the shelf's display order.
     */
    static final List<Reward> FEATURE_ORDER = List.of(
            Reward.QUICKER_STREAMS, Reward.VOICE_ON_STREAM, Reward.TWO_STREAMS,
            Reward.HD_STREAMS, Reward.REPLAYS_KEPT);

    /**
     * The reward the Contribute panel features: the first in {@link #FEATURE_ORDER} that is off now,
     * so there is always something a giver can switch on today; when all five are on, the one whose
     * next free month comes soonest (ties in {@link #FEATURE_ORDER}).
     */
    static Reward featured(LiveRewards state, long now) {
        for (Reward r : FEATURE_ORDER) {
            if (!active(state, r, now)) return r;
        }
        Reward best = null;
        long soonest = Long.MAX_VALUE;
        for (Reward r : FEATURE_ORDER) {
            long next = nextFreeMs(state, r, now);
            if (next < soonest) { soonest = next; best = r; }
        }
        return best;
    }

    /** The month a gift made now would land in, in the player's zone. */
    static ZonedDateTime nextFreeMonth(LiveRewards state, Reward reward, long now, ZoneId zone) {
        return Instant.ofEpochMilli(nextFreeMs(state, reward, now)).atZone(zone);
    }

    /** Names of the givers queued behind the current month, in order. */
    static List<String> queued(LiveRewards state, Reward reward) {
        LiveRewards.Reward live = state.find(reward.id());
        return live == null ? List.of() : live.queuedBy();
    }

    /** Who kept {@code reward} on this month, or "" when it is off. */
    static String keptOnBy(LiveRewards state, Reward reward, long now) {
        LiveRewards.Reward live = state.find(reward.id());
        return live != null && live.activeUntilMs() > now ? live.by() : "";
    }

    // ---- links ------------------------------------------------------------------------------------

    /**
     * Whether gifts to rewards can be made yet: the relay serves the {@code rewards_pay} checkout
     * route only once each reward has a Stripe price. Until then every Give button reads "Soon".
     */
    public static boolean givingOpen() {
        return devForceGive || games.brennan.dungeontrain.client.links.OfficialLinks.rewardsPay() != null;
    }

    /** The checkout URL for a gift to {@code reward}, or null when giving is not open. */
    @Nullable
    public static String giveUrl(Reward reward) {
        String base = games.brennan.dungeontrain.client.links.OfficialLinks.rewardsPay();
        if (base == null) return null;
        return PaymentLinks.rewardUrl(base, reward.id());
    }
}
