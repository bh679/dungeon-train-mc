package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.narrative.SharedBookPool.PoolBook;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

/**
 * Per-player selection of a community (player-written) book from the {@link SharedBookPool} snapshot,
 * replacing the uniform {@link SharedBookPool#rollShared} pick with a priority chain so the book a player
 * receives feels curated to them. Applied when a pending loot placeholder is resolved into a player's hand
 * (see {@code NarrativeBookEvents}); the world-level "does a shared book appear at all" taper is unchanged.
 *
 * <h3>Priority chain (design model A)</h3>
 * <ol>
 *   <li><b>Eligible</b> — a book already served this life is skipped, UNLESS it is still unread AND its
 *       serve-carriage has scrolled at least {@code repeatCarriages} behind the player (the "unloaded"
 *       escape). If that leaves NOTHING, the dedup is relaxed and the whole pool is reconsidered — see
 *       "Exhaustion" below.</li>
 *   <li><b>Language bucket</b> (hard) — books available in the player's language ({@link Origin#MINE} +
 *       {@link Origin#TRANSLATED}) are fully drained before any {@link Origin#OTHER} book.</li>
 *   <li><b>Unread-first</b> (soft) — within the chosen bucket, prefer books the player hasn't read.</li>
 *   <li><b>Weight tier</b> (hard) — keep only the top {@code effectiveWeight} group, where
 *       {@code effectiveWeight = weight - (origin==TRANSLATED ? 1 : 0)}.</li>
 *   <li><b>Random</b> — seeded tiebreak within the surviving group.</li>
 * </ol>
 *
 * <h3>Division of labour with the relay</h3>
 * <p>The relay already tiers by curation weight across the WHOLE approved pool and returns only its
 * highest tier, so within one snapshot {@code weight} is normally uniform and step 4 degenerates to
 * exactly "a native book edges out a translated one" — which is precisely the translated −1 rule. The
 * relay also family-scopes the pool, so {@link Origin#OTHER} books normally only appear via its English
 * fallback. What the relay cannot do is the PER-PLAYER part — dedup, unread, and the far-behind escape —
 * which is why those live here.</p>
 *
 * <h3>Exhaustion — when a life has seen everything</h3>
 * <p>The no-duplicates-per-life rule is a PREFERENCE, not a hard gate. When every servable book has
 * already been served this life, the dedup is relaxed and the full pool is reconsidered rather than
 * returning nothing. This matters because the caller bakes a built-in mod book as the placeholder: a
 * "no pick" answer does not mean "no book", it means the player silently gets MOD content out of a slot
 * whose contract is PLAYER writing. Repeating a community book is the better failure. Consequently
 * {@link #select} returns empty only for a genuinely empty pool — i.e. the relay is unreachable or has
 * no approved books, the two cases where a built-in fallback is actually correct.</p>
 *
 * <p>Pure and side-effect-free: the caller supplies the player's state, so this is unit-testable without
 * a live server. Recording a pick as "served" is the caller's job.</p>
 */
public final class SharedBookSelector {

    private SharedBookSelector() {}

    /**
     * A book's language relationship to one particular player, derived at selection time from the book's
     * stored {@code lang} and that player's locale — NOT a stored field, because a single shared pool
     * snapshot serves every player and the answer differs per player.
     */
    public enum Origin {
        /** Authored in the player's language family. */
        MINE,
        /**
         * Authored in another language but available translated into the player's.
         *
         * <p><b>Currently dormant:</b> the relay stores a translation for some books but does not expose
         * translation availability on {@code /books/pool}, so {@link #originOf} never returns this today.
         * The −1 weight penalty and the bucket placement are implemented and unit-tested, ready for when
         * the relay surfaces it.</p>
         */
        TRANSLATED,
        /** Neither authored in nor translated into the player's language — last-resort fallback. */
        OTHER
    }

    /**
     * The per-player facts the chain needs, passed in so selection stays pure and testable.
     *
     * @param playerLang      the player's raw client locale (e.g. {@code "en_us"}); blank/null is treated
     *                        as English by {@link LanguageFamily}
     * @param hasRead         whether the player has ever read the book with this relay id (across lives)
     * @param wasServed       whether the player has already been served this id this life
     * @param servedCarriage  for an already-served id, the signed carriage index it was served at
     * @param currentCarriage the player's current signed travelled-carriage index
     * @param repeatCarriages how far behind {@code servedCarriage} must be for a served-but-unread book to
     *                        become eligible again, in CARRIAGES. Resolved by
     *                        {@code DungeonTrainConfig.getSharedBookRepeatCarriages()} from the
     *                        group-based {@code sharedBookRepeatGroups} setting (groups × groupSize), so
     *                        the threshold is "one whole carriage group behind" by default.
     * @param kidMode         whether THIS player is in Kid mode. Per-player rather than per-world on
     *                        purpose: the pool snapshot is shared by everyone on a server, so a child
     *                        playing on an adult's world is protected here rather than at fetch time.
     * @param politicalFilter whether this player asked not to be served content the relay tagged as
     *                        politically sensitive (mirrored server-side from their client — see
     *                        {@link games.brennan.dungeontrain.event.PoliticalFilterMirror}). Per
     *                        player for the same reason {@code kidMode} is.
     * @param isOwn           whether a book was written by THIS player. Drives the English mix-in only
     *                        (see {@link #select(List, List, PlayerContext, long)}); own books are
     *                        otherwise ordinary candidates.
     */
    public record PlayerContext(String playerLang,
                                IntPredicate hasRead,
                                IntPredicate wasServed,
                                IntUnaryOperator servedCarriage,
                                int currentCarriage,
                                int repeatCarriages,
                                boolean kidMode,
                                boolean politicalFilter,
                                Predicate<PoolBook> isOwn) {

        /** Context with no own-book knowledge — every book counts as somebody else's. */
        public PlayerContext(String playerLang,
                             IntPredicate hasRead,
                             IntPredicate wasServed,
                             IntUnaryOperator servedCarriage,
                             int currentCarriage,
                             int repeatCarriages,
                             boolean kidMode,
                             boolean politicalFilter) {
            this(playerLang, hasRead, wasServed, servedCarriage, currentCarriage, repeatCarriages,
                    kidMode, politicalFilter, b -> false);
        }

        /** Unfiltered context — the shape every call site had before either content filter existed. */
        public PlayerContext(String playerLang,
                             IntPredicate hasRead,
                             IntPredicate wasServed,
                             IntUnaryOperator servedCarriage,
                             int currentCarriage,
                             int repeatCarriages) {
            this(playerLang, hasRead, wasServed, servedCarriage, currentCarriage, repeatCarriages, false, false);
        }
    }

    /**
     * Choose a book for the player from {@code pool}, or empty if nothing is eligible (e.g. every book is
     * served this life and none qualifies for the far-behind escape). Deterministic for a given
     * {@code (pool, ctx, seed)}.
     */
    public static Optional<PoolBook> select(List<PoolBook> pool, PlayerContext ctx, long seed) {
        return select(pool, List.of(), ctx, seed);
    }

    /**
     * {@link #select(List, PlayerContext, long)} with an English window to mix in.
     *
     * <h3>English mix-in</h3>
     * <p>For a non-English player, once no unread in-language book by ANYONE ELSE is left, the unread
     * books remaining in their language are all ones they wrote. Their own books are not demoted — but
     * from that point the pick is a seeded coin between {their unread own books} and {unread English
     * books from {@code englishPool}}, so a prolific writer in a small language stops being handed only
     * their own work. Either side empty → the other. No unread English → the ordinary chain below,
     * unchanged. It switches itself off as soon as an unread book by another in-language writer arrives.</p>
     */
    public static Optional<PoolBook> select(List<PoolBook> pool, List<PoolBook> englishPool,
                                            PlayerContext ctx, long seed) {
        if (pool == null || pool.isEmpty()) return Optional.empty();

        // 0. The two hard per-player content filters, BEFORE anything else. Applied to the input pool
        // rather than as one more preference tier, because every later step is allowed to relax back
        // toward the wider set it was given — step 1's exhaustion fallback reconsiders `pool` wholesale,
        // which would hand back the very books this player asked not to see. Filtering here means they
        // are never in the set any fallback can reach. An empty result is a legitimate answer: the
        // caller falls back to a built-in book, which is the right outcome for both filters — better mod
        // content than an unjudged stranger's writing, or content the player opted out of.
        pool = contentFiltered(pool, ctx);
        if (pool.isEmpty()) return Optional.empty();

        // 1. Eligibility — drop books already served this life unless the unread + far-behind escape applies.
        List<PoolBook> eligible = eligibleOrAll(pool, ctx);

        List<PoolBook> pool3;
        List<PoolBook> english = servableEnglish(pool, englishPool, ctx);
        if (needsMix(eligible, ctx) && !english.isEmpty()) {
            // English mix-in — see the method javadoc.
            List<PoolBook> ownUnread = filter(eligible, b -> originOf(b, ctx) != Origin.OTHER
                    && ctx.isOwn().test(b) && !ctx.hasRead().test(b.id()));
            boolean takeEnglish = ownUnread.isEmpty() || (mix(seed ^ COIN_SALT) & 1L) == 0L;
            pool3 = takeEnglish ? english : ownUnread;
        } else {
            // 2. Language bucket (hard) — {MINE, TRANSLATED} drained before OTHER.
            List<PoolBook> inLanguage = filter(eligible, b -> originOf(b, ctx) != Origin.OTHER);
            List<PoolBook> tier = inLanguage.isEmpty() ? eligible : inLanguage;

            // 3. Unread-first (soft) — prefer unread within the tier, else fall back to the whole tier.
            List<PoolBook> unread = filter(tier, b -> !ctx.hasRead().test(b.id()));
            pool3 = unread.isEmpty() ? tier : unread;
        }

        // 4. Weight tier (hard) — keep only the top effectiveWeight group.
        int topWeight = Integer.MIN_VALUE;
        for (PoolBook b : pool3) topWeight = Math.max(topWeight, effectiveWeight(b, ctx));
        final int top = topWeight;
        List<PoolBook> weightPool = filter(pool3, b -> effectiveWeight(b, ctx) == top);

        // 5. Seeded random tiebreak.
        int index = (int) Long.remainderUnsigned(mix(seed), weightPool.size());
        return Optional.of(weightPool.get(index));
    }

    /**
     * This book's language relationship to the player: same family → {@link Origin#MINE}, else
     * {@link Origin#OTHER}. {@link Origin#TRANSLATED} is not yet reachable — see its javadoc.
     */
    static Origin originOf(PoolBook book, PlayerContext ctx) {
        return LanguageFamily.sameFamily(book.lang(), ctx.playerLang()) ? Origin.MINE : Origin.OTHER;
    }

    /** {@code weight}, docked 1 for a translated book so a native book of equal raw weight ranks above it. */
    static int effectiveWeight(PoolBook b, PlayerContext ctx) {
        return b.weight() - (originOf(b, ctx) == Origin.TRANSLATED ? 1 : 0);
    }

    /**
     * Authorship by display name, case-insensitive; a blank name or author never matches. The sign-book
     * mixin submits the signer's name as the author and names are unique, so a match is authorship.
     */
    public static Predicate<PoolBook> isOwnBy(String playerName) {
        if (playerName == null || playerName.isBlank()) return b -> false;
        return b -> b.author() != null && b.author().equalsIgnoreCase(playerName);
    }

    /** Salt for the own-vs-English coin, so it is independent of the index pick drawn from the same seed. */
    private static final long COIN_SALT = 0x454e474c495348L; // "ENGLISH"

    /**
     * Whether a pickup by this player has reached the English mix-in point: non-English player, and no
     * unread in-language book by anyone else left in the (filtered) pool. The caller uses it to fetch
     * the English window lazily — {@code englishPool} is the window it already has; true is returned
     * only when that window has nothing servable left, i.e. when a fetch would actually help.
     */
    public static boolean needsEnglishFallback(List<PoolBook> pool, List<PoolBook> englishPool, PlayerContext ctx) {
        if (pool == null || pool.isEmpty()) return false;
        List<PoolBook> filtered = contentFiltered(pool, ctx);
        if (filtered.isEmpty()) return false;
        return needsMix(eligibleOrAll(filtered, ctx), ctx) && servableEnglish(filtered, englishPool, ctx).isEmpty();
    }

    /** Kid / political hard filters (no-ops when the player has neither on). */
    private static List<PoolBook> contentFiltered(List<PoolBook> in, PlayerContext ctx) {
        List<PoolBook> out = in;
        if (ctx.kidMode()) out = filter(out, PoolBook::kidSafe);
        if (ctx.politicalFilter()) out = filter(out, b -> !b.political());
        return out;
    }

    /**
     * Books not yet served this life (or past the far-behind escape). RELAXATION: no-duplicates-per-life
     * is a PREFERENCE, not a hard gate. Once a life has seen the whole servable pool, honouring it
     * strictly would leave the caller with nothing and a random_playerbook slot would silently degrade
     * to a built-in book — but that slot's contract is to serve PLAYER writing. Re-showing a community
     * book the player already got beats handing them mod content, so on exhaustion the entire pool is
     * reconsidered. The built-in fallback is then reserved for the only cases that truly warrant it:
     * the relay being unreachable or having no books at all, which the caller checks first.
     */
    private static List<PoolBook> eligibleOrAll(List<PoolBook> pool, PlayerContext ctx) {
        List<PoolBook> eligible = filter(pool, b -> isEligible(b, ctx));
        return eligible.isEmpty() ? pool : eligible;
    }

    /** Non-English player with no unread in-language book by anybody else left in {@code eligible}. */
    private static boolean needsMix(List<PoolBook> eligible, PlayerContext ctx) {
        if (LanguageFamily.sameFamily(ctx.playerLang(), LanguageFamily.DEFAULT_FAMILY)) return false;
        for (PoolBook b : eligible) {
            if (originOf(b, ctx) != Origin.OTHER && !ctx.isOwn().test(b) && !ctx.hasRead().test(b.id())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Unread English books from {@code englishPool} this player may be served: same hard content
     * filters and per-life eligibility as the main pool, not their own, and not already in the main
     * pool (an English-host fallback can put the same ids in both).
     */
    private static List<PoolBook> servableEnglish(List<PoolBook> pool, List<PoolBook> englishPool, PlayerContext ctx) {
        if (englishPool == null || englishPool.isEmpty()) return List.of();
        java.util.Set<Integer> inMain = new java.util.HashSet<>();
        for (PoolBook b : pool) inMain.add(b.id());
        return filter(contentFiltered(englishPool, ctx), b -> !inMain.contains(b.id())
                && LanguageFamily.sameFamily(b.lang(), LanguageFamily.DEFAULT_FAMILY)
                && !ctx.isOwn().test(b)
                && !ctx.hasRead().test(b.id())
                && isEligible(b, ctx));
    }

    /** A book is eligible unless served this life — then only if still unread AND its carriage scrolled far behind. */
    private static boolean isEligible(PoolBook b, PlayerContext ctx) {
        if (!ctx.wasServed().test(b.id())) return true;
        if (ctx.hasRead().test(b.id())) return false;
        int behind = ctx.currentCarriage() - ctx.servedCarriage().applyAsInt(b.id());
        return behind >= ctx.repeatCarriages();
    }

    private static List<PoolBook> filter(List<PoolBook> in, Predicate<PoolBook> keep) {
        List<PoolBook> out = new ArrayList<>();
        for (PoolBook b : in) {
            if (keep.test(b)) out.add(b);
        }
        return out;
    }

    /** Splittable-mix so a raw seed spreads uniformly across the surviving group index (matches SharedBookPool). */
    private static long mix(long seed) {
        long state = seed ^ 0x53454c454354L; // "SELECT"
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        state = state ^ (state >>> 31);
        return state;
    }
}
