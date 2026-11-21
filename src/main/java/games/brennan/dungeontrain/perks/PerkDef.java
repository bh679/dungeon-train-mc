package games.brennan.dungeontrain.perks;

import java.util.regex.Pattern;

/**
 * One perk in the tree: its kebab-case id, class, tier (1..3) and how many active points
 * (ranks) it can hold. Immutable; registered once in {@link PerkRegistry}.
 *
 * @param id       kebab-case slug of the design name ({@code "stackable-book-quill"})
 * @param perkClass the class whose branch it sits on
 * @param tier     1, 2 or 3; unlock cost is {@link #unlockCost()}
 * @param maxRank  active-point cap, {@link #DEFAULT_MAX_RANK} unless the design says otherwise
 */
public record PerkDef(String id, PerkClass perkClass, int tier, int maxRank) {

    /** Ring slots in the constellation editor; every perk holds up to this many active points. */
    public static final int DEFAULT_MAX_RANK = 10;
    public static final int MIN_TIER = 1;
    public static final int MAX_TIER = 3;

    private static final Pattern KEBAB = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final int[] UNLOCK_COST = {0, 1, 2, 3};

    public PerkDef {
        if (id == null || !KEBAB.matcher(id).matches()) {
            throw new IllegalArgumentException("perk id must be kebab-case: " + id);
        }
        if (perkClass == null) throw new IllegalArgumentException("perk class missing for " + id);
        if (tier < MIN_TIER || tier > MAX_TIER) throw new IllegalArgumentException("perk tier out of range for " + id + ": " + tier);
        if (maxRank < 1) throw new IllegalArgumentException("perk maxRank must be positive for " + id + ": " + maxRank);
    }

    /** Convenience: default max rank. */
    public PerkDef(String id, PerkClass perkClass, int tier) {
        this(id, perkClass, tier, DEFAULT_MAX_RANK);
    }

    /** Unlock points a perk of this tier costs: 1 / 2 / 3. */
    public int unlockCost() {
        return unlockCostFor(tier);
    }

    public static int unlockCostFor(int tier) {
        if (tier < MIN_TIER || tier > MAX_TIER) throw new IllegalArgumentException("tier out of range: " + tier);
        return UNLOCK_COST[tier];
    }

    /** Clamp a requested rank into {@code 0..maxRank}. */
    public int clampRank(int rank) {
        return Math.max(0, Math.min(maxRank, rank));
    }
}
