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
 *       escape).</li>
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
     *                        become eligible again (config {@code sharedBookRepeatCarriages})
     */
    public record PlayerContext(String playerLang,
                                IntPredicate hasRead,
                                IntPredicate wasServed,
                                IntUnaryOperator servedCarriage,
                                int currentCarriage,
                                int repeatCarriages) {}

    /**
     * Choose a book for the player from {@code pool}, or empty if nothing is eligible (e.g. every book is
     * served this life and none qualifies for the far-behind escape). Deterministic for a given
     * {@code (pool, ctx, seed)}.
     */
    public static Optional<PoolBook> select(List<PoolBook> pool, PlayerContext ctx, long seed) {
        if (pool == null || pool.isEmpty()) return Optional.empty();

        // 1. Eligibility — drop books already served this life unless the unread + far-behind escape applies.
        List<PoolBook> eligible = filter(pool, b -> isEligible(b, ctx));
        if (eligible.isEmpty()) return Optional.empty();

        // 2. Language bucket (hard) — {MINE, TRANSLATED} drained before OTHER.
        List<PoolBook> inLanguage = filter(eligible, b -> originOf(b, ctx) != Origin.OTHER);
        List<PoolBook> tier = inLanguage.isEmpty() ? eligible : inLanguage;

        // 3. Unread-first (soft) — prefer unread within the tier, else fall back to the whole tier.
        List<PoolBook> unread = filter(tier, b -> !ctx.hasRead().test(b.id()));
        List<PoolBook> pool3 = unread.isEmpty() ? tier : unread;

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
