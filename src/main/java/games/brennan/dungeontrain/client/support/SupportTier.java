package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;

/**
 * The three named price points offered on both donation surfaces — the Support page's Financial
 * section ({@link SupportScreen}) and the death screen's Contribute window
 * ({@link games.brennan.dungeontrain.client.DonationOptionsScreen}).
 *
 * <p><b>Why named amounts at all.</b> Both screens used to offer only open-ended options: a Revolut
 * link and Patreon, neither of which names a figure. That leaves the donor to invent an amount on a
 * page they have never seen, which is the weakest point of the funnel. Three concrete amounts, each
 * with a reason attached, ask a much easier question — and the open-ended options survive underneath
 * for the people the tiers don't fit.</p>
 *
 * <p><b>Why a quantity and not a price.</b> The relay's checkout route builds a Stripe Checkout
 * Session from one unit price ($10 AUD, the "Dungeon Train Priority Book") and multiplies it by
 * {@code ?qty=}. So a tier IS a quantity, and the amounts it can express are exactly the multiples
 * of that unit — which is why the middle tier is $30 rather than a rounder-sounding $25. A donor at
 * {@link #SERVER_COSTS} therefore receives seven Priority Books; the perk scales with the tier.</p>
 *
 * <p><b>Amounts live in the lang file, not here.</b> The label is the only place a figure is shown,
 * and the unit price is editable in the Stripe Dashboard without a mod release. Baking "$10" into
 * Java would mean a re-release to re-price, and a jar in someone's mods folder can never be
 * corrected — so the number stays where a translator (and a future re-price) can reach it.</p>
 */
public enum SupportTier {

    /** One unit — the entry point, sized so saying yes needs no thought. */
    HELP_DEV(1, "help_dev", UiAnalytics.TARGET_DONATE_HELP_DEV),

    /** Three units — the middle tier. */
    BIG_FAN(3, "big_fan", UiAnalytics.TARGET_DONATE_BIG_FAN),

    /** Seven units — pitched at the mod's actual monthly server bill. */
    SERVER_COSTS(7, "server_costs", UiAnalytics.TARGET_DONATE_SERVER_COSTS);

    private static final String LABEL_PREFIX = "gui.dungeontrain.support.tier.";

    private final int quantity;
    private final String key;
    private final String analyticsTarget;

    SupportTier(int quantity, String key, String analyticsTarget) {
        this.quantity = quantity;
        this.key = key;
        this.analyticsTarget = analyticsTarget;
    }

    /** Units of the relay's checkout price — sent as {@code ?qty=} and multiplied by Stripe. */
    public int quantity() {
        return quantity;
    }

    /** Button label, carrying the amount (e.g. "$30 A Big Fan"). */
    public String labelKey() {
        return LABEL_PREFIX + key;
    }

    /** Hover copy spelling out what the tier is for — the label alone has no room for it. */
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
}
