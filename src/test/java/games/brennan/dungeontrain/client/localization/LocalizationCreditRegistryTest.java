package games.brennan.dungeontrain.client.localization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coverage half of {@link LocalizationCreditRegistry#isHumanReviewed} — the rule that decides
 * whether a locale's Dungeon Train logo renders solid or faded in the language-selection list.
 *
 * <p>Pure logic only: {@link LocalizationCredit.AiCounts} is three ints, so none of this needs a
 * ResourceManager or a live resource-pack reload. The flag half of {@code isHumanReviewed} does
 * need loaded credits and is left to in-game verification.</p>
 */
class LocalizationCreditRegistryTest {

    /** Counts for a locale where {@code unreviewed} of {@code total} lines await a reviewer. */
    private static LocalizationCredit.AiCounts counts(int total, int unreviewed) {
        return new LocalizationCredit.AiCounts(total, total, unreviewed);
    }

    @Test
    @DisplayName("exactly 90% reviewed clears the bar — the threshold is inclusive")
    void ninetyPercentQualifies() {
        assertTrue(LocalizationCreditRegistry.meetsReviewedCoverage(counts(100, 10)));
    }

    @Test
    @DisplayName("89% reviewed does not — one line the wrong side of the bar flips the verdict")
    void justUnderDoesNotQualify() {
        assertFalse(LocalizationCreditRegistry.meetsReviewedCoverage(counts(100, 11)));
    }

    @Test
    @DisplayName("a fully reviewed locale qualifies")
    void fullyReviewedQualifies() {
        assertTrue(LocalizationCreditRegistry.meetsReviewedCoverage(counts(1083, 0)));
    }

    @Test
    @DisplayName("zh_cn's shipped counts (38 of 1083 unreviewed) qualify")
    void shippedSimplifiedChineseQualifies() {
        assertTrue(LocalizationCreditRegistry.meetsReviewedCoverage(counts(1083, 38)));
    }

    @Test
    @DisplayName("an untouched machine-translated locale does not")
    void whollyUnreviewedDoesNotQualify() {
        assertFalse(LocalizationCreditRegistry.meetsReviewedCoverage(counts(1069, 1069)));
    }

    @Test
    @DisplayName("absent counts never qualify — no data is not evidence of review")
    void absentCountsDoNotQualify() {
        assertFalse(LocalizationCreditRegistry.meetsReviewedCoverage(null));
    }
}
