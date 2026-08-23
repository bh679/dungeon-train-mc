package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.narrative.SharedBookPool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a library room is allowed to write into its shelves.
 *
 * <p>This is the privacy test for the whole unapproved-books feature. A writer's own catalogue can
 * contain books the relay withholds from everyone else, but a stocked shelf is WORLD state — real
 * stacks in real chiseled bookshelves, written once, readable by anyone who walks in afterwards. So
 * a room with company stocks the author's public shelf and nothing more.</p>
 */
class PortalRoomLibrarianShelvableTest {

    private static SharedBookPool.PoolBook book(int id, String status) {
        return new SharedBookPool.PoolBook(id, "t" + id, "A", List.of("p"), "en_us", 1, true, false, status);
    }

    private static final SharedBookPool.PoolBook OPEN = book(1, SharedBookPool.STATUS_APPROVED);
    private static final SharedBookPool.PoolBook PENDING = book(2, "pending");
    private static final SharedBookPool.PoolBook FLAGGED = book(3, "flagged");
    private static final SharedBookPool.PoolBook REJECTED = book(4, "rejected");

    @Test
    @DisplayName("Alone in the world, a writer's own withheld books may go on their own shelves")
    void aloneKeepsTheWholeCatalogue() {
        List<SharedBookPool.PoolBook> all = List.of(OPEN, PENDING, FLAGGED, REJECTED);
        assertSame(all, PortalRoomLibrarian.shelvable(all, 1), "no filtering, and no copy either");
        assertSame(all, PortalRoomLibrarian.shelvable(all, 0), "an empty level cannot leak to anyone");
    }

    @Test
    @DisplayName("With company, NOTHING withheld reaches a shelf")
    void companyStripsEveryWithheldBook() {
        List<SharedBookPool.PoolBook> out =
            PortalRoomLibrarian.shelvable(List.of(OPEN, PENDING, FLAGGED, REJECTED), 2);
        assertEquals(List.of(OPEN), out, "only the released book may be placed");
        for (SharedBookPool.PoolBook b : out) assertFalse(b.isWithheld());
    }

    @Test
    @DisplayName("A room stocked with company is exactly the room that existed before this feature")
    void companyLeavesAPublicShelfUntouched() {
        List<SharedBookPool.PoolBook> onlyPublic = List.of(OPEN, book(9, SharedBookPool.STATUS_APPROVED));
        assertEquals(onlyPublic, PortalRoomLibrarian.shelvable(onlyPublic, 4));
    }

    @Test
    @DisplayName("A catalogue of nothing but withheld books stocks an empty room, not a leaky one")
    void companyCanYieldNothing() {
        assertTrue(PortalRoomLibrarian.shelvable(List.of(PENDING, REJECTED), 3).isEmpty());
        assertTrue(PortalRoomLibrarian.shelvable(List.of(), 3).isEmpty());
    }
}
