package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
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
 * rotates into the random-player pool when they have written nothing, which is most players — or when
 * they have not granted network access, since asking for their shelf means sending their uuid. Only
 * when nothing at all resolves does the room quietly serve ordinary books.</p>
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

    /**
     * How an author request turned out.
     *
     * <p>Three answers, not two, because "not yet" and "not ever" want opposite things from the
     * caller. A room still waiting on a fetch should ask again next tick; a room whose band nobody in
     * the corpus falls inside should stop asking and say so — otherwise it sits empty and silent, and
     * the operator has no way to tell a slow relay from a band nothing matches.</p>
     */
    public enum Outcome {
        /** An author was found; {@link Resolution#author()} is present. */
        RESOLVED,
        /** A fetch is on its way. Ask again. */
        PENDING,
        /** The directory has answered and nobody in it qualifies. Asking again will not help. */
        NONE
    }

    /** {@link Outcome} plus the author, when there is one. */
    public record Resolution(Outcome outcome, BookAuthorsClient.Author author) {
        static final Resolution PENDING = new Resolution(Outcome.PENDING, null);
        static final Resolution NONE = new Resolution(Outcome.NONE, null);

        static Resolution of(BookAuthorsClient.Author author) {
            return new Resolution(Outcome.RESOLVED, author);
        }

        public boolean isResolved() {
            return outcome == Outcome.RESOLVED && author != null;
        }
    }

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
        return Optional.ofNullable(resolve(player, pairKey, books, kidSafe).author());
    }

    /**
     * As {@link #authorFor}, but saying WHY when there is no author — see {@link Outcome}.
     */
    public static Resolution resolve(ServerPlayer player, int pairKey,
                                     PortalRoomBooks books, boolean kidSafe) {
        if (player == null || books == null || !books.locks()) return Resolution.NONE;
        PortalRoomBooks.Share share = effectiveShare(pairKey, books);
        Key key = keyFor(pairKey, player, share);

        BookAuthorsClient.Author held = LOCKS.get(key);
        if (held != null) {
            // Settled — unless the catalogue we settled on turned out to be empty, in which case the
            // room has to move on rather than serve nothing for as long as it stands.
            UUID heldOwner = ownerFor(player, held);
            if (!AuthorBookPool.isFetched(held.token(), heldOwner != null)) {
                AuthorBookPool.refreshAsync(held.token(), hostLocaleOf(player), kidSafe, heldOwner);
                return Resolution.PENDING;                     // in flight; the next pass has it
            }
            if (!AuthorBookPool.booksFor(held.token(), heldOwner != null).isEmpty()) return Resolution.of(held);
            reject(key, held.token());
            LOCKS.remove(key);
        }

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<BookAuthorsClient.Author> candidate =
                nextCandidate(player, key, share, books, kidSafe);
            if (candidate.isEmpty()) {
                // Nothing to try. Which of the two that is depends on whether the pages it would have
                // consulted have actually come back yet.
                return directoriesAnswered(player, share, books) ? Resolution.NONE : Resolution.PENDING;
            }
            BookAuthorsClient.Author author = candidate.get();
            UUID owner = ownerFor(player, author);
            if (!AuthorBookPool.isFetched(author.token(), owner != null)) {
                AuthorBookPool.refreshAsync(author.token(), hostLocaleOf(player), kidSafe, owner);
                return Resolution.PENDING;                      // in flight; the next pass has it
            }
            if (AuthorBookPool.booksFor(author.token(), owner != null).isEmpty()) {
                // Counted in the directory, servable to nobody here. Try somebody else.
                reject(key, author.token());
                continue;
            }
            LOCKS.put(key, author);
            remember(author.token());
            LOGGER.info("[DungeonTrain] Portal room {} locked to author '{}' ({} book(s))",
                pairKey, author.name(), author.count());
            return Resolution.of(author);
        }
        // Every attempt found a candidate whose catalogue turned out to be empty. Not "wait" — the
        // room has been through the directory and come out the other side.
        return Resolution.NONE;
    }

    /**
     * Whether every directory page this room would consult has come back.
     *
     * <p>This is what separates "the relay has not answered yet" from "the relay answered and nobody
     * is inside this room's band" — the two look identical at the call site and want opposite
     * things.</p>
     */
    private static boolean directoriesAnswered(ServerPlayer player, PortalRoomBooks.Share share,
                                               PortalRoomBooks books) {
        boolean useSelf = useSelfDirectory(share, consented(player));
        String lang = hostLocaleOf(player);
        boolean selfPage = DIRECTORY.containsKey(
            directoryKey(PortalRoomBooks.Share.SELF.directoryKind(), player.getUUID(), books, lang));
        String kind = share.isSelf()
            ? PortalRoomBooks.Share.PLAYER.directoryKind() : share.directoryKind();
        boolean poolPage = DIRECTORY.containsKey(directoryKey(kind, player.getUUID(), books, lang));
        return answered(useSelf, selfPage, poolPage);
    }

    /**
     * PENDING vs NONE, given which pages this room is actually waiting on.
     *
     * <p>Pure and package-private so the wedge case can be tested without a server. The one that
     * matters: a room whose holder declined network access never asks for a self page, so it must not
     * wait on one — waiting would pin it at {@link Outcome#PENDING} for as long as it stands, and
     * {@link PortalRoomLibrarian} would re-ask every tick and never report the room settled.</p>
     */
    static boolean answered(boolean useSelf, boolean selfPagePresent, boolean poolPagePresent) {
        return (!useSelf || selfPagePresent) && poolPagePresent;
    }

    /**
     * Whether this room should consult the holder's own shelf: the Self share, and only where the
     * holder has granted network access.
     *
     * <p>The self page is the one directory call that names a person — it goes out as {@code &uuid=}
     * (see {@link BookAuthorsClient#fetch}), which is exactly what the network-consent prompt governs.
     * Declined, the room simply rotates into the random-player directory, the same path it already
     * takes for the many players who have written nothing. Pure and package-private for the test.</p>
     */
    static boolean useSelfDirectory(PortalRoomBooks.Share share, boolean consented) {
        return share != null && share.isSelf() && consented;
    }

    /** Has this player's client synced network consent? The self page sends their uuid. */
    private static boolean consented(ServerPlayer player) {
        return NetworkConsentMirror.isGranted(player);
    }

    /**
     * Which of the three ways of naming an author this room came up with.
     *
     * <p><b>Seeded from the pair key, not from chance.</b> The roll has to be stable for the room's
     * life or its shelves would be restocked from a different person, and deriving it rather than
     * remembering it means a relocation, a re-stamp or a restart all land on the same answer — the
     * same "a pair rolls its room ONCE" property {@code ensureStructure} already relies on for which
     * room a pair gets in the first place.</p>
     */
    public static PortalRoomBooks.Share effectiveShare(int pairKey, PortalRoomBooks books) {
        return books == null ? PortalRoomBooks.Share.PLAYER : books.resolveShare(pairKey);
    }

    /**
     * The next author worth trying for {@code key}, or empty when the directory has nothing to offer
     * yet (a fetch is kicked in that case, so a later pickup finds it warm).
     *
     * <p>Under {@link PortalRoomBooks.Share#SELF} the reader's own entry comes first and the
     * random-player directory is the rotation behind it — which is what makes Self work for the many
     * players who have written nothing, rather than leaving their rooms bare. That same rotation is
     * what serves a holder who declined network access: their shelf is never asked for.</p>
     */
    private static Optional<BookAuthorsClient.Author> nextCandidate(ServerPlayer player, Key key,
                                                                   PortalRoomBooks.Share share,
                                                                   PortalRoomBooks books, boolean kidSafe) {
        if (useSelfDirectory(share, consented(player))) {
            // The reader's own shelf is exempt from the room's range: finding YOUR two books in a
            // room is the point of the Self share, and a floor meant for strangers should not veto it.
            Optional<BookAuthorsClient.Author> self =
                pick(PortalRoomBooks.Share.SELF.directoryKind(), key, player, books, kidSafe, false);
            if (self.isPresent()) return self;
        }
        String kind = share.isSelf()
            ? PortalRoomBooks.Share.PLAYER.directoryKind() : share.directoryKind();
        return pick(kind, key, player, books, kidSafe, true);
    }

    /**
     * One untried candidate from the {@code kind} directory, preferring authors this world has not
     * just served. Kicks a fetch and returns empty when the directory is cold or exhausted.
     */
    private static Optional<BookAuthorsClient.Author> pick(String kind, Key key, ServerPlayer player,
                                                           PortalRoomBooks books, boolean kidSafe,
                                                           boolean applyRange) {
        String lang = hostLocaleOf(player);
        String cacheKey = directoryKey(kind, player.getUUID(), books, lang);
        List<BookAuthorsClient.Author> candidates = DIRECTORY.get(cacheKey);
        if (candidates == null) {
            fetchDirectory(kind, cacheKey, player, books, kidSafe, lang);
            return Optional.empty();
        }
        // Belt and braces over the relay's own filter: a cached page outliving an edit to the range
        // must not hand back somebody the room has since stopped accepting.
        if (applyRange) {
            List<BookAuthorsClient.Author> inRange = new ArrayList<>();
            for (BookAuthorsClient.Author a : candidates) {
                if (books.accepts(a.count())) inRange.add(a);
            }
            candidates = inRange;
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

    /**
     * What a directory page is remembered under — the question that produced it, not just its kind.
     *
     * <p>The range is part of it because two rooms asking for different bands of author are asking
     * the relay two different questions, and one answer must not stand in for the other. The host
     * locale is part of it for the same reason: the relay counts an author's books inside that
     * language family, so the same band in two languages is two different pages.</p>
     *
     * <p>The self page is keyed on the holder alone. It is not language-scoped at either end — a
     * writer's own library is their own writing whatever they wrote it in — and
     * {@link #isSelfAuthor} reads that key literally.</p>
     *
     * <p>Built in ONE place because {@link #pick} and {@link #directoriesAnswered} both need it: they
     * used to spell it out separately, and a drift between them would leave a room asking about a page
     * that is never coming — {@link Outcome#PENDING} for as long as it stands.</p>
     */
    static String directoryKey(String kind, UUID holder, PortalRoomBooks books, String lang) {
        if ("self".equals(kind)) return kind + ':' + holder;
        return kind + ':' + books.minBooks() + ':' + books.maxBooks()
            + ':' + (lang == null ? "" : lang);
    }

    /** Pull a directory page for {@code kind}, once at a time. Caches an empty page as "asked, nothing". */
    private static void fetchDirectory(String kind, String cacheKey, ServerPlayer player,
                                       PortalRoomBooks books, boolean kidSafe, String lang) {
        // Unreachable while nextCandidate holds the gate; kept so a later caller cannot send a uuid
        // the player never agreed to. Ahead of the in-flight mark, which nothing would then clear.
        if ("self".equals(kind) && !consented(player)) return;
        if (!DIRECTORY_IN_FLIGHT.add(cacheKey)) return;
        boolean self = "self".equals(kind);
        UUID uuid = self ? player.getUUID() : null;
        // No locale on the self page. The relay ignores it for `self` anyway, and its key does not
        // carry one — sending it would mean one cached page standing for two different questions if
        // that ever stopped being true.
        BookAuthorsClient.fetch(kind, books.minBooks(), books.maxBooks(), uuid, kidSafe,
            self ? null : lang,
            authors -> {
                try {
                    DIRECTORY.put(cacheKey, List.copyOf(authors));
                } finally {
                    DIRECTORY_IN_FLIGHT.remove(cacheKey);
                }
            });
    }

    /**
     * The locale this room's books are chosen for — the HOST's, like every other pool fetch, since one
     * room serves everybody standing in it. Null-safe: a player with no server yet leaves the fetch
     * unfiltered rather than failing it.
     *
     * <p>It reaches BOTH relay calls: the directory (which author this room can be) and the catalogue
     * (which of their books it holds). Only filtering the second would pick a person nobody in the
     * room can read and then hand back an empty shelf.</p>
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
    private static Key keyFor(int pairKey, ServerPlayer player, PortalRoomBooks.Share share) {
        return new Key(pairKey, share.isSelf() ? player.getUUID() : null);
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
    /**
     * The reader's uuid when {@code author} is the reader's OWN entry and they have consented to
     * network features — otherwise {@code null}, meaning "fetch this as anybody else's public shelf".
     *
     * <p>This one value decides three things at once, which is why it is computed in a single place:
     * which query goes to the relay ({@code mine=1&uuid=} vs {@code author=}), which cache key the
     * result lands under (see {@link AuthorBookPool}), and therefore whether withheld books can be in
     * it at all. Deriving them separately is how they would drift apart.</p>
     *
     * <p>Consent is load-bearing, not ceremony: the {@code mine=1} query is the first thing on this
     * path that ever puts a player uuid on the wire. Without consent the room falls back to the
     * author's public shelf, which is exactly what it served before this feature existed.</p>
     */
    public static UUID ownerFor(ServerPlayer player, BookAuthorsClient.Author author) {
        if (player == null || author == null || !author.mine()) return null;
        if (!NetworkConsentMirror.isGranted(player)) return null;
        return player.getUUID();
    }

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
