package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalExitSites.Site;
import games.brennan.dungeontrain.portal.PortalRoomTiling.Tile;
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

    private static PortalRoomExits on(int every) {
        return new PortalRoomExits(PortalRoomExits.Kind.ON, every);
    }

    private static PortalRoomExits random(int every) {
        return new PortalRoomExits(PortalRoomExits.Kind.RANDOM, every);
    }

    // ---- Off ----

    @Test
    @DisplayName("Off lays nothing, anywhere")
    void offLaysNothing() {
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                assertTrue(PortalExitSites.owedAt(PortalRoomExits.OFF, new Tile(x, z), SEED).isEmpty());
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
