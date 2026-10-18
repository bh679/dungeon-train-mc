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
    static final String BODY_PATREON = PREFIX + "body.patreon";
    static final String BODY_REVOLUT = PREFIX + "body.revolut";
    static final String OPEN = PREFIX + "open";
    static final String CANCEL = PREFIX + "cancel";

    /**
     * The copy for {@code analyticsTarget}, or null when it is not a payment link (Discord,
     * affiliate, translate, an inline URL, or nothing at all) and the caller should keep vanilla's
     * confirm screen.
     */
    public static PaymentConfirmCopy forTarget(String analyticsTarget) {
        if (analyticsTarget == null) return null;
        for (SupportTier tier : SupportTier.values()) {
            if (tier.analyticsTarget().equals(analyticsTarget)) {
                return new PaymentConfirmCopy(TITLE_STRIPE, BODY_STRIPE, tier.amountKey());
            }
        }
        return switch (analyticsTarget) {
            // The China route is a Stripe checkout too, so it shares Stripe's explanation line.
            case UiAnalytics.TARGET_DONATE_CN -> new PaymentConfirmCopy(TITLE_WECHAT, BODY_STRIPE, null);
            case UiAnalytics.TARGET_PATREON -> new PaymentConfirmCopy(TITLE_PATREON, BODY_PATREON, null);
            case UiAnalytics.TARGET_DONATE -> new PaymentConfirmCopy(TITLE_REVOLUT, BODY_REVOLUT, null);
            default -> null;
        };
    }
}
