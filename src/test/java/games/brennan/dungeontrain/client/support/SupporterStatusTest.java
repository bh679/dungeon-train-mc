package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.net.relay.DonationSummaryClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The supporter rule: a {@code you} block whose all-time total is at least $30 AUD. */
class SupporterStatusTest {

    private static DonationSummaryClient.Summary summary(boolean hasYou, int monthly, int total) {
        return new DonationSummaryClient.Summary(0, 0, -1, -1, 0, 0, List.of(), List.of(),
                hasYou, monthly, total, List.of(), null, null, null, null);
    }

    @Test
    void thirtyDollarsAllTimeUnlocks() {
        assertTrue(SupporterStatus.qualifies(summary(true, 0, 30)));
        assertTrue(SupporterStatus.qualifies(summary(true, 0, 250)));
    }

    private static DonationSummaryClient.Summary split(int total, Boolean supporter) {
        return new DonationSummaryClient.Summary(0, 0, -1, -1, 0, 0, List.of(), List.of(),
                true, 0, total, List.of(), null, null, null, null, 0, supporter);
    }

    @Test
    void relaysSupporterYesNoWinsWhenSent() {
        // A friend gifted the perks of a payment: a yes, with no amount on their name.
        assertTrue(SupporterStatus.qualifies(split(0, true)));
        // The payer who gave the perks away keeps the funding total but not the perk.
        assertFalse(SupporterStatus.qualifies(split(30, false)));
        // Not sent: the all-time total stands, as on an older relay.
        assertTrue(SupporterStatus.qualifies(split(30, null)));
        assertFalse(SupporterStatus.qualifies(split(10, null)));
    }

    @Test
    void lessThanThirtyOrNoYouBlockStaysLocked() {
        assertFalse(SupporterStatus.qualifies(summary(true, 29, 29)));
        assertFalse(SupporterStatus.qualifies(summary(true, 10, 10)));
        assertFalse(SupporterStatus.qualifies(summary(true, 0, 0)));
        assertFalse(SupporterStatus.qualifies(summary(false, 0, 0)));
        assertFalse(SupporterStatus.qualifies(null));
    }
}
