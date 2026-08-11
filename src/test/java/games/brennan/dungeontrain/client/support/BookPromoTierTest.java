package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the book-promotion price points. {@link BookPromoTier#url()} is deliberately
 * not covered — it reads the player name and locale off a live client; the URL build itself is
 * exercised through {@code PaymentLinks.withQuantity}/{@code withDisplayName} in
 * {@link PaymentLinksTest}.
 */
class BookPromoTierTest {

    @Test
    void everyTierMapsToItsOwnQuantityOfTheCheckoutUnit() {
        // A tier IS a quantity — the relay multiplies its unit price by it.
        assertEquals(1, BookPromoTier.PROMOTE.quantity());
        assertEquals(3, BookPromoTier.BOOST.quantity());
        assertEquals(7, BookPromoTier.FEATURE.quantity());
    }

    @Test
    void everyTierCarriesItsOwnLabelTooltipAndAnalyticsTarget() {
        Set<String> labels = new HashSet<>();
        Set<String> targets = new HashSet<>();
        for (BookPromoTier tier : BookPromoTier.values()) {
            assertTrue(tier.labelKey().startsWith("gui.dungeontrain.book_promo.tier."),
                    "label key must live under the page's own namespace: " + tier.labelKey());
            assertEquals(tier.labelKey() + ".tooltip", tier.tooltipKey());
            labels.add(tier.labelKey());
            targets.add(tier.analyticsTarget());
        }
        int n = BookPromoTier.values().length;
        assertEquals(n, labels.size(), "two tiers sharing a label key would render the same button twice");
        assertEquals(n, targets.size(), "two tiers sharing a target would be indistinguishable in the funnel");
    }

    @Test
    void analyticsTargetsAreTheOnesTheRelayIsToldAbout() {
        // Lock-step with dp-relay's ui-events.js TARGETS allowlist — an unknown value is rejected
        // with a 400, so a rename here has to be a deliberate, two-sided change.
        assertEquals(UiAnalytics.TARGET_BOOK_PROMO_PROMOTE, BookPromoTier.PROMOTE.analyticsTarget());
        assertEquals(UiAnalytics.TARGET_BOOK_PROMO_BOOST, BookPromoTier.BOOST.analyticsTarget());
        assertEquals(UiAnalytics.TARGET_BOOK_PROMO_FEATURE, BookPromoTier.FEATURE.analyticsTarget());
    }

    @Test
    void promotionTargetsAreDistinctFromTheDonationOnes() {
        // Same Stripe unit, different question being asked — the funnel must be able to tell a
        // book buyer from a donor.
        Set<String> donation = new HashSet<>();
        for (SupportTier tier : SupportTier.values()) {
            donation.add(tier.analyticsTarget());
        }
        for (BookPromoTier tier : BookPromoTier.values()) {
            assertTrue(donation.add(tier.analyticsTarget()),
                    "promotion tier reuses a donation target: " + tier.analyticsTarget());
        }
    }
}
