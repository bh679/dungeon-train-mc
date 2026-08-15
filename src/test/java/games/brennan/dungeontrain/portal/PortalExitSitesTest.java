package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalExitSites.Site;
import games.brennan.dungeontrain.portal.DimensionalCarriageTiling.Tile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where an endless room's extra corridors land.
 *
 * <p>The load-bearing property is <b>determinism</b>: a site is a pure function of the world seed,
 * the pair, the room name and the tile, never of when it was asked. A way out that moved when the
 * player turned their back would be worse than no extra way out at all — and the sliding window
 * retires and re-lays copies constantly, so "when it was asked" changes all the time.</p>
 */
class PortalExitSitesTest {

    private static final long SEED = PortalExitSites.seedFor(1234567L, 47, "labrynth");

    private static DimensionalCarriageExits on(int every) {
        return new DimensionalCarriageExits(DimensionalCarriageExits.Kind.ON, every);
    }

    private static DimensionalCarriageExits random(int every) {
        return new DimensionalCarriageExits(DimensionalCarriageExits.Kind.RANDOM, every);
    }

    // ---- Off ----

    @Test
    @DisplayName("Off lays nothing, anywhere")
    void offLaysNothing() {
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                assertTrue(PortalExitSites.owedAt(DimensionalCarriageExits.OFF, new Tile(x, z), SEED).isEmpty());
            }
        }
    }

    // ---- On ----

    @Test
    @DisplayName("On is a lattice: every X tiles on BOTH axes, and a set of two when it hits")
    void onIsALattice() {
        int every = 8;
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                Tile tile = new Tile(x, z);
                List<Site> owed = PortalExitSites.owedAt(on(every), tile, SEED);
                boolean lattice = Math.floorMod(x, every) == 0 && Math.floorMod(z, every) == 0;
                boolean expected = lattice && !Tile.BASE.equals(tile);
                assertEquals(expected, !owed.isEmpty(), tile.toString());
                if (!expected) continue;

                // A "set" is one of each, so a player who finds one has both a way back and a way on.
                assertEquals(2, owed.size(), tile.toString());
                assertTrue(owed.contains(new Site(tile, PortalCarriageRole.ENTRY)));
                assertTrue(owed.contains(new Site(tile, PortalCarriageRole.EXIT)));
            }
        }
    }

    @Test
    @DisplayName("The base tile is never a site — the pair's own two corridors already stand there")
    void baseTileIsNeverASite() {
        assertTrue(PortalExitSites.owedAt(on(8), Tile.BASE, SEED).isEmpty());
        assertTrue(PortalExitSites.owedAt(on(2), Tile.BASE, SEED).isEmpty());
        // Random rolls the base tile too; whatever it says, nothing is owed there.
        for (int every = 2; every <= 8; every++) {
            assertTrue(PortalExitSites.owedAt(random(every), Tile.BASE, SEED).isEmpty(),
                "every=" + every);
        }
    }

    // ---- Random ----

    @Test
    @DisplayName("Random hits about one tile in X, and lays a single corridor when it does")
    void randomHitsAboutOneTileInX() {
        int every = 8;
        int tiles = 0;
        int hits = 0;
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                Tile tile = new Tile(x, z);
                if (Tile.BASE.equals(tile)) continue;
                tiles++;
                List<Site> owed = PortalExitSites.owedAt(random(every), tile, SEED);
                if (owed.isEmpty()) continue;
                hits++;
                assertEquals(1, owed.size(), tile.toString());
            }
        }
        double rate = hits / (double) tiles;
        // 3721 tiles: a fair 1-in-8 lands near 0.125, and anything outside this band means the mix
        // has collapsed rather than that the sample was unlucky.
        assertTrue(rate > 0.09 && rate < 0.17, "hit rate " + rate + " over " + tiles + " tiles");
    }

    @Test
    @DisplayName("Roughly a quarter of random sites are entries, the rest the way onward")
    void randomFavoursExits() {
        int entries = 0;
        int exits = 0;
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                for (Site site : PortalExitSites.owedAt(random(4), new Tile(x, z), SEED)) {
                    if (site.role() == PortalCarriageRole.ENTRY) entries++; else exits++;
                }
            }
        }
        int total = entries + exits;
        assertTrue(total > 500, "only " + total + " sites to measure the split on");
        double entryShare = entries / (double) total;
        assertTrue(entryShare > 0.18 && entryShare < 0.33,
            "entry share " + entryShare + " over " + total + " sites");
    }

    @Test
    @DisplayName("The role survives a change of spacing — nudging X must not flip every corridor")
    void roleIsIndependentOfSpacing() {
        // Two draws rather than one split into ranges, so the two questions cannot interfere. Any
        // tile that is a site at both spacings must be the same KIND of site at both.
        int compared = 0;
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                Tile tile = new Tile(x, z);
                List<Site> a = PortalExitSites.owedAt(random(4), tile, SEED);
                List<Site> b = PortalExitSites.owedAt(random(9), tile, SEED);
                if (a.isEmpty() || b.isEmpty()) continue;
                compared++;
                assertEquals(a.get(0).role(), b.get(0).role(), tile.toString());
            }
        }
        assertTrue(compared > 50, "only " + compared + " tiles were sites at both spacings");
    }

    // ---- determinism ----

    @Test
    @DisplayName("A site is a pure function of where it is — walking back finds the corridor you left")
    void sitesAreDeterministic() {
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                Tile tile = new Tile(x, z);
                assertEquals(PortalExitSites.owedAt(random(6), tile, SEED),
                    PortalExitSites.owedAt(random(6), tile, SEED), tile.toString());
            }
        }
    }

    @Test
    @DisplayName("Two pairs that rolled the same room put their corridors in different places")
    void differentPairsScatterDifferently() {
        long a = PortalExitSites.seedFor(1234567L, 47, "labrynth");
        long b = PortalExitSites.seedFor(1234567L, 91, "labrynth");
        assertTrue(differsSomewhere(a, b), "two pairs produced identical site maps");
    }

    @Test
    @DisplayName("Two worlds, and two rooms, scatter differently too")
    void seedAndNameBothMatter() {
        long here = PortalExitSites.seedFor(1234567L, 47, "labrynth");
        assertTrue(differsSomewhere(here, PortalExitSites.seedFor(7654321L, 47, "labrynth")),
            "two world seeds produced identical site maps");
        assertTrue(differsSomewhere(here, PortalExitSites.seedFor(1234567L, 47, "distantenemies")),
            "two room names produced identical site maps");
    }

    private static boolean differsSomewhere(long a, long b) {
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                Tile tile = new Tile(x, z);
                if (!PortalExitSites.owedAt(random(6), tile, a)
                        .equals(PortalExitSites.owedAt(random(6), tile, b))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("A null or non-laying setting owes nothing, rather than throwing")
    void nullsAreTolerated() {
        assertTrue(PortalExitSites.owedAt(null, new Tile(3, 3), SEED).isEmpty());
        assertTrue(PortalExitSites.owedAt(on(8), null, SEED).isEmpty());
    }

    // ---- reach ----

    @Test
    @DisplayName("The reach covers a corridor plus its plug, rounded up, and is never zero")
    void tileReachCoversTheWholeCopy() {
        // The default numbers: a 13-long corridor with a 3-deep plug beside an 11-long room reaches
        // two tiles. One would leave the far end of the corridor in a tile that could still be
        // standing when the copy is cleared — which is the pit this margin exists to prevent.
        assertEquals(2, PortalExitSites.tileReach(13, 11, 3));
        // Exactly one tile's worth still reaches one tile, not zero.
        assertEquals(1, PortalExitSites.tileReach(8, 11, 3));
        assertTrue(PortalExitSites.tileReach(13, 0, 3) >= 1);

        // Whatever the room, the reach must cover the corridor: a copy anchored at 0 puts blocks out
        // to corridorLength + plugDepth, and that has to fall inside `reach` tiles.
        for (int roomLength = 5; roomLength <= 48; roomLength++) {
            for (int corridorLength = 5; corridorLength <= 48; corridorLength++) {
                int reach = PortalExitSites.tileReach(corridorLength, roomLength, 3);
                assertTrue(reach * roomLength >= corridorLength + 3,
                    "reach " + reach + " misses a " + corridorLength + " corridor in a "
                        + roomLength + " room");
            }
        }
    }

    // ---- the moved exit ----

    private static DimensionalCarriageExits moved(int chance) {
        return new DimensionalCarriageExits(DimensionalCarriageExits.Kind.RANDOM, 8, chance);
    }

    /** How many of a run of pairs stand their exit elsewhere at this setting. */
    private static int movedOutOf(DimensionalCarriageExits exits, int pairs) {
        int n = 0;
        for (int pairKey = 0; pairKey < pairs; pairKey++) {
            if (PortalExitSites.movesExit(exits,
                    PortalExitSites.seedFor(1234567L, pairKey, "labrynth"))) {
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("Nought never moves it and ten always does — the two ends of the dial are absolute")
    void theEndsOfTheScaleAreAbsolute() {
        assertEquals(0, movedOutOf(moved(DimensionalCarriageExits.MOVE_NEVER), 400));
        assertEquals(400, movedOutOf(moved(DimensionalCarriageExits.MOVE_ALWAYS), 400));
        assertFalse(PortalExitSites.movesExit(null, SEED));
    }

    @Test
    @DisplayName("In between, the rate follows the dial")
    void theRateFollowsTheDial() {
        int pairs = 600;
        int low = movedOutOf(moved(3), pairs);
        int high = movedOutOf(moved(7), pairs);
        assertTrue(low > pairs * 0.20 && low < pairs * 0.40, "3/10 moved " + low + "/" + pairs);
        assertTrue(high > pairs * 0.60 && high < pairs * 0.80, "7/10 moved " + high + "/" + pairs);
        assertTrue(high > low);
    }

    @Test
    @DisplayName("Only Random moves the exit — the lattice is a walk you could work out in advance")
    void onlyRandomMovesTheExit() {
        for (DimensionalCarriageExits.Kind kind : new DimensionalCarriageExits.Kind[]{
                DimensionalCarriageExits.Kind.ON, DimensionalCarriageExits.Kind.OFF}) {
            assertEquals(0, movedOutOf(new DimensionalCarriageExits(kind, 8, DimensionalCarriageExits.MOVE_ALWAYS), 200),
                kind.name());
        }
    }

    @Test
    @DisplayName("A portal's answer never changes — walking out and back cannot re-roll it")
    void moveIsFixedForThePair() {
        long seed = PortalExitSites.seedFor(1234567L, 47, "labrynth");
        boolean first = PortalExitSites.movesExit(moved(5), seed);
        for (int i = 0; i < 50; i++) {
            assertEquals(first, PortalExitSites.movesExit(moved(5), seed));
        }
        // And it is the pair that decides, not the room's other settings: changing the spacing must
        // not silently move a portal's exit back.
        assertEquals(first, PortalExitSites.movesExit(
            new DimensionalCarriageExits(DimensionalCarriageExits.Kind.RANDOM, 3, 5), seed));
    }

    @Test
    @DisplayName("The move draw does not track the site draws — they are salted apart")
    void moveDoesNotCorrelateWithSites() {
        // If the two shared a draw, every moved-exit portal would have the same site map shape as every
        // other, which would read as a pattern rather than as chance.
        int agree = 0;
        int pairs = 300;
        for (int pairKey = 0; pairKey < pairs; pairKey++) {
            long seed = PortalExitSites.seedFor(1234567L, pairKey, "labrynth");
            boolean moves = PortalExitSites.movesExit(moved(5), seed);
            boolean siteAtOneOne = !PortalExitSites.owedAt(random(2), new Tile(1, 1), seed).isEmpty();
            if (moves == siteAtOneOne) agree++;
        }
        double rate = agree / (double) pairs;
        assertTrue(rate > 0.35 && rate < 0.65, "the two draws agreed " + rate + " of the time");
    }

    // ---- where a moved exit lands ----

    @Test
    @DisplayName("A moved exit goes to a tile these same rules already owe an exit at")
    void relocatedExitFollowsTheExistingRules() {
        DimensionalCarriageExits exits = new DimensionalCarriageExits(DimensionalCarriageExits.Kind.RANDOM, 4,
            DimensionalCarriageExits.MOVE_ALWAYS);
        int checked = 0;
        for (int pairKey = 0; pairKey < 200; pairKey++) {
            long seed = PortalExitSites.seedFor(1234567L, pairKey, "labrynth");
            Tile tile = PortalExitSites.relocatedExitTile(exits, seed, DimensionalCarriageTiling.MAX_RADIUS);
            if (Tile.BASE.equals(tile)) continue;
            checked++;
            // No placement rule of its own: the tile it picks is one the site rules own.
            assertTrue(PortalExitSites.owedAt(exits, tile, seed)
                    .contains(new Site(tile, PortalCarriageRole.EXIT)),
                "tile " + tile + " is not a site these rules owe an exit at");
        }
        assertTrue(checked > 150, "only " + checked + " pairs moved their exit at 10/10");
    }

    @Test
    @DisplayName("It takes the nearest such tile, so the walk is the shortest the rules allow")
    void relocatedExitTakesTheNearest() {
        DimensionalCarriageExits exits = new DimensionalCarriageExits(DimensionalCarriageExits.Kind.RANDOM, 4,
            DimensionalCarriageExits.MOVE_ALWAYS);
        for (int pairKey = 0; pairKey < 120; pairKey++) {
            long seed = PortalExitSites.seedFor(1234567L, pairKey, "labrynth");
            Tile chosen = PortalExitSites.relocatedExitTile(exits, seed, DimensionalCarriageTiling.MAX_RADIUS);
            if (Tile.BASE.equals(chosen)) continue;
            int ring = Math.max(Math.abs(chosen.x()), Math.abs(chosen.z()));
            // Nothing owed on any nearer ring.
            for (int x = -ring + 1; x <= ring - 1; x++) {
                for (int z = -ring + 1; z <= ring - 1; z++) {
                    Tile nearer = new Tile(x, z);
                    assertTrue(!PortalExitSites.owedAt(exits, nearer, seed)
                            .contains(new Site(nearer, PortalCarriageRole.EXIT)),
                        "a nearer exit site at " + nearer + " was passed over for " + chosen);
                }
            }
        }
    }

    @Test
    @DisplayName("Nothing owed in range means the exit stays where it has always been")
    void relocatedExitFallsBackToBase() {
        // Off owes nothing anywhere.
        assertEquals(Tile.BASE, PortalExitSites.relocatedExitTile(
            new DimensionalCarriageExits(DimensionalCarriageExits.Kind.OFF, 4, DimensionalCarriageExits.MOVE_ALWAYS),
            SEED, DimensionalCarriageTiling.MAX_RADIUS));
        // A dial at zero never moves it, however thickly the copies are scattered.
        assertEquals(Tile.BASE, PortalExitSites.relocatedExitTile(
            new DimensionalCarriageExits(DimensionalCarriageExits.Kind.RANDOM, 2, DimensionalCarriageExits.MOVE_NEVER),
            SEED, DimensionalCarriageTiling.MAX_RADIUS));
        // And a radius with nothing inside it falls back rather than reaching further.
        assertEquals(Tile.BASE, PortalExitSites.relocatedExitTile(
            new DimensionalCarriageExits(DimensionalCarriageExits.Kind.RANDOM, 4, DimensionalCarriageExits.MOVE_ALWAYS),
            SEED, 0));
        assertEquals(Tile.BASE, PortalExitSites.relocatedExitTile(null, SEED, 5));
    }

    @Test
    @DisplayName("Under the lattice a moved exit lands on a lattice point")
    void relocatedExitUnderOn() {
        // ON never moves an exit today (movesApply is Random-only), but the placement rule itself
        // must still answer sensibly if that gate is ever widened.
        DimensionalCarriageExits lattice = new DimensionalCarriageExits(DimensionalCarriageExits.Kind.ON, 2, 0);
        Tile tile = PortalExitSites.relocatedExitTile(lattice, SEED, DimensionalCarriageTiling.MAX_RADIUS);
        assertEquals(Tile.BASE, tile, "ON does not move its exit");
    }

    @Test
    @DisplayName("A pair's exit stands in the same place every time it is asked")
    void relocatedExitIsStable() {
        DimensionalCarriageExits exits = new DimensionalCarriageExits(DimensionalCarriageExits.Kind.RANDOM, 4,
            DimensionalCarriageExits.MOVE_ALWAYS);
        long seed = PortalExitSites.seedFor(1234567L, 47, "labrynth");
        Tile first = PortalExitSites.relocatedExitTile(exits, seed, DimensionalCarriageTiling.MAX_RADIUS);
        for (int i = 0; i < 30; i++) {
            assertEquals(first,
                PortalExitSites.relocatedExitTile(exits, seed, DimensionalCarriageTiling.MAX_RADIUS));
        }
    }

    @Test
    @DisplayName("A site knows how far it is from the window's centre, on the Chebyshev the window uses")
    void chebyshevMatchesTheWindow() {
        Site site = new Site(new Tile(8, -3), PortalCarriageRole.EXIT);
        assertEquals(8, site.chebyshevTo(Tile.BASE));
        assertEquals(0, site.chebyshevTo(new Tile(8, -3)));
        assertEquals(5, site.chebyshevTo(new Tile(3, -3)));
        assertFalse(site.chebyshevTo(new Tile(8, 2)) == 0);
    }
}
