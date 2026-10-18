package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.net.relay.DonationSummaryClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The supporter rule: a {@code you} block whose all-time total is at least $10 AUD. */
class SupporterStatusTest {

    private static DonationSummaryClient.Summary summary(boolean hasYou, int monthly, int total) {
        return new DonationSummaryClient.Summary(0, 0, -1, -1, 0, 0, List.of(), List.of(),
                hasYou, monthly, total, List.of(), null, null, null, null);
    }

    @Test
    void tenDollarsAllTimeUnlocks() {
        assertTrue(SupporterStatus.qualifies(summary(true, 0, 10)));
        assertTrue(SupporterStatus.qualifies(summary(true, 0, 250)));
    }

    @Test
    void lessThanTenOrNoYouBlockStaysLocked() {
        assertFalse(SupporterStatus.qualifies(summary(true, 9, 9)));
        assertFalse(SupporterStatus.qualifies(summary(true, 0, 0)));
        assertFalse(SupporterStatus.qualifies(summary(false, 0, 0)));
        assertFalse(SupporterStatus.qualifies(null));
    }
}
