package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalExitSites.Site;
import games.brennan.dungeontrain.portal.PortalRoomTiling.Tile;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        return new PortalStructure(ORIGIN, "labrynth", PortalRoomLayout.builtInSize(DIMS),
            PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.ENDLESS_REPETITION),
            PortalRoomTiling.base(), copies);
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
