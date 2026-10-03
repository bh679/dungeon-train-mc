package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.net.relay.BookAuthorsClient;
import games.brennan.dungeontrain.portal.PortalAuthorRoomPick.Candidate;
import games.brennan.dungeontrain.portal.PortalAuthorRoomPick.Choice;
import games.brennan.dungeontrain.portal.PortalRoomBooks.Kind;
import games.brennan.dungeontrain.portal.PortalRoomBooks.Share;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Author rooms: the parent's split first, then a sub-room that fits what it rolled. */
class PortalAuthorRoomPickTest {

    private static final long SEED = 0xA07408L;
    private static final int TALLY = 30;

    /** Ranges as shipped: small (1–12], mid (10–24], big (24–120], open (5–∞). */
    private static final Candidate SMALL = room("small", 1, 12);
    private static final Candidate MID = room("mid", 10, 24);
    private static final Candidate BIG = room("big", 24, 120);
    private static final Candidate OPEN = room("open", 5, PortalRoomBooks.NO_MAXIMUM);
    private static final List<Candidate> ROOMS = List.of(SMALL, MID, BIG, OPEN);

    private static Candidate room(String name, int min, int max) {
        return new Candidate(name, 1, new PortalRoomBooks(Kind.MIX, 1, 1, 1, 1, min, max));
    }

    private static BookAuthorsClient.Author author(String name, int count) {
        return BookAuthorsClient.Author.other("t-" + name, name, count);
    }

    /** Fixed pages: {@code self} for Self, {@code directory} for any directory ask. */
    private static PortalAuthorRoomPick.Authors pages(List<BookAuthorsClient.Author> self,
                                                      List<BookAuthorsClient.Author> directory) {
        return new PortalAuthorRoomPick.Authors() {
            @Override
            public List<BookAuthorsClient.Author> self() {
                return self;
            }

            @Override
            public List<BookAuthorsClient.Author> directory(Share share, PortalRoomBooks band) {
                return directory;
            }
        };
    }

    private static final PortalAuthorRoomPick.Authors COLD = pages(List.of(), List.of());

    @Test
    @DisplayName("the parent's 1:2:0:2 split rolls Self, Player and Stats at those odds, never Signature")
    void parentSplitMatchesWeights() {
        PortalRoomBooks parent = new PortalRoomBooks(Kind.MIX, 1, 2, 0, 2, 0, 0);
        Map<Share, Integer> seen = new EnumMap<>(Share.class);
        int pairs = 50_000;
        for (int pair = 0; pair < pairs; pair++) {
            seen.merge(PortalAuthorRoomPick.share(parent, pair, false), 1, Integer::sum);
        }
        assertEquals(0, seen.getOrDefault(Share.SIGNATURE, 0));
        assertEquals(0.2, seen.get(Share.SELF) / (double) pairs, 0.01);
        assertEquals(0.4, seen.get(Share.PLAYER) / (double) pairs, 0.01);
        assertEquals(0.4, seen.get(Share.STATS) / (double) pairs, 0.01);
    }

    @Test
    @DisplayName("the own-books boost pins Self whatever the split says")
    void pinnedIsSelf() {
        PortalRoomBooks parent = new PortalRoomBooks(Kind.MIX, 0, 1, 0, 1, 0, 0);
        for (int pair = 0; pair < 500; pair++) {
            assertSame(Share.SELF, PortalAuthorRoomPick.share(parent, pair, true));
        }
    }

    @Test
    @DisplayName("Player: the author is drawn first and the room holds their count")
    void playerRoomFitsAuthor() {
        PortalAuthorRoomPick.Authors dir = pages(List.of(), List.of(author("prolific", 80)));
        for (int pair = 0; pair < 300; pair++) {
            Choice c = PortalAuthorRoomPick.choose(SEED, pair, Share.PLAYER, ROOMS, dir, TALLY);
            assertEquals("prolific", c.author().name());
            assertTrue(c.roomName().equals("big") || c.roomName().equals("open"), c.roomName());
            assertSame(Share.PLAYER, c.share());
        }
    }

    @Test
    @DisplayName("Player: an author no room holds is skipped for one that fits")
    void playerSkipsUnfittableAuthor() {
        PortalAuthorRoomPick.Authors dir = pages(List.of(), List.of(author("huge", 900), author("few", 3)));
        for (int pair = 0; pair < 200; pair++) {
            Choice c = PortalAuthorRoomPick.choose(SEED, pair, Share.PLAYER, List.of(SMALL, MID, BIG), dir, TALLY);
            assertEquals("few", c.author().name());
            assertEquals("small", c.roomName());
        }
    }

    @Test
    @DisplayName("Player with a cold directory: a room by weight, no author chosen up front")
    void playerColdFallsBack() {
        Choice c = PortalAuthorRoomPick.choose(SEED, 7, Share.PLAYER, ROOMS, COLD, TALLY);
        assertSame(Share.PLAYER, c.share());
        assertNull(c.author());
        assertTrue(ROOMS.stream().anyMatch(r -> r.name().equals(c.roomName())));
    }

    @Test
    @DisplayName("Self: the room is fitted to the reader's own count")
    void selfFitsOwnCount() {
        PortalAuthorRoomPick.Authors mine = pages(List.of(author("me", 20)), List.of());
        for (int pair = 0; pair < 300; pair++) {
            Choice c = PortalAuthorRoomPick.choose(SEED, pair, Share.SELF, ROOMS, mine, TALLY);
            assertSame(Share.SELF, c.share());
            assertEquals("me", c.author().name());
            assertTrue(c.roomName().equals("mid") || c.roomName().equals("open"), c.roomName());
        }
    }

    @Test
    @DisplayName("Self with one book fits no range exactly, so the nearest room takes it")
    void selfFirstBookTakesNearestRoom() {
        PortalAuthorRoomPick.Authors mine = pages(List.of(author("me", 1)), List.of());
        Choice c = PortalAuthorRoomPick.choose(SEED, 3, Share.SELF, ROOMS, mine, TALLY);
        assertEquals("small", c.roomName());
    }

    @Test
    @DisplayName("Self with no page stays Self — the lock's own rotation covers it")
    void selfColdStaysSelf() {
        Choice c = PortalAuthorRoomPick.choose(SEED, 11, Share.SELF, ROOMS, COLD, TALLY);
        assertSame(Share.SELF, c.share());
        assertNull(c.author());
    }

    @Test
    @DisplayName("Stats lands only on rooms whose range holds the full tally")
    void statsFitsTally() {
        for (int pair = 0; pair < 300; pair++) {
            Choice c = PortalAuthorRoomPick.choose(SEED, pair, Share.STATS, ROOMS, COLD, TALLY);
            assertTrue(c.roomName().equals("big") || c.roomName().equals("open"), c.roomName());
        }
    }

    @Test
    @DisplayName("the same seed and pair always give the same room")
    void deterministic() {
        PortalAuthorRoomPick.Authors dir = pages(List.of(), List.of(author("a", 15), author("b", 50), author("c", 4)));
        for (int pair = 0; pair < 200; pair++) {
            Choice first = PortalAuthorRoomPick.choose(SEED, pair, Share.PLAYER, ROOMS, dir, TALLY);
            Choice again = PortalAuthorRoomPick.choose(SEED, pair, Share.PLAYER, ROOMS, dir, TALLY);
            assertEquals(first, again);
        }
    }

    @Test
    @DisplayName("the directory band covers every room: lowest floor, open ceiling if any room is open")
    void bandIsUnion() {
        PortalRoomBooks band = PortalAuthorRoomPick.band(ROOMS);
        assertEquals(1, band.minBooks());
        assertEquals(PortalRoomBooks.NO_MAXIMUM, band.maxBooks());
        PortalRoomBooks closed = PortalAuthorRoomPick.band(List.of(SMALL, MID));
        assertEquals(1, closed.minBooks());
        assertEquals(24, closed.maxBooks());
    }

    @Test
    @DisplayName("only(share) pins the share and keeps the range")
    void onlyKeepsRange() {
        PortalRoomBooks pinned = new PortalRoomBooks(Kind.OFF, 3, 1, 1, 1, 24, 120).only(Share.STATS);
        assertTrue(pinned.locks());
        assertEquals(24, pinned.minBooks());
        assertEquals(120, pinned.maxBooks());
        for (int pair = -200; pair <= 200; pair++) assertSame(Share.STATS, pinned.resolveShare(pair));
    }
}
