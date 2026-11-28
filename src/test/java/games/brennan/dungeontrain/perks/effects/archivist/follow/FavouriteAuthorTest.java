package games.brennan.dungeontrain.perks.effects.archivist.follow;

import games.brennan.dungeontrain.narrative.SharedBookPool;
import games.brennan.dungeontrain.narrative.SharedBookPool.PoolBook;
import games.brennan.dungeontrain.narrative.SharedBookSelector;
import games.brennan.dungeontrain.narrative.SharedBookSelector.PlayerContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Favourite Author's numbers, its follow list, finds, the relay parse and the followed book tier. */
final class FavouriteAuthorTest {

    private static AuthorFollows.Follow follow(String token, long ts, boolean exhausted) {
        return new AuthorFollows.Follow(token, "name" + token, ts, exhausted);
    }

    private static PoolBook book(int id, String token, boolean kidSafe) {
        return new PoolBook(id, "t" + id, "Wren", List.of("p"), "en_us", 1, kidSafe, false,
            SharedBookPool.STATUS_UNKNOWN, false, 0, 0, false, token);
    }

    private static PlayerContext player(Set<Integer> read, boolean kid) {
        return new PlayerContext("en_us", read::contains, id -> false, id -> 0, 0, 10, kid, false, b -> false, null);
    }

    @Test
    @DisplayName("canFollowMore: every follow needs an active point of its own")
    void canFollowMore() {
        assertFalse(FavouriteAuthor.canFollowMore(0, 0), "no points active: no follows");
        assertFalse(FavouriteAuthor.canFollowMore(1, 0));
        assertTrue(FavouriteAuthor.canFollowMore(0, 1));
        assertFalse(FavouriteAuthor.canFollowMore(1, 1), "full: as many follows as points");
        assertTrue(FavouriteAuthor.canFollowMore(2, 3));
        assertFalse(FavouriteAuthor.canFollowMore(4, 3), "over the limit after points were taken out");
    }

    @Test
    @DisplayName("share: 15% + 2% per active followed author; nothing with none")
    void share() {
        assertEquals(0, FavouriteAuthor.share(0));
        assertEquals(0.17, FavouriteAuthor.share(1), 1e-9);
        assertEquals(0.21, FavouriteAuthor.share(3), 1e-9);
        assertEquals(0.15, FavouriteAuthor.cardShare(0), 1e-9);
    }

    @Test
    @DisplayName("slots: the newest follows are active, the rest wait for a point")
    void newestAreActive() {
        AuthorFollows f = new AuthorFollows(List.of(follow("old", 1, false), follow("new", 3, false), follow("mid", 2, true)));
        assertEquals(List.of("new", "mid"), f.activeTokens(2));
        assertEquals(List.of("new"), f.findableTokens(2), "an exhausted author has nothing to find");
        assertEquals(List.of(), f.activeTokens(0));
        assertEquals(3, FavouriteAuthor.activeCount(3, 5));
    }

    @Test
    @DisplayName("follow list: add replaces by token, remove drops it")
    void withAndWithout() {
        AuthorFollows f = AuthorFollows.empty().with(follow("a", 1, false)).with(follow("a", 2, true));
        assertEquals(1, f.follows().size());
        assertTrue(f.follows().get(0).exhausted());
        assertTrue(f.without("a").isEmpty());
    }

    @Test
    @DisplayName("finds: counted per token; names deduped, newest first for autocomplete")
    void finds() {
        FollowFinds f = FollowFinds.empty().found("p1", "Wren").found("p1", "wren").found("", "Ash");
        assertEquals(2, f.count("p1"));
        assertEquals(List.of("Ash", "wren"), f.suggestions(""));
        assertEquals(List.of("wren"), f.suggestions("W"));
    }

    @Test
    @DisplayName("relay list parses, skipping rows with no token, never reading a count")
    void parseList() {
        String body = "{\"ok\":true,\"follows\":[{\"token\":\"p1\",\"name\":\"Wren\",\"ts\":5,\"exhausted\":true},{\"name\":\"x\"}]}";
        List<AuthorFollows.Follow> list = FollowsRelay.parseList(body).orElseThrow();
        assertEquals(1, list.size());
        assertEquals(new AuthorFollows.Follow("p1", "Wren", 5, true), list.get(0));
        assertTrue(FollowsRelay.parseList("{\"ok\":true}").isEmpty());
    }

    @Test
    @DisplayName("followed tier: a share of 1 always picks an unread followed book; 0 never does")
    void selectFollowedShare() {
        List<PoolBook> books = List.of(book(1, "p1", true), book(2, "p1", true));
        assertTrue(SharedBookSelector.selectFollowed(books, 1.0, player(Set.of(), false), 7L).isPresent());
        assertTrue(SharedBookSelector.selectFollowed(books, 0.0, player(Set.of(), false), 7L).isEmpty());
    }

    @Test
    @DisplayName("followed tier: only unread books; filters still apply; nothing left means the normal pick")
    void selectFollowedFilters() {
        List<PoolBook> books = List.of(book(1, "p1", true), book(2, "p1", false));
        Optional<PoolBook> pick = SharedBookSelector.selectFollowed(books, 1.0, player(Set.of(1), false), 3L);
        assertEquals(2, pick.orElseThrow().id());
        assertFalse(SharedBookSelector.selectFollowed(books, 1.0, player(Set.of(1), true), 3L).isPresent(),
            "kid mode drops the one unread book that is not kid-safe");
    }

    @Test
    @DisplayName("followed tier rolls about the share over many seeds")
    void selectFollowedRate() {
        List<PoolBook> books = List.of(book(1, "p1", true));
        int hits = 0;
        for (long seed = 0; seed < 20_000; seed++) {
            if (SharedBookSelector.selectFollowed(books, 0.17, player(Set.of(), false), seed).isPresent()) hits++;
        }
        assertEquals(0.17, hits / 20_000.0, 0.015);
    }
}
