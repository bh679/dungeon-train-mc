package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalRoomTiling.Tile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sliding window of room copies. Pure integer geometry, so this needs no NeoForge bootstrap —
 * which is the point of keeping Minecraft types out of {@link PortalRoomTiling}.
 */
class PortalRoomTilingTest {

    private static final int RADIUS = PortalRoomTiling.MAX_RADIUS;
    private static final int UNLIMITED = Integer.MAX_VALUE;

    /** Fill the window around {@code centre} the way the tick loop does — one tile at a time. */
    private static PortalRoomTiling fillAround(Tile centre, int radius, int budget) {
        PortalRoomTiling tiling = PortalRoomTiling.base();
        for (Tile next; (next = tiling.nextToAdd(centre, radius, budget)) != null; ) {
            tiling = tiling.with(next);
        }
        return tiling;
    }

    // ---- the corridor row ----

    @Test
    @DisplayName("the corridor row tiles like any other — the corridors are stamped back on top")
    void corridorRowTilesLikeAnyOther() {
        PortalRoomTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        for (int x = -RADIUS; x <= RADIUS; x++) {
            assertTrue(tiling.has(new Tile(x, 0)), "corridor row tile " + x + " should be filled");
        }
    }

    @Test
    @DisplayName("filling covers the whole square, corridor row included")
    void fillCoversEveryCell() {
        PortalRoomTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        int side = 2 * RADIUS + 1;
        assertEquals(side * side, tiling.size());
        assertNull(tiling.nextToAdd(Tile.BASE, RADIUS, UNLIMITED));
    }

    // ---- the base tile ----

    @Test
    @DisplayName("the base tile is always present and never retired")
    void baseTileIsPinned() {
        assertTrue(PortalRoomTiling.base().has(Tile.BASE));
        assertTrue(PortalRoomTiling.base().isBaseOnly());
        // Explicitly removing it does nothing.
        assertTrue(PortalRoomTiling.base().without(Tile.BASE).has(Tile.BASE));
        // Nor is it ever offered up for retirement, however far the player walks.
        PortalRoomTiling filled = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        assertFalse(Tile.BASE.equals(filled.nextToRemove(new Tile(0, 40), RADIUS)));
        assertFalse(Tile.BASE.equals(filled.farthestFrom(new Tile(0, 40))));
        // A tiling stripped back to nothing but the base offers no more to drain.
        assertNull(PortalRoomTiling.base().farthestFrom(Tile.BASE));
    }

    // ---- filling ----

    @Test
    @DisplayName("filling is nearest-first, so the player is surrounded before anything distant")
    void fillIsNearestFirst() {
        PortalRoomTiling tiling = PortalRoomTiling.base();
        int previousDistance = 0;
        for (Tile next; (next = tiling.nextToAdd(Tile.BASE, RADIUS, UNLIMITED)) != null; ) {
            int distance = next.distanceSqTo(Tile.BASE);
            assertTrue(distance >= previousDistance,
                "tile " + next + " at " + distance + " came after one at " + previousDistance);
            previousDistance = distance;
            tiling = tiling.with(next);
        }
    }

    @Test
    @DisplayName("the budget caps the resident set — a big room gets neighbours, not a hundred copies")
    void budgetCapsTheResidentSet() {
        PortalRoomTiling tiling = fillAround(Tile.BASE, RADIUS, 6);
        assertEquals(6, tiling.size());
        assertNull(tiling.nextToAdd(Tile.BASE, RADIUS, 6));
        // And what it kept is the nearest ring, not an arbitrary six.
        assertTrue(tiling.has(new Tile(0, 1)));
        assertTrue(tiling.has(new Tile(0, -1)));
    }

    @Test
    @DisplayName("budgetTiles spends the block budget: a small room fills the window, a max room does not")
    void budgetTilesScalesWithRoomSize() {
        int window = (2 * RADIUS + 1) * (2 * RADIUS + 1);
        int builtIn = 11 * 7 * 13;
        int biggest = PortalRoomLayout.MAX_LENGTH * PortalRoomLayout.MAX_HEIGHT * PortalRoomLayout.MAX_WIDTH;

        assertEquals(window, PortalRoomTiling.budgetTiles(builtIn));
        assertEquals(PortalRoomTiling.MAX_RADIUS, PortalRoomTiling.budgetTiles(biggest));
        // Never below one, and never above the window, whatever it is handed.
        assertEquals(1, PortalRoomTiling.budgetTiles(PortalRoomTiling.MAX_RESIDENT_BLOCKS * 8));
        assertEquals(window, PortalRoomTiling.budgetTiles(0));
    }

    @Test
    @DisplayName("no resident set can outgrow the block ceiling, whatever size the room is")
    void residentBlocksStayWithinBudget() {
        for (int blocks : new int[]{1, 200, 1001, 5000, 25344}) {
            assertTrue((long) PortalRoomTiling.budgetTiles(blocks) * blocks
                    <= Math.max(PortalRoomTiling.MAX_RESIDENT_BLOCKS, (long) blocks),
                "budget for a " + blocks + "-block room exceeds the ceiling");
        }
        // Including a room so large that a single copy already blows the ceiling — it still gets one,
        // because refusing the base tile would leave a player walking into an unbuilt corridor.
        assertEquals(1, PortalRoomTiling.budgetTiles(PortalRoomTiling.MAX_RESIDENT_BLOCKS + 1));
    }

    // ---- sliding ----

    @Test
    @DisplayName("walking away sheds what is behind: tiles out of range retire, farthest first")
    void windowSlidesAfterThePlayer() {
        PortalRoomTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        Tile walkedTo = new Tile(0, 3);

        Tile retiring = tiling.nextToRemove(walkedTo, RADIUS);
        assertNotNull(retiring, "tiles behind the player should fall out of range");
        assertTrue(Math.abs(retiring.z() - walkedTo.z()) > RADIUS
                || Math.abs(retiring.x() - walkedTo.x()) > RADIUS,
            retiring + " is still inside the window around " + walkedTo);
        // Farthest first.
        assertEquals(-RADIUS, retiring.z());
    }

    @Test
    @DisplayName("a window centred on the player has nothing to retire")
    void nothingRetiresWhileCentred() {
        PortalRoomTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        assertNull(tiling.nextToRemove(Tile.BASE, RADIUS));
    }

    @Test
    @DisplayName("slide then refill leaves the window centred on where the player now is")
    void slideThenRefillRecentres() {
        Tile walkedTo = new Tile(2, 8);
        PortalRoomTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);

        // Retire what fell behind, then fill what came into range — the same two steps the tick loop
        // takes, one tile at a time, threaded through the same tiling rather than starting over.
        for (Tile stale; (stale = tiling.nextToRemove(walkedTo, RADIUS)) != null; ) {
            tiling = tiling.without(stale);
        }
        for (Tile next; (next = tiling.nextToAdd(walkedTo, RADIUS, UNLIMITED)) != null; ) {
            tiling = tiling.with(next);
        }

        assertTrue(tiling.has(walkedTo.offset(0, RADIUS)), "the leading edge should have filled in");
        assertTrue(tiling.has(walkedTo.offset(RADIUS, 0)));
        // Nothing is left behind the window bar the base, which holds the corridors and never retires.
        assertTrue(tiling.has(Tile.BASE));
        assertFalse(tiling.has(new Tile(0, 1)), "a tile far behind the player should have retired");
        assertNull(tiling.nextToRemove(walkedTo, RADIUS));
    }

    @Test
    @DisplayName("the approach radius builds one ring — eight copies, not a hundred and twenty")
    void approachRadiusBuildsOneRing() {
        PortalRoomTiling tiling = fillAround(Tile.BASE, PortalRoomTiling.APPROACH_RADIUS, UNLIMITED);
        // The whole 3×3 around the base, the base included.
        assertEquals(9, tiling.size());
        assertTrue(tiling.has(new Tile(1, 1)));
        assertTrue(tiling.has(new Tile(0, 1)));
        assertTrue(tiling.has(new Tile(1, 0)), "the corridor row tiles too");
        assertFalse(tiling.has(new Tile(0, 2)), "one ring, not two");

        // And it is a small fraction of what standing in the room gets you.
        assertTrue(fillAround(Tile.BASE, RADIUS, UNLIMITED).size() > tiling.size() * 10);
    }

    @Test
    @DisplayName("walking in widens the window rather than rebuilding it — the ring is kept")
    void approachRingSurvivesWideningToTheFullWindow() {
        PortalRoomTiling approached =
            fillAround(Tile.BASE, PortalRoomTiling.APPROACH_RADIUS, UNLIMITED);
        PortalRoomTiling inside = approached;
        for (Tile next; (next = inside.nextToAdd(Tile.BASE, RADIUS, UNLIMITED)) != null; ) {
            inside = inside.with(next);
        }
        for (Tile t : approached.tiles()) {
            assertTrue(inside.has(t), t + " was dropped when the window widened");
        }
        assertNull(inside.nextToRemove(Tile.BASE, RADIUS));
    }

    // ---- copies that cannot be built, and copies that must not be erased ----

    @Test
    @DisplayName("a candidate that cannot be built is passed over for the next-nearest, not returned")
    void unbuildableCandidatesArePassedOver() {
        PortalRoomTiling tiling = PortalRoomTiling.base();

        // Whatever it would have picked first...
        Tile first = tiling.nextToAdd(Tile.BASE, RADIUS, UNLIMITED);
        assertNotNull(first);

        // ...stands in for an unloaded chunk, or another pair's structure sitting in the way.
        Tile chosen = tiling.nextToAdd(Tile.BASE, RADIUS, UNLIMITED, t -> !t.equals(first));
        assertNotNull(chosen, "a blocked candidate should be passed over, not stall the fill");
        assertFalse(first.equals(chosen));
        // Still the nearest of what is left, not an arbitrary cell.
        assertEquals(1, chosen.distanceSqTo(Tile.BASE));
    }

    @Test
    @DisplayName("a fill whose every candidate is refused simply builds nothing")
    void everythingRefusedBuildsNothing() {
        assertNull(PortalRoomTiling.base().nextToAdd(Tile.BASE, RADIUS, UNLIMITED, t -> false));
    }

    @Test
    @DisplayName("a copy somebody is standing in is never retired — clearing it takes its floor too")
    void occupiedCopiesAreSpared() {
        PortalRoomTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        Tile walkedTo = new Tile(0, 3);
        Tile secondPlayer = new Tile(5, -5);   // the far corner, first in line to retire

        assertEquals(secondPlayer, tiling.nextToRemove(walkedTo, RADIUS));
        Tile spared = tiling.nextToRemove(walkedTo, RADIUS, t -> !t.equals(secondPlayer));
        assertNotNull(spared);
        assertFalse(secondPlayer.equals(spared));
    }

    @Test
    @DisplayName("draining spares occupied copies too — a logged-in player is not dropped")
    void drainSparesOccupiedCopies() {
        Tile occupied = new Tile(0, 5);
        PortalRoomTiling tiling = PortalRoomTiling.base().with(occupied).with(new Tile(0, 1));

        PortalRoomTiling drained = tiling;
        for (Tile next; (next = drained.farthestFrom(Tile.BASE, t -> !t.equals(occupied))) != null; ) {
            drained = drained.without(next);
        }
        assertTrue(drained.has(occupied));
        assertTrue(drained.has(Tile.BASE));
        assertEquals(2, drained.size());
    }

    // ---- several people in one room ----

    /** Fill the window around every one of {@code centres}, the way the tick loop does. */
    private static PortalRoomTiling fillAround(Set<Tile> centres, int radius, int budget) {
        PortalRoomTiling tiling = PortalRoomTiling.base();
        for (Tile next; (next = tiling.nextToAdd(centres, radius, budget, t -> true)) != null; ) {
            tiling = tiling.with(next);
        }
        return tiling;
    }

    /** Retire everything the window offers up, the way the tick loop does — one tile at a time. */
    private static PortalRoomTiling drainTo(PortalRoomTiling tiling, Set<Tile> centres, int radius) {
        PortalRoomTiling drained = tiling;
        for (Tile stale; (stale = drained.nextToRemove(centres, radius, t -> true)) != null; ) {
            drained = drained.without(stale);
        }
        return drained;
    }

    @Test
    @DisplayName("the floor around a second player is not erased when the first walks the other way")
    void aSecondOccupantKeepsTheirSurroundings() {
        // The multiplayer bug this all exists for. Two players walk in; one heads off along -z while
        // the other stays put ten tiles away. Following one centre, everything around the one who
        // stayed falls outside the window and is cleared — floor included — leaving them on an island
        // one room across with the world floor below the next step they take.
        PortalRoomTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        Tile walker = new Tile(0, -5);
        Tile stayed = new Tile(0, 5);
        Tile besideStayed = new Tile(1, 5);
        assertTrue(tiling.has(besideStayed));

        PortalRoomTiling drained = drainTo(tiling, Set.of(walker, stayed), RADIUS);
        for (Tile t : tiling.tiles()) {
            if (Math.abs(t.x() - stayed.x()) <= RADIUS && Math.abs(t.z() - stayed.z()) <= RADIUS) {
                assertTrue(drained.has(t), t + " was erased beside the second player");
            }
        }
        assertTrue(drained.has(besideStayed));

        // And the contrast: with the walker as the only centre — what the tiler used to do — the tile
        // beside the player who stayed is exactly what goes.
        PortalRoomTiling followedOne = drainTo(tiling, Set.of(walker), RADIUS);
        assertFalse(followedOne.has(besideStayed));
    }

    @Test
    @DisplayName("the room grows ahead of every occupant, not only the first")
    void theWindowFollowsEverybody() {
        Tile first = Tile.BASE;
        Tile second = new Tile(0, 12);
        PortalRoomTiling tiling = fillAround(Set.of(first, second), RADIUS, UNLIMITED);

        // Both are surrounded to the full radius — nobody is walking into unbuilt space.
        for (int d = -RADIUS; d <= RADIUS; d++) {
            assertTrue(tiling.has(second.offset(0, d)), "the second player's row should be built");
            assertTrue(tiling.has(first.offset(0, d)));
        }
        // Including the tile they are walking towards, well outside the first player's window.
        assertTrue(tiling.has(new Tile(0, 17)));
        assertNull(tiling.nextToRemove(Set.of(first, second), RADIUS, t -> true));
    }

    @Test
    @DisplayName("the fill is nearest-to-anybody, so nobody waits for the other's window to finish")
    void fillIsNearestToTheNearestOccupant() {
        Tile first = Tile.BASE;
        Tile second = new Tile(0, 12);
        PortalRoomTiling tiling = PortalRoomTiling.base().with(second);

        // The very first tile laid is beside one of them, not four rooms out in the first's window.
        Tile next = tiling.nextToAdd(Set.of(first, second), RADIUS, UNLIMITED, t -> true);
        assertNotNull(next);
        assertEquals(1, Math.min(next.distanceSqTo(first), next.distanceSqTo(second)));
    }

    @Test
    @DisplayName("the budget scales with the occupants, so a shared window is not a wall")
    void budgetScalesWithOccupants() {
        int builtIn = 11 * 7 * 13;
        int one = PortalRoomTiling.budgetTiles(builtIn);

        assertEquals(one, PortalRoomTiling.budgetTiles(builtIn, 1));
        assertEquals(one * 2, PortalRoomTiling.budgetTiles(builtIn, 2));
        // Capped: a full server in one room does not get the world floor.
        assertEquals(one * PortalRoomTiling.MAX_CENTRES,
            PortalRoomTiling.budgetTiles(builtIn, PortalRoomTiling.MAX_CENTRES + 20));
        // And an occupant count of nothing reads as one rather than as no budget at all.
        assertEquals(one, PortalRoomTiling.budgetTiles(builtIn, 0));
        // The per-occupant ceiling still holds for a room too big to tile.
        assertEquals(2, PortalRoomTiling.budgetTiles(PortalRoomTiling.MAX_RESIDENT_BLOCKS, 2));
    }

    @Test
    @DisplayName("one occupant behaves exactly as it always did — the set overload is the same window")
    void oneOccupantIsUnchanged() {
        Tile walkedTo = new Tile(0, 3);
        PortalRoomTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        assertEquals(tiling.tiles(), fillAround(Set.of(Tile.BASE), RADIUS, UNLIMITED).tiles());
        assertEquals(tiling.nextToRemove(walkedTo, RADIUS),
            tiling.nextToRemove(Set.of(walkedTo), RADIUS, t -> true));
        assertEquals(tiling.nextToAdd(walkedTo, RADIUS, UNLIMITED),
            tiling.nextToAdd(Set.of(walkedTo), RADIUS, UNLIMITED, t -> true));
    }

    // ---- bounds ----

    @Test
    @DisplayName("a base-only tiling reports the base tile's own bounds — the boxes stay what they were")
    void baseOnlyBoundsAreZero() {
        PortalRoomTiling tiling = PortalRoomTiling.base();
        assertEquals(0, tiling.minTileX());
        assertEquals(0, tiling.maxTileX());
        assertEquals(0, tiling.minTileZ());
        assertEquals(0, tiling.maxTileZ());
    }

    @Test
    @DisplayName("bounds span every standing copy")
    void boundsSpanTheResidentSet() {
        PortalRoomTiling tiling = PortalRoomTiling.base()
            .with(new Tile(-2, 1)).with(new Tile(3, -4));
        assertEquals(-2, tiling.minTileX());
        assertEquals(3, tiling.maxTileX());
        assertEquals(-4, tiling.minTileZ());
        assertEquals(1, tiling.maxTileZ());
    }

    @Test
    @DisplayName("adding a tile that is already standing is a no-op, not a copy")
    void addingAnExistingTileIsANoOp() {
        PortalRoomTiling tiling = PortalRoomTiling.base().with(new Tile(0, 1));
        assertSame(tiling, tiling.with(new Tile(0, 1)));
        assertSame(tiling, tiling.without(new Tile(5, 5)));
    }
}
