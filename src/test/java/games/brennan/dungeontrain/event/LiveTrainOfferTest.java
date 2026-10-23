package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveTrainOfferTest {

    @Test
    @DisplayName("the train's offer is redeemed only by its own id, before it expires")
    void redeems() {
        LiveTrainOffer.Pending pending = new LiveTrainOffer.Pending("abc123", 10_000L);
        assertTrue(pending.redeems("abc123", 9_999L));
        assertFalse(pending.redeems("abc123", 10_000L), "expired");
        assertFalse(pending.redeems("zzz999", 5_000L), "someone else's id");
        assertTrue(LiveTrainOffer.TTL_MS >= 60_000L, "open long enough to read and click");
    }
}
