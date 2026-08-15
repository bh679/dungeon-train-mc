package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalExitSites.Site;
import games.brennan.dungeontrain.portal.DimensionalCarriageTiling.Tile;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where each player left an endless room, and — the part that matters — every way that answer is
 * allowed to go stale.
 *
 * <p>Nothing invalidates a binding. A copy retires as the window slides, a structure relocates and
 * drops all of them, an author turns Exits off between visits: all three have to resolve to "the
 * original twin" by being <b>checked</b> rather than by anyone having remembered to clear anything,
 * because the cost of a missed invalidation is teleporting somebody into solid rock at the bottom of
 * the world.</p>
 */
class PortalExitBindingsTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;
    private static final BlockPos ORIGIN = new BlockPos(200, -60, -30);
    private static final int PAIR = 47;

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private static final Tile FAR = new Tile(8, 0);

    /** A repeating room with both corridors of a set standing at {@link #FAR}. */
    private static PortalStructure withCopies(Site... standing) {
        PortalExitCopies copies = PortalExitCopies.NONE;
        for (Site site : standing) copies = copies.with(site);
        return new PortalStructure(ORIGIN, "labrynth", DimensionalCarriageLayout.builtInSize(DIMS),
            DimensionalCarriageSettings.DEFAULT.withMode(DimensionalCarriageMode.ENDLESS_REPETITION),
            DimensionalCarriageTiling.base(), copies);
    }

    private static Site entry(Tile tile) {
        return new Site(tile, PortalCarriageRole.ENTRY);
    }

    private static Site exit(Tile tile) {
        return new Site(tile, PortalCarriageRole.EXIT);
    }

    private static BlockPos resolve(PortalStructure s, UUID player, PortalCarriageRole role) {
        return PortalExitBindings.boundOriginFor(s, DIMS, player, PAIR, role);
    }

    @BeforeEach
    @AfterEach
    void clean() {
        PortalExitBindings.clear();
    }

    @Test
    @DisplayName("Nobody is bound to anything until they walk out of something")
    void unboundByDefault() {
        assertTrue(PortalExitBindings.isEmpty());
        assertNull(PortalExitBindings.boundTile(ALICE, PAIR));
        assertNull(resolve(withCopies(exit(FAR)), ALICE, PortalCarriageRole.EXIT));
    }

    @Test
    @DisplayName("Both carriages lead back to the tile you left from, each through its own corridor")
    void bothRolesResolveToTheBoundTile() {
        // The case the tile binding exists for: you came out through the EXIT copy at tile 8, and
        // the natural way back in is the ENTRY carriage. Binding to the corridor would send you to
        // the original entry twin and lose your place at exactly the wrong moment.
        PortalStructure s = withCopies(entry(FAR), exit(FAR));
        PortalExitBindings.bind(ALICE, PAIR, FAR);

        assertEquals(s.exitCopyOrigin(DIMS, entry(FAR)), resolve(s, ALICE, PortalCarriageRole.ENTRY));
        assertEquals(s.exitCopyOrigin(DIMS, exit(FAR)), resolve(s, ALICE, PortalCarriageRole.EXIT));
        // And they are two different corridors — one either side of the room at tile 8.
        assertNotNull(resolve(s, ALICE, PortalCarriageRole.ENTRY));
        assertTrue(resolve(s, ALICE, PortalCarriageRole.ENTRY).getX()
            < resolve(s, ALICE, PortalCarriageRole.EXIT).getX());
    }

    @Test
    @DisplayName("A role with no copy at that tile falls back to the original — Random lays only one")
    void oneRoleOnlyFallsBack() {
        PortalStructure s = withCopies(exit(FAR));
        PortalExitBindings.bind(ALICE, PAIR, FAR);

        assertEquals(s.exitCopyOrigin(DIMS, exit(FAR)), resolve(s, ALICE, PortalCarriageRole.EXIT));
        assertNull(resolve(s, ALICE, PortalCarriageRole.ENTRY),
            "there is no entry corridor at that tile to arrive in");
    }

    @Test
    @DisplayName("Coming back through the original clears the binding rather than storing BASE")
    void baseClears() {
        PortalStructure s = withCopies(exit(FAR));
        PortalExitBindings.bind(ALICE, PAIR, FAR);
        assertEquals(FAR, PortalExitBindings.boundTile(ALICE, PAIR));

        PortalExitBindings.bind(ALICE, PAIR, Tile.BASE);
        assertNull(PortalExitBindings.boundTile(ALICE, PAIR));
        assertNull(resolve(s, ALICE, PortalCarriageRole.EXIT));
        assertTrue(PortalExitBindings.isEmpty(), "BASE must not leave an entry behind to leak");
    }

    @Test
    @DisplayName("A copy that has retired resolves to the original, without anyone clearing anything")
    void retiredCopyHealsItself() {
        PortalStructure standing = withCopies(exit(FAR));
        PortalExitBindings.bind(ALICE, PAIR, FAR);
        assertNotNull(resolve(standing, ALICE, PortalCarriageRole.EXIT));

        PortalStructure retired = standing.withExitCopies(standing.exitCopies().without(exit(FAR)));
        assertNull(resolve(retired, ALICE, PortalCarriageRole.EXIT));
        // The binding is still there; it is the resolve that refuses, every time it is asked.
        assertEquals(FAR, PortalExitBindings.boundTile(ALICE, PAIR));
    }

    @Test
    @DisplayName("A relocated structure drops every copy, so the binding stops resolving with it")
    void relocationHealsItself() {
        PortalStructure standing = withCopies(entry(FAR), exit(FAR));
        PortalExitBindings.bind(ALICE, PAIR, FAR);

        PortalStructure moved = standing.movedTo(ORIGIN.offset(4096, 0, 0));
        assertTrue(moved.exitCopies().isEmpty());
        assertNull(resolve(moved, ALICE, PortalCarriageRole.EXIT));
        assertNull(resolve(moved, ALICE, PortalCarriageRole.ENTRY));
    }

    @Test
    @DisplayName("A null structure resolves to the original rather than throwing")
    void nullStructureIsTolerated() {
        PortalExitBindings.bind(ALICE, PAIR, FAR);
        assertNull(resolve(null, ALICE, PortalCarriageRole.EXIT));
        assertNull(PortalExitBindings.boundOriginFor(
            withCopies(exit(FAR)), DIMS, null, PAIR, PortalCarriageRole.EXIT));
    }

    @Test
    @DisplayName("Two players at opposite ends of one room each return to their own")
    void bindingsArePerPlayer() {
        Tile near = new Tile(-8, 0);
        PortalStructure s = withCopies(exit(FAR), exit(near));
        PortalExitBindings.bind(ALICE, PAIR, FAR);
        PortalExitBindings.bind(BOB, PAIR, near);

        assertEquals(s.exitCopyOrigin(DIMS, exit(FAR)), resolve(s, ALICE, PortalCarriageRole.EXIT));
        assertEquals(s.exitCopyOrigin(DIMS, exit(near)), resolve(s, BOB, PortalCarriageRole.EXIT));
    }

    @Test
    @DisplayName("One player's place in one pair says nothing about another pair")
    void bindingsArePerPair() {
        PortalStructure s = withCopies(exit(FAR));
        PortalExitBindings.bind(ALICE, PAIR, FAR);
        assertNull(PortalExitBindings.boundTile(ALICE, PAIR + 3));
        assertNull(PortalExitBindings.boundOriginFor(
            s, DIMS, ALICE, PAIR + 3, PortalCarriageRole.EXIT));
    }

    // ---- the trail a follower walks in on ----

    /**
     * The trail resolved the way the live path resolves it: who is being followed, then where that
     * player is bound. Only the chunk check is left out, and that is the one part that genuinely
     * needs a world.
     */
    private static BlockPos follower(PortalStructure s, PortalCarriageRole role, long now) {
        UUID leader = PortalExitBindings.followerFor(PAIR, role, now);
        return leader == null ? null : PortalExitBindings.boundOriginFor(s, DIMS, leader, PAIR, role);
    }

    @Test
    @DisplayName("Nothing follows anybody until somebody has walked in")
    void noTrailByDefault() {
        assertNull(follower(withCopies(exit(FAR)), PortalCarriageRole.EXIT, 0L));
    }

    @Test
    @DisplayName("A follower goes where the player who led it went, seconds after they did")
    void trailOutlivesTheLeader() {
        PortalStructure s = withCopies(entry(FAR));
        PortalExitBindings.bind(ALICE, PAIR, FAR);
        PortalExitBindings.noteInbound(PAIR, PortalCarriageRole.ENTRY, ALICE, 1000L);

        // The villager crosses two seconds later, by which time Alice has long left the corridor —
        // asking "who is standing here now" would find nobody, which is the whole point.
        assertEquals(s.exitCopyOrigin(DIMS, entry(FAR)),
            follower(s, PortalCarriageRole.ENTRY, 1040L));
    }

    @Test
    @DisplayName("The leash runs out, so the next person through does not inherit stray followers")
    void trailExpires() {
        PortalStructure s = withCopies(entry(FAR));
        PortalExitBindings.bind(ALICE, PAIR, FAR);
        PortalExitBindings.noteInbound(PAIR, PortalCarriageRole.ENTRY, ALICE, 1000L);

        assertNotNull(follower(s, PortalCarriageRole.ENTRY, 1200L));
        assertNull(follower(s, PortalCarriageRole.ENTRY, 1201L));
    }

    @Test
    @DisplayName("A trail re-points to whoever walked in most recently — they are the leader now")
    void trailRePointsToTheLatest() {
        Tile near = new Tile(-8, 0);
        PortalStructure s = withCopies(entry(FAR), entry(near));
        PortalExitBindings.bind(ALICE, PAIR, FAR);
        PortalExitBindings.bind(BOB, PAIR, near);

        PortalExitBindings.noteInbound(PAIR, PortalCarriageRole.ENTRY, ALICE, 1000L);
        assertEquals(s.exitCopyOrigin(DIMS, entry(FAR)),
            follower(s, PortalCarriageRole.ENTRY, 1010L));

        PortalExitBindings.noteInbound(PAIR, PortalCarriageRole.ENTRY, BOB, 1020L);
        assertEquals(s.exitCopyOrigin(DIMS, entry(near)),
            follower(s, PortalCarriageRole.ENTRY, 1030L));
    }

    @Test
    @DisplayName("A trail names a player, so it re-checks the copy every time rather than a stale place")
    void trailReResolvesRatherThanRemembersAPlace() {
        PortalStructure standing = withCopies(entry(FAR));
        PortalExitBindings.bind(ALICE, PAIR, FAR);
        PortalExitBindings.noteInbound(PAIR, PortalCarriageRole.ENTRY, ALICE, 1000L);
        assertNotNull(follower(standing, PortalCarriageRole.ENTRY, 1010L));

        // The copy retired between the player going in and the villager catching up.
        PortalStructure retired = standing.withExitCopies(PortalExitCopies.NONE);
        assertNull(follower(retired, PortalCarriageRole.ENTRY, 1010L));
    }

    @Test
    @DisplayName("The two carriages of a pair keep separate trails")
    void trailsArePerRole() {
        PortalStructure s = withCopies(entry(FAR), exit(FAR));
        PortalExitBindings.bind(ALICE, PAIR, FAR);
        PortalExitBindings.noteInbound(PAIR, PortalCarriageRole.ENTRY, ALICE, 1000L);

        assertNotNull(follower(s, PortalCarriageRole.ENTRY, 1010L));
        assertNull(follower(s, PortalCarriageRole.EXIT, 1010L));
    }

    @Test
    @DisplayName("A bound tile is flagged as in use, so the window cannot retire it under anybody")
    void anyBoundToKeepsACopyAlive() {
        assertFalse(PortalExitBindings.anyBoundTo(PAIR, FAR));

        PortalExitBindings.bind(ALICE, PAIR, FAR);
        assertTrue(PortalExitBindings.anyBoundTo(PAIR, FAR));
        // Another pair's tile of the same name is a different place entirely.
        assertFalse(PortalExitBindings.anyBoundTo(PAIR + 3, FAR));
        assertFalse(PortalExitBindings.anyBoundTo(PAIR, new Tile(0, 8)));
        assertFalse(PortalExitBindings.anyBoundTo(PAIR, null));

        // Bob's binding keeps his own tile alive after Alice comes back through the original.
        PortalExitBindings.bind(BOB, PAIR, FAR);
        PortalExitBindings.bind(ALICE, PAIR, Tile.BASE);
        assertTrue(PortalExitBindings.anyBoundTo(PAIR, FAR));
        PortalExitBindings.bind(BOB, PAIR, Tile.BASE);
        assertFalse(PortalExitBindings.anyBoundTo(PAIR, FAR));
    }

    @Test
    @DisplayName("Leaving drops your bindings and nobody else's")
    void forgetAndPrune() {
        PortalExitBindings.bind(ALICE, PAIR, FAR);
        PortalExitBindings.bind(ALICE, PAIR + 3, new Tile(0, 8));
        PortalExitBindings.bind(BOB, PAIR, FAR);
        assertEquals(3, PortalExitBindings.size());

        PortalExitBindings.forget(ALICE);
        assertEquals(1, PortalExitBindings.size());
        assertEquals(FAR, PortalExitBindings.boundTile(BOB, PAIR));

        // The tick's prune is the backstop for a crash-disconnect that fires no leave event.
        PortalExitBindings.pruneTo(List.of(ALICE));
        assertTrue(PortalExitBindings.isEmpty());
    }
}
