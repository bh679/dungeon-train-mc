package games.brennan.dungeontrain.client.localization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ---- "has a person been through any of this?" ------------------------------------------------

    @Test
    @DisplayName("one reviewed line is enough, at any coverage")
    void anyReviewIsEnough() {
        // zh_tw as shipped: 1279 keys, 1265 still AI-unreviewed. Fourteen lines is not a reviewed
        // language, but it is unmistakably a language somebody has been in.
        assertTrue(LocalizationCreditRegistry.hasAnyReview(
            new LocalizationCredit.AiCounts(1279, 1268, 1265)));
        assertFalse(LocalizationCreditRegistry.meetsReviewedCoverage(
            new LocalizationCredit.AiCounts(1279, 1268, 1265)),
            "and the badge still calls it machine-translated — the two questions differ on purpose");
    }

    @Test
    @DisplayName("a wholly unreviewed language has had no human review")
    void untouchedIsNotReviewed() {
        // de_de as shipped: every key AI-authored, none reviewed.
        assertFalse(LocalizationCreditRegistry.hasAnyReview(
            new LocalizationCredit.AiCounts(1265, 1265, 1265)));
    }

    @Test
    @DisplayName("human-authored lines count as review even with nothing corrected")
    void humanAuthoredCounts() {
        // 1000 keys, only 200 of them machine-written and all 200 unreviewed: 800 were written by
        // a person in the first place, which is the stronger form of the same claim.
        assertTrue(LocalizationCreditRegistry.hasAnyReview(
            new LocalizationCredit.AiCounts(1000, 200, 200)));
    }

    @Test
    @DisplayName("absent or empty counts are not evidence of review")
    void absentCountsAreNotReview() {
        assertFalse(LocalizationCreditRegistry.hasAnyReview(null));
        assertFalse(LocalizationCreditRegistry.hasAnyReview(
            new LocalizationCredit.AiCounts(0, 0, 0)));
    }

    @Test
    @DisplayName("'still wants a human' and 'has been reviewed' are independent, not opposites")
    void unreviewedAndReviewedAreIndependent() {
        // zh_cn as shipped: 1279 keys, 232 still AI-unreviewed. Both answers are yes, which is the
        // whole reason the two filters overlap.
        LocalizationCredit.AiCounts partial = new LocalizationCredit.AiCounts(1279, 284, 232);
        assertTrue(LocalizationCreditRegistry.hasAnyReview(partial));
        assertTrue(LocalizationCreditRegistry.hasUnreviewed(partial));

        // Finished: reviewed, nothing left.
        LocalizationCredit.AiCounts done = new LocalizationCredit.AiCounts(1000, 0, 0);
        assertTrue(LocalizationCreditRegistry.hasAnyReview(done));
        assertFalse(LocalizationCreditRegistry.hasUnreviewed(done));

        // Untouched machine translation: work outstanding, nobody has been through it.
        LocalizationCredit.AiCounts raw = new LocalizationCredit.AiCounts(1265, 1265, 1265);
        assertFalse(LocalizationCreditRegistry.hasAnyReview(raw));
        assertTrue(LocalizationCreditRegistry.hasUnreviewed(raw));
    }

    @Test
    @DisplayName("absent counts fail towards more work being visible, not less")
    void absentCountsMeanUnreviewed() {
        // The two readings of the same absence are deliberately opposite: no data is not evidence
        // of review, and a translated language in none of the filters could not be found at all.
        assertFalse(LocalizationCreditRegistry.hasAnyReview(null));
        assertTrue(LocalizationCreditRegistry.hasUnreviewed(null));
    }
}
