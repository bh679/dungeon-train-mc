package games.brennan.dungeontrain.train;

import java.util.Random;

/**
 * The deterministic "what fills this shared-carriage slot" decision, kept separate from
 * {@link TrainAssembler} so it can be reasoned about (and unit-tested) without a level, a relay or a
 * physics engine.
 *
 * <p>One roll picks one of three buckets — a relay build by anyone ({@link Bucket#POOL}), a relay
 * build by a player in this world ({@link Bucket#OWN}), or a fresh unbuilt template
 * ({@link Bucket#FRESH}). The roll is seeded from the world generation seed and the carriage index,
 * so walking back over the same stretch of track re-decides identically.</p>
 *
 * <p>The bucket is an intent, not a guarantee: the spawn path may find nothing buffered to serve it
 * with and fall back (see {@code TrainAssembler.tryLeaseShared}). {@link Bucket#FRESH} is the one
 * bucket that is always honoured, which is what keeps genuinely new canvases entering the pool.</p>
 */
public final class SharedCarriageRolls {

    /** Salt for the slot-content roll (distinct from the variant stream). "SHARPOOL". */
    private static final long SHARED_POOL_SALT = 0x53484152504F4F4CL;

    /** What a shared slot should try to place. */
    public enum Bucket { POOL, OWN, FRESH }

    private SharedCarriageRolls() {}

    /**
     * Pick this slot's bucket. {@code poolChance} and {@code ownChance} are read from config and are
     * expected to be clamped so they sum to at most 1; the remainder is the FRESH share.
     */
    public static Bucket bucket(long generationSeed, int carriagePIdx, double poolChance, double ownChance) {
        double roll = roll(generationSeed, carriagePIdx);
        if (roll < poolChance) return Bucket.POOL;
        if (roll < poolChance + ownChance) return Bucket.OWN;
        return Bucket.FRESH;
    }

    /** The raw [0,1) roll for a slot — exposed so tests can assert the bucket boundaries directly. */
    public static double roll(long generationSeed, int carriagePIdx) {
        long mixed = generationSeed ^ ((long) carriagePIdx * 0x9E3779B97F4A7C15L) ^ SHARED_POOL_SALT;
        return new Random(mixed).nextDouble();
    }
}
