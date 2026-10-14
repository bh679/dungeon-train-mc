package games.brennan.dungeontrain.portal;

import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * How often a dimensional carriage turns out to be a library of the rider's <b>own</b> books, as a
 * weight in the whole room lottery rather than inside one library's share mix.
 *
 * <p>"Own-books library" is an outcome two rolls deep: the pair rolls a room that stocks an author
 * ({@link PortalRoomBooks#locks}), and that room's share comes up {@link PortalRoomBooks.Share#SELF}.
 * Turning the Self weight up or down inside the room would only trade it against the room's other
 * shares — more of your own shelves, exactly as many libraries. What is wanted is the outcome itself
 * made heavier or lighter <i>against every other dimension</i>, with the libraries of other authors
 * and the tally left where they were.</p>
 *
 * <h2>A correction on top of the pick, not a second lottery</h2>
 * <p>The seeded pick ({@code TrackVariantRegistry.pickName}) is untouched. This looks at what it
 * answered and, for a seeded fraction of pairs, changes the answer:</p>
 * <ul>
 *   <li><b>scale below 1</b> — some pairs that came up own-books are sent back to draw again until
 *       they land on anything else, so the mass they give up is shared out in proportion;</li>
 *   <li><b>scale above 1</b> — some pairs that came up anything else become a library, drawn among
 *       the libraries by how much own-books mass each carries, and {@link #selfOnly pinned} to
 *       Self.</li>
 * </ul>
 * <p>At exactly 1 it returns the pick as it found it, so a server that sets both scales to 1 has the
 * lottery it always had. The fractions are chosen so the outcome's <i>weight</i> is multiplied by the
 * scale: mass {@code p} becomes {@code k·p / (1 − p + k·p)}.</p>
 *
 * <p><b>The two directions nest.</b> A pair that is own-books at a low scale is own-books at every
 * higher one — a low scale only ever removes pairs from the set the pick produced, and a high one
 * only ever adds to it. So signing a book never takes a library away.</p>
 *
 * <p>Pure: every input is passed in, so the arithmetic can be tested without a registry.</p>
 */
public final class PortalOwnShelves {

    /** Redraws a demoted pair may take before it keeps the room it had. */
    static final int MAX_REDRAWS = 16;

    /**
     * Offsets a redraw's index past every real pair key. Pair keys are {@code int}s, so anything
     * this far out is an index no pair will ever ask the pick for.
     */
    private static final long REDRAW_STRIDE = 1L << 32;

    private static final double EPSILON = 1.0e-9;

    private PortalOwnShelves() {}

    /**
     * What a pair rolls once the scale has been applied.
     *
     * @param roomName     the room to stamp
     * @param pinnedToSelf true when the room was chosen <i>as</i> an own-books library, so its share
     *                     must come up Self whatever the pair key would have rolled
     */
    public record Outcome(String roomName, boolean pinnedToSelf) {}

    /**
     * {@code baseline} with the own-books outcome's weight multiplied by {@code scale}.
     *
     * @param worldSeed the world's seed — with {@code pairKey}, what makes the answer repeatable
     * @param pairKey   the pair being planned
     * @param baseline  what the seeded pick answered for this pair
     * @param leafOdds  every room the pick could have answered, with its probability; only asked
     *                  for when a pair is actually up for correction, since it walks the whole pool
     * @param booksOf   a room's authored Books setting
     * @param pickAt    the seeded pick at another index, for a pair sent back to draw again
     * @param scale     the multiplier on the own-books outcome's weight
     */
    public static Outcome adjust(long worldSeed, int pairKey, String baseline,
                                 Supplier<Map<String, Double>> leafOdds,
                                 Function<String, PortalRoomBooks> booksOf,
                                 LongFunction<String> pickAt, double scale) {
        Outcome unchanged = new Outcome(baseline, false);
        if (Math.abs(scale - 1.0) < EPSILON) return unchanged;

        boolean own = isOwn(booksOf.apply(baseline), pairKey);
        boolean lowering = scale < 1.0;
        // Each direction only ever touches one side of the pick, which is what makes them nest —
        // and what lets the common case return before the pool is walked at all.
        if (lowering != own) return unchanged;

        Map<String, Double> odds = leafOdds.get();
        double mass = ownMass(odds, booksOf);
        if (mass <= EPSILON || mass >= 1.0 - EPSILON) return unchanged;

        double target = scale * mass / (1.0 - mass + scale * mass);
        Random rng = new Random(mix(worldSeed, pairKey));
        double roll = rng.nextDouble();

        if (own) {
            if (roll < target / mass) return unchanged;
            return redrawn(pairKey, booksOf, pickAt, unchanged);
        }
        if (roll >= (target - mass) / (1.0 - mass)) return unchanged;
        String library = drawLibrary(odds, booksOf, rng.nextDouble() * mass);
        return library == null ? unchanged : new Outcome(library, true);
    }

    /** The first redraw that is not an own-books library, or {@code fallback} when none turns up. */
    private static Outcome redrawn(int pairKey, Function<String, PortalRoomBooks> booksOf,
                                   LongFunction<String> pickAt, Outcome fallback) {
        for (int attempt = 1; attempt <= MAX_REDRAWS; attempt++) {
            String name = pickAt.apply(pairKey + attempt * REDRAW_STRIDE);
            // Against the REAL pair key: that is what the standing room's share is rolled from.
            if (name != null && !isOwn(booksOf.apply(name), pairKey)) return new Outcome(name, false);
        }
        return fallback;
    }

    /** The library standing at {@code point} along the pool's own-books mass, or null for none. */
    private static String drawLibrary(Map<String, Double> odds,
                                      Function<String, PortalRoomBooks> booksOf, double point) {
        String last = null;
        double cumulative = 0.0;
        for (Map.Entry<String, Double> leaf : odds.entrySet()) {
            double share = leaf.getValue() * selfFraction(booksOf.apply(leaf.getKey()));
            if (share <= 0.0) continue;
            cumulative += share;
            last = leaf.getKey();
            if (point < cumulative) return last;
        }
        return last;    // rounding left the point a hair past the end
    }

    /** How much of the pool is an own-books library: each room's odds times its Self share. */
    static double ownMass(Map<String, Double> odds, Function<String, PortalRoomBooks> booksOf) {
        double mass = 0.0;
        for (Map.Entry<String, Double> leaf : odds.entrySet()) {
            mass += leaf.getValue() * selfFraction(booksOf.apply(leaf.getKey()));
        }
        return mass;
    }

    /**
     * The chance {@code books} rolls Self — 0 for a room that stocks no author.
     *
     * <p>All-zero weights read as an even four-way roll, because that is what
     * {@link PortalRoomBooks#resolveShare} does with them.</p>
     */
    static double selfFraction(PortalRoomBooks books) {
        if (books == null || !books.locks()) return 0.0;
        int total = books.selfWeight() + books.playerWeight()
            + books.signatureWeight() + books.statsWeight();
        if (total <= 0) return 1.0 / PortalRoomBooks.Share.values().length;
        return (double) books.selfWeight() / total;
    }

    /** True when a room with these books, standing at {@code pairKey}, holds the rider's own. */
    static boolean isOwn(PortalRoomBooks books, int pairKey) {
        return books != null && books.locks() && books.resolveShare(pairKey).isSelf();
    }

    /**
     * {@code books} with the roll taken out of it: Self, every time.
     *
     * <p>Carried on the standing room's settings rather than remembered beside them, so everything
     * that already asks the room what it stocks — the librarian, the lock, the greeting — gets the
     * pinned answer without knowing there was one.</p>
     */
    public static PortalRoomBooks selfOnly(PortalRoomBooks books) {
        return books.only(PortalRoomBooks.Share.SELF);
    }

    /** Splittable-mix, salted so this roll does not track the room pick's or the share's. */
    private static long mix(long worldSeed, int pairKey) {
        long state = worldSeed ^ (pairKey * 0x9E3779B97F4A7C15L) ^ 0x4F574E5348454C46L; // "OWNSHELF"
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        return state ^ (state >>> 31);
    }
}
