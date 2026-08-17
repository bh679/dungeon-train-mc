package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.event.ContentModeMirror;
import games.brennan.dungeontrain.event.SharedBookGate;
import games.brennan.dungeontrain.event.PortalCarriageEvents;
import games.brennan.dungeontrain.net.relay.BookAuthorsClient;
import games.brennan.dungeontrain.portal.PortalRoomAuthorLocks;
import games.brennan.dungeontrain.portal.PortalRoomBooks;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells a player whose library they are standing in, once per room.
 *
 * <p>Driven from the portal-room occupancy scan that already runs every few ticks
 * ({@code BoardingProgressEvents}), so this adds no scan of its own — it is handed the player and the
 * pair they are in and decides whether there is anything to say.</p>
 *
 * <h2>Once per room, not once per entry</h2>
 * <p>The last pair greeted is remembered per player, so standing in a doorway does not repeat the
 * line and stepping back into the room you just left stays quiet. Walking into a DIFFERENT library and
 * back again does announce again, which is the point — the second announcement is news.</p>
 *
 * <h2>Late rather than never</h2>
 * <p>The author may not be resolved on the first scan: the directory fetch is kicked by the first
 * call and lands a moment later. Nothing is said until it does, so the line names a real person or
 * does not appear — a room whose relay is unreachable stays silent rather than announcing a library
 * it cannot serve.</p>
 */
public final class PortalLibraryGreeter {

    /** Player → the pair whose library they were last told about. */
    private static final Map<UUID, Integer> GREETED = new ConcurrentHashMap<>();

    private PortalLibraryGreeter() {}

    /**
     * Consider greeting {@code player}, who is standing in the room of {@code pairKey}.
     *
     * <p>Cheap and total: an unlocked room (the overwhelming majority) costs one map read and one enum
     * comparison, and nothing here can throw into the occupancy scan.</p>
     */
    public static void tick(ServerPlayer player, int pairKey) {
        if (player == null || !SharedBookGate.canDiscover()) return;
        Integer last = GREETED.get(player.getUUID());
        if (last != null && last == pairKey) return;

        PortalRoomBooks books = PortalCarriageEvents.portalRoomBooksFor(pairKey);
        if (!books.locks()) {
            // Not a library. Forget the last one so walking out of a locked room and back in later
            // announces again — the map holds "the library you are currently in", not a history.
            GREETED.remove(player.getUUID());
            return;
        }
        Optional<BookAuthorsClient.Author> author =
            PortalRoomAuthorLocks.authorFor(player, pairKey, books, ContentModeMirror.isKid(player));
        if (author.isEmpty()) return;    // still resolving, or nobody to name — try again next scan

        GREETED.put(player.getUUID(), pairKey);
        // The ROLLED kind, not the setting: a Random room that came up Self should still say "you
        // wrote these" rather than name the reader as a stranger to their own shelves.
        boolean mine = PortalRoomAuthorLocks.effectiveKind(pairKey, books).startsFromSelf()
            && isOwn(player, author.get());
        player.sendSystemMessage(
            PortalLibraryMessage.random(player.getRandom(), author.get().name(), mine));
    }

    /**
     * Whether the room resolved to the player's OWN writing.
     *
     * <p>Under Current Player the lock starts from the holder and rotates to a stranger when they have
     * written nothing, so "is this mine?" cannot be read off the setting alone. The relay never sends
     * uuids, so this compares the one thing both sides have: whether the author it named is the
     * player's own {@code self} directory entry.</p>
     */
    private static boolean isOwn(ServerPlayer player, BookAuthorsClient.Author author) {
        return PortalRoomAuthorLocks.isSelfAuthor(player, author.token());
    }

    /** Forget a player's current library — on logout, and on server stop. */
    public static void forget(UUID player) {
        GREETED.remove(player);
    }

    /** Drop every greeting record. */
    public static void clear() {
        GREETED.clear();
    }
}
