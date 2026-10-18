package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
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
        assertEquals(new PaymentConfirmCopy(PaymentConfirmCopy.TITLE_PATREON, PaymentConfirmCopy.BODY_PATREON, null),
                PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_PATREON));
        assertEquals(new PaymentConfirmCopy(PaymentConfirmCopy.TITLE_REVOLUT, PaymentConfirmCopy.BODY_REVOLUT, null),
                PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DONATE));
        // The China route is a Stripe checkout, so it borrows Stripe's explanation line.
        assertEquals(new PaymentConfirmCopy(PaymentConfirmCopy.TITLE_WECHAT, PaymentConfirmCopy.BODY_STRIPE, null),
                PaymentConfirmCopy.forTarget(UiAnalytics.TARGET_DONATE_CN));
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
