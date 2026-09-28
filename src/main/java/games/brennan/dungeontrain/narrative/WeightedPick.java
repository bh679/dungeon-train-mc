package games.brennan.dungeontrain.narrative;

/**
 * Seeded target for a weighted pick over {@code double} weights.
 *
 * <p>The narrative pools all share one shape: sum the candidates' weights, derive a target in
 * {@code [0, total)} from a seed, then walk the candidates in a deterministic order subtracting
 * each weight until the target goes negative. While weights were integers each picker did its own
 * {@code (seed & 0x7FFF…) % total}; fractional weights ({@code 0.1} for a deliberately rare book)
 * make that modulo meaningless, so the seed→target step lives here instead of being re-derived
 * five times.</p>
 *
 * <p>The seed is the only randomness source — same seed and same total always yield the same
 * target, which is what the lectern/chest determinism guarantees rest on.</p>
 */
public final class WeightedPick {

    /**
     * Resolution of the seed→target mapping. A seed is reduced modulo this before being scaled, so
     * the target lands on one of a million evenly spaced points across {@code [0, total)} — far
     * finer than any weight the corpus uses (the smallest is {@code 0.1}).
     */
    private static final long SCALE = 1_000_000L;

    private WeightedPick() {}

    /**
     * A deterministic target in {@code [0, total)} for {@code seed}.
     *
     * @param seed  any long; its sign bit is masked off, so callers need not pre-clean it
     * @param total sum of the candidate weights; must be &gt; 0 (callers check for an empty or
     *              all-zero pool before getting here)
     */
    public static double target(long seed, double total) {
        long unsigned = seed & 0x7FFFFFFFFFFFFFFFL;
        return (double) (unsigned % SCALE) / (double) SCALE * total;
    }
}
