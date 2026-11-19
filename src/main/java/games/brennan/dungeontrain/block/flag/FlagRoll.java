package games.brennan.dungeontrain.block.flag;

import java.util.Random;

/**
 * Which design each numbered flag shows for one room roll.
 *
 * <p>A roll is one {@code long} key. The key shuffles the design list once, and Flag N takes the
 * N-th entry of that shuffle. So every copy of Flag N in the roll matches, a different key gives a
 * fresh pick, and two different flag numbers never share a design (while there are at least as
 * many designs as flag numbers).</p>
 */
public final class FlagRoll {

    /** Keeps flag picks independent of anything else seeded from the same carriage. */
    private static final long SALT = 0x5F1A_6B0B_D7A1_17A1L;
    /** Keeps portal room keys apart from carriage keys with the same number. */
    private static final long ROOM_SALT = 0x2C6F_E1A3_90B4_4D17L;

    private FlagRoll() {}

    /** The roll key for train carriage {@code carriageIndex} of the world with {@code worldSeed}. */
    public static long carriageKey(long worldSeed, int carriageIndex) {
        return mix(worldSeed ^ SALT) ^ mix(carriageIndex * 0x9E37_79B9_7F4A_7C15L + 1);
    }

    /**
     * {@link #carriageKey} for {@code level}'s seed, ready to hand a stage scope that stamps one
     * carriage (or one multi-carriage room keyed by its anchor) outside the carriage's own
     * {@code ProvenanceScope}, so its flags match the rest of that carriage.
     */
    public static java.util.OptionalLong scopeKey(net.minecraft.world.level.WorldGenLevel level, int carriageIndex) {
        return java.util.OptionalLong.of(carriageKey(level.getSeed(), carriageIndex));
    }

    /**
     * The roll key for portal room pair {@code pairKey} of the world with {@code worldSeed}: the room
     * and every copy of it share it, so they show the same flags.
     */
    public static long roomKey(long worldSeed, int pairKey) {
        return mix(worldSeed ^ ROOM_SALT) ^ mix(pairKey * 0xC2B2_AE3D_27D4_EB4FL + 7);
    }

    /**
     * The design index (into a list of {@code designCount}) for flag {@code slot} (1-based) in the
     * roll {@code rollKey}.
     */
    public static int designIndex(long rollKey, int slot, int designCount) {
        if (designCount <= 0) throw new IllegalArgumentException("designCount must be positive");
        if (slot < 1) throw new IllegalArgumentException("slot is 1-based");
        int[] order = shuffled(rollKey, designCount);
        return order[(slot - 1) % designCount];
    }

    /** Fisher–Yates shuffle of {@code 0..count-1}, seeded by the roll key. */
    private static int[] shuffled(long rollKey, int count) {
        int[] order = new int[count];
        for (int i = 0; i < count; i++) order[i] = i;
        Random random = new Random(mix(rollKey ^ SALT));
        for (int i = count - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        return order;
    }

    /** SplitMix64 finaliser: spreads nearby keys (carriage 1, 2, 3…) across the whole range. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D0_49BB_1331_11EBL;
        return z ^ (z >>> 31);
    }
}
