package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;

/**
 * Which words {@link PaymentConfirmScreen} says for a given analytics target, or nothing at all
 * when the target is not a payment link.
 *
 * <p>The donation funnel showed most players backing out on vanilla's confirm screen, which prints
 * the raw checkout URL (query string and all) and reads as a phishing warning. The replacement names
 * the provider and, for the price points, the amount, and says where the browser is going. Which of
 * those sentences applies is decided here, from the same target name the analytics already carry,
 * so every call site resolves copy exactly one way and there is no second list of "money buttons"
 * to drift from {@link SupportTier} and {@link UiAnalytics}.</p>
 *
 * <p>Pure: no Minecraft bootstrap on any path, so the mapping unit-tests directly.</p>
 *
 * @param titleKey lang key of the question line; takes one {@code %s} (the amount) when
 *                 {@link #amountKey} is non-null, none otherwise
 * @param bodyKey  lang key of the one-line explanation under it
 * @param amountKey lang key of the tier's price (e.g. "$30 AUD"), or null when the provider has no
 *                  fixed amount
 */
public record PaymentConfirmCopy(String titleKey, String bodyKey, String amountKey) {

    static final String PREFIX = "gui.dungeontrain.support.confirm.";

    static final String TITLE_STRIPE = PREFIX + "title.stripe";
    static final String TITLE_WECHAT = PREFIX + "title.wechat";
    static final String TITLE_PATREON = PREFIX + "title.patreon";
    static final String TITLE_REVOLUT = PREFIX + "title.revolut";
    static final String BODY_STRIPE = PREFIX + "body.stripe";
    // The raw Stripe Payment Link, used only when the relay serves no checkout route: it opens on
    // Stripe's own domain, so the "at brennan.games" line would be wrong there.
    static final String BODY_STRIPE_DIRECT = PREFIX + "body.stripe_direct";
    static final String BODY_PATREON = PREFIX + "body.patreon";
    static final String BODY_REVOLUT = PREFIX + "body.revolut";
    static final String OPEN = PREFIX + "open";
    static final String CANCEL = PREFIX + "cancel";
    static final String COPY_LINK = PREFIX + "copy_link";
    static final String COPIED = PREFIX + "copied";

    /** The host the relay's checkout route lives on; anything else is a provider's own page. */
    private static final String RELAY_HOST = "brennan.games";

    /**
     * The copy for {@code analyticsTarget}, or null when it is not a payment link (Discord,
     * affiliate, translate, an inline URL, or nothing at all) and the caller should keep vanilla's
     * confirm screen.
     */
    public static PaymentConfirmCopy forTarget(String analyticsTarget) {
        return forTarget(analyticsTarget, null);
    }

    /**
     * As {@link #forTarget(String)}, but with the URL about to open, so a Stripe link that does not
     * go through the relay (no {@code brennan.games} host) gets the line that says so instead of
     * one promising a page it will not land on. A null URL keeps the relay wording.
     */
    public static PaymentConfirmCopy forTarget(String analyticsTarget, String url) {
        PaymentConfirmCopy copy = resolve(analyticsTarget);
        if (copy == null || !BODY_STRIPE.equals(copy.bodyKey()) || url == null || onRelay(url)) return copy;
        return new PaymentConfirmCopy(copy.titleKey(), BODY_STRIPE_DIRECT, copy.amountKey());
    }

    /** Whether {@code url}'s host is the relay's (or a subdomain of it). */
    static boolean onRelay(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host != null && (host.equals(RELAY_HOST) || host.endsWith("." + RELAY_HOST));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static PaymentConfirmCopy resolve(String analyticsTarget) {
        if (analyticsTarget == null) return null;
        for (SupportTier tier : SupportTier.values()) {
            if (tier.analyticsTarget().equals(analyticsTarget)) {
                return new PaymentConfirmCopy(TITLE_STRIPE, BODY_STRIPE, tier.amountKey());
            }
        }
        // A gift to a community reward is one whole Stripe checkout, named by its amount like a tier.
        CommunityRewards.Reward reward = CommunityRewards.Reward.byTarget(analyticsTarget);
        if (reward != null) return new PaymentConfirmCopy(TITLE_STRIPE, BODY_STRIPE, reward.amountKey());
        return switch (analyticsTarget) {
            // The China route is a Stripe checkout too, so it shares Stripe's explanation line.
            case UiAnalytics.TARGET_DONATE_CN -> new PaymentConfirmCopy(TITLE_WECHAT, BODY_STRIPE, null);
            case UiAnalytics.TARGET_PATREON -> new PaymentConfirmCopy(TITLE_PATREON, BODY_PATREON, null);
            case UiAnalytics.TARGET_DONATE -> new PaymentConfirmCopy(TITLE_REVOLUT, BODY_REVOLUT, null);
            default -> null;
        };
    }
}
