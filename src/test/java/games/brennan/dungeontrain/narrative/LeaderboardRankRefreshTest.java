package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two rules that decide when a player's leaderboard standings are re-asked for after a death:
 * the post-death delay ({@link LeaderboardRankSchedule}, in wall-clock millis) and the per-player
 * cooldown that keeps a run of quick deaths down to one request
 * ({@link LeaderboardPool#dueForRankRequest}).
 */
class LeaderboardRankRefreshTest {

    private static final UUID ADA = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID BOB = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    @Test
    @DisplayName("nothing drains before its due time, and the drain is exactly once")
    void drainsOnceAtDueTime() {
        LeaderboardRankSchedule schedule = new LeaderboardRankSchedule();
        assertTrue(schedule.isEmpty());
        schedule.schedule(ADA, 100L);
        assertFalse(schedule.isEmpty());

        assertEquals(List.of(), schedule.drainDue(99L), "not due yet");
        assertEquals(List.of(ADA), schedule.drainDue(100L), "due at exactly its deadline");
        assertEquals(List.of(), schedule.drainDue(500L), "already drained");
        assertTrue(schedule.isEmpty());
    }

    @Test
    @DisplayName("a second death keeps the earlier due time rather than pushing the fetch out")
    void reschedulingKeepsTheEarlierTime() {
        LeaderboardRankSchedule schedule = new LeaderboardRankSchedule();
        schedule.schedule(ADA, 100L);
        schedule.schedule(ADA, 300L);

        assertEquals(List.of(ADA), schedule.drainDue(100L));
        assertTrue(schedule.isEmpty(), "one entry per player, not one per death");
    }

    @Test
    @DisplayName("drain returns only the players actually due")
    void drainsOnlyTheDue() {
        LeaderboardRankSchedule schedule = new LeaderboardRankSchedule();
        schedule.schedule(ADA, 100L);
        schedule.schedule(BOB, 400L);

        assertEquals(List.of(ADA), schedule.drainDue(150L));
        assertFalse(schedule.isEmpty());
        assertEquals(List.of(BOB), schedule.drainDue(400L));
    }

    @Test
    @DisplayName("a logout cancels a pending refetch")
    void cancelRemoves() {
        LeaderboardRankSchedule schedule = new LeaderboardRankSchedule();
        schedule.schedule(ADA, 100L);
        schedule.cancel(ADA);

        assertTrue(schedule.isEmpty());
        assertEquals(List.of(), schedule.drainDue(1_000L));
    }

    @Test
    @DisplayName("a throttled ask reports exactly how long it must wait, so it can be deferred")
    void rankCooldownReportsItsRemainder() {
        long now = 1_000_000L;
        assertEquals(0L, LeaderboardPool.rankRequestWaitMs(null, now), "never asked → no wait");
        assertEquals(0L, LeaderboardPool.rankRequestWaitMs(
                now - LeaderboardPool.RANK_ATTEMPT_COOLDOWN_MS, now), "cooldown elapsed → no wait");
        // A death 26s after the last fetch waits out the remaining 34s rather than being dropped —
        // the case seen in testing, where a death shortly after joining updated nothing at all.
        assertEquals(LeaderboardPool.RANK_ATTEMPT_COOLDOWN_MS - 26_000L,
                LeaderboardPool.rankRequestWaitMs(now - 26_000L, now));
    }

    @Test
    @DisplayName("first ask goes through; a second inside the cooldown does not")
    void rankCooldown() {
        long now = 1_000_000L;
        assertTrue(LeaderboardPool.dueForRankRequest(null, now), "never asked → due");
        assertFalse(LeaderboardPool.dueForRankRequest(now - 1_000L, now), "asked a second ago");
        assertFalse(LeaderboardPool.dueForRankRequest(
                now - (LeaderboardPool.RANK_ATTEMPT_COOLDOWN_MS - 1L), now), "just inside");
        assertTrue(LeaderboardPool.dueForRankRequest(
                now - LeaderboardPool.RANK_ATTEMPT_COOLDOWN_MS, now), "cooldown elapsed");
    }
}
