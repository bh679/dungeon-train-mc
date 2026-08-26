package games.brennan.dungeontrain.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which room the librarian gives up on when it is holding more than it may.
 *
 * <p>The bound exists because a room that finds no author now STAYS pending, nothing calls
 * {@code forget()}, and rooms keep being registered for as long as a train runs. What matters is
 * WHICH one goes: the room registered longest ago, not the one with the smallest pair key.</p>
 *
 * <p><b>Pair keys are not a clock.</b> They run outward from the origin in BOTH directions — a train
 * standing at pairs -6, -3, 3, 6 is ordinary, and was observed in a live session. So the smallest key
 * is the furthest carriage one way, which for a player riding the negative direction is the room they
 * just met. Ordering eviction on the key threw that room away and kept the one behind them.</p>
 */
class PortalRoomLibrarianPendingTest {

    private static final Vec3i SIZE = new Vec3i(8, 7, 9);
    private static final int MAX = PortalRoomLibrarian.MAX_PENDING_ROOMS;

    /** A room that locks to an author, so {@code register} actually keeps it. */
    private static PortalRoomBooks locking() {
        return new PortalRoomBooks(PortalRoomBooks.Kind.MIX, 1, 1, 1, 10, 24);
    }

    private static void register(int pairKey) {
        PortalRoomLibrarian.register(pairKey, new BlockPos(pairKey, -62, 0), SIZE, locking());
    }

    @BeforeEach
    void reset() {
        PortalRoomLibrarian.clear();
    }

    @Test
    @DisplayName("Riding the negative direction does not evict the room you just met")
    void evictsByRegistrationOrderNotByPairKey() {
        // Newer rooms get SMALLER keys, which is what a player travelling one way along the train
        // produces. Room 0 is registered first; room -MAX is registered last, one over the bound.
        register(0);
        for (int i = 1; i <= MAX; i++) register(-i);

        assertFalse(PortalRoomLibrarian.isPending(0),
            "the room registered FIRST is the one to drop");
        assertTrue(PortalRoomLibrarian.isPending(-MAX),
            "the most recently met room must survive — it has the smallest key, and ordering on the "
                + "key is exactly what used to throw it away");
    }

    @Test
    @DisplayName("Re-stamping a room moves it to the back of the queue, not the front")
    void reRegisteringRefreshesTheStamp() {
        register(0);
        for (int i = 1; i < MAX; i++) register(-i);
        // Room 0 is the oldest — until the train re-stamps it, which is the librarian seeing it again.
        register(0);
        register(-MAX);                       // one over the bound, so exactly one room goes

        assertTrue(PortalRoomLibrarian.isPending(0),
            "a re-stamped room has just been seen, so it is not the one to give up on");
        assertFalse(PortalRoomLibrarian.isPending(-1),
            "the oldest stamp that was NOT refreshed goes instead");
    }
}
