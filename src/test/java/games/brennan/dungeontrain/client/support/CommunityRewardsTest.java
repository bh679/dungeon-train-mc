package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.live.life.LiveRewards;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The shelf rules every Community Rewards surface shares: which reward is featured, when the next month starts. */
class CommunityRewardsTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static LiveRewards.Reward on(String id, long untilFromNow, String by, String... queued) {
        return new LiveRewards.Reward(id, NOW + untilFromNow, by, List.of(queued));
    }

    @Test
    void nothingOnFeaturesQuickerStreams() {
        assertEquals(CommunityRewards.Reward.QUICKER_STREAMS, CommunityRewards.featured(LiveRewards.NONE, NOW));
    }

    @Test
    void theFirstRewardInBrennansOrderThatIsOffIsFeatured() {
        // Order: Quicker Streams, Voice on Stream, Two Streams, HD Streams, Replays Kept.
        LiveRewards quickerOn = new LiveRewards(List.of(on(LiveRewards.QUICKER_STREAMS, 3 * DAY, "Kestrel")));
        assertEquals(CommunityRewards.Reward.VOICE_ON_STREAM, CommunityRewards.featured(quickerOn, NOW));
        LiveRewards threeOn = new LiveRewards(List.of(
                on(LiveRewards.QUICKER_STREAMS, 3 * DAY, "Kestrel"),
                on(LiveRewards.VOICE_ON_STREAM, 9 * DAY, "Ash"),
                on(LiveRewards.TWO_STREAMS, 9 * DAY, "Rook")));
        assertEquals(CommunityRewards.Reward.HD_STREAMS, CommunityRewards.featured(threeOn, NOW));
    }

    @Test
    void allOnFeaturesTheSoonestFreeMonth() {
        LiveRewards state = new LiveRewards(List.of(
                on(LiveRewards.QUICKER_STREAMS, 2 * DAY, "Kestrel", "Moth"),
                on(LiveRewards.TWO_STREAMS, 20 * DAY, "A"),
                on(LiveRewards.VOICE_ON_STREAM, 25 * DAY, "B"),
                on(LiveRewards.HD_STREAMS, 15 * DAY, "C"),
                on(LiveRewards.REPLAYS_KEPT, 10 * DAY, "D")));
        // Quicker Streams ends soonest, but a month is already queued behind it: free on day 32.
        assertEquals(CommunityRewards.Reward.REPLAYS_KEPT, CommunityRewards.featured(state, NOW));
    }

    @Test
    void nextFreeMonthStacksQueuedGifts() {
        LiveRewards state = new LiveRewards(List.of(on(LiveRewards.QUICKER_STREAMS, 5 * DAY, "Kestrel", "Moth", "Ash")));
        assertEquals(NOW + 5 * DAY + 2 * CommunityRewards.MONTH_MS,
                CommunityRewards.nextFreeMs(state, CommunityRewards.Reward.QUICKER_STREAMS, NOW));
        assertEquals(NOW, CommunityRewards.nextFreeMs(state, CommunityRewards.Reward.TWO_STREAMS, NOW));
    }

    @Test
    void anExpiredRewardIsOffAndFreeNow() {
        LiveRewards state = new LiveRewards(List.of(on(LiveRewards.QUICKER_STREAMS, -DAY, "Moth", "Ash")));
        assertEquals(NOW, CommunityRewards.nextFreeMs(state, CommunityRewards.Reward.QUICKER_STREAMS, NOW));
        assertEquals("", CommunityRewards.keptOnBy(state, CommunityRewards.Reward.QUICKER_STREAMS, NOW));
        assertEquals(CommunityRewards.Reward.QUICKER_STREAMS, CommunityRewards.featured(state, NOW));
    }

    @Test
    void analyticsTargetsMatchTheRelayAllowlist() {
        assertEquals("reward_quicker_streams", CommunityRewards.Reward.QUICKER_STREAMS.analyticsTarget());
        assertEquals("reward_replays_kept", CommunityRewards.Reward.REPLAYS_KEPT.analyticsTarget());
        assertEquals(CommunityRewards.Reward.HD_STREAMS, CommunityRewards.Reward.byTarget("reward_hd_streams"));
        assertNull(CommunityRewards.Reward.byTarget("donate_big_fan"));
    }

    @Test
    void aRewardGiftConfirmsAsAStripeCheckoutForItsAmount() {
        PaymentConfirmCopy copy = PaymentConfirmCopy.forTarget("reward_two_streams");
        assertEquals(PaymentConfirmCopy.TITLE_STRIPE, copy.titleKey());
        assertEquals("gui.dungeontrain.rewards.two_streams.amount", copy.amountKey());
    }

    @Test
    void rewardIdRidesOnTheCheckoutUrl() {
        assertEquals("https://brennan.games/api/dp-relay/rewards/pay?displayname=Steve&reward=hd_streams",
                PaymentLinks.withRewardId("https://brennan.games/api/dp-relay/rewards/pay?displayname=Steve", "hd_streams"));
    }

    @Test
    void theFeaturedRewardIsOneThatCanBeGivenWhenAnyCan() {
        java.util.function.Predicate<CommunityRewards.Reward> onlyQuicker = r -> r == CommunityRewards.Reward.QUICKER_STREAMS;
        LiveRewards quickerOn = new LiveRewards(List.of(on(LiveRewards.QUICKER_STREAMS, 3 * DAY, "Kestrel")));
        // Quicker Streams is the only open reward, so it stays featured (as "Give <next month>") while on.
        assertEquals(CommunityRewards.Reward.QUICKER_STREAMS, CommunityRewards.featured(quickerOn, NOW, onlyQuicker));
        assertEquals(CommunityRewards.Reward.QUICKER_STREAMS, CommunityRewards.featured(LiveRewards.NONE, NOW, onlyQuicker));
        // None open yet: Brennan's whole order, as before.
        assertEquals(CommunityRewards.Reward.VOICE_ON_STREAM, CommunityRewards.featured(quickerOn, NOW, r -> false));
    }
}
