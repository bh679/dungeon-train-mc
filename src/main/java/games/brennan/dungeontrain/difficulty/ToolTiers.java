package games.brennan.dungeontrain.difficulty;

import java.util.List;

/**
 * Digging tools on deep-run mobs: how likely a mob is to carry one, and what it may break with it.
 * Pure difficulty-level math, no world access; {@link DifficultyApplier} hands out the tool and
 * {@code ai.MobDigGoal} enforces {@link #mayBreak}.
 *
 * <p>No tools below {@link #TOOL_START_LEVEL}. From there the chance climbs from
 * {@link #START_CHANCE} toward (never reaching) 100%, passing {@link #ANCHOR_CHANCE} at
 * {@link #ANCHOR_LEVEL}: {@code 1 - (1 - start) * e^(-k (level - start level))}.</p>
 *
 * <p>The break tier unlocks with level too (see {@link #breakTier}) and is fixed on the mob at
 * spawn, so a mob's reach never changes mid-life.</p>
 */
public final class ToolTiers {

    /** First difficulty level at which a mob may spawn with a digging tool. */
    public static final int TOOL_START_LEVEL = 50;
    /** Tool chance at {@link #TOOL_START_LEVEL}. */
    static final double START_CHANCE = 0.02;
    /** Second point the curve passes through: {@link #ANCHOR_CHANCE} at {@link #ANCHOR_LEVEL}. */
    static final int ANCHOR_LEVEL = 150;
    static final double ANCHOR_CHANCE = 0.20;

    private static final double DECAY = Math.log((1.0 - START_CHANCE) / (1.0 - ANCHOR_CHANCE))
            / (ANCHOR_LEVEL - TOOL_START_LEVEL);

    /** Levels at which break tiers 1-4 unlock. */
    static final int[] TIER_LEVELS = {TOOL_START_LEVEL, 100, 250, 750};

    /** No break tier: the mob has no tool, or the level is below {@link #TOOL_START_LEVEL}. */
    public static final int NO_TIER = 0;

    private ToolTiers() {}

    /** Chance a mob spawning at {@code level} carries a digging tool; 0 below the start level, always below 1. */
    public static double toolChance(int level) {
        if (level < TOOL_START_LEVEL) return 0.0;
        return 1.0 - (1.0 - START_CHANCE) * Math.exp(-DECAY * (level - TOOL_START_LEVEL));
    }

    /**
     * Break tier for a tool mob spawning at {@code level}:
     * <ol>
     *   <li>player-placed blocks its tool is the right tool for</li>
     *   <li>any player-placed block</li>
     *   <li>any player-placed block, plus any other block its tool is the right tool for</li>
     *   <li>any block</li>
     * </ol>
     * Floors are never breakable at any tier. {@link #NO_TIER} below {@link #TOOL_START_LEVEL}.
     */
    public static int breakTier(int level) {
        int tier = NO_TIER;
        for (int i = 0; i < TIER_LEVELS.length; i++) {
            if (level >= TIER_LEVELS[i]) tier = i + 1;
        }
        return tier;
    }

    /**
     * Whether a mob of break tier {@code tier} may break a block. {@code playerPlaced}: a player
     * added it; {@code correctTool}: the held tool is the right tool for it; {@code floor}: it lies
     * below the mob's feet.
     */
    public static boolean mayBreak(int tier, boolean playerPlaced, boolean correctTool, boolean floor) {
        if (floor) return false;
        return switch (tier) {
            case 1 -> playerPlaced && correctTool;
            case 2 -> playerPlaced;
            case 3 -> playerPlaced || correctTool;
            case 4 -> true;
            default -> false;
        };
    }

    /** Tool pool a mob at {@code level} draws from. */
    static List<DifficultyTier.WeightedItem> toolPool(int level) {
        return ProceduralTiers.toolPool(level);
    }
}
