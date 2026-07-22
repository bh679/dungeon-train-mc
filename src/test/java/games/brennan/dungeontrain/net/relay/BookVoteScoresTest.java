package games.brennan.dungeontrain.net.relay;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure tests for {@link BookVoteScores}' parse + factor + weight math — no Minecraft bootstrap, no
 * network: state is fed through the package-private {@code applyResponse} and dropped via
 * {@code clear()}. The invariants that matter: unvoted/unknown/unfetched → factor exactly 1 (rolls
 * byte-identical to today), the bounded formula with K=5, malformed payloads keep the last good
 * snapshot, and effectiveWeight's uniform ×100 scale + 0-stays-0 + ≥1 floor.
 */
class BookVoteScoresTest {

    @AfterEach
    void tearDown() {
        BookVoteScores.clear();
    }

    private static String summary(String rows) {
        return "{\"ok\":true,\"votes\":[" + rows + "]}";
    }

    @Test
    @DisplayName("no snapshot (never fetched) → factor exactly 1, weight = base*100")
    void neutralWhenUnfetched() {
        assertEquals(1.0, BookVoteScores.voteFactor("random", "any"), 1e-9);
        assertEquals(500, BookVoteScores.effectiveWeight("random", "any", 5));
    }

    @Test
    @DisplayName("factor follows clamp(1 + 0.5*(up-down)/(up+down+K), 0.5, 1.5) with K=5")
    void factorFormula() {
        BookVoteScores.applyResponse(summary(
            "{\"bookType\":\"random\",\"bookId\":\"loved\",\"up\":10,\"down\":0},"
            + "{\"bookType\":\"random\",\"bookId\":\"hated\",\"up\":0,\"down\":10},"
            + "{\"bookType\":\"random\",\"bookId\":\"split\",\"up\":7,\"down\":7}"));
        assertEquals(1.0 + 0.5 * 10 / 15.0, BookVoteScores.voteFactor("random", "loved"), 1e-9);
        assertEquals(1.0 - 0.5 * 10 / 15.0, BookVoteScores.voteFactor("random", "hated"), 1e-9);
        assertEquals(1.0, BookVoteScores.voteFactor("random", "split"), 1e-9);
        // The asymptote never crosses the clamp edges; huge tallies stay inside [0.5, 1.5].
        BookVoteScores.applyResponse(summary("{\"bookType\":\"random\",\"bookId\":\"mono\",\"up\":1000000,\"down\":0}"));
        double f = BookVoteScores.voteFactor("random", "mono");
        org.junit.jupiter.api.Assertions.assertTrue(f > 1.0 && f <= BookVoteScores.FACTOR_MAX);
    }

    @Test
    @DisplayName("keys are type-scoped: a random vote never bleeds into starting")
    void typeScoping() {
        BookVoteScores.applyResponse(summary("{\"bookType\":\"random\",\"bookId\":\"tale\",\"up\":9,\"down\":0}"));
        assertEquals(1.0, BookVoteScores.voteFactor("starting", "tale"), 1e-9);
    }

    @Test
    @DisplayName("effectiveWeight: 0 stays 0; positive bases floor at 1; scale is uniform ×100")
    void effectiveWeightRules() {
        BookVoteScores.applyResponse(summary("{\"bookType\":\"random\",\"bookId\":\"down\",\"up\":0,\"down\":50}"));
        assertEquals(0, BookVoteScores.effectiveWeight("random", "down", 0), "weight-0 books stay excluded");
        double f = BookVoteScores.voteFactor("random", "down");
        assertEquals((int) Math.round(4 * 100 * f), BookVoteScores.effectiveWeight("random", "down", 4));
        assertEquals(100, BookVoteScores.effectiveWeight("random", "unvoted", 1));
    }

    @Test
    @DisplayName("malformed body / not-ok / wrong shape keeps the last good snapshot; bad rows skipped")
    void malformedTolerated() {
        BookVoteScores.applyResponse(summary("{\"bookType\":\"random\",\"bookId\":\"keep\",\"up\":10,\"down\":0}"));
        double kept = BookVoteScores.voteFactor("random", "keep");

        BookVoteScores.applyResponse("not json at all {{{");
        assertEquals(kept, BookVoteScores.voteFactor("random", "keep"), 1e-9, "garbage body keeps snapshot");
        BookVoteScores.applyResponse("{\"ok\":false,\"votes\":[]}");
        assertEquals(kept, BookVoteScores.voteFactor("random", "keep"), 1e-9, "not-ok keeps snapshot");
        BookVoteScores.applyResponse("{\"ok\":true}");
        assertEquals(kept, BookVoteScores.voteFactor("random", "keep"), 1e-9, "missing votes array keeps snapshot");

        // A garbled row is skipped without sinking the parse of its siblings.
        BookVoteScores.applyResponse(summary(
            "{\"bookType\":\"random\",\"bookId\":\"good\",\"up\":5,\"down\":0},"
            + "{\"bookType\":\"random\",\"bookId\":\"bad\",\"up\":\"many\",\"down\":0}"));
        org.junit.jupiter.api.Assertions.assertTrue(BookVoteScores.voteFactor("random", "good") > 1.0);
        assertEquals(1.0, BookVoteScores.voteFactor("random", "bad"), 1e-9);
    }
}
