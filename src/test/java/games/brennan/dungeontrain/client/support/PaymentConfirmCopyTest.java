package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.LinkPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link PaymentConfirmCopy#forTarget}: payment targets get their provider's words, nothing else does. */
class PaymentConfirmCopyTest {

    @Test
    void eachTierOpensStripeWithItsOwnAmount() {
        for (SupportTier tier : SupportTier.values()) {
            PaymentConfirmCopy copy = PaymentConfirmCopy.forTarget(tier.analyticsTarget());
            assertEquals(PaymentConfirmCopy.TITLE_STRIPE, copy.titleKey(), tier.name());
            assertEquals(PaymentConfirmCopy.BODY_STRIPE, copy.bodyKey(), tier.name());
            assertEquals(tier.amountKey(), copy.amountKey(), tier.name());
        }
        assertEquals("gui.dungeontrain.support.tier.big_fan.amount", SupportTier.BIG_FAN.amountKey());
    }

    @Test
    void openEndedProvidersHaveNoAmount() {
        assertEquals(new PaymentConfirmCopy(PaymentConfirmCopy.TITLE_PATREON, PaymentConfirmCopy.BODY_PATREON, null, LinkPolicy.KEY_PATREON),
                PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_PATREON));
        assertEquals(new PaymentConfirmCopy(PaymentConfirmCopy.TITLE_REVOLUT, PaymentConfirmCopy.BODY_REVOLUT, null, LinkPolicy.KEY_PAYMENT),
                PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DONATE));
        // The China route is a Stripe checkout, so it borrows Stripe's explanation line.
        assertEquals(new PaymentConfirmCopy(PaymentConfirmCopy.TITLE_WECHAT, PaymentConfirmCopy.BODY_STRIPE, null, LinkPolicy.KEY_PAYMENT_CN),
                PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DONATE_CN));
    }

    @Test
    void stripeBodyNamesTheRelayOnlyWhenTheLinkGoesThere() {
        String relay = "https://brennan.games/api/dp-relay/live/pay/cn?displayname=Steve&qty=3";
        String raw = "https://buy.stripe.com/abc123?client_reference_id=Steve";
        assertEquals(PaymentConfirmCopy.BODY_STRIPE, PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DONATE_HELP_DEV, relay).bodyKey());
        assertEquals(PaymentConfirmCopy.BODY_STRIPE_DIRECT, PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DONATE_CN, raw).bodyKey());
        // Title and amount survive the swap; a null URL keeps the relay wording; other providers are untouched.
        assertEquals(SupportTier.HELP_DEV.amountKey(), PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DONATE_HELP_DEV, raw).amountKey());
        assertEquals(PaymentConfirmCopy.BODY_STRIPE, PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DONATE_HELP_DEV, null).bodyKey());
        assertEquals(PaymentConfirmCopy.BODY_PATREON,
                PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_PATREON, "https://www.patreon.com/brennanhatton").bodyKey());
        assertNull(PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DISCORD, raw));
        assertEquals(false, PaymentConfirmCopy.onRelay("not a url"));
        assertEquals(true, PaymentConfirmCopy.onRelay("https://dungeon.training/pay"));
        assertEquals(false, PaymentConfirmCopy.onRelay("https://brennan.games.evil.com/pay"));
    }

    @Test
    void everyPaymentTargetNamesTheLinkKeyItsUrlCameFrom() {
        for (SupportTier tier : SupportTier.values()) {
            assertEquals(LinkPolicy.KEY_PAYMENT_CHECKOUT, PaymentConfirmCopy.forTarget(tier.analyticsTarget()).linkKey());
        }
        for (CommunityRewards.Reward reward : CommunityRewards.Reward.values()) {
            assertEquals(LinkPolicy.KEY_REWARDS_PAY, PaymentConfirmCopy.forTarget(reward.analyticsTarget()).linkKey());
        }
        assertEquals(LinkPolicy.KEY_PAYMENT_CN, PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DONATE_CN).linkKey());
        assertEquals(LinkPolicy.KEY_PATREON, PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_PATREON).linkKey());
        assertEquals(LinkPolicy.KEY_PAYMENT, PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DONATE).linkKey());
    }

    @Test
    void aSignedLinkOnAnotherProvidersHostGetsVanillasScreenNotTheWrongName() {
        // Payment links may point at any signed host; the provider-named words must not lie about it.
        String elsewhere = "https://pay.example.com/brennan?note=Steve";
        assertNull(PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DONATE_HELP_DEV, elsewhere));
        assertNull(PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DONATE, elsewhere));
        assertNull(PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_PATREON, elsewhere));
        assertNull(PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DONATE_CN, elsewhere));
        assertNull(PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DONATE, "https://buy.stripe.com/x"), "Stripe is not Revolut");
        assertNull(PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_PATREON, "https://brennan.games/x"));
        // Each provider's own host keeps its words; Stripe's also on our relay's checkout route.
        assertEquals(PaymentConfirmCopy.TITLE_REVOLUT,
                PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DONATE, "https://revolut.me/brennacg7?note=x").titleKey());
        assertEquals(PaymentConfirmCopy.TITLE_PATREON,
                PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_PATREON, "https://patreon.com/brennanhatton").titleKey());
        assertEquals(PaymentConfirmCopy.TITLE_STRIPE, PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DONATE_HELP_DEV,
                "https://checkout.stripe.com/c/pay/cs_1").titleKey());
    }

    @Test
    void nonPaymentTargetsKeepVanillaConfirm() {
        assertNull(PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DISCORD));
        assertNull(PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_AFFILIATE));
        assertNull(PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_TRANSLATE));
        assertNull(PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_SUPPORT));
        assertNull(PaymentConfirmCopy.forTarget(null));
        assertNull(PaymentConfirmCopy.forTarget("donate_30"));
    }
}
