package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.narrative.AuthorBookPool;
import games.brennan.dungeontrain.net.relay.BookAuthorsClient;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which author each standing portal room is serving, and what happens when that answer does not work
 * out.
 *
 * <p>Keyed by pair key — the room instance, not the room <i>name</i> — so two rooms of the same
 * design standing at once are two different libraries, and walking back into one you left finds the
 * author you left. Cleared when the pair retires, so a room stamped again later rolls afresh.</p>
 *
 * <h2>Resolved at pickup, not at stamp</h2>
 * <p>A room is stamped the moment the train reaches it, which can be seconds into a world — before
 * the relay's author directory has warmed. The lock is therefore carried on the book as a <i>mode</i>
 * (see {@link games.brennan.dungeontrain.narrative.PortalBookLockTag}) and turned into a person here,
 * lazily, the first time somebody actually picks a book up. By then the fetch has usually landed; when
 * it has not, this kicks one and the pickup falls through to an ordinary book, which the next pickup
 * corrects.</p>
 *
 * <h2>A failed author is replaced, not fallen back from</h2>
 * <p>An author whose catalogue comes back empty — they were counted on a rating this world cannot be
 * served, or the relay lost them between the two calls — is discarded and the next candidate tried,
 * up to {@link #MAX_ATTEMPTS}. {@link PortalRoomBooks#SELF} starts from the holder's own catalogue and
 * rotates into the random-player pool when they have written nothing, which is most players. Only when
 * nothing at all resolves does the room quietly serve ordinary books.</p>
 *
 * <h2>Self is per player, the others are per room</h2>
 * <p>Under {@link PortalRoomBooks#SELF} two people in one room are reading two different libraries, so
 * the memo is keyed by pair AND holder. Under the other two the room has one author for everybody in
 * it, and the holder is not part of the key.</p>
 */
public final class PortalRoomAuthorLocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * How many authors a room will try before giving up for this pickup. Small on purpose: each
     * attempt is a relay round trip, and a world where two candidates in a row come back empty has
     * something wrong with it that a third will not fix.
     */
    static final int MAX_ATTEMPTS = 3;

    /**
     * How many rooms' locks are remembered at once.
     *
     * <p>Bounded because a pair key is a carriage index and the train's keep climbing — an unbounded
     * map would grow for as long as a world runs. Well above the handful of pairs that can stand at
     * once, so evicting is effectively never; a room that did lose its entry simply resolves again.</p>
     */
    static final int MAX_REMEMBERED_ROOMS = 32;

    /** pair key (plus holder, under Self) → the author that room settled on. Access-ordered LRU. */
    private static final Map<Key, BookAuthorsClient.Author> LOCKS = boundedLru();

    /** Candidates already tried and found empty for a key, so a rotation never re-picks them. */
    private static final Map<Key, Set<String>> REJECTED = boundedLru();

    /** An access-ordered map that drops its least-recently-used entry past {@link #MAX_REMEMBERED_ROOMS}. */
    private static <V> Map<Key, V> boundedLru() {
        return java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, V> eldest) {
                return size() > MAX_REMEMBERED_ROOMS;
            }
        });
    }

    /** Cached directory candidates per kind, newest fetch wins. Refreshed when a room runs out. */
    private static final Map<String, List<BookAuthorsClient.Author>> DIRECTORY = new ConcurrentHashMap<>();

    /** Kinds with a directory fetch in flight, so a room being walked through does not stack them. */
    private static final Set<String> DIRECTORY_IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /**
     * Recently issued authors, newest first — the reason two rooms in a row rarely feel like the same
     * library. Bounded well under a directory page so it can never exhaust the candidates.
     */
    private static final Deque<String> RECENT = new ArrayDeque<>();
    private static final int RECENT_MEMORY = 4;

    /** What a lock is remembered against: the room instance, and the holder only where it varies. */
    private record Key(int pairKey, UUID holder) {}

    private PortalRoomAuthorLocks() {}

    /**
     * The author this room is serving {@code player}, fetching what it needs and rotating past any
     * candidate with nothing to give.
     *
     * <p>Empty means "not this pickup": either nothing has arrived yet (a fetch is now on its way and
     * the next pickup will have it) or no author could be resolved at all. Both read the same way to
     * the caller — serve an ordinary book — because a room that shows a built-in book for one pickup
     * is better than one that shows none.</p>
     */
    public static Optional<BookAuthorsClient.Author> authorFor(ServerPlayer player, int pairKey,
                                                              PortalRoomBooks books, boolean kidSafe) {
        if (player == null || books == null || !books.locks()) return Optional.empty();
        PortalRoomBooks.Kind mode = effectiveKind(pairKey, books);
        Key key = keyFor(pairKey, player, mode);

        BookAuthorsClient.Author held = LOCKS.get(key);
        if (held != null) {
            // Settled — unless the catalogue we settled on turned out to be empty, in which case the
            // room has to move on rather than serve nothing for as long as it stands.
            if (!AuthorBookPool.isFetched(held.token())) {
                AuthorBookPool.refreshAsync(held.token(), hostLocaleOf(player), kidSafe);
                return Optional.empty();                       // in flight; the next pickup has it
            }
            if (!AuthorBookPool.booksFor(held.token()).isEmpty()) return Optional.of(held);
            reject(key, held.token());
            LOCKS.remove(key);
        }

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<BookAuthorsClient.Author> candidate = nextCandidate(player, key, mode, kidSafe);
            if (candidate.isEmpty()) return Optional.empty();   // nothing to try — fetch kicked, if any
            BookAuthorsClient.Author author = candidate.get();
            if (!AuthorBookPool.isFetched(author.token())) {
                AuthorBookPool.refreshAsync(author.token(), hostLocaleOf(player), kidSafe);
                return Optional.empty();                        // in flight; the next pickup has it
            }
            if (AuthorBookPool.booksFor(author.token()).isEmpty()) {
                // Counted in the directory, servable to nobody here. Try somebody else.
                reject(key, author.token());
                continue;
            }
            LOCKS.put(key, author);
            remember(author.token());
            LOGGER.info("[DungeonTrain] Portal room {} locked to author '{}' ({} book(s))",
                pairKey, author.name(), author.count());
            return Optional.of(author);
        }
        return Optional.empty();
    }

    /**
     * The kind a room actually serves — itself for the three plain locks, and a weighted roll for
     * {@link PortalRoomBooks.Kind#RANDOM}.
     *
     * <p><b>Seeded from the pair key, not from chance.</b> The roll has to be stable for the room's
     * life or a player would meet three different libraries in one room, and deriving it rather than
     * remembering it means a relocation, a re-stamp or a restart all land on the same answer — the
     * same "a pair rolls its room ONCE" property {@code ensureStructure} already relies on for which
     * room a pair gets in the first place.</p>
     */
    public static PortalRoomBooks.Kind effectiveKind(int pairKey, PortalRoomBooks books) {
        return books == null ? PortalRoomBooks.Kind.OFF : books.resolveKind(pairKey);
    }

    /**
     * The next author worth trying for {@code key}, or empty when the directory has nothing to offer
     * yet (a fetch is kicked in that case, so a later pickup finds it warm).
     *
     * <p>Under {@link PortalRoomBooks.Kind#SELF} the holder's own entry comes first and the
     * random-player directory is the rotation behind it — which is what makes Self work for the many
     * players who have written nothing, rather than leaving their rooms unlocked.</p>
     */
    private static Optional<BookAuthorsClient.Author> nextCandidate(ServerPlayer player, Key key,
                                                                   PortalRoomBooks.Kind mode, boolean kidSafe) {
        if (mode.startsFromSelf()) {
            Optional<BookAuthorsClient.Author> self = pick("self", key, player, kidSafe);
            if (self.isPresent()) return self;
        }
        String kind = mode.startsFromSelf()
            ? PortalRoomBooks.Kind.PLAYER.directoryKind() : mode.directoryKind();
        return pick(kind, key, player, kidSafe);
    }

    /**
     * One untried candidate from the {@code kind} directory, preferring authors this world has not
     * just served. Kicks a fetch and returns empty when the directory is cold or exhausted.
     */
    private static Optional<BookAuthorsClient.Author> pick(String kind, Key key, ServerPlayer player,
                                                           boolean kidSafe) {
        String cacheKey = "self".equals(kind) ? kind + ':' + player.getUUID() : kind;
        List<BookAuthorsClient.Author> candidates = DIRECTORY.get(cacheKey);
        if (candidates == null) {
            fetchDirectory(kind, cacheKey, player, kidSafe);
            return Optional.empty();
        }
        Set<String> rejected = REJECTED.getOrDefault(key, Set.of());
        synchronized (PortalRoomAuthorLocks.class) {
            return choose(candidates, rejected, RECENT, player.getRandom()::nextInt);
        }
    }

    /**
     * Pick one author from {@code candidates}, skipping those this room has already tried and
     * preferring one the last few rooms did not use.
     *
     * <p>Pure and package-private so the rotation rule can be tested without a server. The
     * recently-used preference is SOFT on purpose: on a small corpus every candidate is recent within
     * a few rooms, and refusing to repeat then would leave a room with no author at all — worse than
     * the same library twice in a session.</p>
     */
    static Optional<BookAuthorsClient.Author> choose(List<BookAuthorsClient.Author> candidates,
                                                     Set<String> rejected,
                                                     java.util.Collection<String> recent,
                                                     java.util.function.IntUnaryOperator randomIndex) {
        List<BookAuthorsClient.Author> eligible = new ArrayList<>();
        for (BookAuthorsClient.Author a : candidates) {
            if (!rejected.contains(a.token())) eligible.add(a);
        }
        if (eligible.isEmpty()) return Optional.empty();
        List<BookAuthorsClient.Author> fresh = new ArrayList<>();
        for (BookAuthorsClient.Author a : eligible) {
            if (!recent.contains(a.token())) fresh.add(a);
        }
        List<BookAuthorsClient.Author> from = fresh.isEmpty() ? eligible : fresh;
        return Optional.of(from.get(randomIndex.applyAsInt(from.size())));
    }

    /** Pull a directory page for {@code kind}, once at a time. Caches an empty page as "asked, nothing". */
    private static void fetchDirectory(String kind, String cacheKey, ServerPlayer player, boolean kidSafe) {
        if (!DIRECTORY_IN_FLIGHT.add(cacheKey)) return;
        UUID uuid = "self".equals(kind) ? player.getUUID() : null;
        BookAuthorsClient.fetch(kind, DungeonTrainConfig.getPortalRoomAuthorMinBooks(), uuid, kidSafe,
            authors -> {
                try {
                    DIRECTORY.put(cacheKey, List.copyOf(authors));
                } finally {
                    DIRECTORY_IN_FLIGHT.remove(cacheKey);
                }
            });
    }

    /**
     * The locale an author's catalogue is fetched for — the HOST's, like every other pool fetch, since
     * one catalogue serves everybody in the room. Null-safe: a player with no server yet leaves the
     * fetch unfiltered rather than failing it.
     */
    private static String hostLocaleOf(ServerPlayer player) {
        return player.getServer() == null ? null
            : games.brennan.dungeontrain.narrative.WorldLanguage.hostLocale(player.getServer());
    }

    private static void reject(Key key, String token) {
        REJECTED.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(token);
    }

    private static synchronized void remember(String token) {
        RECENT.remove(token);
        RECENT.addFirst(token);
        while (RECENT.size() > RECENT_MEMORY) RECENT.removeLast();
    }

    /** Self is per player; the other two give one room one author for everybody standing in it. */
    private static Key keyFor(int pairKey, ServerPlayer player, PortalRoomBooks.Kind mode) {
        return new Key(pairKey, mode.startsFromSelf() ? player.getUUID() : null);
    }

    /**
     * Whether {@code token} is {@code player}'s own author entry — what the greeting line needs to say
     * "your own shelves" rather than name a stranger.
     *
     * <p>Answered from the cached {@code self} directory page rather than by comparing uuids, because
     * the relay deliberately never sends one. A page that has not arrived yet reads as "not mine",
     * which is the safe way round: naming somebody else's library as yours would be a lie, while a
     * missed self-line is only a slightly less personal greeting.</p>
     */
    public static boolean isSelfAuthor(ServerPlayer player, String token) {
        if (player == null || token == null) return false;
        List<BookAuthorsClient.Author> mine = DIRECTORY.get("self:" + player.getUUID());
        if (mine == null) return false;
        for (BookAuthorsClient.Author a : mine) {
            if (token.equals(a.token())) return true;
        }
        return false;
    }

    /** Drop every lock, directory page and catalogue — server stop, and unit tests. */
    public static void clear() {
        LOCKS.clear();
        REJECTED.clear();
        DIRECTORY.clear();
        DIRECTORY_IN_FLIGHT.clear();
        synchronized (PortalRoomAuthorLocks.class) {
            RECENT.clear();
        }
        AuthorBookPool.clear();
    }

    /** Test hook: publish a directory page without going near the network. */
    static void seedDirectory(String cacheKey, List<BookAuthorsClient.Author> authors) {
        DIRECTORY.put(cacheKey, List.copyOf(authors));
    }

    /** Test hook: which author a key has settled on, without resolving one. */
    static Optional<BookAuthorsClient.Author> settled(int pairKey, UUID holder) {
        return Optional.ofNullable(LOCKS.get(new Key(pairKey, holder)));
    }

    /** Test hook: the tokens a key has tried and discarded. */
    static Set<String> rejectedFor(int pairKey, UUID holder) {
        return new HashSet<>(REJECTED.getOrDefault(new Key(pairKey, holder), Set.of()));
    }
}
