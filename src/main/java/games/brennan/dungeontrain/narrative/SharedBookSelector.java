package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.narrative.SharedBookPool.Origin;
import games.brennan.dungeontrain.narrative.SharedBookPool.PoolBook;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;

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
 *   <li><b>Unread-first</b> (soft) — within the chosen bucket, prefer books the player hasn't read; fall
 *       back to read books only if every candidate is read.</li>
 *   <li><b>Weight tier</b> (hard) — keep only the top {@code effectiveWeight} group, where
 *       {@code effectiveWeight = weight - (origin==TRANSLATED ? 1 : 0)} so a native book edges out a
 *       translated one of equal raw weight.</li>
 *   <li><b>Random</b> — seeded tiebreak within the surviving group.</li>
 * </ol>
 *
 * <p>Pure and side-effect-free: the caller supplies the player's state as predicates/values, so this is
 * unit-testable without a live server. Recording a pick as "served" is the caller's job.</p>
 */
public final class SharedBookSelector {

    private SharedBookSelector() {}

    /**
     * The per-player facts the chain needs, passed in so selection stays pure and testable.
     *
     * @param hasRead      whether the player has ever read the community book with this relay id
     *                     (persistent, across lives)
     * @param wasServed    whether the player has already been served this id this life
     * @param servedCarriage for an already-served id, the signed carriage index it was served at; ignored
     *                       when {@code wasServed} is false
     * @param currentCarriage the player's current signed travelled-carriage index
     * @param repeatCarriages how far behind {@code servedCarriage} must be for a served-but-unread book to
     *                        become eligible again (config {@code sharedBookRepeatCarriages})
     */
    public record PlayerContext(IntPredicate hasRead,
                                IntPredicate wasServed,
                                java.util.function.IntUnaryOperator servedCarriage,
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
        List<PoolBook> eligible = new ArrayList<>();
        for (PoolBook b : pool) {
            if (isEligible(b, ctx)) eligible.add(b);
        }
        if (eligible.isEmpty()) return Optional.empty();

        // 2. Language bucket (hard) — {MINE, TRANSLATED} drained before OTHER.
        List<PoolBook> inLanguage = filter(eligible, b -> b.origin() != Origin.OTHER);
        List<PoolBook> tier = inLanguage.isEmpty() ? eligible : inLanguage;

        // 3. Unread-first (soft) — prefer unread within the tier, else fall back to the whole tier.
        List<PoolBook> unread = filter(tier, b -> !ctx.hasRead().test(b.id()));
        List<PoolBook> pool3 = unread.isEmpty() ? tier : unread;

        // 4. Weight tier (hard) — keep only the top effectiveWeight group.
        int topWeight = Integer.MIN_VALUE;
        for (PoolBook b : pool3) topWeight = Math.max(topWeight, effectiveWeight(b));
        final int top = topWeight;
        List<PoolBook> weightPool = filter(pool3, b -> effectiveWeight(b) == top);

        // 5. Seeded random tiebreak.
        int index = (int) Long.remainderUnsigned(mix(seed), weightPool.size());
        return Optional.of(weightPool.get(index));
    }

    /** A book is eligible unless served this life — then only if still unread AND its carriage scrolled far behind. */
    private static boolean isEligible(PoolBook b, PlayerContext ctx) {
        if (!ctx.wasServed().test(b.id())) return true;
        boolean unread = !ctx.hasRead().test(b.id());
        if (!unread) return false;
        int behind = ctx.currentCarriage() - ctx.servedCarriage().applyAsInt(b.id());
        return behind >= ctx.repeatCarriages();
    }

    /** {@code weight}, docked 1 for a translated book so a native book of equal raw weight ranks above it. */
    static int effectiveWeight(PoolBook b) {
        return b.weight() - (b.origin() == Origin.TRANSLATED ? 1 : 0);
    }

    private static List<PoolBook> filter(List<PoolBook> in, java.util.function.Predicate<PoolBook> keep) {
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
