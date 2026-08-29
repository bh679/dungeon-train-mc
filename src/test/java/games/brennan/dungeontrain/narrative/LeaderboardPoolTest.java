package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing tests for {@link LeaderboardPool}. Pure over response bodies — no relay, no network.
 * The contract under test is the defensive one: anything malformed leaves the last good snapshot
 * alone rather than emptying a board mid-game.
 */
class LeaderboardPoolTest {

    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @BeforeEach
    void reset() { LeaderboardPool.clear(); }

    @Test
    @DisplayName("a well-formed board parses in rank order")
    void parsesRowsInOrder() {
        List<LeaderboardPool.Entry> rows = LeaderboardPool.parseRows(
            "{\"ok\":true,\"cat\":\"lives\",\"rows\":[{\"name\":\"Grace\",\"score\":11},"
            + "{\"name\":\"Alan\",\"score\":7},{\"name\":\"Ada\",\"score\":3}]}");
        assertEquals(3, rows.size());
        assertEquals("Grace", rows.get(0).name());
        assertEquals(11L, rows.get(0).score());
        assertEquals("Ada", rows.get(2).name());
    }

    @Test
    @DisplayName("nameless and zero-score rows are dropped rather than shown as blanks")
    void dropsUnusableRows() {
        List<LeaderboardPool.Entry> rows = LeaderboardPool.parseRows(
            "{\"rows\":[{\"name\":\"\",\"score\":9},{\"name\":\"Ada\",\"score\":0},"
            + "{\"score\":4},{\"name\":\"Grace\",\"score\":2}]}");
        assertEquals(List.of(new LeaderboardPool.Entry("Grace", 2L)), rows);
    }

    @Test
    @DisplayName("an over-long name is clamped, so a wrong relay cannot blow out a book page")
    void clampsName() {
        List<LeaderboardPool.Entry> rows = LeaderboardPool.parseRows(
            "{\"rows\":[{\"name\":\"" + "x".repeat(200) + "\",\"score\":1}]}");
        assertEquals(LeaderboardPool.MAX_NAME_LEN, rows.get(0).name().length());
    }

    @Test
    @DisplayName("more rows than the ceiling are truncated")
    void clampsRowCount() {
        StringBuilder b = new StringBuilder("{\"rows\":[");
        for (int i = 0; i < LeaderboardPool.MAX_ROWS + 50; i++) {
            if (i > 0) b.append(',');
            b.append("{\"name\":\"P").append(i).append("\",\"score\":1}");
        }
        b.append("]}");
        assertEquals(LeaderboardPool.MAX_ROWS, LeaderboardPool.parseRows(b.toString()).size());
    }

    @Test
    @DisplayName("garbage, wrong shapes and empty bodies parse to nothing rather than throwing")
    void malformedIsEmptyNotFatal() {
        for (String body : new String[]{"", "not json", "[]", "null", "{}", "{\"rows\":5}",
                                        "{\"rows\":[1,2,3]}", "{\"rows\":[{}]}"}) {
            assertTrue(LeaderboardPool.parseRows(body).isEmpty(), "expected nothing from: " + body);
        }
    }

    @Test
    @DisplayName("a malformed response leaves the previously fetched board in place")
    void badResponseKeepsSnapshot() {
        LeaderboardPool.applyBoard(LeaderboardCategory.LIVES,
            "{\"rows\":[{\"name\":\"Grace\",\"score\":11}]}");
        LeaderboardPool.applyBoard(LeaderboardCategory.LIVES, "{\"rows\":[]}");
        LeaderboardPool.applyBoard(LeaderboardCategory.LIVES, "garbage");

        assertEquals(1, LeaderboardPool.board(LeaderboardCategory.LIVES).entries().size());
        assertEquals("Grace", LeaderboardPool.board(LeaderboardCategory.LIVES).entries().get(0).name());
    }

    @Test
    @DisplayName("populated() lists only boards with rows, so a book can never roll an empty one")
    void populatedSkipsEmptyBoards() {
        LeaderboardPool.applyBoard(LeaderboardCategory.LIVES, "{\"rows\":[{\"name\":\"Ada\",\"score\":1}]}");
        LeaderboardPool.applyBoard(LeaderboardCategory.BOOKS_READ, "{\"rows\":[]}");
        assertEquals(List.of(LeaderboardCategory.LIVES), LeaderboardPool.populated());
    }

    @Test
    @DisplayName("player ranks parse per category, and an unknown category is skipped not fatal")
    void parsesRanks() {
        Map<LeaderboardCategory, LeaderboardPool.Standing> ranks = LeaderboardPool.parseRanks(
            "{\"ok\":true,\"ranks\":{\"lives\":{\"rank\":31,\"score\":4},"
            + "\"a_board_from_the_future\":{\"rank\":1,\"score\":9},"
            + "\"books_read\":{\"rank\":2,\"score\":88}}}");
        assertEquals(2, ranks.size());
        assertEquals(31, ranks.get(LeaderboardCategory.LIVES).rank());
        assertEquals(88L, ranks.get(LeaderboardCategory.BOOKS_READ).score());
    }

    @Test
    @DisplayName("a rank of zero is not a rank")
    void rejectsZeroRank() {
        assertTrue(LeaderboardPool.parseRanks("{\"ranks\":{\"lives\":{\"rank\":0,\"score\":4}}}").isEmpty());
    }

    @Test
    @DisplayName("standings are per player, and forgetting one leaves the others")
    void standingsArePerPlayer() {
        UUID other = UUID.fromString("11111111-2222-3333-4444-555555555555");
        LeaderboardPool.applyRanks(PLAYER, "{\"ranks\":{\"lives\":{\"rank\":3,\"score\":9}}}");
        LeaderboardPool.applyRanks(other, "{\"ranks\":{\"lives\":{\"rank\":8,\"score\":2}}}");

        assertEquals(3, LeaderboardPool.standing(PLAYER, LeaderboardCategory.LIVES).orElseThrow().rank());
        LeaderboardPool.forget(PLAYER);
        assertTrue(LeaderboardPool.standing(PLAYER, LeaderboardCategory.LIVES).isEmpty());
        assertEquals(8, LeaderboardPool.standing(other, LeaderboardCategory.LIVES).orElseThrow().rank());
    }

    @Test
    @DisplayName("a category that has never answered is not re-asked on every call")
    void emptyAnswerIsHeldOffByTheAttemptCooldown() {
        long t = 1_000_000L;
        // Never asked, nothing cached: ask.
        assertTrue(LeaderboardPool.dueForRequest(null, null, t));
        // Just asked, and it left no board behind — empty rows, a bad_cat, a dropped connection. This
        // is the case that used to fire one request per tick for the rest of the session.
        assertFalse(LeaderboardPool.dueForRequest(null, t, t));
        assertFalse(LeaderboardPool.dueForRequest(null, t, t + 59_000L));
        // Once the cooldown is up it is worth another go, so a board the relay starts serving appears.
        assertTrue(LeaderboardPool.dueForRequest(null, t, t + 60_000L));
    }

    @Test
    @DisplayName("a board with rows is throttled by its own age, not the attempt cooldown")
    void populatedBoardUsesTheBoardTtl() {
        long t = 1_000_000L;
        LeaderboardPool.Board fresh = new LeaderboardPool.Board(
            List.of(new LeaderboardPool.Entry("Ada", 3L)), t);
        // Fresh board: left alone, even though the attempt cooldown has long expired.
        assertFalse(LeaderboardPool.dueForRequest(fresh, t - 600_000L, t + 299_000L));
        // Past the 5-minute board TTL: refetched.
        assertTrue(LeaderboardPool.dueForRequest(fresh, t - 600_000L, t + 300_000L));
    }

    @Test
    @DisplayName("durations read as the two largest useful units")
    void durationFormatting() {
        assertEquals("30s", LeaderboardCategory.duration(30));
        assertEquals("42m", LeaderboardCategory.duration(42 * 60));
        assertEquals("5h 12m", LeaderboardCategory.duration(5 * 3600 + 12 * 60));
        assertEquals("3d 4h", LeaderboardCategory.duration(3 * 86400 + 4 * 3600));
        assertEquals("0s", LeaderboardCategory.duration(-1));
    }
}
