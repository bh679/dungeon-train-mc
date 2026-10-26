package games.brennan.dungeontrain.perks;

/**
 * Repeat unlocks. A perk can be unlocked again and again; each unlock costs its flat tier price in
 * Advancement Points ({@link PerkDef#unlockCostFor}) and raises the most points it can hold by one.
 * There is no hard cap, but a perk can only go past {@link #STEP} unlocks once {@link #SPENT_PER_STEP}
 * Advancement Points have been spent across the whole tree, past 2 × STEP once 2 × SPENT_PER_STEP
 * have, and so on. See {@code docs/design/perks-earning.md}.
 */
public final class PerkUnlocks {

    /** Unlock levels a perk can reach before the spent gate applies, and per step after it. */
    static final int STEP = 10;
    /** Advancement Points spent across the tree that open each further step. */
    static final int SPENT_PER_STEP = 100;

    private PerkUnlocks() {}

    /** The highest unlock level any perk may reach with {@code totalSpent} Advancement Points spent. */
    public static int levelLimit(int totalSpent) {
        return STEP * (1 + Math.max(0, totalSpent) / SPENT_PER_STEP);
    }

    /** May a perk be unlocked to {@code nextLevel} with {@code totalSpent} spent so far? */
    public static boolean allowed(int nextLevel, int totalSpent) {
        return nextLevel >= 1 && nextLevel <= levelLimit(totalSpent);
    }

    /** Advancement Points that must be spent before a perk can reach {@code nextLevel}. */
    public static int spentNeeded(int nextLevel) {
        return SPENT_PER_STEP * ((Math.max(1, nextLevel) - 1) / STEP);
    }
}
