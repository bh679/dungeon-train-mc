package games.brennan.dungeontrain.client.localization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic behind "downloaded approvals shrink the AI ring". Pure — the count of newly
 * human-reviewed lines is supplied, so this never needs a ResourceManager or a relay.
 */
class LocalizationCreditAdjustTest {

    private static LocalizationCredit.AiCounts baked() {
        return new LocalizationCredit.AiCounts(1000, 400, 300);
    }

    @Test
    @DisplayName("approvals come off both the unreviewed and the authored counts")
    void approvalsReduceBothCounts() {
        LocalizationCredit.AiCounts out = LocalizationCreditRegistry.adjust(baked(), 100);
        assertEquals(1000, out.totalKeys()); // the denominator is the language, and it has not moved
        assertEquals(200, out.aiUnreviewed());
        assertEquals(300, out.aiAuthored());
    }

    @Test
    @DisplayName("no approvals leaves the baked counts exactly as they were")
    void noApprovalsIsIdentity() {
        assertEquals(baked(), LocalizationCreditRegistry.adjust(baked(), 0));
        assertEquals(baked(), LocalizationCreditRegistry.adjust(baked(), -5));
    }

    @Test
    @DisplayName("more approvals than the manifest ever flagged floors at zero, never inverts")
    void cannotGoNegative() {
        LocalizationCredit.AiCounts out = LocalizationCreditRegistry.adjust(baked(), 5000);
        assertEquals(0, out.aiUnreviewed());
        assertEquals(0, out.aiAuthored());
        assertEquals(0.0, out.unreviewedFraction());
    }

    @Test
    @DisplayName("approvals can carry a locale over the human-reviewed line without a rebuild")
    void approvalsCanEarnTheFullLogo() {
        // 300/1000 unreviewed is well over the 10% bar; 250 approvals put it under.
        assertTrue(!LocalizationCreditRegistry.meetsReviewedCoverage(baked()));
        assertTrue(LocalizationCreditRegistry.meetsReviewedCoverage(
            LocalizationCreditRegistry.adjust(baked(), 250)));
    }

    @Test
    @DisplayName("absent counts stay absent — no data is not evidence of review")
    void nullStaysNull() {
        assertNull(LocalizationCreditRegistry.adjust(null, 100));
    }
}
