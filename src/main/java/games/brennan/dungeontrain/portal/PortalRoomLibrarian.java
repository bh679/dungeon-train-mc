package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.event.ContentModeMirror;
import games.brennan.dungeontrain.event.SharedBookGate;
import games.brennan.dungeontrain.narrative.AuthorBookPool;
import games.brennan.dungeontrain.net.relay.BookAuthorsClient;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stocks each standing library room, and keeps trying until it can.
 *
 * <h2>Why the shelves are not filled at stamp time</h2>
 * <p>A room is stamped the moment the train reaches it, which can be seconds into a world — long
 * before the relay has said who has written what. Filling from inside the stamp would therefore make
 * every early room a dud. Instead the stamp only <i>registers</i> the room here, and a cheap pass on
 * the portal tick fills it as soon as its author and their catalogue have landed. The visible effect
 * is that a room met immediately on world load has bare shelves for a second or two.</p>
 *
 * <p>The tick is also where the players are, which is what makes the Self share mean anything: the
 * room is resolved against the rider it was stamped for, and everybody who walks in later reads that
 * person's books, because one set of shelves now serves the whole room.</p>
 *
 * <h2>Once, not repeatedly</h2>
 * <p>A room drops out of the pending set the moment it is stocked, so the pass costs nothing on a
 * world where every library has already been filled — and a room can never be re-stocked into a loot
 * machine by standing next to it.</p>
 */
public final class PortalRoomLibrarian {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A room waiting to be stocked: where it stands and what its author is drawn from. */
    private record Pending(BlockPos origin, Vec3i size, PortalRoomBooks books) {}

    /** pair key → the room still waiting for its books. */
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();

    private PortalRoomLibrarian() {}

    /**
     * Register a freshly stamped room as wanting an author's books.
     *
     * <p>Called from the stamp itself, which is the only place that knows the room's box. No-op for
     * a room that does not stock from an author, which is almost all of them.</p>
     */
    public static void register(int pairKey, BlockPos origin, Vec3i size, PortalRoomBooks books) {
        if (books == null || !books.locks() || origin == null || size == null) {
            // A room re-stamped with the setting turned off must not keep an old pending record, or
            // it would be stocked from a decision its author has since taken back.
            PENDING.remove(pairKey);
            return;
        }
        PENDING.put(pairKey, new Pending(origin.immutable(), size, books));
    }

    /** Forget a pair — its structure has gone, and the box in the record no longer means anything. */
    public static void forget(int pairKey) {
        PENDING.remove(pairKey);
    }

    /**
     * Try to stock every room still waiting. Cheap and total: nothing here may throw into the portal
     * tick, and an empty pending set costs one map check.
     *
     * @param players the riders currently on this level — the Self share resolves against one of them
     */
    public static void tick(ServerLevel level, List<ServerPlayer> players) {
        if (PENDING.isEmpty() || level == null || players == null || players.isEmpty()) return;
        if (!SharedBookGate.canDiscover()) return;   // discovery off — no author to stock from

        for (Map.Entry<Integer, Pending> entry : new ArrayList<>(PENDING.entrySet())) {
            int pairKey = entry.getKey();
            Pending pending = entry.getValue();
            ServerPlayer reader = readerFor(players, pending.origin());
            if (reader == null) continue;

            PortalRoomAuthorLocks.Resolution resolved = PortalRoomAuthorLocks.resolve(
                reader, pairKey, pending.books(), ContentModeMirror.isKid(reader));
            if (resolved.outcome() == PortalRoomAuthorLocks.Outcome.PENDING) continue;  // ask again

            if (resolved.outcome() == PortalRoomAuthorLocks.Outcome.NONE) {
                // The directory answered and nobody is inside this room's band. Said out loud and at
                // INFO, because from in-game this is indistinguishable from a broken feature: the
                // room simply stands there empty. Naming the band is what turns it back into a
                // setting the author can change.
                LOGGER.info("[DungeonTrain] Portal room {} found no author with {}-{} books — "
                        + "its shelves stay empty. Widen the room's Books range, or the corpus has "
                        + "nobody that prolific yet.",
                    pairKey, pending.books().minBooks() + 1,
                    pending.books().maxBooks() == PortalRoomBooks.NO_MAXIMUM
                        ? "any" : String.valueOf(pending.books().maxBooks()));
                PENDING.remove(pairKey);
                continue;
            }

            BookAuthorsClient.Author author = resolved.author();
            int placed = PortalRoomLibrary.stock(level, pending.origin(), pending.size(),
                AuthorBookPool.booksFor(author.token()), pairKey, author.name());
            if (placed <= 0 && !PortalRoomLibrary.hasShelves(level, pending.origin(), pending.size())) {
                // No shelves to stock: the room is set to stock an author but was never built to hold
                // books. Drop it rather than asking again forever.
                LOGGER.info("[DungeonTrain] Portal room {} stocks an author but has no bookshelves",
                    pairKey);
            }
            PENDING.remove(pairKey);
        }
    }

    /**
     * The rider a room is stocked for: whoever is nearest it.
     *
     * <p>Nearest rather than first-in-the-list because the Self share names "the player the room was
     * stamped for", and the pair was stamped because somebody rode up to it. On a single-player world
     * these are the same player; on a shared one, nearest is the honest reading of whose room this
     * turned out to be.</p>
     */
    private static ServerPlayer readerFor(List<ServerPlayer> players, BlockPos origin) {
        ServerPlayer best = null;
        double bestSq = Double.MAX_VALUE;
        for (ServerPlayer player : players) {
            double dSq = player.distanceToSqr(origin.getX(), origin.getY(), origin.getZ());
            if (dSq < bestSq) {
                bestSq = dSq;
                best = player;
            }
        }
        return best;
    }

    /** Drop every pending room — server stop, and unit tests. */
    public static void clear() {
        PENDING.clear();
    }

    /** Test hook: whether a pair is still waiting for its books. */
    static boolean isPending(int pairKey) {
        return PENDING.containsKey(pairKey);
    }
}
