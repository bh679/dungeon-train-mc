package games.brennan.dungeontrain.client.nap;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NapSkyPathsTest {

    private static final UUID PLAYER = UUID.fromString("3f1c2a4e-5b6d-4e7f-8a9b-0c1d2e3f4a5b");

    private static NapSky sky() {
        return NapSky.parse(new InputStreamReader(Objects.requireNonNull(
            NapSkyPathsTest.class.getResourceAsStream("/assets/dungeontrain/nap/sky.json")), StandardCharsets.UTF_8));
    }

    @Test
    void bundledSkyHasTheEngineAtTheCentreAndLinesOffIt() {
        NapSky sky = sky();
        assertEquals(NapSky.CENTRE, sky.x(0), 0.01);
        assertEquals(NapSky.CENTRE, sky.y(0), 0.01);
        assertTrue(sky.neighbours(0).length >= 2, "the Engine needs lines out of it");
    }

    @Test
    void aPastLifeDrawsTheSamePathEveryVisit() {
        NapSky sky = sky();
        for (long life = 0; life < 30; life++) {
            assertEquals(NapSkyPaths.pastLife(sky, PLAYER, life), NapSkyPaths.pastLife(sky, PLAYER, life));
        }
    }

    @Test
    void livesAndPlayersGetDifferentPaths() {
        NapSky sky = sky();
        Set<Object> seen = new HashSet<>();
        for (long life = 0; life < 20; life++) seen.add(NapSkyPaths.pastLife(sky, PLAYER, life).edges());
        assertTrue(seen.size() > 15, "twenty lives should mostly differ, got " + seen.size());
        assertNotEquals(NapSkyPaths.pastLife(sky, PLAYER, 3), NapSkyPaths.pastLife(sky, UUID.randomUUID(), 3));
    }

    @Test
    void pathsFollowRealLinesNeverRevisitAndStayShallow() {
        NapSky sky = sky();
        Random rng = new Random(42);
        for (int i = 0; i < 300; i++) {
            NapSkyPaths.Path p = i % 2 == 0 ? NapSkyPaths.random(sky, rng, -1) : NapSkyPaths.pastLife(sky, PLAYER, i);
            assertValid(sky, p);
        }
    }

    @Test
    void regrowingKeepsAValidPathFromTheEngine() {
        NapSky sky = sky();
        Random rng = new Random(7);
        NapSkyPaths.Path p = NapSkyPaths.random(sky, rng, -1);
        for (int i = 0; i < 200; i++) {
            NapSkyPaths.Regrowth r = NapSkyPaths.regrow(sky, p, rng);
            assertValid(sky, r.path());
            p = r.path();
        }
    }

    @Test
    void nearbySeedsStillGiveDifferentPaths() {
        NapSky sky = sky();
        Set<Object> seen = new HashSet<>();
        long base = 1_763_300_000_000L;
        for (int i = 0; i < 20; i++) seen.add(NapSkyPaths.random(sky, NapSkyPaths.rng(base + i), -1).edges());
        assertTrue(seen.size() > 15, "visits ms apart should mostly differ, got " + seen.size());
    }

    @Test
    void aRegrowChangesTheShape() {
        NapSky sky = sky();
        Random rng = NapSkyPaths.rng(99);
        NapSkyPaths.Path p = NapSkyPaths.random(sky, rng, -1);
        int changed = 0;
        for (int i = 0; i < 50; i++) {
            NapSkyPaths.Regrowth r = NapSkyPaths.regrow(sky, p, rng);
            if (!new HashSet<>(r.path().edges()).equals(new HashSet<>(p.edges()))) changed++;
            p = r.path();
        }
        assertTrue(changed > 40, "regrows should nearly always change the path, got " + changed);
    }

    @Test
    void avoidsLeavingByTheSameStarTwiceInARow() {
        NapSky sky = sky();
        Random rng = new Random(1);
        int first = NapSkyPaths.random(sky, rng, -1).first();
        for (int i = 0; i < 50; i++) {
            int next = NapSkyPaths.random(sky, rng, first).first();
            assertNotEquals(first, next);
            first = next;
        }
    }

    private static void assertValid(NapSky sky, NapSkyPaths.Path p) {
        Set<Integer> reached = new HashSet<>(Set.of(0));
        for (NapSkyPaths.Edge e : p.edges()) {
            assertTrue(sky.joined(e.from(), e.to()), "not a real line: " + e);
            assertTrue(reached.contains(e.from()), "line starts off the path: " + e);
            assertTrue(reached.add(e.to()), "star visited twice: " + e);
            assertTrue(e.depth() >= 1 && e.depth() <= NapSkyPaths.MAX_DEPTH, "too deep: " + e);
        }
        assertFalse(p.edges().isEmpty());
        assertEquals(0, p.edges().get(0).from());
    }

    @Test
    void fromStarsWalksOutThroughLitStarsByDepth() {
        NapSky sky = sky();
        int a = sky.neighbours(0)[0];
        int b = -1;
        for (int n : sky.neighbours(a)) if (n != 0 && !sky.joined(0, n)) { b = n; break; }
        assertTrue(b > 0, "need a star two lines out");
        NapSkyPaths.Path p = NapSkyPaths.fromStars(sky, Set.of(a, b));
        assertEquals(java.util.List.of(new NapSkyPaths.Edge(0, a, 1), new NapSkyPaths.Edge(a, b, 2)), p.edges());
        assertEquals(2, p.depth());
        assertEquals(a, p.first());
    }

    @Test
    void fromStarsBridgesALitStarNoLitStarLeadsTo() {
        NapSky sky = sky();
        int a = sky.neighbours(0)[0];
        int b = -1;
        for (int n : sky.neighbours(a)) if (n != 0 && !sky.joined(0, n)) { b = n; break; }
        NapSkyPaths.Path p = NapSkyPaths.fromStars(sky, Set.of(b));
        Set<Integer> reached = new HashSet<>();
        for (NapSkyPaths.Edge e : p.edges()) {
            assertTrue(sky.joined(e.from(), e.to()));
            reached.add(e.to());
        }
        assertTrue(reached.contains(b), "the lit star is reached");
        assertEquals(2, p.edges().size(), "by the shortest run");
    }

    @Test
    void fromStarsWithNothingLitDrawsNothing() {
        NapSky sky = sky();
        assertTrue(NapSkyPaths.fromStars(sky, Set.of()).edges().isEmpty());
        assertTrue(NapSkyPaths.fromStars(sky, Set.of(0, -3, 9999)).edges().isEmpty());
        assertEquals(-1, NapSkyPaths.fromStars(sky, Set.of()).first());
    }

    @Test
    void fromStarsReachesEveryLitStarOnce() {
        NapSky sky = sky();
        Random rng = new Random(5);
        for (int k = 0; k < 200; k++) {
            Set<Integer> lit = new HashSet<>();
            int n = 1 + rng.nextInt(12);
            for (int i = 0; i < n; i++) lit.add(1 + rng.nextInt(sky.size() - 1));
            NapSkyPaths.Path p = NapSkyPaths.fromStars(sky, lit);
            Set<Integer> to = new HashSet<>();
            for (NapSkyPaths.Edge e : p.edges()) {
                assertTrue(sky.joined(e.from(), e.to()));
                assertTrue(to.add(e.to()), "a star is reached twice");
                assertTrue(e.depth() >= 1);
            }
            assertTrue(to.containsAll(lit), "every lit star drawn");
        }
    }
}
