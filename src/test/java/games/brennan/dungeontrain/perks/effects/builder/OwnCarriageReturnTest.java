package games.brennan.dungeontrain.perks.effects.builder;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Own Carriage Return: the own share widens from the pool share, fresh untouched, pool floored. */
class OwnCarriageReturnTest {

    private static final double POOL = 0.65;
    private static final double OWN = 0.30;
    private static final double EPS = 1e-9;

    @Test
    void rankZeroLeavesTheConfigSplitAlone() {
        OwnCarriageReturn.Split s = OwnCarriageReturn.split(POOL, OWN, 0);
        assertEquals(POOL, s.pool(), EPS);
        assertEquals(OWN, s.own(), EPS);
    }

    @Test
    void eachRankMovesTenPercentFromPoolToOwn() {
        OwnCarriageReturn.Split s1 = OwnCarriageReturn.split(POOL, OWN, 1);
        assertEquals(0.55, s1.pool(), EPS);
        assertEquals(0.40, s1.own(), EPS);
        OwnCarriageReturn.Split s3 = OwnCarriageReturn.split(POOL, OWN, 3);
        assertEquals(0.35, s3.pool(), EPS);
        assertEquals(0.60, s3.own(), EPS);
    }

    @Test
    void freshShareNeverChanges() {
        for (int rank = 0; rank <= 10; rank++) {
            OwnCarriageReturn.Split s = OwnCarriageReturn.split(POOL, OWN, rank);
            assertEquals(1.0 - POOL - OWN, 1.0 - s.pool() - s.own(), EPS, "rank " + rank);
        }
    }

    @Test
    void poolIsFlooredSoOtherBuildsKeepArriving() {
        OwnCarriageReturn.Split s = OwnCarriageReturn.split(POOL, OWN, 9);
        assertEquals(OwnCarriageReturn.MIN_POOL_SHARE, s.pool(), EPS);
        assertEquals(0.85, s.own(), EPS);
        assertEquals(s, OwnCarriageReturn.split(POOL, OWN, 50), "past the floor more rank changes nothing");
    }

    @Test
    void aPoolAlreadyBelowTheFloorIsNotTouched() {
        OwnCarriageReturn.Split s = OwnCarriageReturn.split(0.05, 0.30, 3);
        assertEquals(0.05, s.pool(), EPS);
        assertEquals(0.30, s.own(), EPS);
    }

    @Test
    void prefetchAlternatesHoldersWithTheRoundRobin() {
        List<String> players = List.of("a", "b", "c");
        List<Integer> ranks = List.of(0, 2, 0);
        assertEquals("b", OwnCarriageReturn.prefetchOwner(players, ranks, 0));
        assertEquals("b", OwnCarriageReturn.prefetchOwner(players, ranks, 1), "odd ticks round-robin: cursor 1 = b anyway");
        assertEquals("b", OwnCarriageReturn.prefetchOwner(players, ranks, 2));
        assertEquals("a", OwnCarriageReturn.prefetchOwner(players, ranks, 3));
        assertEquals("b", OwnCarriageReturn.prefetchOwner(players, ranks, 4));
        assertEquals("c", OwnCarriageReturn.prefetchOwner(players, ranks, 5));
    }

    @Test
    void prefetchWithNoHoldersIsThePlainRoundRobin() {
        List<String> players = List.of("a", "b", "c");
        List<Integer> ranks = List.of(0, 0, 0);
        for (int c = 0; c < 9; c++) {
            assertEquals(players.get(c % 3), OwnCarriageReturn.prefetchOwner(players, ranks, c));
        }
    }

    @Test
    void severalHoldersRotateAmongThemselves() {
        List<String> players = List.of("a", "b", "c");
        List<Integer> ranks = List.of(1, 0, 3);
        assertEquals("a", OwnCarriageReturn.prefetchOwner(players, ranks, 0));
        assertEquals("c", OwnCarriageReturn.prefetchOwner(players, ranks, 2));
        assertEquals("a", OwnCarriageReturn.prefetchOwner(players, ranks, 4));
    }
}
