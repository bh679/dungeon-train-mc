package games.brennan.dungeontrain.perks.effects.archivist.follow;

import java.util.List;
import java.util.Locale;

/**
 * The client's copy of its Favourite Author state ({@link FollowsSyncPacket}): read by the perk card,
 * the Followed Authors menu and the Follow buttons. Holds no client-only types.
 */
public final class FollowsClientState {

    private static volatile FollowsSyncPacket last =
        new FollowsSyncPacket(List.of(), List.of(), "", FollowsSyncPacket.Lookup.NONE);
    /** Bumped on every sync, so a screen can tell its rows are stale. */
    private static volatile int version;

    private FollowsClientState() {}

    static void set(FollowsSyncPacket packet) {
        last = packet;
        version++;
    }

    public static FollowsSyncPacket get() {
        return last;
    }

    public static int version() {
        return version;
    }

    public static int followCount() {
        return last.rows().size();
    }

    /** Whether a Follow would be taken at {@code rank} active points (the server checks the same). */
    public static boolean canFollowMore(int rank) {
        return FavouriteAuthor.canFollowMore(followCount(), rank);
    }

    /** Whether the author behind {@code name} is followed (case-insensitive). */
    public static boolean followsName(String name) {
        if (name == null || name.isBlank()) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return last.rows().stream().anyMatch(r -> r.name().toLowerCase(Locale.ROOT).equals(n));
    }

    /** Autocomplete: names starting with {@code prefix}, most recent first, not already followed. */
    public static List<String> suggestions(String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return last.names().stream()
            .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(p))
            .filter(n -> !followsName(n))
            .toList();
    }

    /** Clears on disconnect. */
    public static void clear() {
        set(new FollowsSyncPacket(List.of(), List.of(), "", FollowsSyncPacket.Lookup.NONE));
    }
}
