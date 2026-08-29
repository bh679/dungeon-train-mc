package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.event.ContentModeMirror;
import games.brennan.dungeontrain.event.SharedBookGate;
import games.brennan.dungeontrain.narrative.AuthorBookPool;
import games.brennan.dungeontrain.narrative.SharedBookPool;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
 *
 * <p><b>A room that found nobody is the exception</b> and stays pending. Its answer came from a
 * directory page that expires (see {@code PortalRoomAuthorLocks.EMPTY_PAGE_TTL_MS}), so a room met
 * while the corpus was thin — or while the relay was unwell — can fill itself minutes later instead
 * of standing bare until the server restarts, which is what dropping it here used to mean. Kept
 * bounded by {@link #MAX_PENDING_ROOMS}, since nothing calls {@link #forget} and rooms keep being
 * registered for as long as a train runs.</p>
 */
public final class PortalRoomLibrarian {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * A room waiting to be stocked: where it stands, what its author is drawn from, and WHEN it was
     * registered.
     *
     * <p>{@code seq} is a monotonic registration stamp, and it is what {@link #evictOldest} orders
     * on. It cannot order on the pair key: keys run OUTWARD from the origin in both directions —
     * a train standing at pairs -6, -3, 3, 6 is ordinary — so the smallest key is the furthest
     * carriage one way, not the room registered longest ago. Ordering on the key evicted the NEWEST
     * room for a player travelling in the negative direction.</p>
     */
    private record Pending(BlockPos origin, Vec3i size, PortalRoomBooks books, long seq) {}

    /**
     * How many rooms may be waiting for their books at once.
     *
     * <p>Bounded because a room that finds no author now STAYS pending — it is waiting on a corpus
     * that may grow, not giving up — while rooms keep being registered for as long as a train runs
     * and nothing calls {@link #forget}. Well above the handful of rooms that can be standing at
     * once; past it the room registered longest ago goes, whichever direction it lies in.</p>
     */
    static final int MAX_PENDING_ROOMS = 32;

    /** pair key → the room still waiting for its books. */
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();

    /**
     * pair key → which books a stat room already holds, by {@code PortalRoomStatShelves} key.
     *
     * <p>A stat room is stocked over many ticks as its boards arrive, so unlike a library room it
     * cannot simply drop out of {@link #PENDING} after one pass. This is what stops the second pass
     * shelving a duplicate of everything the first pass placed. Empty for library rooms, which are
     * stocked once and forgotten.</p>
     */
    private static final Map<Integer, Set<String>> PLACED = new ConcurrentHashMap<>();

    /**
     * Ticks between two stocking passes over the same stat room.
     *
     * <p>A stat room that is merely incomplete stays pending indefinitely and is re-asked on every
     * tick — deliberate, because a board the relay has not served yet is not a failure. What was not
     * deliberate is the cost of asking: each pass allocates through {@code missingStats},
     * {@code LeaderboardPool.populated()} (which allocates <em>and</em> sorts) and
     * {@code missingBoards}, then calls {@link PortalRoomStatShelves#requestBoards()} over every
     * category — at 20 Hz, for up to {@link #MAX_PENDING_ROOMS} rooms, for the rest of the session.
     * A board arrives over minutes, so asking once a second finds it just as fast.</p>
     */
    private static final int STAT_ROOM_PASS_PERIOD_TICKS = 20;

    /**
     * pair key → the game tick before which that stat room should not be stocked again. Absent for a
     * room that has never been passed over, so a player walking into a fresh room is served at once
     * rather than waiting out a period.
     */
    private static final Map<Integer, Long> NEXT_STAT_PASS_TICK = new ConcurrentHashMap<>();

    /**
     * Hands out {@link Pending#seq}. Monotonic for the life of the process, which is all eviction
     * order needs — it is only ever compared against other live entries, never persisted.
     */
    private static final AtomicLong REGISTRATIONS = new AtomicLong();

    /**
     * Rooms that have already had their "no author in this band" line logged.
     *
     * <p>A room that finds nobody stays pending and is re-asked every tick, so without this the one
     * line worth reading would be buried under thousands of copies of itself. Cleared for a pair the
     * moment it does resolve, so a later dry spell says so again.</p>
     */
    private static final Set<Integer> REPORTED_NONE = ConcurrentHashMap.newKeySet();

    private PortalRoomLibrarian() {}

    /**
     * Register a freshly stamped room as wanting an author's books.
     *
     * <p>Called from the stamp itself, which is the only place that knows the room's box. No-op for
     * a room that does not stock from an author, which is almost all of them.</p>
     */
    public static void register(int pairKey, BlockPos origin, Vec3i size, PortalRoomBooks books) {
        REPORTED_NONE.remove(pairKey);
        PLACED.remove(pairKey);
        NEXT_STAT_PASS_TICK.remove(pairKey);
        if (books == null || !books.locks() || origin == null || size == null) {
            // A room re-stamped with the setting turned off must not keep an old pending record, or
            // it would be stocked from a decision its author has since taken back.
            PENDING.remove(pairKey);
            return;
        }
        // A re-stamped room is registered afresh, so it takes a new seq and goes to the back of the
        // eviction queue — it is the most recently seen room, whatever it was before.
        PENDING.put(pairKey, new Pending(origin.immutable(), size, books,
            REGISTRATIONS.incrementAndGet()));
        // The roll is deterministic on the pair key, so whether this room is the tally is knowable
        // now — and if it is, it wants every board there is. warmNext()'s one-per-tick rotation takes
        // about twelve minutes to get through them; ask for the set while the player is still walking
        // in. (Only the roll, not the whole resolution, which needs a reader.)
        if (books.resolveShare(pairKey).isStats()) PortalRoomStatShelves.requestBoards();
        evictOldest();
    }

    /**
     * Keep {@link #PENDING} inside {@link #MAX_PENDING_ROOMS}, dropping the room registered longest
     * ago first.
     *
     * <p>Ordered on {@link Pending#seq}, NOT on the pair key. Pair keys run outward from the origin
     * in both directions, so the smallest is the furthest carriage one way rather than the oldest
     * room; ordering on it threw away the newest room for a player riding the negative direction.
     * The registration stamp says what the key cannot: which of these rooms this train met first.</p>
     *
     * <p>Only runs on {@link #register}, which happens once per stamped room, so the cost never
     * lands on the tick.</p>
     */
    private static void evictOldest() {
        while (PENDING.size() > MAX_PENDING_ROOMS) {
            Integer oldest = null;
            long oldestSeq = Long.MAX_VALUE;
            for (Map.Entry<Integer, Pending> entry : PENDING.entrySet()) {
                if (entry.getValue().seq() < oldestSeq) {
                    oldestSeq = entry.getValue().seq();
                    oldest = entry.getKey();
                }
            }
            if (oldest == null) return;
            PENDING.remove(oldest);
            REPORTED_NONE.remove(oldest);
            PLACED.remove(oldest);
            NEXT_STAT_PASS_TICK.remove(oldest);
        }
    }

    /** Forget a pair — its structure has gone, and the box in the record no longer means anything. */
    public static void forget(int pairKey) {
        PENDING.remove(pairKey);
        REPORTED_NONE.remove(pairKey);
        PLACED.remove(pairKey);
        NEXT_STAT_PASS_TICK.remove(pairKey);
    }

    /**
     * The books this catalogue may actually be written into shelves with.
     *
     * <p>A writer's own catalogue can contain books the pool withholds — pending, undecided, refused —
     * and those are theirs alone to read. But a stocked shelf is <b>world state</b>, not a per-player
     * view: {@link PortalRoomLibrary#stock} writes real stacks into real chiseled bookshelves, this
     * room is stocked exactly once (see {@link #tick}), and anyone who walks in afterwards can open it
     * and take what is there. So the withheld books go in only when the reader is alone on the level;
     * with company the room stocks the author's public shelf, exactly as it did before this feature.</p>
     *
     * <p><b>Known residual.</b> "Alone" is true at stock time, not forever. A room stocked in a
     * single-player world keeps its withheld books if that world is later opened to others. Closing
     * that properly means rendering the pages per-viewer rather than placing them, which is a
     * different piece of machinery than this one.</p>
     */
    static List<SharedBookPool.PoolBook> shelvable(List<SharedBookPool.PoolBook> catalogue, int playerCount) {
        if (catalogue.isEmpty() || playerCount <= 1) return catalogue;
        List<SharedBookPool.PoolBook> out = new ArrayList<>(catalogue.size());
        for (SharedBookPool.PoolBook book : catalogue) {
            if (!book.isWithheld()) out.add(book);
        }
        return out;
    }

    /**
     * Try to stock every room still waiting. Cheap and total: nothing here may throw into the portal
     * tick, and an empty pending set costs one map check.
     *
     * @param players the riders currently on this level — the Self share resolves against one of them
     */
    public static void tick(ServerLevel level, List<ServerPlayer> players) {
        if (PENDING.isEmpty() || level == null || players == null || players.isEmpty()) return;

        for (Map.Entry<Integer, Pending> entry : new ArrayList<>(PENDING.entrySet())) {
            int pairKey = entry.getKey();
            Pending pending = entry.getValue();
            ServerPlayer reader = readerFor(players, pending.origin());
            if (reader == null) continue;

            // Resolve first, gate second. Which of the four shares this room came up is settled by
            // the pair key alone, and the tally needs no community-book discovery — so gating on
            // discovery before the roll is read would leave a stat room bare on a server that has
            // simply turned sharing off.
            PortalRoomAuthorLocks.Resolution resolved = PortalRoomAuthorLocks.resolve(
                reader, pairKey, pending.books(), ContentModeMirror.isKid(reader));
            if (resolved.outcome() == PortalRoomAuthorLocks.Outcome.STATS) {
                stockStatRoom(level, pairKey, pending, reader);
                continue;
            }

            // Everything past here wants an author, which is the only thing discovery gates.
            if (!SharedBookGate.canDiscover()) continue;   // discovery off — no author to stock from
            if (resolved.outcome() == PortalRoomAuthorLocks.Outcome.PENDING) continue;  // ask again

            if (resolved.outcome() == PortalRoomAuthorLocks.Outcome.NONE) {
                // The directory answered and nobody is inside this room's band. Said out loud and at
                // INFO, because from in-game this is indistinguishable from a broken feature: the
                // room simply stands there empty. Naming the band is what turns it back into a
                // setting the author can change. Once per room, not once per tick.
                if (REPORTED_NONE.add(pairKey)) {
                    LOGGER.info("[DungeonTrain] Portal room {} found no author with {}-{} books — "
                            + "its shelves stay empty for now. Widen the room's Books range, or the "
                            + "corpus has nobody that prolific yet.",
                        pairKey, pending.books().minBooks() + 1,
                        pending.books().maxBooks() == PortalRoomBooks.NO_MAXIMUM
                            ? "any" : String.valueOf(pending.books().maxBooks()));
                }
                // Deliberately still PENDING. The directory page behind this answer expires (see
                // PortalRoomAuthorLocks.EMPTY_PAGE_TTL_MS), so a room that found nobody while the
                // corpus was thin can fill itself later. Dropping it here is what made "empty once"
                // mean "empty until the server restarts".
                continue;
            }
            REPORTED_NONE.remove(pairKey);

            BookAuthorsClient.Author author = resolved.author();
            UUID owner = PortalRoomAuthorLocks.ownerFor(reader, author);
            int placed = PortalRoomLibrary.stock(level, pending.origin(), pending.size(),
                shelvable(AuthorBookPool.booksFor(author.token(), owner != null), players.size()),
                pairKey, author.name());
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
     * One incremental pass over a stat room: shelve whatever it is still missing.
     *
     * <p>Unlike the library room this runs again and again — a stat room's leaderboard books arrive
     * from the relay over the first minutes, so one pass could only ever place the run-stat notes.
     * The room leaves {@link #PENDING} on either of the two ways it can be finished: it holds the
     * full set, or it turns out to have no shelves to hold anything.</p>
     *
     * <p>A room that is merely incomplete stays pending indefinitely, which is deliberate — a board
     * the relay has not served yet is not a failure, and there is nothing to do about it but ask
     * again. The pass costs a map lookup once the room is full, because it is no longer in the map.</p>
     */
    private static void stockStatRoom(ServerLevel level, int pairKey, Pending pending, ServerPlayer reader) {
        // Incomplete rooms are re-asked forever by design; they just don't need asking every tick.
        // A room seen for the first time has no entry and is stocked immediately.
        long gameTime = level.getGameTime();
        Long notBefore = NEXT_STAT_PASS_TICK.get(pairKey);
        if (notBefore != null && gameTime < notBefore) return;
        NEXT_STAT_PASS_TICK.put(pairKey, gameTime + STAT_ROOM_PASS_PERIOD_TICKS);

        Set<String> already = PLACED.getOrDefault(pairKey, Set.of());
        PortalRoomStatShelves.Progress progress = PortalRoomStatShelves.stock(
            level, pending.origin(), pending.size(), already, pairKey, reader.getUUID());
        PLACED.put(pairKey, progress.placed());

        if (progress.stalled()) {
            // Nowhere to put a book — no shelves, or a template that spoke for every slot. More
            // boards arriving will not change that, so stop asking rather than rescan the room's box
            // on every tick for the rest of the session.
            LOGGER.info("[DungeonTrain] Portal room {} is a stat room with no free shelf slots — "
                + "it holds {} of {} book(s) and will not fill further",
                pairKey, progress.placed().size(), PortalRoomStatShelves.FULL_SET);
            PENDING.remove(pairKey);
            NEXT_STAT_PASS_TICK.remove(pairKey);
            return;
        }
        if (PortalRoomStatShelves.isComplete(progress.placed())) {
            PENDING.remove(pairKey);
            NEXT_STAT_PASS_TICK.remove(pairKey);
            return;
        }
        // Still filling. Keep the boards coming rather than waiting on warmNext()'s rotation.
        PortalRoomStatShelves.requestBoards();
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
        REPORTED_NONE.clear();
        PLACED.clear();
        NEXT_STAT_PASS_TICK.clear();
    }

    /** Test hook: whether a pair is still waiting for its books. */
    static boolean isPending(int pairKey) {
        return PENDING.containsKey(pairKey);
    }
}
