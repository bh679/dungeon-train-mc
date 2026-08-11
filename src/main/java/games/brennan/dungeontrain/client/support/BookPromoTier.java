package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;

/**
 * The three price points offered on the book-promotion page
 * ({@link games.brennan.dungeontrain.client.support.BookPromoScreen}) — buying a community book a
 * better place in the shared pool.
 *
 * <p><b>Why this is a sibling of {@link SupportTier} rather than the same enum.</b> Both are
 * quantities of the very same Stripe unit — the "Dungeon Train Priority Book" — so mechanically they
 * are interchangeable. What differs is what the player is being asked: a donation supports the mod
 * and the Priority Books are the thank-you, while this is a purchase whose whole point is the books.
 * That is a different question with different copy and its own funnel, and collapsing the two would
 * make the analytics unable to tell a donor from a buyer.</p>
 *
 * <p><b>A tier IS a quantity.</b> The relay's checkout route multiplies its unit price by
 * {@code ?qty=}, so the amounts these can express are exactly the multiples of that unit — which is
 * why the middle tier is $30 and not a rounder-sounding $25. Same reasoning as {@link SupportTier},
 * and the same reason the figures live in the lang file: the unit is re-priceable in the Stripe
 * Dashboard without a mod release, and a jar already in someone's mods folder can never be
 * corrected.</p>
 */
public enum BookPromoTier {

    /** One Priority Book — promoting a single book, the entry point. */
    PROMOTE(1, "promote", UiAnalytics.TARGET_BOOK_PROMO_PROMOTE),

    /** Three units. */
    BOOST(3, "boost", UiAnalytics.TARGET_BOOK_PROMO_BOOST),

    /** Seven units — the top tier, mirroring the donation page's own seven-unit step. */
    FEATURE(7, "feature", UiAnalytics.TARGET_BOOK_PROMO_FEATURE);

    private static final String LABEL_PREFIX = "gui.dungeontrain.book_promo.tier.";

    private final int quantity;
    private final String key;
    private final String analyticsTarget;

    BookPromoTier(int quantity, String key, String analyticsTarget) {
        this.quantity = quantity;
        this.key = key;
        this.analyticsTarget = analyticsTarget;
    }

    /** Units of the relay's checkout price — sent as {@code ?qty=} and multiplied by Stripe. */
    public int quantity() {
        return quantity;
    }

    /** Button label, carrying the amount (e.g. "$30 Boost"). */
    public String labelKey() {
        return LABEL_PREFIX + key;
    }

    /** Hover copy spelling out what the tier buys — the label alone has no room for it. */
    public String tooltipKey() {
        return LABEL_PREFIX + key + ".tooltip";
    }

    /**
     * The {@link UiAnalytics} target name for this tier's clicks. Must stay in lock-step with the
     * relay's {@code ui-events.js} TARGETS allowlist, which rejects anything it doesn't know.
     */
    public String analyticsTarget() {
        return analyticsTarget;
    }

    /** This tier's checkout URL, or null when the relay has served no checkout route. */
    public String url() {
        return PaymentLinks.checkoutUrl(quantity);
    }
}
