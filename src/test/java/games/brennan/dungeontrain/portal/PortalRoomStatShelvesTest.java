package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.narrative.LeaderboardCategory;
import games.brennan.dungeontrain.narrative.RunStatSubject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bookkeeping behind a Stat Room's incremental fill.
 *
 * <p>A stat room is stocked over many ticks — its leaderboard books arrive from the relay one at a
 * time — so the thing that can actually go wrong is not the books but the accounting: shelving a
 * second copy of what is already standing there, or deciding the room is finished before it is.
 * Both are pure set arithmetic, which is why they are tested here rather than in the Gate 2 walk.</p>
 *
 * <p>Server-free: nothing here builds an {@code ItemStack}.</p>
 */
class PortalRoomStatShelvesTest {

    private static final int PAIR = 7;

    @Test
    @DisplayName("A full set is every board plus every run stat — 49 as the mod stands")
    void fullSetIsBoardsPlusStats() {
        assertEquals(LeaderboardCategory.values().length, PortalRoomStatShelves.LEADERBOARD_BOOK_COUNT);
        assertEquals(RunStatSubject.values().length, PortalRoomStatShelves.STAT_BOOK_COUNT);
        assertEquals(PortalRoomStatShelves.LEADERBOARD_BOOK_COUNT + PortalRoomStatShelves.STAT_BOOK_COUNT,
            PortalRoomStatShelves.FULL_SET);
        // Locked in deliberately: if either enum grows, this number is what a Stat Room now promises.
        assertEquals(49, PortalRoomStatShelves.FULL_SET);
    }

    @Test
    @DisplayName("An empty room is missing every run stat; a stocked one is missing none")
    void missingStatsTracksWhatIsShelved() {
        assertEquals(RunStatSubject.values().length,
            PortalRoomStatShelves.missingStats(Set.of()).size());

        Set<String> all = new HashSet<>();
        for (RunStatSubject subject : RunStatSubject.values()) {
            all.add(PortalRoomStatShelves.statKey(subject));
        }
        assertTrue(PortalRoomStatShelves.missingStats(all).isEmpty());
    }

    @Test
    @DisplayName("One shelved stat drops out and the rest stay — no duplicate on the next tick")
    void missingStatsExcludesOnlyWhatIsPlaced() {
        RunStatSubject placed = RunStatSubject.values()[0];
        List<RunStatSubject> missing =
            PortalRoomStatShelves.missingStats(Set.of(PortalRoomStatShelves.statKey(placed)));
        assertEquals(RunStatSubject.values().length - 1, missing.size());
        assertFalse(missing.contains(placed));
    }

    @Test
    @DisplayName("Boards are filtered by BOTH what is shelved and what the relay has served")
    void missingBoardsNeedsPopulatedAndUnshelved() {
        List<LeaderboardCategory> populated = Arrays.asList(
            LeaderboardCategory.values()[0], LeaderboardCategory.values()[1], LeaderboardCategory.values()[2]);

        // Nothing shelved yet: everything served is wanted.
        assertEquals(3, PortalRoomStatShelves.missingBoards(Set.of(), populated).size());

        // One of the served boards is already up — it must not be shelved twice.
        Set<String> shelved = Set.of(PortalRoomStatShelves.boardKey(populated.get(1)));
        List<LeaderboardCategory> missing = PortalRoomStatShelves.missingBoards(shelved, populated);
        assertEquals(2, missing.size());
        assertFalse(missing.contains(populated.get(1)));

        // A board the relay has not served is not wanted, however empty the room is.
        assertTrue(PortalRoomStatShelves.missingBoards(Set.of(), List.of()).isEmpty());
    }

    @Test
    @DisplayName("Board and stat keys cannot collide, so the two id spaces stay separate")
    void keySpacesAreDisjoint() {
        Set<String> keys = new HashSet<>();
        for (LeaderboardCategory c : LeaderboardCategory.values()) {
            assertTrue(keys.add(PortalRoomStatShelves.boardKey(c)), "duplicate key for " + c);
        }
        for (RunStatSubject s : RunStatSubject.values()) {
            assertTrue(keys.add(PortalRoomStatShelves.statKey(s)), "duplicate key for " + s);
        }
        assertEquals(PortalRoomStatShelves.FULL_SET, keys.size());
    }

    @Test
    @DisplayName("A room is complete only once it holds the whole set")
    void completeOnlyAtFullSet() {
        assertFalse(PortalRoomStatShelves.isComplete(Set.of()));
        assertFalse(PortalRoomStatShelves.isComplete(null));

        Set<String> nearly = new HashSet<>();
        for (int i = 0; i < PortalRoomStatShelves.FULL_SET - 1; i++) nearly.add("k" + i);
        assertFalse(PortalRoomStatShelves.isComplete(nearly));

        nearly.add("k" + (PortalRoomStatShelves.FULL_SET - 1));
        assertTrue(PortalRoomStatShelves.isComplete(nearly));
    }

    @Test
    @DisplayName("The shelf order is a permutation, stable per room and different between rooms")
    void dealOrderIsSeededPermutation() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 20; i++) keys.add("k" + i);

        List<String> once = PortalRoomStatShelves.dealOrder(keys, PAIR);
        List<String> again = PortalRoomStatShelves.dealOrder(keys, PAIR);
        assertEquals(once, again, "a re-stamped room must deal the same way");
        assertEquals(new HashSet<>(keys), new HashSet<>(once), "nothing may be lost or invented");
        assertEquals(keys.size(), once.size());

        // Two rooms must not read as the same shelf. (Not a guarantee for any two keys in principle,
        // but these two, on this shuffle, differ — and that is what is being locked in.)
        assertNotEquals(once, PortalRoomStatShelves.dealOrder(keys, PAIR + 1));

        // The caller's list is not disturbed — it is still the caller's.
        assertEquals("k0", keys.get(0));
    }

    @Test
    @DisplayName("Dealing a single key, or none, is not a special case")
    void dealOrderHandlesDegenerateSizes() {
        assertTrue(PortalRoomStatShelves.dealOrder(List.of(), PAIR).isEmpty());
        assertEquals(List.of("only"), PortalRoomStatShelves.dealOrder(List.of("only"), PAIR));
    }
}
