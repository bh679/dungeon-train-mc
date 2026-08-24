package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire contract between this enum and the relay's {@code leaderboard.js} CATEGORIES.
 *
 * <p>A mismatch does not fail loudly at runtime — the relay answers {@code bad_cat}, the pool logs
 * at debug, and that board is simply never populated. So the contract is pinned here instead: if
 * this list and {@code leaderboard.js} disagree, one of them was edited alone.</p>
 */
class LeaderboardCategoryTest {

    /** Must equal leaderboard.js's SCORE_CATEGORIES keys + RESIDENT_CATEGORIES, in any order. */
    private static final List<String> RELAY_IDS = List.of(
        "playtime_total", "playtime_run", "carriages_total", "carriages_run", "pacifist_carriages",
        "friends_run", "friends_total", "lives", "chests_opened", "books_written", "books_read",
        "advancements", "echoes_killed_run", "echoes_killed_total", "carriages_no_chest",
        "deathnotes_written", "deathnotes_fought", "lovenotes_written", "lovenotes_received",
        "book_votes", "translations", "donations");

    @Test
    @DisplayName("every category id matches one the relay serves, and none is missing")
    void idsMatchTheRelay() {
        Set<String> mine = new HashSet<>();
        for (LeaderboardCategory c : LeaderboardCategory.values()) mine.add(c.id());
        assertEquals(new HashSet<>(RELAY_IDS), mine);
        assertEquals(RELAY_IDS.size(), LeaderboardCategory.values().length, "no duplicate ids");
    }

    @Test
    @DisplayName("every id round-trips through byId, and an unknown one is empty not an exception")
    void byIdRoundTrips() {
        for (LeaderboardCategory c : LeaderboardCategory.values()) {
            assertEquals(c, LeaderboardCategory.byId(c.id()).orElseThrow());
        }
        assertTrue(LeaderboardCategory.byId("nope").isEmpty());
        assertTrue(LeaderboardCategory.byId(null).isEmpty());
    }

    @Test
    @DisplayName("every board has a title that fits a book cover and a distinct header key")
    void titlesAndKeysAreUsable() {
        Set<String> keys = new HashSet<>();
        for (LeaderboardCategory c : LeaderboardCategory.values()) {
            assertTrue(c.title().length() <= 32, c.title() + " is too long for a book title");
            assertTrue(keys.add(c.headerKey()), "duplicate header key: " + c.headerKey());
        }
    }

    @Test
    @DisplayName("scores render per format")
    void rendersPerFormat() {
        assertEquals("42", LeaderboardCategory.LIVES.render(42));
        assertEquals("2h 0m", LeaderboardCategory.PLAYTIME_RUN.render(7200));
        assertEquals("$120", LeaderboardCategory.DONATIONS.render(120));
    }
}
