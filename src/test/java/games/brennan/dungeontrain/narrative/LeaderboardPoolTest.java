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

    // ---- eras -----------------------------------------------------------------

    private static final String ERAS = "{\"ok\":true,\"current\":{\"version\":\"v0.1013\",\"year\":\"y2026\"},"
        + "\"categories\":[\"playtime_run\"],\"eras\":["
        + "{\"id\":\"v0.0\",\"kind\":\"version\",\"label\":\"The First Era\",\"minVersion\":\"0.0.0\",\"retiredTs\":5,\"current\":false},"
        + "{\"id\":\"v0.1013\",\"kind\":\"version\",\"label\":\"After the balancing\",\"current\":true},"
        + "{\"id\":\"y2025\",\"kind\":\"year\",\"label\":\"2025\",\"current\":false},"
        + "{\"id\":\"y2026\",\"kind\":\"year\",\"label\":\"2026\",\"current\":true},"
        + "{\"id\":\"../x\",\"kind\":\"version\",\"label\":\"junk\"},"
        + "{\"id\":\"m2026-09\",\"kind\":\"month\",\"label\":\"unknown kind\"},"
        + "{\"id\":\"v9.9\",\"kind\":\"version\",\"current\":\"yes\"}]}";

    @Test
    @DisplayName("the era list parses: two kinds, current flags, junk ids and unknown kinds skipped")
    void parsesEras() {
        List<LeaderboardPool.Era> eras = LeaderboardPool.parseEras(ERAS);
        assertEquals(List.of("v0.0", "v0.1013", "y2025", "y2026", "v9.9"),
            eras.stream().map(LeaderboardPool.Era::id).toList());
        assertEquals("The First Era", eras.get(0).label());
        assertTrue(eras.get(0).isRetired());
        assertFalse(eras.get(1).isRetired());
        assertTrue(eras.get(2).isYear());
        assertFalse(eras.get(1).isYear());
        assertEquals("v9.9", eras.get(4).label(), "a missing label falls back to the id");
        assertFalse(eras.get(4).current(), "a non-boolean current is not current");
        assertEquals("0.0.0", eras.get(0).minVersion());
        assertTrue(eras.get(0).isFounding());
        assertEquals("", eras.get(1).minVersion(), "absent floor stays empty");
        assertFalse(eras.get(2).isFounding(), "a year is never the founding era");
        LeaderboardPool.applyEras(ERAS);
        assertTrue(LeaderboardPool.nextVersionFloor("v9.9").isEmpty(), "the last one has no successor");
        LeaderboardPool.applyEras("{\"eras\":[{\"id\":\"v0.0\",\"kind\":\"version\",\"minVersion\":\"0.0.0\"},"
            + "{\"id\":\"y2025\",\"kind\":\"year\"},{\"id\":\"v0.1013\",\"kind\":\"version\",\"minVersion\":\"0.1013\"},"
            + "{\"id\":\"v0.1020\",\"kind\":\"version\",\"minVersion\":\"0.1020\",\"current\":true}]}");
        assertEquals("0.1013", LeaderboardPool.nextVersionFloor("v0.0").orElseThrow(), "years are skipped over");
        assertEquals("0.1020", LeaderboardPool.nextVersionFloor("v0.1013").orElseThrow());
        assertTrue(LeaderboardPool.nextVersionFloor("v0.1020").isEmpty());
        assertTrue(LeaderboardPool.nextVersionFloor("y2025").isEmpty());
        assertTrue(LeaderboardPool.parseEras("{\"ok\":true}").isEmpty());
        assertTrue(LeaderboardPool.parseEras("not json").isEmpty());
    }

    @Test
    @DisplayName("the retired eras are what circulate; the current ones never do")
    void circulatingErasAreTheRetiredOnes() {
        assertTrue(LeaderboardPool.circulatingEras().isEmpty(), "nothing before the list lands");
        LeaderboardPool.applyEras(ERAS);
        assertEquals(List.of("v0.0", "y2025", "v9.9"), LeaderboardPool.circulatingEras());
        assertEquals("After the balancing", LeaderboardPool.era("v0.1013").orElseThrow().label());
        assertTrue(LeaderboardPool.era("v0.5").isEmpty());
        LeaderboardPool.applyEras("{\"eras\":[]}");
        assertEquals(3, LeaderboardPool.circulatingEras().size(), "an empty answer keeps the last list");
    }

    @Test
    @DisplayName("boards are cached per era, and only current ones count as populated")
    void boardsArePerEra() {
        LeaderboardPool.applyEras(ERAS);
        LeaderboardPool.applyBoard(LeaderboardCategory.DISTANCE_RUN, "{\"rows\":[{\"name\":\"Ada\",\"score\":3}]}");
        LeaderboardPool.applyBoard(new LeaderboardPool.BoardKey(LeaderboardCategory.DISTANCE_RUN, "v0.0"),
            "{\"rows\":[{\"name\":\"Grace\",\"score\":9}]}");
        LeaderboardPool.applyBoard(new LeaderboardPool.BoardKey(LeaderboardCategory.PLAYTIME_RUN, "y2025"),
            "{\"rows\":[{\"name\":\"Alan\",\"score\":7}]}");
        LeaderboardPool.applyBoard(new LeaderboardPool.BoardKey(LeaderboardCategory.PLAYTIME_RUN, "v0.5"),
            "{\"rows\":[{\"name\":\"Ghost\",\"score\":1}]}"); // an era the relay no longer lists

        assertEquals("Ada", LeaderboardPool.board(LeaderboardCategory.DISTANCE_RUN).entries().get(0).name());
        assertEquals("Grace", LeaderboardPool.board(LeaderboardCategory.DISTANCE_RUN, "v0.0").entries().get(0).name());
        assertTrue(LeaderboardPool.board(LeaderboardCategory.DISTANCE_RUN, "y2025").isEmpty());
        assertEquals(List.of(LeaderboardCategory.DISTANCE_RUN), LeaderboardPool.populated());
        assertEquals(List.of(
                new LeaderboardPool.BoardKey(LeaderboardCategory.DISTANCE_RUN, "v0.0"),
                new LeaderboardPool.BoardKey(LeaderboardCategory.PLAYTIME_RUN, "y2025")),
            LeaderboardPool.populatedRetired(), "a board for an unlisted era is not offered");
    }

    @Test
    @DisplayName("per-era standings parse beside the current ones and are read by era")
    void parsesEraRanks() {
        LeaderboardPool.applyRanks(PLAYER, "{\"ranks\":{\"lives\":{\"rank\":3,\"score\":9},\"distance_run\":{\"rank\":2,\"score\":500}},"
            + "\"eras\":{\"v0.0\":{\"distance_run\":{\"rank\":1,\"score\":900}},"
            + "\"y2025\":{\"distance_run\":{\"score\":50,\"beyond\":10000}},"
            + "\"bad era!\":{\"distance_run\":{\"rank\":1,\"score\":1}}}}");
        assertEquals(2, LeaderboardPool.standing(PLAYER, LeaderboardCategory.DISTANCE_RUN).orElseThrow().rank());
        assertEquals(1, LeaderboardPool.standing(PLAYER, LeaderboardCategory.DISTANCE_RUN, "v0.0").orElseThrow().rank());
        assertEquals(900L, LeaderboardPool.standing(PLAYER, LeaderboardCategory.DISTANCE_RUN, "v0.0").orElseThrow().score());
        assertFalse(LeaderboardPool.standing(PLAYER, LeaderboardCategory.DISTANCE_RUN, "y2025").orElseThrow().isExact());
        assertTrue(LeaderboardPool.standing(PLAYER, LeaderboardCategory.LIVES, "v0.0").isEmpty());
        assertTrue(LeaderboardPool.standing(PLAYER, LeaderboardCategory.DISTANCE_RUN, "v0.5").isEmpty());
    }

    @Test
    @DisplayName("the warm rotation covers every current board, then the one-life boards of each retired era")
    void warmKeysCoverRetiredEras() {
        int all = LeaderboardCategory.values().length;
        assertEquals(all, LeaderboardPool.warmKeys().size(), "no eras known: current boards only");
        LeaderboardPool.applyEras(ERAS);
        List<LeaderboardPool.BoardKey> keys = LeaderboardPool.warmKeys();
        int oneLife = LeaderboardPool.eraCategories().size();
        assertEquals(13, oneLife);
        assertEquals(all + 3 * oneLife, keys.size());
        assertTrue(keys.subList(0, all).stream().allMatch(LeaderboardPool.BoardKey::isCurrent));
        assertTrue(keys.subList(all, keys.size()).stream().noneMatch(LeaderboardPool.BoardKey::isCurrent));
        assertTrue(keys.subList(all, keys.size()).stream()
            .allMatch(k -> k.category().scope() == LeaderboardCategory.Scope.RUN));
    }
}
