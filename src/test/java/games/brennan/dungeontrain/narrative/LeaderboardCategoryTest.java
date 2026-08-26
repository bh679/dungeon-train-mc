package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        "playtime_total", "playtime_run", "carriages_total", "carriages_run",
        "distance_run", "distance_total", "pacifist_carriages",
        "friends_run", "friends_total", "lives", "chests_opened", "books_written", "books_read",
        "advancements", "echoes_killed_run", "echoes_killed_total", "carriages_no_chest",
        "chests_run",
        "deathnotes_written", "deathnotes_fought", "deathnotes_people",
        "lovenotes_written", "lovenotes_received", "lovenotes_people",
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
    @DisplayName("every board has a title that fits a book cover, and no two titles collide")
    void titlesAreUsable() {
        Set<String> titles = new HashSet<>();
        for (LeaderboardCategory c : LeaderboardCategory.values()) {
            assertTrue(c.title().length() <= BookFactory.MAX_TITLE_CHARS,
                c.title() + " is " + c.title().length() + " characters; vanilla caps a book title at "
                    + BookFactory.MAX_TITLE_CHARS);
            assertTrue(titles.add(c.title()), "duplicate title: " + c.title());
        }
    }

    @Test
    @DisplayName("a header key is shared only by a run/total pair of the same subject, never more")
    void headerKeysArePerSubject() {
        Map<String, List<LeaderboardCategory>> bySubject = new HashMap<>();
        for (LeaderboardCategory c : LeaderboardCategory.values()) {
            bySubject.computeIfAbsent(c.headerKey(), k -> new ArrayList<>()).add(c);
        }
        for (var e : bySubject.entrySet()) {
            List<LeaderboardCategory> sharing = e.getValue();
            assertTrue(sharing.size() <= 2, e.getKey() + " is shared by " + sharing.size() + " boards");
            if (sharing.size() == 2) {
                // The only reason to share is that one is the one-life half and the other the
                // all-lives half of the same subject.
                assertNotEquals(sharing.get(0).scope(), sharing.get(1).scope(),
                    e.getKey() + " is shared by two boards of the same scope");
                assertEquals(sharing.get(0).baseTitle(), sharing.get(1).baseTitle(),
                    e.getKey() + " is shared by boards with different base titles");
            }
        }
    }

    @Test
    @DisplayName("a lone board says nothing about its span; a paired one says it on both halves")
    void onlyPairedBoardsLabelTheirSpan() {
        for (LeaderboardCategory c : LeaderboardCategory.values()) {
            if (c.spanLabel() != LeaderboardCategory.SpanLabel.AUTO) continue;
            assertEquals(c.isPaired() && c.scope() != LeaderboardCategory.Scope.NONE, c.labelsSpan(),
                c.id() + ": a span is only worth saying where there is a twin to say it against");
        }
        // Chests has both halves now, so both say which is which.
        assertTrue(LeaderboardCategory.CHESTS_OPENED.labelsSpan());
        assertTrue(LeaderboardCategory.CHESTS_RUN.labelsSpan());
        assertEquals("Most Chests Opened, All Lives", LeaderboardCategory.CHESTS_OPENED.title());
        assertEquals("Most Chests Opened, One Life", LeaderboardCategory.CHESTS_RUN.title());
        // These have no twin, so the qualifier would be answering nothing.
        assertFalse(LeaderboardCategory.LIVES.labelsSpan());
        assertEquals("Most Lives Spent", LeaderboardCategory.LIVES.title());
        assertNull(LeaderboardCategory.LIVES.scopeKey());
        assertFalse(LeaderboardCategory.PACIFIST_CARRIAGES.labelsSpan());
        assertEquals("Furthest Pacifist", LeaderboardCategory.PACIFIST_CARRIAGES.title());
    }

    @Test
    @DisplayName("scope decides the suffix and the wrapper key, and NONE adds neither")
    void scopeDrivesTitleAndHeader() {
        assertEquals("Furthest Distance, One Life", LeaderboardCategory.DISTANCE_RUN.title());
        assertEquals("Furthest Distance, All Lives", LeaderboardCategory.DISTANCE_TOTAL.title());
        // Same subject, so the same heading key - the scope key is the only difference.
        assertEquals(LeaderboardCategory.DISTANCE_RUN.headerKey(),
                     LeaderboardCategory.DISTANCE_TOTAL.headerKey());
        assertNotEquals(LeaderboardCategory.DISTANCE_RUN.scopeKey(),
                        LeaderboardCategory.DISTANCE_TOTAL.scopeKey());
        // A board with no span says nothing about one. Translations and donations are the only two:
        // they are contributions, not runs, so there is no life to measure them over.
        assertEquals("Kindest Benefactors", LeaderboardCategory.DONATIONS.title());
        assertNull(LeaderboardCategory.DONATIONS.scopeKey());
        // The note boards DO have a span - they accumulate across lives - even though, being alone,
        // they stay quiet about it. Without that, a one-life half of them could not be asked for.
        assertEquals(LeaderboardCategory.Scope.TOTAL, LeaderboardCategory.DEATHNOTES_FOUGHT.scope());
        assertFalse(LeaderboardCategory.DEATHNOTES_FOUGHT.labelsSpan());
        assertEquals("Most Curses Survived", LeaderboardCategory.DEATHNOTES_FOUGHT.title());
    }

    @Test
    @DisplayName("scores render per format")
    void rendersPerFormat() {
        assertEquals("42", LeaderboardCategory.LIVES.render(42));
        assertEquals("2h 0m", LeaderboardCategory.PLAYTIME_RUN.render(7200));
        assertEquals("$120", LeaderboardCategory.DONATIONS.render(120));
        assertEquals("8400m", LeaderboardCategory.DISTANCE_RUN.render(8400));
    }
}
