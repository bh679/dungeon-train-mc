package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.minecraft.server.level.ServerLevel;

/**
 * Decides which carriages along the train are portal carriages.
 *
 * <p>Two knobs, and they answer different questions. {@code carriageEvery} is the <b>spacing</b>
 * between a pair's entry and exit corridor — the lattice a portal carriage can land on at all, since
 * consecutive lattice slots alternate ENTRY/EXIT ({@link PortalCarriageRole}). {@code oneInGroups}
 * is the <b>rarity</b>: of the candidate pairs that lattice offers, only about one per that many
 * carriage groups is accepted. Both persist per world on {@link PortalRegistry} and are tunable live
 * via {@code /dungeontrain portal carriage <n|off>} and {@code /dungeontrain portal rarity <n>}.</p>
 *
 * <p><b>Random, but not re-rolled.</b> Acceptance is a hash of the world seed and the pair's ordinal
 * rather than a draw from an RNG, so the spacing looks irregular while the verdict stays a pure
 * function of the carriage index. That is not a stylistic preference: a carriage's blocks are
 * re-stamped whenever the rolling window brings it back round, so an answer that could change would
 * let a corridor turn into an ordinary carriage under a player standing in it, and would strand a
 * player who saved inside one. Same reason it must not vary per player.</p>
 *
 * <p>The gate is applied to the <i>pair</i>, not the carriage: an ENTRY and its EXIT share one pair
 * ordinal, so they are accepted or rejected together and no caller ever sees half a pair.</p>
 */
public final class PortalCarriageSelection {

    /** Carriages between a pair's entry and exit corridor. */
    public static final int DEFAULT_CARRIAGE_EVERY = 4;

    /** Value meaning "no carriage is a portal". */
    public static final int CARRIAGE_EVERY_OFF = 0;

    /** On average one portal pair per this many carriage groups. */
    public static final int DEFAULT_ONE_IN_GROUPS = 20;

    /** Splitmix64 salt, so this hash can't correlate with another seed-derived pick. */
    private static final long PORTAL_SALT = 0x504F5254414CL; // "PORTAL"

    private PortalCarriageSelection() {}

    /**
     * Whether the carriage at this index should be stamped as a portal corridor, reading the
     * per-world settings off {@link PortalRegistry} and the group size off
     * {@link DungeonTrainConfig}.
     *
     * <p>Group size is a config value rather than a per-world one, so changing it shifts which pairs
     * are accepted. That is fine in practice — changing group size restructures the train anyway —
     * but it does mean the setting is part of the identity of a portal layout.</p>
     */
    public static boolean isPortalCarriage(ServerLevel level, int carriageIndex) {
        PortalRegistry registry = PortalRegistry.get(level);
        return isPortalCarriage(level.getSeed(), carriageIndex,
            registry.carriageEvery(), registry.oneInGroups(), DungeonTrainConfig.getGroupSize());
    }

    /**
     * The decision as a pure function, for testing and for callers that already hold the settings.
     *
     * <p>Uses {@link Math#floorMod} and {@link Math#floorDiv} throughout because carriage indices go
     * negative when the train extends backwards, and {@code %} / {@code /} truncate toward zero —
     * which would make the spacing asymmetric about the origin and repeat a role either side of it.</p>
     */
    public static boolean isPortalCarriage(long worldSeed, int carriageIndex,
                                           int every, int oneInGroups, int groupSize) {
        if (every <= CARRIAGE_EVERY_OFF) return false;
        if (Math.floorMod(carriageIndex, every) != 0) return false;

        // ENTRY sits on an even lattice ordinal and its EXIT on the next one, so integer-halving the
        // ordinal gives both members of a pair the same key to be judged by.
        int ordinal = Math.floorDiv(carriageIndex, every);
        int pairOrdinal = Math.floorDiv(ordinal, 2);
        return isPairSelected(worldSeed, pairOrdinal, every, oneInGroups, groupSize);
    }

    /**
     * Whether the candidate pair at {@code pairOrdinal} is one of the accepted ones.
     *
     * <p>Candidate pairs sit {@code 2 × every} carriages apart, so one candidate covers
     * {@code 2 × every / groupSize} groups. Accepting them with probability
     * {@code (2 × every) / (oneInGroups × groupSize)} therefore lands one accepted pair per
     * {@code oneInGroups} groups on average. Kept as an exact integer ratio — a float threshold
     * would put rounding between a world and whether a corridor exists.</p>
     *
     * <p>A ratio of 1 or more means every candidate is accepted, which is the un-thinned lattice:
     * {@code rarity 1} is the dev setting that restores the old dense layout.</p>
     */
    private static boolean isPairSelected(long worldSeed, int pairOrdinal,
                                          int every, int oneInGroups, int groupSize) {
        long numerator = 2L * every;
        long denominator = (long) Math.max(1, oneInGroups) * Math.max(1, groupSize);
        if (numerator >= denominator) return true;

        return Math.floorMod(hash(worldSeed, pairOrdinal), denominator) < numerator;
    }

    /** Splitmix64 finaliser over the seed and the pair ordinal — the idiom used for stairs anchors. */
    private static long hash(long worldSeed, int pairOrdinal) {
        long h = worldSeed ^ PORTAL_SALT ^ (pairOrdinal * 0x9E3779B97F4A7C15L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
