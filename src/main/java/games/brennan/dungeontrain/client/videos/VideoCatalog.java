package games.brennan.dungeontrain.client.videos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Session cache of the relay's curated video list — the data behind the main-menu Videos page.
 *
 * <p>Fetched at <b>title-screen init</b> like the official-links overlay ({@code
 * TitleScreenLayoutHandler}) — the menu's Videos icon shows a live dot ({@link #anyLive()}) when
 * a streamer is on, so the catalogue has to be in hand before anyone opens the page. One
 * anonymous relay GET a session; a failure can be retried from the page's Retry button.</p>
 *
 * <p>State is a pair of volatiles swapped whole — the list is never mutated, only replaced — so
 * the HTTP completion thread and the render thread never see a half-written catalogue.</p>
 */
public final class VideoCatalog {

    public enum State { IDLE, LOADING, LOADED, FAILED }

    private static volatile State state = State.IDLE;
    private static volatile List<VideoEntry> entries = List.of();
    /** Row ids this player flagged this session — the row's flag draws filled and won't re-open. */
    private static volatile Set<Integer> flagged = Set.of();

    private VideoCatalog() {}

    public static State state() {
        return state;
    }

    /** The last successful fetch, or an empty list. Immutable. */
    public static List<VideoEntry> entries() {
        return entries;
    }

    /** Is any streamer relay-verified live right now? The menu icon's green dot. */
    public static boolean anyLive() {
        for (VideoEntry v : entries) {
            if (v.live()) return true;
        }
        return false;
    }

    /** Start the fetch unless one has already succeeded or is in flight. */
    public static void ensureFetched() {
        State s = state;
        if (s == State.IDLE || s == State.FAILED) {
            state = State.LOADING;
            VideoCatalogFetcher.fetchAsync();
        }
    }

    /** Try again after a failure (no-op while loading or already loaded). */
    public static void retry() {
        ensureFetched();
    }

    /** Has this player already flagged this row this session? */
    public static boolean isFlagged(int id) {
        return flagged.contains(id);
    }

    /** Remember a flag (new set, old one untouched). */
    public static void markFlagged(int id) {
        Set<Integer> next = new HashSet<>(flagged);
        next.add(id);
        flagged = Set.copyOf(next);
    }

    /**
     * Drop one row locally — the relay just said it is off this player's list (hidden, or off the
     * kid list on a kid-mode client), and waiting for the next session to see that would look like
     * the flag did nothing.
     */
    public static void remove(int id) {
        List<VideoEntry> next = new ArrayList<>(entries.size());
        for (VideoEntry v : entries) {
            if (v.id() != id) next.add(v);
        }
        entries = List.copyOf(next);
    }

    static void accept(List<VideoEntry> fetched) {
        entries = List.copyOf(fetched);
        state = State.LOADED;
    }

    static void markFailed() {
        state = State.FAILED;
    }

    /** Test seam — back to the pristine never-fetched state. */
    static void reset() {
        state = State.IDLE;
        entries = List.of();
        flagged = Set.of();
    }
}
