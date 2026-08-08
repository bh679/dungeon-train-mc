package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.minecraft.server.level.ServerLevel;

/**
 * Decides which carriages along the train are portal carriages.
 *
 * <p>The unit is the <b>carriage group</b>, not the carriage. About one group in
 * {@code oneInGroups} is a portal group, and in one of those the first carriage is the ENTRY
 * corridor and the last is the EXIT ({@link PortalCarriageRole}) — so a portal is always contained
 * in a single group, which is a single Sable sub-level. That containment is why the group is the
 * unit: the pair's pocket room is anchored to the entry carriage, and an exit in a neighbouring
 * group would drift away from it. Persisted per world on {@link PortalRegistry} and tunable live via
 * {@code /dungeontrain portal carriage <on|off>} and {@code /dungeontrain portal rarity <n>}.</p>
 *
 * <p><b>Random, but not re-rolled.</b> A group is chosen by a hash of the world seed and the group
 * index rather than a draw from an RNG, so the spacing looks irregular while the verdict stays a
 * pure function of the carriage index. That is not a stylistic preference: a carriage's blocks are
 * re-stamped whenever the rolling window brings it back round, so an answer that could change would
 * let a corridor turn into an ordinary carriage under a player standing in it, and would strand a
 * player who saved inside one. Same reason it must not vary per player.</p>
 *
 * <p>Because both roles are derived from one group index, a pair can never be half-selected and can
 * never straddle a group boundary — neither is a case to be checked for, there is no way to express
 * it.</p>
 */
public final class PortalCarriageSelection {

    /** On average one carriage group in this many is a portal group. */
    public static final int DEFAULT_ONE_IN_GROUPS = 20;

    /**
     * Smallest group that can hold a portal. At size 1 the first and last carriage are the same one,
     * so the ENTRY and EXIT would collide — there is nowhere for the pair to go, and no portals are
     * placed at all.
     */
    public static final int MIN_GROUP_SIZE = 2;

    /** Splitmix64 salt, so this hash can't correlate with another seed-derived pick. */
    private static final long PORTAL_SALT = 0x504F5254414CL; // "PORTAL"

    private PortalCarriageSelection() {}

    /**
     * Whether the carriage at this index should be stamped as a portal corridor, reading the
     * per-world settings off {@link PortalRegistry} and the group size off
     * {@link DungeonTrainConfig}.
     *
     * <p>Group size is a config value rather than a per-world one, so changing it moves which
     * carriages are portals. That is fine in practice — changing group size restructures the train
     * anyway — but it does mean the setting is part of the identity of a portal layout.</p>
     */
    public static boolean isPortalCarriage(ServerLevel level, int carriageIndex) {
        PortalRegistry registry = PortalRegistry.get(level);
        return isPortalCarriage(level.getSeed(), carriageIndex,
            registry.carriagesEnabled(), registry.oneInGroups(), DungeonTrainConfig.getGroupSize());
    }

    /**
     * The decision as a pure function, for testing and for callers that already hold the settings.
     *
     * <p>Uses {@link Math#floorMod} and {@link Math#floorDiv} because carriage indices go negative
     * when the train extends backwards, and {@code %} / {@code /} keep the sign of the left operand
     * — which would put slot 0 in the wrong place, and make two groups either side of the origin
     * share an index.</p>
     */
    public static boolean isPortalCarriage(long worldSeed, int carriageIndex,
                                           boolean enabled, int oneInGroups, int groupSize) {
        if (!enabled) return false;
        if (groupSize < MIN_GROUP_SIZE) return false;

        // Only the group's first and last carriage carry a corridor; the ones between are ordinary
        // train that the player skips by walking through the room.
        int slot = Math.floorMod(carriageIndex, groupSize);
        if (slot != 0 && slot != groupSize - 1) return false;

        return isGroupSelected(worldSeed, Math.floorDiv(carriageIndex, groupSize), oneInGroups);
    }

    /**
     * Whether this group is one of the portal groups: one in {@code oneInGroups}, on average.
     *
     * <p>A plain 1-in-N over group indices, because the thing being selected and the thing being
     * counted are now the same unit. {@code oneInGroups <= 1} selects every group — the dense layout
     * {@code /dungeontrain portal rarity 1} exists to give back for testing.</p>
     */
    private static boolean isGroupSelected(long worldSeed, int groupIndex, int oneInGroups) {
        if (oneInGroups <= 1) return true;
        return Math.floorMod(hash(worldSeed, groupIndex), oneInGroups) == 0;
    }

    /** Splitmix64 finaliser over the seed and the group index — the idiom used for stairs anchors. */
    private static long hash(long worldSeed, int groupIndex) {
        long h = worldSeed ^ PORTAL_SALT ^ (groupIndex * 0x9E3779B97F4A7C15L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
