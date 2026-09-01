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
 * the post-death delay ({@link LeaderboardRankSchedule}) and the per-player cooldown that keeps a
 * run of quick deaths down to one request ({@link LeaderboardPool#dueForRankRequest}).
 */
class LeaderboardRankRefreshTest {

    private static final UUID ADA = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID BOB = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    @Test
    @DisplayName("nothing drains before its due tick, and the drain is exactly once")
    void drainsOnceAtDueTick() {
        LeaderboardRankSchedule schedule = new LeaderboardRankSchedule();
        assertTrue(schedule.isEmpty());
        schedule.schedule(ADA, 100L);
        assertFalse(schedule.isEmpty());

        assertEquals(List.of(), schedule.drainDue(99L), "not due yet");
        assertEquals(List.of(ADA), schedule.drainDue(100L), "due at exactly its tick");
        assertEquals(List.of(), schedule.drainDue(500L), "already drained");
        assertTrue(schedule.isEmpty());
    }

    @Test
    @DisplayName("a second death keeps the earlier due tick rather than pushing the fetch out")
    void reschedulingKeepsTheEarlierTick() {
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
